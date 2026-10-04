package io.github.rahuldandotiya.o2c8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Converts every process of the loan-origination sample (a real Oracle BPM 12c project) one file
 * at a time, without composite.xml, and checks the output against Camunda 8 rules.
 */
class SampleConversionTest {

  static final Path SAMPLE_PROJECT = Path.of("..", "samples", "oracle-bpm-12c", "loan-origination");
  static final Path SAMPLES = SAMPLE_PROJECT.resolve(Path.of("LoanOrigination", "SOA", "processes"));

  static Stream<Path> samples() throws IOException {
    return Files.list(SAMPLES).filter(p -> p.toString().endsWith(".bpmn")).sorted();
  }

  private final OracleToCamundaConverter converter = new OracleToCamundaConverter();

  private String xml(String file) throws IOException {
    return converter.convert(SAMPLES.resolve(file)).bpmnXml();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("samples")
  void convertsWithoutValidationIssues(Path sample) throws IOException {
    ConversionResult r = converter.convert(sample);
    assertEquals(List.of(), r.report().validationIssues());
    String xml = r.bpmnXml();
    assertFalse(xml.contains("bpmnext"), "Oracle extensions must be stripped");
    assertTrue(xml.contains("bpmndi:BPMNDiagram"), "diagram must be generated");
    assertTrue(xml.contains("modeler:executionPlatform=\"Camunda Cloud\""));
    assertTrue(r.report().entries().stream().noneMatch(e -> e.level() == Level.MANUAL),
        () -> "unexpected MANUAL items: " + r.report().entries());
  }

  @Test
  void userTaskGetsCandidateGroupPriorityAndMappings() throws IOException {
    String xml = xml("LOProcessHumanInitiation.bpmn");
    assertTrue(xml.contains("<zeebe:userTask/>"));
    assertTrue(xml.contains("candidateGroups=\"LoanOfficer\""));
    assertTrue(xml.contains("<zeebe:priorityDefinition priority=\"75\"/>"), "Oracle priority 2 -> 75");
    assertTrue(xml.contains("<zeebe:input source=\"=lOProcessHumanInitiationINPDO\" target=\"loanRequest\"/>"));
    assertTrue(xml.contains("<zeebe:output source=\"=outcome\" target=\"taskOutcome\"/>"));
    assertFalse(xml.contains("execData"), "Oracle task system payload is dropped");
  }

  @Test
  void gatewayConditionsBecomeFeelAndImplicitBranchBecomesDefault() throws IOException {
    String xml = xml("LOProcessAsService.bpmn");
    assertTrue(xml.contains(">=(1 = 2)</bpmn:conditionExpression>"));
    assertTrue(xml.contains("default=\"sf1046338177843\""));
    assertTrue(xml.contains("<bpmn:terminateEventDefinition"));
    assertTrue(xml(("LOProcessOneRequestTwoResponse.bpmn")).contains("default=\"sf10463857490314\""));
  }

  @Test
  void businessEventStartBecomesSignalStart() throws IOException {
    String xml = xml("LOProcessActivationFromEvent.bpmn");
    assertTrue(xml.contains("name=\"LoanOriginationEvent\""));
    assertTrue(xml.contains("<bpmn:signalEventDefinition"));
  }

  @Test
  void instantiatingEventGatewayBecomesMessageStartEvents() throws IOException {
    String xml = xml("LOProcessMultiEvent.bpmn");
    assertFalse(xml.contains("eventBasedGateway"), "Zeebe does not support instantiating event-based gateways");
    assertEquals(2, count(xml, "<bpmn:startEvent "));
    assertEquals(2, count(xml, "<bpmn:messageEventDefinition "));
  }

  @Test
  void instantiatingReceiveTaskIsMergedIntoTheStartEvent() throws IOException {
    String xml = xml("LOProcessSendReceive.bpmn");
    assertFalse(xml.contains("<bpmn:receiveTask"));
    assertEquals(1, count(xml, "<bpmn:startEvent "));
    assertTrue(xml.contains("<bpmn:messageEventDefinition "));
    assertTrue(xml.contains("type=\"send-loan-origination-resp\""), "async reply becomes a job worker");
  }

  @Test
  void oracleScheduleBecomesCronAndIsoCycleGetsRepetitionPrefix() throws IOException {
    String xml = xml("LOProcessSchedule.bpmn");
    assertTrue(xml.contains(">0 28 22 * * *</bpmn:timeCycle>"), "daily 22:28");
    assertTrue(xml.contains(">R/PT2M</bpmn:timeCycle>"));
  }

  @Test
  void lanesAreKeptInAPool() throws IOException {
    String xml = xml("LOProcessHumanInitiation.bpmn");
    assertTrue(xml.contains("<bpmn:participant id=\"Participant_LOProcessHumanInitiation\""));
    assertTrue(xml.contains("<bpmn:lane id=\"lane1\" name=\"LoanOfficer\">"));
  }

  @Test
  void jobWorkerModeLeavesOutCamundaUserTaskMarker() throws IOException {
    var options = ConverterOptions.defaults()
        .withUserTaskImplementation(ConverterOptions.UserTaskImplementation.JOB_WORKER);
    String xml = new OracleToCamundaConverter(options).convert(SAMPLES.resolve("LOProcessHumanInitiation.bpmn")).bpmnXml();
    assertFalse(xml.contains("zeebe:userTask"));
  }

  @Test
  void rejectsXmlWithDoctype() {
    byte[] evil = """
        <?xml version="1.0"?>
        <!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <x>&xxe;</x>""".getBytes();
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> converter.convert(evil, "evil.bpmn"));
  }

  static int count(String text, String needle) {
    int n = 0;
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }
}
