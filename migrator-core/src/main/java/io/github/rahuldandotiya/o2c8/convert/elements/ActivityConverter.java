package io.github.rahuldandotiya.o2c8.convert.elements;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.attr;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.ownText;

import io.github.rahuldandotiya.o2c8.ConverterOptions.UserTaskImplementation;
import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.convert.DataMappings;
import io.github.rahuldandotiya.o2c8.convert.ElementConverter;
import io.github.rahuldandotiya.o2c8.expression.XPathToFeel;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

/** User, service, send, receive, script, business rule, manual and abstract tasks; call activities. */
public final class ActivityConverter implements ElementConverter {

  private static final Set<String> TASKS =
      Set.of("task", "userTask", "serviceTask", "sendTask", "receiveTask", "scriptTask",
          "businessRuleTask", "manualTask", "callActivity");

  @Override
  public boolean canConvert(Element source) {
    return Ns.BPMN.equals(source.getNamespaceURI()) && TASKS.contains(source.getLocalName());
  }

  @Override
  public Element convert(Element source, Element parent, ConversionContext ctx) {
    String kind = source.getLocalName();
    if ((kind.equals("serviceTask") || kind.equals("sendTask")) && ctx.serviceCalls() != null) {
      var call = ctx.serviceCalls().resolve(source);
      if (call.isPresent()) {
        var mapped = io.github.rahuldandotiya.o2c8.connectors.ServiceCallMapper.map(source, parent, ctx, call.get());
        if (mapped.isPresent()) {
          loop(source, mapped.get(), ctx);
          return mapped.get();
        }
      }
    }
    Element e = switch (kind) {
      case "userTask" -> userTask(source, parent, ctx);
      case "serviceTask", "sendTask", "scriptTask", "businessRuleTask" -> jobTask(source, parent, ctx, kind);
      case "receiveTask" -> receiveTask(source, parent, ctx);
      case "callActivity" -> callActivity(source, parent, ctx);
      case "manualTask" -> {
        Element m = ctx.createLike(source, "manualTask", parent);
        ctx.report(source, Level.AUTO, "Manual task (pass-through in Camunda 8).");
        yield m;
      }
      default -> {
        Element t = ctx.createLike(source, "task", parent);
        ctx.report(source, Level.PARTIAL,
            "Abstract (undefined) task kept as a pass-through task. Decide its implementation "
                + "(service task, user task, script) in Camunda Modeler.");
        yield t;
      }
    };
    if (!kind.equals("manualTask") && !kind.equals("task")) {
      DataMappings.apply(source, e, ctx, true, true);
    }
    loop(source, e, ctx);
    return e;
  }

  private Element userTask(Element source, Element parent, ConversionContext ctx) {
    OracleExtensions ox = OracleExtensions.of(source);
    Element e = ctx.createLike(source, "userTask", parent);
    boolean camunda = ctx.options().userTaskImplementation() == UserTaskImplementation.CAMUNDA_USER_TASK;
    if (camunda) {
      ctx.addZeebe(e, "userTask");
    }

    String role = child(source, Ns.BPMN, "resourceRole").map(r -> attr(r, "name"))
        .orElse(ctx.laneRole(source.getAttribute("id")));
    if (role != null) {
      Element a = ctx.addZeebe(e, "assignmentDefinition");
      a.setAttribute("candidateGroups", role);
    }

    Optional<String> prio = ox.attributeExpression("priorityExpressionFeature");
    if (camunda && prio.isPresent() && prio.get().trim().matches("\\d+")) {
      int oracle = Integer.parseInt(prio.get().trim());
      Element p = ctx.addZeebe(e, "priorityDefinition");
      p.setAttribute("priority", String.valueOf(priority(oracle)));
    }

    Optional<OracleExtensions.TypeRef> ht = ox.humanTaskRef();
    String taskFile = ht.map(t -> t.name() + ".task").orElse("the Oracle .task file");
    ht.ifPresent(t -> ctx.addDocumentation(e, "Oracle human task: " + t.name()
        + (t.namespace() == null ? "" : " (" + t.namespace() + ")")));
    String taskType = ox.feature("humanTaskType").orElse("SIMPLE");
    if (!taskType.equals("SIMPLE")) {
      ctx.report(source, Level.INFO, switch (taskType) {
        case "INITIATOR" -> "Oracle initiator task: the person who starts the process completes it. Consider a start "
            + "form on the start event instead (zeebe:formDefinition) or keep this user task.";
        case "FYI" -> "Oracle FYI task (notification only): consider a notification or a send task instead.";
        case "GROUP", "MANAGEMENT", "COMPLEX" -> "Oracle " + taskType.toLowerCase(java.util.Locale.ROOT)
            + " task: its routing/approval chain from the .task file must be modelled explicitly in Camunda.";
        default -> "Oracle human task type " + taskType + ".";
      });
    }
    ctx.report(source, Level.PARTIAL,
        "User task" + (role == null ? "" : " for candidate group '" + role + "'")
            + (prio.isPresent() ? ", Oracle priority " + prio.get().trim() : "")
            + ". Rebuild the form as a Camunda Form and check assignment/escalation rules from "
            + taskFile + ". Oracle outcome is returned in the variable 'outcome'.");
    return e;
  }

