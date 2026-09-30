package io.github.rahuldandotiya.o2c8;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.DeploymentEvent;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.process.test.api.ZeebeTestEngine;
import io.camunda.zeebe.process.test.assertions.BpmnAssert;
import io.camunda.zeebe.process.test.extension.ZeebeProcessTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The real acceptance test: every converted sample must deploy to a Zeebe engine, and converted
 * processes must run end to end. Uses the in-memory engine from zeebe-process-test (no Docker).
 */
@ZeebeProcessTest
class ZeebeDeploymentTest {

  /** Job type Zeebe uses for job-worker based user tasks. */
  private static final String USER_TASK_JOB = "io.camunda.zeebe:userTask";

  private ZeebeTestEngine engine;
  private ZeebeClient client;

  private final OracleToCamundaConverter camundaUserTasks = new OracleToCamundaConverter();
  // The in-memory engine can only complete job-based user tasks, so run scenarios in that mode
  private final OracleToCamundaConverter jobUserTasks = new OracleToCamundaConverter(
      ConverterOptions.defaults().withUserTaskImplementation(ConverterOptions.UserTaskImplementation.JOB_WORKER));

  @Test
  void allSamplesDeploy() throws Exception {
    List<Path> samples = SampleConversionTest.samples().toList();
    for (Path sample : samples) {
      String xml = camundaUserTasks.convert(sample).bpmnXml();
      DeploymentEvent d = client.newDeployResourceCommand()
          .addResourceStringUtf8(xml, sample.getFileName().toString())
          .send().join();
      assertEquals(1, d.getProcesses().size(), sample + " should contain one process");
    }
  }

  @Test
  void fnolRunsEndToEnd() throws Exception {
    deploy("FNOLProcess.bpmn");
    ProcessInstanceEvent pi = client.newCreateInstanceCommand()
        .bpmnProcessId("FNOLProcess").latestVersion()
        .variables(Map.of("FNOLProcessIN", Map.of("FNOL", Map.of("sensitivity", "Regular"))))
        .send().join();
    engine.waitForIdleState(Duration.ofSeconds(5));
    BpmnAssert.assertThat(pi).isWaitingAtElements("ACT10316916088596");

    completeUserTask(Map.of("claim", Map.of("status", "captured")));
    BpmnAssert.assertThat(pi).isCompleted()
        .hasVariableWithValue("fNOLProcessINPDO", Map.of("status", "captured")) // task output mapping
        .hasVariableWithValue("organizationalUnit", "OrganizationalUnit");     // Oracle instance attribute
  }

  @Test
  void verificationRoutesByTranslatedXPathCondition() throws Exception {
    deploy("VerificationProcess.bpmn");

    ProcessInstanceEvent expert = start("VerificationProcess", "Expert");
    BpmnAssert.assertThat(expert).isWaitingAtElements("ACT10325139620772"); // EVerificationUserTask (default)

    completeUserTask(Map.of("claim", Map.of("FNOL", Map.of("sensitivity", "Expert"))));
    BpmnAssert.assertThat(expert).isCompleted();

    ProcessInstanceEvent regular = start("VerificationProcess", "Regular");
    BpmnAssert.assertThat(regular).isWaitingAtElements("ACT10325149764563"); // RVerificationUserTask
  }

  @Test
  void customerRejectionThrowsErrorCaughtByEventSubProcess() throws Exception {
    deploy("CustomerAcceptanceProcess.bpmn");
    ProcessInstanceEvent pi = client.newCreateInstanceCommand()
        .bpmnProcessId("CustomerAcceptanceProcess").latestVersion()
        .variables(Map.of("CustomerAcceptanceProcessIN", Map.of("id", "C-1")))
        .send().join();
    engine.waitForIdleState(Duration.ofSeconds(5));

    completeUserTask(Map.of("outcome", "REJECT"));
    BpmnAssert.assertThat(pi)
        .hasPassedElement("EVT10333471331630") // ThrowCustRejectionFault
        .hasPassedElement("EVT10333782299420") // RaiseCustomerRejection (signal)
        .isCompleted();
  }

  private ProcessInstanceEvent start(String processId, String sensitivity) throws Exception {
    ProcessInstanceEvent pi = client.newCreateInstanceCommand()
        .bpmnProcessId(processId).latestVersion()
        .variables(Map.of("VerificationProcessIN", Map.of("FNOL", Map.of("sensitivity", sensitivity))))
        .send().join();
    engine.waitForIdleState(Duration.ofSeconds(5));
    return pi;
  }

  private void deploy(String sample) throws Exception {
    String xml = jobUserTasks.convert(SampleConversionTest.SAMPLES.resolve(sample)).bpmnXml();
    client.newDeployResourceCommand().addResourceStringUtf8(xml, sample).send().join();
  }

  private void completeUserTask(Map<String, Object> variables) throws Exception {
    List<ActivatedJob> jobs = client.newActivateJobsCommand()
        .jobType(USER_TASK_JOB).maxJobsToActivate(1).send().join().getJobs();
    assertEquals(1, jobs.size(), "expected one open user task");
    client.newCompleteCommand(jobs.get(0).getKey()).variables(variables).send().join();
    engine.waitForIdleState(Duration.ofSeconds(5));
  }

  @SuppressWarnings("unused")
  private static String read(Path p) throws Exception {
    return Files.readString(p);
  }
}
