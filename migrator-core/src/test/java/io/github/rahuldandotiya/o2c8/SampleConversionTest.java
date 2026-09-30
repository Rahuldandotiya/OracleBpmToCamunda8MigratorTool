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

/** Converts every Oracle sample in /samples and checks the output against Camunda 8 rules. */
class SampleConversionTest {

  static final Path SAMPLES = Path.of("..", "samples", "oracle-bpm-12c", "insurance-claim");

  static Stream<Path> samples() throws IOException {
    return Files.list(SAMPLES).filter(p -> p.toString().endsWith(".bpmn")).sorted();
  }

  private final OracleToCamundaConverter converter = new OracleToCamundaConverter();

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
    String xml = converter.convert(SAMPLES.resolve("FNOLProcess.bpmn")).bpmnXml();
    assertTrue(xml.contains("<zeebe:userTask/>"));
    assertTrue(xml.contains("candidateGroups=\"CSR\""));
    assertTrue(xml.contains("<zeebe:priorityDefinition priority=\"75\"/>"));
    assertTrue(xml.contains("<zeebe:input source=\"=fNOLProcessINPDO\" target=\"claim\"/>"));
    assertTrue(xml.contains("<zeebe:output source=\"=claim\" target=\"fNOLProcessINPDO\"/>"));
    assertFalse(xml.contains("execData"), "Oracle task system payload is dropped");
  }

  @Test
  void gatewayConditionsBecomeFeelAndImplicitBranchBecomesDefault() throws IOException {
    String xml = converter.convert(SAMPLES.resolve("VerificationProcess.bpmn")).bpmnXml();
    assertTrue(xml.contains("=(verificationProcessINPDO.FNOL.sensitivity != \"Expert\")"));
    assertTrue(xml.contains("default=\"sf10323962518756\""));
    assertTrue(xml.contains("candidateGroups=\"ExpertAgent\""));
    assertTrue(xml.contains("candidateGroups=\"RegularAgent\""));
  }

  @Test
  void signalsAndErrorsAreDeclaredWithOracleNames() throws IOException {
    String xml = converter.convert(SAMPLES.resolve("CustomerAcceptanceProcess.bpmn")).bpmnXml();
    assertTrue(xml.contains("<bpmn:signal id=\"Signal_CustomerRejectionEvent\" name=\"CustomerRejectionEvent\"/>"));
    assertTrue(xml.contains("errorCode=\"CustomerRejection\""));
    assertTrue(xml.contains("triggeredByEvent=\"true\""));
  }

  @Test
  void lanesAreKeptInAPool() throws IOException {
    String xml = converter.convert(SAMPLES.resolve("VerificationProcess.bpmn")).bpmnXml();
    assertTrue(xml.contains("<bpmn:participant id=\"Participant_VerificationProcess\""));
    assertTrue(xml.contains("<bpmn:lane id=\"lane0\" name=\"ExpertAgent\">"));
    assertTrue(xml.contains("<bpmn:lane id=\"lane1\" name=\"RegularAgent\">"));
  }

  @Test
  void jobWorkerModeLeavesOutCamundaUserTaskMarker() throws IOException {
    var options = ConverterOptions.defaults()
        .withUserTaskImplementation(ConverterOptions.UserTaskImplementation.JOB_WORKER);
    String xml = new OracleToCamundaConverter(options).convert(SAMPLES.resolve("FNOLProcess.bpmn")).bpmnXml();
    assertFalse(xml.contains("zeebe:userTask"));
  }

  @Test
  void rejectsXmlWithDoctype() {
    byte[] evil = """
        <?xml version="1.0"?>
        <!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <x>&xxe;</x>""".getBytes();
    assertThrowsIo(() -> converter.convert(evil, "evil.bpmn"));
  }

  private static void assertThrowsIo(org.junit.jupiter.api.function.Executable e) {
    org.junit.jupiter.api.Assertions.assertThrows(IOException.class, e);
  }
}
