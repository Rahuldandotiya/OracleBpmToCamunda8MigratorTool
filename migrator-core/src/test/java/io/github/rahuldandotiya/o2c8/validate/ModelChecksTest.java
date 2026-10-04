package io.github.rahuldandotiya.o2c8.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.layout.OracleDiagramRenderer;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Schema check, Camunda timer/reference rules, timer conversion and the Oracle diagram renderer. */
class ModelChecksTest {

  static final Path PROCESSES = Path.of("..", "samples", "oracle-bpm-12c", "loan-origination", "LoanOrigination",
      "SOA", "processes");

  private static String converted(String file) throws Exception {
    return new OracleToCamundaConverter().convert(PROCESSES.resolve(file)).bpmnXml();
  }

  @Test
  void schemaAcceptsConvertedModelsAndRejectsBrokenOnes() throws Exception {
    String ok = converted("LOProcessSendReceive.bpmn");
    assertEquals(List.of(), BpmnSchemaValidator.validate(ok));
    List<String> unknown = BpmnSchemaValidator.validate(ok.replace("<bpmn:task ", "<bpmn:taskk ")
        .replace("</bpmn:task>", "</bpmn:taskk>"));
    assertTrue(!unknown.isEmpty() && unknown.get(0).startsWith("BPMN schema: "), unknown.toString());
    List<String> missing = BpmnSchemaValidator.validate(ok.replaceFirst("sourceRef=\"[^\"]*\"", ""));
    assertTrue(missing.stream().anyMatch(m -> m.contains("sourceRef")), missing.toString());
  }

  /** A minimal BPMN process with one timer catch event and a timer boundary event. */
  private static final String TIMERS = """
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="t">
        <bpmn:process id="Timers" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
          <bpmn:intermediateCatchEvent id="wait" name="Wait">
            <bpmn:timerEventDefinition><bpmn:timeCycle>'PT2M'</bpmn:timeCycle></bpmn:timerEventDefinition>
          </bpmn:intermediateCatchEvent>
          <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="work"/>
          <bpmn:task id="work" name="Work"/>
          <bpmn:boundaryEvent id="reminder" attachedToRef="bpmn:work" cancelActivity="false">
            <bpmn:timerEventDefinition><bpmn:timeCycle>'PT1H'</bpmn:timeCycle></bpmn:timerEventDefinition>
          </bpmn:boundaryEvent>
          <bpmn:boundaryEvent id="timeout" attachedToRef="bpmn:work" cancelActivity="true">
            <bpmn:timerEventDefinition><bpmn:timeCycle>'R3/P1D'</bpmn:timeCycle></bpmn:timerEventDefinition>
          </bpmn:boundaryEvent>
          <bpmn:sequenceFlow id="f3" sourceRef="work" targetRef="end"/>
          <bpmn:endEvent id="end"/>
          <bpmn:sequenceFlow id="f4" sourceRef="reminder" targetRef="end2"/>
          <bpmn:endEvent id="end2"/>
          <bpmn:sequenceFlow id="f5" sourceRef="timeout" targetRef="end3"/>
          <bpmn:endEvent id="end3"/>
        </bpmn:process>
      </bpmn:definitions>""";

  @Test
  void timersFollowCamundasRulesPerEventType() throws Exception {
    var r = new OracleToCamundaConverter().convert(TIMERS.getBytes(StandardCharsets.UTF_8), "Timers.bpmn");
    String xml = r.bpmnXml();
    assertEquals(List.of(), r.report().validationIssues());
    // intermediate catch: Oracle's "cycle" is a one-off delay
    assertTrue(xml.contains(">PT2M</bpmn:timeDuration>"), xml);
    // non-interrupting boundary keeps a cycle
    assertTrue(xml.contains(">R/PT1H</bpmn:timeCycle>"), xml);
    // interrupting boundary cannot repeat: duration of one period, flagged for review
    assertTrue(xml.contains(">P1D</bpmn:timeDuration>"), xml);
    assertTrue(r.report().entries().stream().anyMatch(e -> e.elementId().equals("timeout")
        && e.message().contains("fires only once")));
    // QName references from Oracle ("bpmn:work") are written as plain ids
    assertTrue(xml.contains("attachedToRef=\"work\""), xml);
  }

  @Test
  void validatorFlagsTimersAndReferencesZeebeWouldReject() throws Exception {
    String ok = converted("LOProcessSchedule.bpmn");
    var doc = XmlUtils.parse(ok.replaceFirst("<bpmn:timeCycle([^>]*)>0 28 22 \\* \\* \\*</bpmn:timeCycle>",
        "<bpmn:timeDuration$1>P1D</bpmn:timeDuration>").getBytes(StandardCharsets.UTF_8));
    List<String> issues = Camunda8ModelValidator.validate(doc);
    assertTrue(issues.stream().anyMatch(i -> i.contains("timeDuration is not allowed on startEvent")), issues.toString());

    String send = converted("LOProcessSendReceive.bpmn");
    List<String> lanes = Camunda8ModelValidator.validate(XmlUtils.parse(send
        .replaceFirst("<bpmn:flowNodeRef>([^<]*)</bpmn:flowNodeRef>", "<bpmn:flowNodeRef>missing-node</bpmn:flowNodeRef>")
        .getBytes(StandardCharsets.UTF_8)));
    assertTrue(lanes.contains("Lane refers to unknown element 'missing-node'"), lanes.toString());
  }

  @Test
  void oracleDiagramShowsLanesNodesAndLabels() throws Exception {
    String svg = OracleDiagramRenderer.render(XmlUtils.parse(PROCESSES.resolve("LOProcessAsService.bpmn")));
    XmlUtils.parse(svg.getBytes(StandardCharsets.UTF_8)); // well-formed
    assertTrue(svg.startsWith("<svg"));
    for (String label : List.of("AutomaticHandler", "VerifyWebApplication", "AllOtherActivities", "StartLoan")) {
      assertTrue(svg.contains(label), label);
    }
    assertTrue(svg.contains("marker-end=\"url(#arr)\""), "sequence flows drawn");
  }
}
