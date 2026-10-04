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
 * The real acceptance test: every process of the loan-origination sample must deploy to a Zeebe
 * engine, and converted processes must run end to end. Uses the in-memory engine from zeebe-process-test (no Docker).
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
  void projectConvertedWithCompositeAndKnowledgeBaseDeploys(@org.junit.jupiter.api.io.TempDir Path tmp)
      throws Exception {
    var kbDrop = io.github.rahuldandotiya.o2c8.project.DropFolder.load(
        io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBaseFixture.create(tmp));
    var drop = io.github.rahuldandotiya.o2c8.project.DropFolder.load(SampleConversionTest.SAMPLE_PROJECT);
    var fixture = io.github.rahuldandotiya.o2c8.project.DropFolder.load(
        Path.of("src", "test", "resources", "fixtures", "claim-settlement"));
    try {
      var kb = new io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBaseBuilder(camundaUserTasks).build(kbDrop);
      for (var d : List.of(drop, fixture)) {
        for (var f : d.processes()) {
          String xml = camundaUserTasks.convert(f, d.compositeFor(f), kb).bpmnXml();
          DeploymentEvent dep = client.newDeployResourceCommand()
              .addResourceStringUtf8(xml, f.path().getFileName().toString())
              .send().join();
          assertEquals(1, dep.getProcesses().size(), f.displayPath() + " should deploy");
        }
      }
    } finally {
      kbDrop.close();
      drop.close();
      fixture.close();
    }
  }

  @Test
  void asServiceTakesTheDefaultFlowThroughTheSubProcess() throws Exception {
    deploy("LOProcessAsService.bpmn");
    ProcessInstanceEvent pi = client.newCreateInstanceCommand()
        .bpmnProcessId("LOProcessAsService").latestVersion()
        .variables(Map.of("LOProcessAsServiceIN", Map.of("loanId", "L-1")))
        .send().join();
    engine.waitForIdleState(Duration.ofSeconds(5));
    BpmnAssert.assertThat(pi)
        .hasPassedElement("ACT10463376437238")  // AllOtherActivities (default flow, condition 1 = 2 is false)
        .hasNotPassedElement("ACT10463387736533") // ApplicationRejectionTasks
        .hasPassedElement("EVT10463345141140")  // EndLoanOrigination
        .isCompleted()
        .hasVariableWithValue("lOProcessAsServiceINPDO", Map.of("loanId", "L-1"));
  }

  @Test
  void oneRequestTwoResponseRoutesByTranslatedCondition() throws Exception {
    deploy("LOProcessOneRequestTwoResponse.bpmn");
    ProcessInstanceEvent pi = client.newCreateInstanceCommand()
        .bpmnProcessId("LOProcessOneRequestTwoResponse").latestVersion()
        .send().join();
    engine.waitForIdleState(Duration.ofSeconds(5));
    BpmnAssert.assertThat(pi)
        .hasPassedElement("EVT1046384728756")   // NotApproved: condition (1 = 1) is true
        .hasNotPassedElement("EVT1046382745712") // Approved (default)
        .isCompleted();
  }

  @Test
  void humanInitiationWaitsForTheLoanOfficerTask() throws Exception {
    deploy("LOProcessHumanInitiation.bpmn");
    ProcessInstanceEvent pi = client.newCreateInstanceCommand()
        .bpmnProcessId("LOProcessHumanInitiation").latestVersion()
        .variables(Map.of("lOProcessHumanInitiationINPDO", Map.of("amount", 1000)))
        .send().join();
    engine.waitForIdleState(Duration.ofSeconds(5));
    BpmnAssert.assertThat(pi).isWaitingAtElements("ACT10471467918889"); // LOProcessHumanInitiationTask

    completeUserTask(Map.of("loanRequest", Map.of("amount", 2000), "outcome", "APPROVE"));
    BpmnAssert.assertThat(pi).isCompleted()
        .hasVariableWithValue("lOProcessHumanInitiationINPDO", Map.of("amount", 2000)) // task output mapping
        .hasVariableWithValue("taskOutcome", "APPROVE");
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
