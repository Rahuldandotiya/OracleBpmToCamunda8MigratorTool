package io.github.rahuldandotiya.o2c8.validate;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants;

import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Offline checks for the rules Zeebe applies at deployment, plus diagram completeness. Not a full
 * replacement for deploying to an engine (the test suite does that), but it catches the common
 * reasons a migrated model is rejected, without any dependency.
 */
public final class Camunda8ModelValidator {

  private static final Set<String> JOB_TASKS = Set.of("serviceTask", "sendTask", "scriptTask", "businessRuleTask");
  private static final Set<String> SUPPORTED_NODES =
      Set.of("startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent",
          "task", "userTask", "serviceTask", "sendTask", "receiveTask", "scriptTask", "businessRuleTask",
          "manualTask", "callActivity", "subProcess", "adHocSubProcess", "exclusiveGateway",
          "parallelGateway", "inclusiveGateway", "eventBasedGateway");

  private Camunda8ModelValidator() {}

  public static List<String> validate(Document doc) {
    List<String> issues = new ArrayList<>();
    Element defs = doc.getDocumentElement();
    Map<String, Element> byId = new HashMap<>();
    for (Element e : descendants(defs, null, null)) {
      if (e.hasAttribute("id") && !e.getLocalName().equals("BPMNDiagram")) {
        if (byId.put(e.getAttribute("id"), e) != null) {
          issues.add("Duplicate id '" + e.getAttribute("id") + "'");
        }
      }
    }
    if (!descendants(defs, Ns.ORACLE, null).isEmpty()) {
      issues.add("Oracle extension elements (bpmnext) remain in the output");
    }

    Set<String> diElements = new HashSet<>();
    for (Element s : descendants(defs, Ns.BPMNDI, null)) {
      if (s.hasAttribute("bpmnElement")) {
        String ref = s.getAttribute("bpmnElement");
        diElements.add(ref);
        if (!byId.containsKey(ref)) {
          issues.add("Diagram element points to unknown id '" + ref + "'");
        }
      }
    }

    for (Element process : children(defs, Ns.BPMN, "process")) {
      if (!"true".equals(process.getAttribute("isExecutable"))) {
        issues.add("Process " + process.getAttribute("id") + " is not executable");
      }
      checkContainer(process, byId, diElements, issues);
    }
    for (Element m : children(defs, Ns.BPMN, "message")) {
      if (m.getAttribute("name").isBlank()) {
        issues.add("Message " + m.getAttribute("id") + " has no name");
      }
    }
    for (Element er : children(defs, Ns.BPMN, "error")) {
      if (er.getAttribute("errorCode").isBlank()) {
        issues.add("Error " + er.getAttribute("id") + " has no errorCode");
      }
    }
    return issues;
  }

  private static void checkContainer(Element c, Map<String, Element> byId, Set<String> di, List<String> issues) {
    String scope = c.getAttribute("id");
    List<Element> flows = children(c, Ns.BPMN, "sequenceFlow");
    for (Element n : children(c, Ns.BPMN, null)) {
      String kind = n.getLocalName();
      String id = n.getAttribute("id");
      if (kind.equals("sequenceFlow")) {
        for (String ref : new String[] {"sourceRef", "targetRef"}) {
          Element t = byId.get(n.getAttribute(ref));
          if (t == null || t.getParentNode() != c) {
            issues.add("Sequence flow " + id + " has invalid " + ref + " '" + n.getAttribute(ref) + "'");
          }
        }
        child(n, Ns.BPMN, "conditionExpression").ifPresent(ce -> {
          if (!ce.getTextContent().trim().startsWith("=")) {
            issues.add("Condition on " + id + " is not a FEEL expression (must start with '=')");
          }
        });
        if (!di.contains(id)) {
          issues.add("Sequence flow " + id + " has no diagram edge");
        }
        continue;
      }
      if (Set.of("laneSet", "extensionElements", "documentation", "incoming", "outgoing",
          "multiInstanceLoopCharacteristics", "standardLoopCharacteristics", "loopCharacteristics").contains(kind)) {
        continue;
      }
      if (!SUPPORTED_NODES.contains(kind)) {
        issues.add("Element " + id + " of type " + kind + " is not supported by Camunda 8");
        continue;
      }
      if (!di.contains(id)) {
        issues.add("Element " + id + " has no diagram shape");
      }
      if (kind.equals("boundaryEvent")) {
        Element host = byId.get(n.getAttribute("attachedToRef"));
        if (host == null || host.getParentNode() != c) {
          issues.add("Boundary event " + id + " is attached to unknown activity '" + n.getAttribute("attachedToRef") + "'");
        }
      }
      if (n.hasAttribute("default")) {
        Element f = byId.get(n.getAttribute("default"));
        if (f == null || !f.getLocalName().equals("sequenceFlow") || !id.equals(f.getAttribute("sourceRef"))) {
          issues.add("Default flow '" + n.getAttribute("default") + "' of " + id + " is not one of its outgoing flows");
        }
      }
      if (JOB_TASKS.contains(kind) && zeebe(n, "taskDefinition") == null && zeebe(n, "calledDecision") == null
          && zeebe(n, "script") == null) {
        issues.add(kind + " " + id + " has no zeebe:taskDefinition");
      }
      if (kind.equals("callActivity") && zeebe(n, "calledElement") == null) {
        issues.add("Call activity " + id + " has no zeebe:calledElement");
      }
      checkEventDefinitions(n, kind, byId, issues);
      if (kind.equals("exclusiveGateway") || kind.equals("inclusiveGateway")) {
        List<Element> out = flows.stream().filter(f -> id.equals(f.getAttribute("sourceRef"))).toList();
        if (out.size() > 1) {
          for (Element f : out) {
            if (!f.getAttribute("id").equals(n.getAttribute("default"))
                && child(f, Ns.BPMN, "conditionExpression").isEmpty()) {
              issues.add("Gateway " + id + ": flow " + f.getAttribute("id") + " needs a condition or must be the default");
            }
          }
        }
      }
      if (kind.equals("subProcess") || kind.equals("adHocSubProcess")) {
        checkContainer(n, byId, di, issues);
      }
    }
    for (Element ls : children(c, Ns.BPMN, "laneSet")) {
      for (Element ref : descendants(ls, Ns.BPMN, "flowNodeRef")) {
        if (!byId.containsKey(ref.getTextContent().trim())) {
          issues.add("Lane refers to unknown element '" + ref.getTextContent().trim() + "'");
        }
      }
    }
    if (children(c, Ns.BPMN, "startEvent").isEmpty() && c.getLocalName().equals("process")) {
      issues.add("Process " + scope + " has no start event");
    }
  }

