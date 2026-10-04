package io.github.rahuldandotiya.o2c8.web;

import io.github.rahuldandotiya.o2c8.util.Json;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Uploads converted models to Camunda Web Modeler (SaaS or Self-Managed) through the Web Modeler
 * REST API v1. Models are only stored in a project, never deployed.
 *
 * <p>Configured in application.properties ({@code camunda.webmodeler.*}). Every failure surfaces as a
 * {@link WebModelerException} with a message that says what to fix.
 */
public final class WebModelerClient {

  /** Deployment flavour, which decides the default URLs. */
  public enum Mode {
    SAAS, SELF_MANAGED
  }

  /** Resolved settings. */
  public record Settings(Mode mode, String clientId, String clientSecret, String apiUrl, String tokenUrl,
      String audience, String scope, String projectId, String projectName, Duration timeout) {

    public static Settings from(AppConfig c) {
      String m = c.get("camunda.webmodeler.mode", "saas").toLowerCase(Locale.ROOT).replace('_', '-');
      Mode mode = switch (m) {
        case "saas", "cloud" -> Mode.SAAS;
        case "self-managed", "selfmanaged", "sm" -> Mode.SELF_MANAGED;
        default -> throw new WebModelerException(WebModelerException.Kind.NOT_CONFIGURED,
            "camunda.webmodeler.mode must be 'saas' or 'self-managed', got '" + m + "'.");
      };
      boolean saas = mode == Mode.SAAS;
      return new Settings(mode,
          c.get("camunda.webmodeler.client-id"),
          c.get("camunda.webmodeler.client-secret"),
          trimSlash(c.get("camunda.webmodeler.api-url", saas ? "https://modeler.cloud.camunda.io" : "http://localhost:8070")),
          c.get("camunda.webmodeler.token-url", saas ? "https://login.cloud.camunda.io/oauth/token"
              : "http://localhost:18080/auth/realms/camunda-platform/protocol/openid-connect/token"),
          c.get("camunda.webmodeler.audience", saas ? "api.cloud.camunda.io" : "web-modeler-api"),
          c.get("camunda.webmodeler.scope"),
          c.get("camunda.webmodeler.project-id"),
          c.get("camunda.webmodeler.project-name", "Oracle BPM migration"),
          Duration.ofSeconds(c.getInt("camunda.webmodeler.timeout-seconds", 30)));
    }

    public boolean configured() {
      return clientId != null && clientSecret != null;
    }

    /** Settings that are safe to show (no secret). */
    public Map<String, Object> describe() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("mode", mode == Mode.SAAS ? "saas" : "self-managed");
      m.put("configured", configured());
      m.put("apiUrl", apiUrl);
      m.put("tokenUrl", tokenUrl);
      m.put("clientId", clientId);
      m.put("project", projectId != null ? "id " + projectId : projectName);
      return m;
    }
  }

  /** What one upload did. */
  public record PushResult(String projectId, String projectName, String folderId, String folderName,
      List<String> created, List<String> updated, String webUrl) {}

  /** A file to upload: Web Modeler name (without .bpmn) and BPMN XML. */
  public record ModelFile(String name, String xml) {}

  private final Settings settings;
  private final HttpClient http;
  private String token;
  private Instant tokenExpiry = Instant.EPOCH;

  public WebModelerClient(Settings settings) {
    this.settings = settings;
    this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
  }

  public Settings settings() {
    return settings;
  }

  /** Checks configuration, authentication and permissions; returns the API's info document. */
  public Map<String, Object> check() {
    requireConfigured();
    Map<String, Object> info = object(call("GET", "/api/v1/info", null));
    if (Boolean.FALSE.equals(info.get("createPermission")) || Boolean.FALSE.equals(info.get("updatePermission"))) {
      throw new WebModelerException(WebModelerException.Kind.PERMISSION,
          "Connected to Web Modeler, but the API client cannot create or update files. Give it the Web Modeler "
              + "API permissions Create, Read and Update (" + clientWhere() + ").");
    }
    return info;
  }

  /**
   * Uploads the models into folder {@code folderName} of the configured project (created if
   * missing). Files that already exist there are updated (new revision), others are created.
   */
  public PushResult push(String folderName, List<ModelFile> files) {
    requireConfigured();
    if (files.isEmpty()) {
      throw new WebModelerException(WebModelerException.Kind.BAD_REQUEST, "There are no converted models to upload.");
    }
    String projectId = settings.projectId() != null ? settings.projectId() : findOrCreateProject(settings.projectName());
    Map<String, Object> project = object(call("GET", "/api/v1/projects/" + enc(projectId), null));
    String projectName = str(object(project.get("metadata")).get("name"));
    String folderId = findOrCreateFolder(projectId, project, folderName);
    Map<String, Object> folder = object(call("GET", "/api/v1/folders/" + enc(folderId), null));
    Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
    for (Object f : list(object(folder.get("content")).get("files"))) {
      Map<String, Object> fm = object(f);
      existing.put(str(fm.get("name")), fm);
    }
    List<String> created = new ArrayList<>();
    List<String> updated = new ArrayList<>();
    for (ModelFile f : files) {
      Map<String, Object> old = existing.get(f.name());
      if (old != null) {
        Map<String, Object> meta = object(call("GET", "/api/v1/files/" + enc(str(old.get("id"))), null));
        Object revision = object(meta.get("metadata")).get("revision");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", f.xml());
        body.put("revision", revision instanceof Number n ? n.longValue() : 0);
        call("PATCH", "/api/v1/files/" + enc(str(old.get("id"))), body);
        updated.add(f.name());
      } else {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", f.name());
        body.put("folderId", folderId);
        body.put("content", f.xml());
        body.put("fileType", "bpmn");
        call("POST", "/api/v1/files", body);
        created.add(f.name());
      }
    }
    String web = settings.mode() == Mode.SAAS ? "https://modeler.camunda.io/projects/" + projectId
        : settings.apiUrl() + "/projects/" + projectId;
    return new PushResult(projectId, projectName, folderId, folderName, created, updated, web);
  }

  // ------------------------------------------------------------------ projects and folders

  private String findOrCreateProject(String name) {
    Map<String, Object> search = new LinkedHashMap<>();
    search.put("filter", Map.of("name", name));
    search.put("page", 0);
    search.put("size", 50);
    for (Object p : list(object(call("POST", "/api/v1/projects/search", search)).get("items"))) {
      Map<String, Object> pm = object(p);
      if (name.equals(pm.get("name"))) {
        return str(pm.get("id"));
      }
    }
    return str(object(call("POST", "/api/v1/projects", Map.of("name", name))).get("id"));
  }

  private String findOrCreateFolder(String projectId, Map<String, Object> project, String name) {
    for (Object f : list(object(project.get("content")).get("folders"))) {
      Map<String, Object> fm = object(f);
      if (name.equals(fm.get("name"))) {
        return str(fm.get("id"));
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("projectId", projectId);
    return str(object(call("POST", "/api/v1/folders", body)).get("id"));
  }

  // ------------------------------------------------------------------ HTTP

  private void requireConfigured() {
    if (!settings.configured()) {
      throw new WebModelerException(WebModelerException.Kind.NOT_CONFIGURED,
          "Web Modeler is not configured. Set camunda.webmodeler.client-id and camunda.webmodeler.client-secret "
              + "(and camunda.webmodeler.mode=saas or self-managed) in application.properties, then restart "
              + "the server. See application.properties.example.");
    }
  }

  private Object call(String method, String path, Object body) {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(settings.apiUrl() + path))
        .timeout(settings.timeout())
        .header("Authorization", "Bearer " + token())
        .header("Accept", "application/json");
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
    }
    HttpResponse<String> r = send(b.build(), settings.apiUrl());
    int code = r.statusCode();
    if (code == 401) {
      token = null;
      throw new WebModelerException(WebModelerException.Kind.AUTHENTICATION,
          "Web Modeler rejected the access token (HTTP 401). Check that the API client belongs to the right "
              + "organization or Identity realm, and that camunda.webmodeler.audience is correct.");
    }
    if (code == 403) {
      throw new WebModelerException(WebModelerException.Kind.PERMISSION,
          "The API client is not allowed to do this in Web Modeler (HTTP 403). Give it the Web Modeler API "
              + "permissions Create, Read and Update (" + clientWhere() + ").");
    }
    if (code == 404) {
      throw new WebModelerException(WebModelerException.Kind.NOT_FOUND,
          "Web Modeler could not find " + path + " (HTTP 404)."
              + (path.contains("/projects/") && settings.projectId() != null
                  ? " Check camunda.webmodeler.project-id." : " Check camunda.webmodeler.api-url."));
    }
    if (code == 409) {
      throw new WebModelerException(WebModelerException.Kind.CONFLICT,
          "Web Modeler reported a conflict (HTTP 409): someone changed the file at the same time. Try again. "
              + apiMessage(r.body()));
    }
    if (code >= 300) {
      throw new WebModelerException(WebModelerException.Kind.API_ERROR,
          "Web Modeler returned HTTP " + code + " for " + method + " " + path + ". " + apiMessage(r.body()));
    }
    String text = r.body();
    return text == null || text.isBlank() ? Map.of() : Json.parse(text);
  }

  private synchronized String token() {
    if (token != null && Instant.now().isBefore(tokenExpiry)) {
      return token;
    }
    StringBuilder form = new StringBuilder("grant_type=client_credentials")
        .append("&client_id=").append(enc(settings.clientId()))
        .append("&client_secret=").append(enc(settings.clientSecret()));
    if (settings.audience() != null) {
      form.append("&audience=").append(enc(settings.audience()));
    }
    if (settings.scope() != null) {
      form.append("&scope=").append(enc(settings.scope()));
    }
    HttpRequest req = HttpRequest.newBuilder(uri(settings.tokenUrl()))
        .timeout(settings.timeout())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
        .build();
    HttpResponse<String> r = send(req, settings.tokenUrl());
    if (r.statusCode() == 400 || r.statusCode() == 401 || r.statusCode() == 403) {
      throw new WebModelerException(WebModelerException.Kind.AUTHENTICATION,
          "Camunda rejected the credentials (HTTP " + r.statusCode() + " from " + settings.tokenUrl() + "). "
              + "Check camunda.webmodeler.client-id and client-secret" + (settings.mode() == Mode.SAAS
                  ? ", and that the client was created under Console > Organization > Administration API with Web Modeler access."
                  : ", and that the application exists in Identity with Web Modeler API permissions.")
              + " " + apiMessage(r.body()));
    }
    if (r.statusCode() >= 300) {
      throw new WebModelerException(WebModelerException.Kind.AUTHENTICATION,
          "Could not get an access token (HTTP " + r.statusCode() + " from " + settings.tokenUrl() + "). "
              + apiMessage(r.body()));
    }
    Map<String, Object> t;
    try {
      t = object(Json.parse(r.body()));
    } catch (RuntimeException e) {
      throw new WebModelerException(WebModelerException.Kind.AUTHENTICATION,
          "The token endpoint " + settings.tokenUrl() + " did not return JSON. Check camunda.webmodeler.token-url.");
    }
    token = str(t.get("access_token"));
    if (token == null) {
      throw new WebModelerException(WebModelerException.Kind.AUTHENTICATION,
          "The token endpoint returned no access_token. Check camunda.webmodeler.token-url and audience.");
    }
    long expiresIn = t.get("expires_in") instanceof Number n ? n.longValue() : 300;
    tokenExpiry = Instant.now().plusSeconds(Math.max(30, expiresIn - 30));
    return token;
  }

  private HttpResponse<String> send(HttpRequest req, String target) {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      throw unreachable(target, "the connection timed out after " + settings.timeout().toSeconds() + " s");
    } catch (ConnectException e) {
      throw unreachable(target, "the connection was refused");
    } catch (IOException e) {
      Throwable root = e;
      while (root.getCause() != null) {
        root = root.getCause();
      }
      if (root instanceof UnknownHostException) {
        throw unreachable(target, "the host name is unknown");
      }
      throw unreachable(target, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unreachable(target, "the request was interrupted");
    }
  }

  private WebModelerException unreachable(String target, String why) {
    return new WebModelerException(WebModelerException.Kind.UNREACHABLE,
        "Cannot reach " + target + ": " + why + ". Check camunda.webmodeler.api-url / token-url, your network, VPN "
            + "or proxy settings.");
  }

  private String clientWhere() {
    return settings.mode() == Mode.SAAS ? "Console > Organization > Administration API"
        : "Identity > Applications > your application > Access to APIs";
  }

  private static URI uri(String s) {
    try {
      return URI.create(s);
    } catch (IllegalArgumentException e) {
      throw new WebModelerException(WebModelerException.Kind.NOT_CONFIGURED, "Invalid URL in configuration: " + s);
    }
  }

  private static String apiMessage(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      Map<String, Object> m = object(Json.parse(body));
      for (String k : new String[] {"detail", "message", "error_description", "error", "title"}) {
        if (m.get(k) instanceof String s && !s.isBlank()) {
          return "Details: " + s;
        }
      }
    } catch (RuntimeException ignored) {
      // not JSON
    }
    String b = body.strip();
    return "Details: " + (b.length() > 200 ? b.substring(0, 200) + "..." : b);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static List<?> list(Object o) {
    return o instanceof List<?> l ? l : List.of();
  }

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }

  private static String trimSlash(String s) {
    return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
