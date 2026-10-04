package io.github.rahuldandotiya.o2c8.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.rahuldandotiya.o2c8.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Local web UI ({@code oracle2c8 serve}). Uses the JDK's built-in HTTP server, so the jar stays
 * dependency-free.
 *
 * <p>Security model: it binds to 127.0.0.1 by default; requests must carry a Host header for this
 * server (blocks DNS rebinding); every state-changing request needs the {@code X-Requested-With}
 * header, which browsers only send from this page's own scripts (blocks cross-site requests).
 */
public final class WebServer {

  private static final String CSRF_HEADER = "X-Requested-With";
  private static final String CSRF_VALUE = "oracle2c8";
  private static final Map<String, String> TYPES = Map.of(
      "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8",
      "svg", "image/svg+xml", "woff", "font/woff", "ttf", "font/ttf", "png", "image/png", "txt", "text/plain; charset=utf-8");

  private final MigratorApp app;
  private final HttpServer server;
  private final ExecutorService pool;
  private final ScheduledExecutorService cleaner;
  private final String host;
  private final int port;

  private WebServer(MigratorApp app, HttpServer server, ExecutorService pool, ScheduledExecutorService cleaner,
      String host, int port) {
    this.app = app;
    this.server = server;
    this.pool = pool;
    this.cleaner = cleaner;
    this.host = host;
    this.port = port;
  }

