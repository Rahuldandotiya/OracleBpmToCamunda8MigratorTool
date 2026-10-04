package io.github.rahuldandotiya.o2c8.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.util.Json;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The web UI's API, end to end over HTTP, on the loan-origination sample. */
@SuppressWarnings("unchecked")
class WebServerTest {

  static final Path SAMPLE = Path.of("..", "samples", "oracle-bpm-12c", "loan-origination");
  static final String PROCESSES = "loan-origination/LoanOrigination/SOA/processes/";
  private final HttpClient http = HttpClient.newHttpClient();

  private record Res(int status, String body, byte[] bytes) {
    Map<String, Object> json() {
      return Json.parseObject(body);
    }
  }

  private WebServer start(Path tmp, Map<String, String> extra) throws Exception {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("migrator.knowledge-base-dir", tmp.resolve("kb").toString());
    m.putAll(extra);
    return WebServer.start(AppConfig.of(m), "127.0.0.1", 0);
  }

  private Res call(WebServer s, String method, String path, byte[] body, boolean csrf) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(s.url() + "api/" + path));
    if (csrf) {
      b.header("X-Requested-With", "oracle2c8");
    }
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    return new Res(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8), r.body());
  }

  private Res call(WebServer s, String method, String path, String json) throws Exception {
    return call(s, method, path, json == null ? null : json.getBytes(StandardCharsets.UTF_8), true);
  }

  private String workspace(WebServer s) throws Exception {
    return (String) call(s, "POST", "workspaces", "{}").json().get("id");
  }

  private void uploadSample(WebServer s, String ws) throws Exception {
    try (Stream<Path> files = Files.walk(SAMPLE)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        String rel = "loan-origination/" + SAMPLE.relativize(f).toString().replace('\\', '/');
        Res r = call(s, "PUT", "workspaces/" + ws + "/files/migrate?path=" + enc(rel), Files.readAllBytes(f), true);
        assertEquals(201, r.status(), r.body());
      }
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  @Test
  void uploadConvertPreviewReviewAndDownload(@TempDir Path tmp) throws Exception {
    WebServer s = start(tmp, Map.of());
    try {
      String ws = workspace(s);
      uploadSample(s, ws);
      Res conv = call(s, "POST", "workspaces/" + ws + "/convert", "{\"platformVersion\":\"8.6.0\"}");
      assertEquals(200, conv.status(), conv.body());
      Map<String, Object> summary = (Map<String, Object>) conv.json().get("summary");
      assertEquals(9L, ((Number) summary.get("converted")).longValue());
      assertEquals(1L, ((Number) summary.get("composites")).longValue());

      Res model = call(s, "GET", "workspaces/" + ws + "/model?path=" + enc(PROCESSES + "LOProcessSchedule.bpmn"), null, false);
      assertTrue(model.body().contains("0 28 22 * * *"));
      Res svg = call(s, "GET", "workspaces/" + ws + "/oracle-svg?path=" + enc(PROCESSES + "LOProcessAsService.bpmn"), null, false);
      assertTrue(svg.body().startsWith("<svg") && svg.body().contains("VerifyWebApplication"));

      List<?> files = (List<?>) conv.json().get("files");
      Map<?, ?> first = (Map<?, ?>) files.get(0);
      String key = (String) ((Map<?, ?>) ((List<?>) first.get("review")).get(0)).get("key");
      Res save = call(s, "PUT", "workspaces/" + ws + "/checklist",
          "{\"items\":{" + Json.write(key).trim() + ":{\"done\":true,\"comment\":\"use the email connector\"}}}");
      assertEquals(200, save.status(), save.body());
      String checklist = call(s, "GET", "workspaces/" + ws + "/checklist.md", null, false).body();
      assertTrue(checklist.contains("- [x]") && checklist.contains("use the email connector"), checklist);

      Res zip = call(s, "GET", "workspaces/" + ws + "/download.zip", null, false);
      List<String> names = new java.util.ArrayList<>();
      try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip.bytes()))) {
        for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
          names.add(e.getName());
        }
      }
      assertTrue(names.contains("camunda8/" + PROCESSES + "LOProcessSchedule.bpmn"), names.toString());
      assertTrue(names.containsAll(List.of("conversion-report.md", "conversion-report.json", "review-checklist.md")));
    } finally {
      s.stop();
    }
  }

  @Test
  void analyzeEstimatesEffort(@TempDir Path tmp) throws Exception {
    WebServer s = start(tmp, Map.of("analyze.hours.undefined-task", "1"));
    try {
      String ws = workspace(s);
      uploadSample(s, ws);
      Res r = call(s, "POST", "workspaces/" + ws + "/analyze", "{}");
      assertEquals(200, r.status(), r.body());
      Map<?, ?> a = (Map<?, ?>) r.json().get("analysis");
      assertEquals(9L, ((Number) a.get("files")).longValue());
      assertEquals(1.0, ((Number) ((Map<?, ?>) a.get("weights")).get("undefined-task")).doubleValue());
      assertTrue(((Number) a.get("totalHours")).doubleValue() > 0);
      assertTrue(call(s, "GET", "workspaces/" + ws + "/analysis.md", null, false).body().startsWith("# Migration analysis"));
    } finally {
      s.stop();
    }
  }

  @Test
  void brokenFilesAreReportedAsFailedNextToGoodOnes(@TempDir Path tmp) throws Exception {
    WebServer s = start(tmp, Map.of());
    try {
      String ws = workspace(s);
      call(s, "PUT", "workspaces/" + ws + "/files/migrate?path=Broken.bpmn", "<bpmn:definitions".getBytes(), true);
      call(s, "PUT", "workspaces/" + ws + "/files/migrate?path=LOProcessSchedule.bpmn",
          Files.readAllBytes(SAMPLE.resolve("LoanOrigination/SOA/processes/LOProcessSchedule.bpmn")), true);
      Res r = call(s, "POST", "workspaces/" + ws + "/convert", "{}");
      assertEquals(200, r.status(), r.body());
      Map<?, ?> summary = (Map<?, ?>) r.json().get("summary");
      assertEquals(1L, ((Number) summary.get("converted")).longValue());
      assertEquals(1L, ((Number) summary.get("failed")).longValue());
      Map<?, ?> failed = ((List<?>) r.json().get("files")).stream().map(f -> (Map<?, ?>) f)
          .filter(f -> "FAILED".equals(f.get("status"))).findFirst().orElseThrow();
      assertTrue(String.valueOf(failed.get("failure")).contains("could not be read as BPMN XML"));
    } finally {
      s.stop();
    }
  }

  @Test
  void knowledgeBaseCanBeSavedListedAppliedAndRemoved(@TempDir Path tmp) throws Exception {
    WebServer s = start(tmp, Map.of());
    try {
      String ws = workspace(s);
      Path oracle = SAMPLE.resolve("LoanOrigination/SOA/processes/LOProcessAsService.bpmn");
      String finished = new OracleToCamundaConverter().convert(oracle).bpmnXml()
          .replace("lOProcessAsServiceINPDO", "loanRequest");
      call(s, "PUT", "workspaces/" + ws + "/files/kb?path=oracle/LOProcessAsService.bpmn", Files.readAllBytes(oracle), true);
      call(s, "PUT", "workspaces/" + ws + "/files/kb?path=camunda/loan-as-service.bpmn", finished.getBytes(), true);

      Res bad = call(s, "POST", "workspaces/" + ws + "/kb/save", "{\"name\":\"../x\"}");
      assertEquals(400, bad.status());
      Res saved = call(s, "POST", "workspaces/" + ws + "/kb/save", "{\"name\":\"loan as service\"}");
      assertEquals(200, saved.status(), saved.body());
      assertEquals(1L, ((Number) saved.json().get("pairs")).longValue());
      assertEquals(409, call(s, "POST", "workspaces/" + ws + "/kb/save", "{\"name\":\"loan as service\"}").status());

      Map<String, Object> kb = call(s, "GET", "knowledge-base", null, false).json();
      assertEquals(1, ((List<?>) kb.get("pairs")).size());

      // a new session converts with the saved knowledge
      String ws2 = workspace(s);
      call(s, "PUT", "workspaces/" + ws2 + "/files/migrate?path=LOProcessActivationFromQueue.bpmn",
          Files.readAllBytes(SAMPLE.resolve("LoanOrigination/SOA/processes/LOProcessActivationFromQueue.bpmn")), true);
      Map<String, Object> r = call(s, "POST", "workspaces/" + ws2 + "/convert", "{}").json();
      assertEquals(1L, ((Number) ((Map<?, ?>) r.get("summary")).get("knowledgeBasePairs")).longValue());
      String xml = call(s, "GET", "workspaces/" + ws2 + "/model?path=LOProcessActivationFromQueue.bpmn", null, false).body();
      assertTrue(xml.contains("loanRequest") && !xml.contains("lOProcessActivationQueueINPDO"));

      assertEquals(200, call(s, "DELETE", "knowledge-base/" + enc("loan as service"), (String) null).status());
      assertFalse(Files.exists(tmp.resolve("kb/loan as service")));
    } finally {
      s.stop();
    }
  }

  @Test
  void pushToWebModelerReportsMissingConfigurationAndWorksWhenConfigured(@TempDir Path tmp) throws Exception {
    WebServer plain = start(tmp, Map.of());
    try {
      String ws = workspace(plain);
      call(plain, "PUT", "workspaces/" + ws + "/files/migrate?path=LOProcessSchedule.bpmn",
          Files.readAllBytes(SAMPLE.resolve("LoanOrigination/SOA/processes/LOProcessSchedule.bpmn")), true);
      call(plain, "POST", "workspaces/" + ws + "/convert", "{}");
      Res r = call(plain, "POST", "workspaces/" + ws + "/webmodeler/push", "{\"folder\":\"Wave 1\"}");
      assertEquals(503, r.status());
      assertEquals("NOT_CONFIGURED", r.json().get("kind"));
      assertTrue(((String) r.json().get("error")).contains("application.properties"));
    } finally {
      plain.stop();
    }
    try (FakeWebModeler wm = new FakeWebModeler()) {
      Map<String, String> cfg = new LinkedHashMap<>();
      AppConfig c = wm.config("saas");
      for (String k : List.of("camunda.webmodeler.mode", "camunda.webmodeler.client-id", "camunda.webmodeler.client-secret",
          "camunda.webmodeler.api-url", "camunda.webmodeler.token-url")) {
        cfg.put(k, c.get(k));
      }
      WebServer s = start(tmp, cfg);
      try {
        assertEquals(200, call(s, "GET", "webmodeler/status", null, false).status());
        String ws = workspace(s);
        call(s, "PUT", "workspaces/" + ws + "/files/migrate?path=LOProcessSchedule.bpmn",
            Files.readAllBytes(SAMPLE.resolve("LoanOrigination/SOA/processes/LOProcessSchedule.bpmn")), true);
        call(s, "POST", "workspaces/" + ws + "/convert", "{}");
        Res r = call(s, "POST", "workspaces/" + ws + "/webmodeler/push", "{\"folder\":\"Wave 1\"}");
        assertEquals(200, r.status(), r.body());
        assertEquals(List.of("LOProcessSchedule"), r.json().get("created"));
        assertTrue(wm.files.values().iterator().next().get("content").toString().contains("0 28 22 * * *"));
      } finally {
        s.stop();
      }
    }
  }

  @Test
  void rejectsCrossSiteRequestsForeignHostsAndPathTraversal(@TempDir Path tmp) throws Exception {
    WebServer s = start(tmp, Map.of());
    try {
      assertEquals(403, call(s, "POST", "workspaces", "{}".getBytes(), false).status(), "no X-Requested-With");
      // java.net.http does not allow overriding Host, so send this one through a raw socket
      assertEquals(403, rawStatus(s.port(), "GET /api/status HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n"));
      String ws = workspace(s);
      Res r = call(s, "PUT", "workspaces/" + ws + "/files/migrate?path=" + enc("../../outside.bpmn"), "x".getBytes(), true);
      assertEquals(400, r.status());
      assertEquals(404, call(s, "GET", "workspaces/does-not-exist/result", null, false).status());
      String index = http.send(HttpRequest.newBuilder(URI.create(s.url())).build(), HttpResponse.BodyHandlers.ofString()).body();
      assertTrue(index.contains("Oracle BPM to Camunda 8 Migrator"));
    } finally {
      s.stop();
    }
  }

  private static int rawStatus(int port, String request) throws Exception {
    try (java.net.Socket sock = new java.net.Socket("127.0.0.1", port)) {
      sock.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      String line = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream())).readLine();
      return Integer.parseInt(line.split(" ")[1]);
    }
  }
}