  private Element jobTask(Element source, Element parent, ConversionContext ctx, String kind) {
    Element e = ctx.createLike(source, kind, parent);
    String base = Optional.ofNullable(attr(source, "name")).orElse(source.getAttribute("id"));
    String operation = attr(source, "operationRef");
    Element td = ctx.addZeebe(e, "taskDefinition");
    td.setAttribute("type", jobType(base));
    String what = switch (kind) {
      case "sendTask" -> "Send task (Oracle message/service call)";
      case "scriptTask" -> "Script task";
      case "businessRuleTask" -> "Business rule task (Oracle Business Rules)";
      default -> "Service task";
    };
    String hint = switch (kind) {
      case "businessRuleTask" -> " Convert the Oracle decision table to DMN and use zeebe:calledDecision instead.";
      case "scriptTask" -> " Or turn simple assignments into a FEEL script (zeebe:script).";
      default -> " Or apply a REST/SOAP connector template.";
    };
    if (kind.equals("sendTask") && OracleExtensions.of(source).definedInterfaceOperation().isPresent()) {
      ctx.report(source, Level.PARTIAL, "Asynchronous reply '" + OracleExtensions.of(source).definedInterfaceOperation().get()
          + "' to the process's caller (Oracle callback). Job worker '" + td.getAttribute("type") + "' generated: "
          + "implement it to call the caller's callback endpoint, or let the caller read the result instead.");
      return e;
    }
    ctx.report(source, Level.PARTIAL,
        what + " needs a job worker for type '" + td.getAttribute("type") + "'"
            + (operation == null ? "" : " (Oracle operation " + operation + ")") + "." + hint);
    return e;
  }

  private Element receiveTask(Element source, Element parent, ConversionContext ctx) {
    Element e = ctx.createLike(source, "receiveTask", parent);
    String name = OracleExtensions.of(source).definedInterfaceOperation().map(op -> ctx.processId() + "." + op)
        .orElse(Optional.ofNullable(attr(source, "messageRef"))
            .orElse(Optional.ofNullable(attr(source, "name")).orElse(source.getAttribute("id"))));
    String msgId = ctx.message(name);
    e.setAttribute("messageRef", msgId);
    ctx.messageSubscription(msgId, ctx.options().correlationKeyPlaceholder());
    ctx.report(source, Level.PARTIAL,
        "Receive task waits for message '" + name + "'; replace correlation key placeholder '"
            + ctx.options().correlationKeyPlaceholder() + "'.");
    return e;
  }