  /** Starts the server; port 0 picks a free port (tests). */
  public static WebServer start(AppConfig config, String hostOverride, Integer portOverride) throws IOException {
    String host = hostOverride != null ? hostOverride : config.get("server.host", "127.0.0.1");
    int port = portOverride != null ? portOverride : config.getInt("server.port", 8080);
    MigratorApp app = new MigratorApp(config);
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
    } catch (java.net.BindException e) {
      throw new IOException("Port " + port + " on " + host + " is already in use. Stop the other program or "
          + "start with --port <number> (or set server.port in application.properties).");
    }
    ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
      Thread t = new Thread(r, "oracle2c8-web");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(pool);
    ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "oracle2c8-cleanup");
      t.setDaemon(true);
      return t;
    });
    cleaner.scheduleAtFixedRate(app::expireIdle, 5, 5, TimeUnit.MINUTES);
    WebServer ws = new WebServer(app, server, pool, cleaner, host, server.getAddress().getPort());
    server.createContext("/", ws::handle);
    server.start();
    return ws;
  }

  public int port() {
    return port;
  }

  public String url() {
    String h = host.equals("0.0.0.0") ? "localhost" : host.contains(":") ? "[" + host + "]" : host;
    return "http://" + h + ":" + port + "/";
  }

  public MigratorApp app() {
    return app;
  }

  public void stop() {
    server.stop(0);
    pool.shutdownNow();
    cleaner.shutdownNow();
    app.shutdown();
  }

  /** Blocks until the JVM stops (Ctrl+C), cleaning up session files on the way out. */
  public void awaitShutdown(PrintStream out) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      out.println("Stopping, removing session files ...");
      stop();
    }));
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ------------------------------------------------------------------ routing

  private void handle(HttpExchange ex) throws IOException {
    try {
      checkHost(ex);
      String path = ex.getRequestURI().getRawPath();
      String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
      if (!method.equals("GET") && !method.equals("HEAD")) {
        String h = ex.getRequestHeaders().getFirst(CSRF_HEADER);
        if (!CSRF_VALUE.equals(h)) {
          throw new WebError(403, "Missing " + CSRF_HEADER + " header (requests must come from the oracle2c8 page).");
        }
      }
      if (path.startsWith("/api/")) {
        api(ex, method, path.substring(5));
      } else {
        staticFile(ex, path);
      }
    } catch (WebError e) {
      error(ex, e.status(), e.getMessage(), null);
    } catch (WebModelerException e) {
      error(ex, e.kind().httpStatus(), e.getMessage(), e.kind().name());
    } catch (IllegalArgumentException e) {
      error(ex, 400, e.getMessage(), null);
    } catch (Exception e) {
      error(ex, 500, "Unexpected server error (" + e.getClass().getSimpleName() + "): " + e.getMessage(), null);
    } finally {
      ex.close();
    }
  }

  private void checkHost(HttpExchange ex) {
    String h = ex.getRequestHeaders().getFirst("Host");
    if (h == null) {
      throw new WebError(400, "Missing Host header.");
    }
    String name = h.startsWith("[") ? h.substring(0, h.indexOf(']') + 1) : h.replaceAll(":\\d+$", "");
    Set<String> allowed = new java.util.HashSet<>(Set.of("localhost", "127.0.0.1", "[::1]"));
    allowed.add(host.toLowerCase(Locale.ROOT));
    allowed.add("[" + host.toLowerCase(Locale.ROOT) + "]");
    if (!allowed.contains(name.toLowerCase(Locale.ROOT)) && !host.equals("0.0.0.0")) {
      throw new WebError(403, "Unexpected Host header '" + h + "'.");
    }
  }

  private void api(HttpExchange ex, String method, String path) throws IOException {
    String[] p = path.split("/");
    Map<String, String> q = query(ex.getRequestURI());
    // /api/status
    if (path.equals("status") && method.equals("GET")) {
      json(ex, 200, app.status());
      return;
    }
    if (path.equals("knowledge-base") && method.equals("GET")) {
      json(ex, 200, app.knowledgeBase());
      return;
    }
    if (p.length == 2 && p[0].equals("knowledge-base") && method.equals("DELETE")) {
      app.deleteKnowledge(decode(p[1]));
      json(ex, 200, Map.of("deleted", decode(p[1])));
      return;
    }
    if (path.equals("webmodeler/status") && method.equals("GET")) {
      json(ex, 200, app.webModelerCheck());
      return;
    }
    if (path.equals("workspaces") && method.equals("POST")) {
      json(ex, 201, app.create().describe());
      return;
    }
    if (p.length >= 2 && p[0].equals("workspaces")) {
      Workspace w = app.workspace(p[1]);
      String rest = p.length > 2 ? path.substring(path.indexOf(p[1]) + p[1].length() + 1) : "";
      workspaceApi(ex, method, w, rest, q);
      return;
    }
    throw new WebError(404, "Unknown API path /api/" + path);
  }

  private void workspaceApi(HttpExchange ex, String method, Workspace w, String rest, Map<String, String> q)
      throws IOException {
    switch (method + " " + rest) {
      case "GET " -> json(ex, 200, w.describe());
      case "POST convert" -> json(ex, 200, app.convert(w, app.options(body(ex))));
      case "POST analyze" -> json(ex, 200, app.analyze(w, app.options(body(ex))));
      case "GET result" -> json(ex, 200, app.result(w));
      case "GET model" -> text(ex, 200, "application/xml; charset=utf-8", app.camundaXml(w, required(q, "path")),
          q.containsKey("download") ? fileName(q.get("path")) : null);
      case "GET oracle-svg" -> text(ex, 200, "image/svg+xml; charset=utf-8", app.oracleSvg(w, required(q, "path")), null);
      case "GET report.md" -> text(ex, 200, "text/markdown; charset=utf-8", app.reportMarkdown(w), "conversion-report.md");
      case "GET analysis.md" -> text(ex, 200, "text/markdown; charset=utf-8", app.analysisMarkdown(w), "migration-analysis.md");
      case "GET checklist.md" -> text(ex, 200, "text/markdown; charset=utf-8", app.checklistMarkdown(w), "review-checklist.md");
      case "PUT checklist" -> {
        app.updateChecklist(w, body(ex));
        json(ex, 200, Map.of("saved", true));
      }
      case "GET download.zip" -> {
        app.result(w); // fails with a readable error before any bytes are sent
        ex.getResponseHeaders().set("Content-Type", "application/zip");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"camunda8-migration.zip\"");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
          app.writeZip(w, os);
        }
      }
      case "POST kb/save" -> {
        Map<String, Object> b = body(ex);
        json(ex, 200, app.saveKnowledge(w, b.get("name") == null ? null : String.valueOf(b.get("name")),
            Boolean.TRUE.equals(b.get("overwrite"))));
      }
      case "POST webmodeler/push" -> {
        Map<String, Object> b = body(ex);
        json(ex, 200, app.pushToWebModeler(w, b.get("folder") == null ? null : String.valueOf(b.get("folder"))));
      }
      default -> {
        if (rest.startsWith("files/")) {
          Workspace.Area area = Workspace.Area.of(rest.substring(6));
          if (method.equals("PUT")) {
            try (InputStream in = ex.getRequestBody()) {
              String stored = w.store(area, required(q, "path"), in);
              json(ex, 201, Map.of("stored", stored));
            }
            return;
          }
          if (method.equals("DELETE")) {
            w.clear(area);
            json(ex, 200, w.describe());
            return;
          }
        }
        throw new WebError(404, "Unknown API path " + method + " /api/workspaces/" + w.id + "/" + rest);
      }
    }
  }

  // ------------------------------------------------------------------ static files

  private void staticFile(HttpExchange ex, String path) throws IOException {
    String p = path.equals("/") ? "/index.html" : path;
    if (p.contains("..") || !p.matches("/[A-Za-z0-9._/-]+")) {
      throw new WebError(404, "Not found");
    }
    try (InputStream in = WebServer.class.getResourceAsStream("/web" + p)) {
      if (in == null) {
        throw new WebError(404, "Not found: " + p);
      }
      byte[] data = in.readAllBytes();
      String ext = p.substring(p.lastIndexOf('.') + 1);
      ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
      ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      ex.getResponseHeaders().set("Cache-Control", "no-cache");
      ex.sendResponseHeaders(200, data.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(data);
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Map<String, Object> body(HttpExchange ex) throws IOException {
    try (InputStream in = ex.getRequestBody()) {
      byte[] b = in.readNBytes(1 << 20);
      String s = new String(b, StandardCharsets.UTF_8).trim();
      if (s.isEmpty()) {
        return Map.of();
      }
      try {
        return Json.parseObject(s);
      } catch (RuntimeException e) {
        throw new WebError(400, "Request body is not valid JSON.");
      }
    }
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> m = new LinkedHashMap<>();
    String raw = uri.getRawQuery();
    if (raw == null) {
      return m;
    }
    for (String kv : raw.split("&")) {
      int i = kv.indexOf('=');
      m.put(decode(i < 0 ? kv : kv.substring(0, i)), i < 0 ? "" : decode(kv.substring(i + 1)));
    }
    return m;
  }

  private static String required(Map<String, String> q, String key) {
    String v = q.get(key);
    if (v == null || v.isBlank()) {
      throw new WebError(400, "Missing query parameter '" + key + "'.");
    }
    return v;
  }

  private static String decode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  private static String fileName(String path) {
    String n = path.substring(path.lastIndexOf('/') + 1);
    return n.replaceAll("[^A-Za-z0-9._ -]", "_");
  }

  private static void json(HttpExchange ex, int status, Object body) throws IOException {
    text(ex, status, "application/json; charset=utf-8", Json.write(body), null);
  }

  private static void text(HttpExchange ex, int status, String type, String body, String downloadName)
      throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    if (downloadName != null) {
      ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
    }
    ex.sendResponseHeaders(status, b.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(b);
    }
  }

  private static void error(HttpExchange ex, int status, String message, String kind) {
    try {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("error", message == null ? "Error" : message);
      if (kind != null) {
        m.put("kind", kind);
      }
      json(ex, status, m);
    } catch (IOException ignored) {
      // client went away
    }
  }
}
