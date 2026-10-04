package io.github.rahuldandotiya.o2c8.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.rahuldandotiya.o2c8.util.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for Camunda's OAuth token endpoint and the Web Modeler REST API v1 (the
 * subset the migrator uses), following the published OpenAPI document.
 */
final class FakeWebModeler implements AutoCloseable {

  final HttpServer server;
  final Map<String, Map<String, Object>> projects = new LinkedHashMap<>();
  final Map<String, Map<String, Object>> folders = new LinkedHashMap<>();
  final Map<String, Map<String, Object>> files = new LinkedHashMap<>();
  final List<String> requests = new ArrayList<>();
  final AtomicInteger ids = new AtomicInteger();
  String clientId = "client";
  String clientSecret = "secret";
  boolean canWrite = true;
  String lastAudience;

  FakeWebModeler() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oauth/token", this::token);
    server.createContext("/api/v1/", this::api);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  AppConfig config(String mode) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("camunda.webmodeler.mode", mode);
    m.put("camunda.webmodeler.client-id", clientId);
    m.put("camunda.webmodeler.client-secret", clientSecret);
    m.put("camunda.webmodeler.api-url", url());
    m.put("camunda.webmodeler.token-url", url() + "/oauth/token");
    m.put("camunda.webmodeler.timeout-seconds", "5");
    return AppConfig.of(m);
  }

  private void token(HttpExchange ex) throws IOException {
    String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add("TOKEN " + form.replaceAll("client_secret=[^&]*", "client_secret=***"));
    Map<String, String> f = new LinkedHashMap<>();
    for (String kv : form.split("&")) {
      int i = kv.indexOf('=');
      f.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
    }
    lastAudience = f.get("audience");
    if (!"client_credentials".equals(f.get("grant_type")) || !clientId.equals(f.get("client_id"))
        || !clientSecret.equals(f.get("client_secret"))) {
      send(ex, 401, Map.of("error", "access_denied", "error_description", "Unauthorized"));
      return;
    }
    send(ex, 200, Map.of("access_token", "token-123", "expires_in", 300, "token_type", "Bearer"));
  }

  private void api(HttpExchange ex) throws IOException {
    String method = ex.getRequestMethod();
    String path = ex.getRequestURI().getPath().substring("/api/v1/".length());
    requests.add(method + " " + path);
    if (!"Bearer token-123".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      send(ex, 401, Map.of("message", "invalid token"));
      return;
    }
    String bodyText = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, Object> body = bodyText.isBlank() ? Map.of() : Json.parseObject(bodyText);
    String[] p = path.split("/");
    if (path.equals("info")) {
      send(ex, 200, Map.of("version", "v1", "authorizedOrganization", "org-1",
          "createPermission", canWrite, "readPermission", true, "updatePermission", canWrite, "deletePermission", false));
    } else if (!canWrite && !method.equals("GET") && !path.endsWith("search")) {
      send(ex, 403, Map.of("message", "Forbidden"));
    } else if (path.equals("projects/search")) {
      Object name = ((Map<?, ?>) body.getOrDefault("filter", Map.of())).get("name");
      List<Object> items = projects.values().stream().filter(x -> name == null || name.equals(x.get("name")))
          .map(x -> (Object) x).toList();
      send(ex, 200, Map.of("items", items, "total", items.size()));
    } else if (path.equals("projects") && method.equals("POST")) {
      String id = "p" + ids.incrementAndGet();
      projects.put(id, new LinkedHashMap<>(Map.of("id", id, "name", body.get("name"))));
      send(ex, 200, projects.get(id));
    } else if (p.length == 2 && p[0].equals("projects") && method.equals("GET")) {
      Map<String, Object> pr = projects.get(p[1]);
      if (pr == null) {
        send(ex, 404, Map.of("message", "project not found"));
        return;
      }
      List<Object> fs = folders.values().stream().filter(f -> p[1].equals(f.get("projectId")) && f.get("parentId") == null)
          .map(x -> (Object) x).toList();
      send(ex, 200, Map.of("metadata", pr, "content", Map.of("folders", fs, "files", List.of())));
    } else if (path.equals("folders") && method.equals("POST")) {
      String id = "f" + ids.incrementAndGet();
      Map<String, Object> f = new LinkedHashMap<>();
      f.put("id", id);
      f.put("name", body.get("name"));
      f.put("projectId", body.get("projectId"));
      folders.put(id, f);
      send(ex, 200, f);
    } else if (p.length == 2 && p[0].equals("folders") && method.equals("GET")) {
      List<Object> fs = files.values().stream().filter(f -> p[1].equals(f.get("folderId")))
          .map(f -> (Object) meta(f)).toList();
      send(ex, 200, Map.of("metadata", folders.get(p[1]), "content", Map.of("folders", List.of(), "files", fs)));
    } else if (path.equals("files") && method.equals("POST")) {
      if (!"bpmn".equals(body.get("fileType")) || body.get("content") == null || body.get("name") == null) {
        send(ex, 400, Map.of("message", "name, content and fileType are required"));
        return;
      }
      String id = "file" + ids.incrementAndGet();
      Map<String, Object> f = new LinkedHashMap<>(body);
      f.put("id", id);
      f.put("revision", 1L);
      files.put(id, f);
      send(ex, 200, meta(f));
    } else if (p.length == 2 && p[0].equals("files") && method.equals("GET")) {
      Map<String, Object> f = files.get(p[1]);
      send(ex, 200, Map.of("metadata", meta(f), "content", f.get("content")));
    } else if (p.length == 2 && p[0].equals("files") && method.equals("PATCH")) {
      Map<String, Object> f = files.get(p[1]);
      long rev = ((Number) f.get("revision")).longValue();
      if (!(body.get("revision") instanceof Number n) || n.longValue() != rev) {
        send(ex, 409, Map.of("message", "revision mismatch"));
        return;
      }
      f.put("content", body.get("content"));
      f.put("revision", rev + 1);
      send(ex, 200, meta(f));
    } else {
      send(ex, 404, Map.of("message", "no such endpoint " + method + " " + path));
    }
  }

  private static Map<String, Object> meta(Map<String, Object> f) {
    Map<String, Object> m = new LinkedHashMap<>(f);
    m.remove("content");
    return m;
  }

  private static void send(HttpExchange ex, int status, Object body) throws IOException {
    byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(b);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