  private Element callActivity(Element source, Element parent, ConversionContext ctx) {
    Element e = ctx.createLike(source, "callActivity", parent);
    String called = attr(source, "calledElement");
    Element ce = ctx.addZeebe(e, "calledElement");
    if (called != null) {
      String processId = called.contains(":") ? called.substring(called.indexOf(':') + 1) : called;
      ce.setAttribute("processId", processId);
      ce.setAttribute("propagateAllChildVariables", "false");
      ctx.report(source, Level.AUTO, "Calls process '" + processId + "'.");
    } else {
      ce.setAttribute("processId", "TODO_called_process");
      ce.setAttribute("propagateAllChildVariables", "false");
      ctx.report(source, Level.MANUAL, "Called process not found in the Oracle model; set zeebe:calledElement.");
    }
    return e;
  }

  /** Multi-instance / standard loop characteristics of any activity, including sub-processes. */
  public static void loop(Element source, Element target, ConversionContext ctx) {
    Optional<Element> mi = multiInstance(source);
    if (standardLoop(source)) {
      ctx.report(source, Level.MANUAL,
          "Standard (while) loops are not supported by Camunda 8; model the loop with a gateway and a loop-back flow.");
    }
    if (mi.isEmpty()) {
      return;
    }
    Element l = ctx.bpmn("multiInstanceLoopCharacteristics");
    if ("true".equals(mi.get().getAttribute("isSequential"))) {
      l.setAttribute("isSequential", "true");
    }
    // schema order: loopCharacteristics come before a sub-process's flow elements
    org.w3c.dom.Node before = null;
    for (Element c : children(target, Ns.BPMN, null)) {
      if (!java.util.Set.of("documentation", "extensionElements", "incoming", "outgoing").contains(c.getLocalName())) {
        before = c;
        break;
      }
    }
    target.insertBefore(l, before);
    Element ext = ctx.bpmn("extensionElements");
    Element zl = ctx.zeebe("loopCharacteristics");
    String inputRef = child(mi.get(), Ns.BPMN, "loopDataInputRef").map(x -> x.getTextContent().trim())
        .filter(x -> !x.isEmpty()).orElse(null);
    String outputRef = child(mi.get(), Ns.BPMN, "loopDataOutputRef").map(x -> x.getTextContent().trim())
        .filter(x -> !x.isEmpty()).orElse(null);
    String cardinality = child(mi.get(), Ns.BPMN, "loopCardinality").map(x -> ownText(x))
        .filter(x -> !x.isBlank()).orElse(null);
    List<String> notes = new java.util.ArrayList<>();
    Level level = Level.AUTO;
    if (inputRef != null) {
      // the loop input is a data input of the activity, filled by an association from a data object
      String collection = associationExpression(source, inputRef, true);
      XPathToFeel.Result r = collection == null ? null : XPathToFeel.translate(collection);
      if (r != null && r.ok()) {
        zl.setAttribute("inputCollection", "=" + r.feel());
      } else {
        zl.setAttribute("inputCollection", "=" + XPathToFeel.feelNameOf(inputRef));
        level = Level.PARTIAL;
        notes.add("input collection: check that variable '" + inputRef + "' holds the list");
      }
      zl.setAttribute("inputElement", inputRef + "Item");
      notes.add("each instance gets one element as '" + inputRef + "Item'");
    } else if (cardinality != null) {
      XPathToFeel.Result r = XPathToFeel.translate(cardinality);
      zl.setAttribute("inputCollection", "=for i in 1.." + (r.ok() ? r.feel() : "1") + " return i");
      if (!r.ok()) {
        level = Level.MANUAL;
        notes.add("cardinality " + cardinality + " could not be translated (" + r.problem() + ")");
      } else {
        notes.add("cardinality " + r.feel() + " becomes a generated collection");
      }
    } else {
      zl.setAttribute("inputCollection", "=[]");
      level = Level.MANUAL;
      notes.add("no input collection or cardinality found");
    }
    if (outputRef != null) {
      String target2 = associationExpression(source, outputRef, false);
      XPathToFeel.Result t = target2 == null ? null : XPathToFeel.translateTarget(target2);
      if (t != null && t.ok()) {
        zl.setAttribute("outputCollection", t.feel());
        zl.setAttribute("outputElement", "=" + (inputRef == null ? "loopCounter" : inputRef + "Item"));
        level = level == Level.AUTO ? Level.PARTIAL : level;
        notes.add("results collected into '" + t.feel() + "'; check the output element");
      }
    }
    String completion = child(mi.get(), Ns.BPMN, "completionCondition").map(x -> ownText(x))
        .filter(x -> !x.isBlank()).orElse(null);
    if (completion != null) {
      XPathToFeel.Result c = XPathToFeel.translate(completion);
      if (c.ok()) {
        Element cc = ctx.bpmn("completionCondition");
        cc.setAttributeNS(Ns.XSI, "xsi:type", "bpmn:tFormalExpression");
        cc.setTextContent("=" + c.feel());
        l.appendChild(cc);
        notes.add("completion condition " + c.feel());
      } else {
        level = Level.MANUAL;
        notes.add("completion condition not translated: " + completion + " (" + c.problem() + ")");
      }
    }
    ext.appendChild(zl);
    l.insertBefore(ext, l.getFirstChild());
    ctx.report(source, level, "Multi-instance (" + ("true".equals(mi.get().getAttribute("isSequential"))
        ? "sequential" : "parallel") + "): " + String.join("; ", notes) + ".");
  }

