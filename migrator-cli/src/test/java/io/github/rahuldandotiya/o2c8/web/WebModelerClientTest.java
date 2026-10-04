package io.github.rahuldandotiya.o2c8.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebModelerClientTest {

  private static final List<WebModelerClient.ModelFile> MODELS = List.of(
      new WebModelerClient.ModelFile("LOProcessSchedule", "<bpmn:definitions/>"),
      new WebModelerClient.ModelFile("LOProcessAsService", "<bpmn:definitions/>"));

  @Test
  void createsProjectFolderAndFilesThenUpdatesThemOnTheNextPush() throws Exception {
    try (FakeWebModeler wm = new FakeWebModeler()) {
      WebModelerClient c = new WebModelerClient(WebModelerClient.Settings.from(wm.config("saas")));
      WebModelerClient.PushResult first = c.push("Loan origination", MODELS);
      assertEquals("Oracle BPM migration", first.projectName());
      assertEquals(List.of("LOProcessSchedule", "LOProcessAsService"), first.created());
      assertEquals(List.of(), first.updated());
      assertEquals(1, wm.projects.size());
      assertEquals(1, wm.folders.size());
      assertEquals(2, wm.files.size());
      assertTrue(wm.files.values().stream().allMatch(f -> "bpmn".equals(f.get("fileType"))));

      WebModelerClient.PushResult second = c.push("Loan origination", List.of(
          new WebModelerClient.ModelFile("LOProcessSchedule", "<bpmn:definitions id=\"v2\"/>")));
      assertEquals(List.of("LOProcessSchedule"), second.updated());
      assertEquals(1, wm.projects.size(), "the project is found again, not duplicated");
      assertEquals(1, wm.folders.size(), "the folder is found again, not duplicated");
      Map<String, Object> schedule = wm.files.values().stream()
          .filter(f -> f.get("name").equals("LOProcessSchedule")).findFirst().orElseThrow();
      assertEquals(2L, ((Number) schedule.get("revision")).longValue());
      assertTrue(schedule.get("content").toString().contains("v2"));
      assertEquals("api.cloud.camunda.io", wm.lastAudience, "SaaS audience by default");
    }
  }

  @Test
  void selfManagedUsesItsOwnAudienceAndAConfiguredProjectId() throws Exception {
    try (FakeWebModeler wm = new FakeWebModeler()) {
      wm.projects.put("p-existing", new java.util.LinkedHashMap<>(Map.of("id", "p-existing", "name", "Team project")));
      Map<String, String> m = new java.util.LinkedHashMap<>();
      AppConfig base = wm.config("self-managed");
      for (String k : List.of("camunda.webmodeler.mode", "camunda.webmodeler.client-id", "camunda.webmodeler.client-secret",
          "camunda.webmodeler.api-url", "camunda.webmodeler.token-url", "camunda.webmodeler.timeout-seconds")) {
        m.put(k, base.get(k));
      }
      m.put("camunda.webmodeler.project-id", "p-existing");
      WebModelerClient c = new WebModelerClient(WebModelerClient.Settings.from(AppConfig.of(m)));
      WebModelerClient.PushResult r = c.push("Wave 1", MODELS);
      assertEquals("Team project", r.projectName());
      assertEquals(1, wm.projects.size(), "no new project when project-id is set");
      assertEquals("web-modeler-api", wm.lastAudience);
    }
  }

  @Test
  void checkReportsMissingWritePermission() throws Exception {
    try (FakeWebModeler wm = new FakeWebModeler()) {
      wm.canWrite = false;
      WebModelerClient c = new WebModelerClient(WebModelerClient.Settings.from(wm.config("saas")));
      WebModelerException e = assertThrows(WebModelerException.class, c::check);
      assertEquals(WebModelerException.Kind.PERMISSION, e.kind());
      assertTrue(e.getMessage().contains("Create, Read and Update"), e.getMessage());
      WebModelerException push = assertThrows(WebModelerException.class, () -> c.push("x", MODELS));
      assertEquals(WebModelerException.Kind.PERMISSION, push.kind());
    }
  }

  @Test
  void wrongCredentialsSayWhichSettingsToCheck() throws Exception {
    try (FakeWebModeler wm = new FakeWebModeler()) {
      AppConfig cfg = wm.config("saas");
      wm.clientSecret = "something-else";
      WebModelerClient c = new WebModelerClient(WebModelerClient.Settings.from(cfg));
      WebModelerException e = assertThrows(WebModelerException.class, c::check);
      assertEquals(WebModelerException.Kind.AUTHENTICATION, e.kind());
      assertTrue(e.getMessage().contains("client-id and client-secret"), e.getMessage());
      assertTrue(e.getMessage().contains("Administration API"), e.getMessage());
    }
  }

  @Test
  void notConfiguredExplainsTheProperties() {
    WebModelerClient c = new WebModelerClient(WebModelerClient.Settings.from(AppConfig.of(Map.of())));
    WebModelerException e = assertThrows(WebModelerException.class, () -> c.push("x", MODELS));
    assertEquals(WebModelerException.Kind.NOT_CONFIGURED, e.kind());
    assertTrue(e.getMessage().contains("camunda.webmodeler.client-id"), e.getMessage());
  }

  @Test
  void unreachableServerIsReportedAsAConnectionProblem() throws Exception {
    int closedPort;
    try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
      closedPort = s.getLocalPort();
    }
    WebModelerClient c = new WebModelerClient(WebModelerClient.Settings.from(AppConfig.of(Map.of(
        "camunda.webmodeler.client-id", "a", "camunda.webmodeler.client-secret", "b",
        "camunda.webmodeler.token-url", "http://127.0.0.1:" + closedPort + "/token",
        "camunda.webmodeler.timeout-seconds", "3"))));
    WebModelerException e = assertThrows(WebModelerException.class, c::check);
    assertEquals(WebModelerException.Kind.UNREACHABLE, e.kind());
    assertTrue(e.getMessage().startsWith("Cannot reach http://127.0.0.1:" + closedPort), e.getMessage());
  }

  @Test
  void invalidModeIsAConfigurationError() {
    WebModelerException e = assertThrows(WebModelerException.class,
        () -> WebModelerClient.Settings.from(AppConfig.of(Map.of("camunda.webmodeler.mode", "cloudy"))));
    assertEquals(WebModelerException.Kind.NOT_CONFIGURED, e.kind());
  }
}