  private static void checkEventDefinitions(Element n, String kind, Map<String, Element> byId, List<String> issues) {
    String id = n.getAttribute("id");
    for (Element d : children(n, Ns.BPMN, null)) {
      String dk = d.getLocalName();
      if (!dk.endsWith("EventDefinition")) {
        continue;
      }
      switch (dk) {
        case "messageEventDefinition" -> {
          String ref = d.getAttribute("messageRef");
          Element m = ref.isBlank() ? null : byId.get(ref);
          boolean throwing = kind.equals("intermediateThrowEvent") || kind.equals("endEvent");
          if (!ref.isBlank() && m == null) {
            issues.add("Message event " + id + " references unknown message '" + ref + "'");
          }
          if (throwing) {
            // Zeebe runs message throw events as jobs; a message reference is optional
            if (zeebe(n, "taskDefinition") == null) {
              issues.add("Message throw event " + id + " needs a zeebe:taskDefinition");
            }
            if (m != null && !hasCorrelationKey(m)) {
              issues.add("Message throw event " + id + " references message '" + ref
                  + "' without a zeebe:subscription; remove the reference or add a correlation key");
            }
          } else if (m == null) {
            issues.add("Message event " + id + " references no message");
          } else if (!kind.equals("startEvent") && !hasCorrelationKey(m)) {
            issues.add("Message catch " + id + " needs a correlation key on its message");
          }
        }
        case "signalEventDefinition" -> {
          if (byId.get(d.getAttribute("signalRef")) == null) {
            issues.add("Signal event " + id + " references no signal");
          }
        }
        case "errorEventDefinition" -> {
          if (d.hasAttribute("errorRef") && byId.get(d.getAttribute("errorRef")) == null) {
            issues.add("Error event " + id + " references unknown error");
          }
          if (kind.equals("endEvent") && !d.hasAttribute("errorRef")) {
            issues.add("Error end event " + id + " needs an error reference");
          }
        }
        case "timerEventDefinition" -> {
          List<Element> props = children(d, Ns.BPMN, null).stream()
              .filter(t -> t.getLocalName().startsWith("time")).toList();
          if (props.isEmpty()) {
            issues.add("Timer event " + id + " has no timeDate, timeDuration or timeCycle");
          }
          Set<String> allowed = io.github.rahuldandotiya.o2c8.convert.elements.EventConverter
              .allowedTimerProperties(n, (Element) n.getParentNode());
          for (Element t : props) {
            String v = t.getTextContent().trim();
            if (!allowed.contains(t.getLocalName())) {
              issues.add("Timer " + t.getLocalName() + " is not allowed on " + kind + " " + id + " (allowed: "
                  + String.join(", ", allowed) + ")");
            }
            boolean ok = switch (t.getLocalName()) {
              case "timeCycle" -> v.startsWith("=") || v.matches("^R\\d*/.+") || v.split("\\s+").length >= 5;
              case "timeDuration" -> v.startsWith("=") || v.startsWith("P");
              case "timeDate" -> v.startsWith("=") || v.matches("^\\d{4}-\\d{2}-\\d{2}T.*");
              default -> true;
            };
            if (!ok) {
              issues.add("Timer " + t.getLocalName() + " on " + id + " is not valid for Camunda: '" + v + "'");
            }
          }
        }
        case "terminateEventDefinition", "escalationEventDefinition", "compensateEventDefinition" -> { }
        default -> issues.add("Event definition " + dk + " on " + id + " is not supported");
      }
    }
  }

  private static boolean hasCorrelationKey(Element message) {
    return child(message, Ns.BPMN, "extensionElements")
        .flatMap(x -> child(x, Ns.ZEEBE, "subscription"))
        .map(s -> !s.getAttribute("correlationKey").isBlank()).orElse(false);
  }

  private static Element zeebe(Element n, String localName) {
    return child(n, Ns.BPMN, "extensionElements").flatMap(x -> child(x, Ns.ZEEBE, localName)).orElse(null);
  }
}