  /** {@code multiInstanceLoopCharacteristics}, or Oracle's {@code loopCharacteristics xsi:type=tMultiInstance...}. */
  public static Optional<Element> multiInstance(Element source) {
    Optional<Element> mi = child(source, Ns.BPMN, "multiInstanceLoopCharacteristics");
    if (mi.isPresent()) {
      return mi;
    }
    return child(source, Ns.BPMN, "loopCharacteristics")
        .filter(l -> l.getAttributeNS(Ns.XSI, "type").contains("MultiInstance"));
  }

  static boolean standardLoop(Element source) {
    return child(source, Ns.BPMN, "standardLoopCharacteristics").isPresent()
        || child(source, Ns.BPMN, "loopCharacteristics")
            .filter(l -> l.getAttributeNS(Ns.XSI, "type").contains("StandardLoop")).isPresent();
  }

  /** XPath on the far side of the association that feeds (input) or reads (output) a data input/output. */
  private static String associationExpression(Element source, String ioId, boolean input) {
    String assocName = input ? "dataInputAssociation" : "dataOutputAssociation";
    for (Element a : children(source, Ns.BPMN, assocName)) {
      String ref = child(a, Ns.BPMN, input ? "targetRef" : "sourceRef").map(x -> x.getTextContent().trim()).orElse("");
      if (!ref.equals(ioId)) {
        continue;
      }
      for (Element as : children(a, Ns.BPMN, "assignment")) {
        String side = child(as, Ns.BPMN, input ? "from" : "to").map(XmlUtils::ownText).orElse(null);
        if (side != null && !side.isBlank()) {
          return side;
        }
      }
      String other = child(a, Ns.BPMN, input ? "sourceRef" : "targetRef").map(x -> x.getTextContent().trim()).orElse(null);
      if (other != null && !other.isEmpty()) {
        return "bpmn:getDataObject('" + other + "')";
      }
    }
    return null;
  }

  /** Oracle priority 1 (highest) .. 5 (lowest) → Camunda 0..100 (higher is more urgent). */
  static int priority(int oracle) {
    int clamped = Math.max(1, Math.min(5, oracle));
    return 100 - (clamped - 1) * 25;
  }

  /** Job type from an element name: "Check Fraud Score" → "check-fraud-score". */
  public static String jobType(String name) {
    String kebab = name.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
        .replaceAll("[^A-Za-z0-9]+", "-")
        .replaceAll("^-|-$", "")
        .toLowerCase(Locale.ROOT);
    return kebab.isEmpty() ? "task" : kebab;
  }
}
