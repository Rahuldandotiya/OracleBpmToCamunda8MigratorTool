package io.github.rahuldandotiya.o2c8.convert;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.attr;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.ownText;

import io.github.rahuldandotiya.o2c8.expression.XPathToFeel;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

/** Converts a {@code bpmn:process} (and, recursively, sub-process contents). */
public final class ProcessConverter {

  /** Elements consumed elsewhere or intentionally dropped without a report line. */
  private static final Set<String> SILENT =
      Set.of(
          "extensionElements",
          "documentation",
          "ioSpecification",
          "dataInput",
          "dataOutput",
          "inputSet",
          "outputSet",
          "dataInputAssociation",
          "dataOutputAssociation",
          "property",
          "resourceRole",
          "laneSet",
          "sequenceFlow",
          "incoming",
          "outgoing",
          "multiInstanceLoopCharacteristics",
          "standardLoopCharacteristics",
          "loopCharacteristics");

  private ProcessConverter() {}

  /** Lane info taken from Oracle: stacking offset and thickness in pool coordinates. */
  public record LaneInfo(String id, String name, String role, double offset, double thickness) {}

  public static Element convertProcess(Element source, Element definitions, ConversionContext ctx) {
    String id = source.getAttribute("id");
    ctx.processId(id);
    Element process = ctx.bpmn("process");
    process.setAttribute("id", ctx.claimId(id));
    if (attr(source, "name") != null) {
      process.setAttribute("name", source.getAttribute("name"));
    }
    process.setAttribute("isExecutable", "true");
    definitions.appendChild(process);

    List<LaneInfo> lanes = lanes(source);
    assignLaneRoles(source, lanes, ctx);

    convertFlowElements(source, process, ctx);
    writeLaneSet(source, process, lanes, ctx);
    return process;
  }

  public static List<LaneInfo> lanes(Element process) {
    List<LaneInfo> out = new ArrayList<>();
    double next = 0;
    for (Element ls : children(process, Ns.BPMN, "laneSet")) {
      for (Element lane : children(ls, Ns.BPMN, "lane")) {
        OracleExtensions ox = OracleExtensions.of(lane);
        double offset = ox.position().map(p -> p[0]).orElse(next);
        double thick = ox.size().map(s -> s[0]).filter(v -> v > 0).orElse(200d);
        String name = attr(lane, "name");
        String role = ox.laneRole().orElse(name);
        out.add(new LaneInfo(lane.getAttribute("id"), name, role, offset, thick));
        next = offset + thick;
      }
    }
    return out;
  }

  /** Lane membership: explicit flowNodeRef, else the lane whose band contains the node's centre. */
  private static void assignLaneRoles(Element process, List<LaneInfo> lanes, ConversionContext ctx) {
    if (lanes.isEmpty()) {
      return;
    }
    for (Element node : children(process, Ns.BPMN, null)) {
      Optional<double[]> p = OracleExtensions.of(node).position();
      if (p.isEmpty()) {
        continue;
      }
      LaneInfo lane = laneAt(lanes, p.get()[1]);
      ctx.laneRole(node.getAttribute("id"), lane.role());
    }
  }

  public static LaneInfo laneAt(List<LaneInfo> lanes, double y) {
    for (LaneInfo l : lanes) {
      if (y >= l.offset() && y < l.offset() + l.thickness()) {
        return l;
      }
    }
    return y < lanes.get(0).offset() ? lanes.get(0) : lanes.get(lanes.size() - 1);
  }

  private static void writeLaneSet(
      Element source, Element process, List<LaneInfo> lanes, ConversionContext ctx) {
    if (lanes.isEmpty()) {
      return;
    }
    Element laneSet = ctx.bpmn("laneSet");
    laneSet.setAttribute("id", ctx.uniqueId("LaneSet_" + process.getAttribute("id")));
    Map<String, Element> laneEls = new LinkedHashMap<>();
    for (LaneInfo l : lanes) {
      Element lane = ctx.bpmn("lane");
      lane.setAttribute("id", ctx.claimId(l.id()));
      if (l.name() != null) {
        lane.setAttribute("name", l.name());
      }
      laneSet.appendChild(lane);
      laneEls.put(l.id(), lane);
    }
    for (Element node : children(process, Ns.BPMN, null)) {
      String ln = node.getLocalName();
      if (ln.equals("sequenceFlow") || ln.equals("laneSet") || ln.equals("extensionElements")
          || ln.equals("documentation")) {
        continue;
      }
      Element src = byId(source, node.getAttribute("id"));
      if (src == null) {
        continue;
      }
      Optional<double[]> p = OracleExtensions.of(src).position();
      if (p.isEmpty() && !ln.equals("boundaryEvent")) {
        continue;
      }
      double y =
          p.map(v -> v[1])
              .orElseGet(
                  () -> {
                    Element host = byId(source, io.github.rahuldandotiya.o2c8.convert.elements.EventConverter.localRef(src.getAttribute("attachedToRef")));
                    return host == null ? 0 : OracleExtensions.of(host).position().map(v -> v[1]).orElse(0d);
                  });
      LaneInfo l = laneAt(lanes, y);
      Element ref = ctx.bpmn("flowNodeRef");
      ref.setTextContent(node.getAttribute("id"));
      laneEls.get(l.id()).appendChild(ref);
    }
    // laneSet must come before flow elements (BPMN schema order)
    Element first = null;
    for (Element c : children(process, Ns.BPMN, null)) {
      if (!c.getLocalName().equals("documentation") && !c.getLocalName().equals("extensionElements")) {
        first = c;
        break;
      }
    }
    process.insertBefore(laneSet, first);
  }

  private static Element byId(Element container, String id) {
    for (Element e : children(container, Ns.BPMN, null)) {
      if (id.equals(e.getAttribute("id"))) {
        return e;
      }
    }
    return null;
  }

  /** Converts flow nodes, then sequence flows, then fixes gateway defaults and incoming/outgoing. */
  static void convertFlowElements(Element source, Element target, ConversionContext ctx) {
    List<Element> flows = new ArrayList<>();
    for (Element child : children(source, Ns.BPMN, null)) {
      String ln = child.getLocalName();
      if (ln.equals("sequenceFlow")) {
        flows.add(child);
        continue;
      }
      if (SILENT.contains(ln)) {
        continue;
      }
      if (ln.equals("dataObject") || ln.equals("dataObjectReference")) {
        String type =
            OracleExtensions.of(child).typeRef().map(t -> " of type " + t.name()).orElse("");
        ctx.report(
            child,
            Level.INFO,
            "Data object becomes process variable '" + child.getAttribute("name") + "'" + type
                + " (JSON instead of XML).");
        continue;
      }
      if (ln.equals("textAnnotation") || ln.equals("association") || ln.equals("group")) {
        ctx.report(child, Level.INFO, "Artifact '" + ln + "' not carried over.");
        continue;
      }
      Optional<ElementConverter> conv = ctx.converters().find(child);
      if (conv.isEmpty()) {
        ctx.report(
            child,
            Level.MANUAL,
            "No converter for Oracle element '" + ln + "'. Model it by hand in Camunda Modeler.");
        continue;
      }
      conv.get().convert(child, target, ctx);
    }
    for (Element f : flows) {
      convertSequenceFlow(f, target, ctx);
    }
    fixGateways(target, ctx);
    StartPatterns.apply(source, target, ctx);
    wireIncomingOutgoing(target, ctx);
  }

  private static void convertSequenceFlow(Element src, Element target, ConversionContext ctx) {
    String s = src.getAttribute("sourceRef");
    String t = src.getAttribute("targetRef");
    if (byId(target, s) == null || byId(target, t) == null) {
      ctx.report(
          src,
          Level.MANUAL,
          "Sequence flow dropped: source or target element was not converted (" + s + " -> " + t + ").");
      return;
    }
    Element flow = ctx.bpmn("sequenceFlow");
    String flowId = src.getAttribute("id");
    if (ctx.isUsed(flowId)) {
      String unique = ctx.uniqueId(flowId);
      ctx.report(src, Level.INFO, "Id '" + flowId + "' is used twice in the Oracle model (allowed per scope in Oracle, "
          + "not in Camunda); this flow was renamed to '" + unique + "'.");
      flowId = unique;
    } else {
      ctx.claimId(flowId);
    }
    flow.setAttribute("id", flowId);
    String name = attr(src, "name");
    if (name != null && !name.equals(src.getAttribute("id"))) {
      flow.setAttribute("name", name); // Oracle repeats the id as name; skip that noise
    }
    flow.setAttribute("sourceRef", s);
    flow.setAttribute("targetRef", t);
    target.appendChild(flow);

    Optional<Element> cond = child(src, Ns.BPMN, "conditionExpression");
    if (cond.isPresent()) {
      String xpath = ownText(cond.get());
      XPathToFeel.Result r = XPathToFeel.translate(xpath);
      Element ce = ctx.bpmn("conditionExpression");
      ce.setAttributeNS(Ns.XSI, "xsi:type", "bpmn:tFormalExpression");
      if (r.ok()) {
        ce.setTextContent("=" + r.feel());
        ctx.report(src, Level.AUTO, "Condition converted: " + xpath + "  ->  =" + r.feel());
      } else {
        ce.setTextContent("=false");
        ctx.addDocumentation(flow, "TODO(oracle2c8): original XPath condition: " + xpath);
        ctx.report(
            src,
            Level.MANUAL,
            "Condition could not be translated (" + r.problem() + "). Placeholder '=false' written; "
                + "original XPath kept in documentation: " + xpath);
      }
      flow.appendChild(ce);
    }
  }

  /** Oracle leaves the unconditional branch implicit; Zeebe needs it as the gateway default flow. */
  private static void fixGateways(Element target, ConversionContext ctx) {
    for (Element gw : children(target, Ns.BPMN, null)) {
      String ln = gw.getLocalName();
      if (!ln.equals("exclusiveGateway") && !ln.equals("inclusiveGateway")) {
        continue;
      }
      String id = gw.getAttribute("id");
      List<Element> outgoing = new ArrayList<>();
      for (Element f : children(target, Ns.BPMN, "sequenceFlow")) {
        if (id.equals(f.getAttribute("sourceRef"))) {
          outgoing.add(f);
        }
      }
      if (outgoing.size() < 2) {
        continue;
      }
      List<Element> unconditional =
          outgoing.stream().filter(f -> child(f, Ns.BPMN, "conditionExpression").isEmpty()).toList();
      if (unconditional.size() == 1) {
        if (!gw.hasAttribute("default")) {
          gw.setAttribute("default", unconditional.get(0).getAttribute("id"));
        }
        ctx.reportObject()
            .add(ctx.processId(), id, attr(gw, "name"), ln, Level.AUTO,
                "Unconditional branch " + unconditional.get(0).getAttribute("id")
                    + " set as default flow.");
      } else if (unconditional.size() > 1) {
        for (Element f : unconditional) {
          if (f.getAttribute("id").equals(gw.getAttribute("default"))) {
            continue;
          }
          Element ce = ctx.bpmn("conditionExpression");
          ce.setAttributeNS(Ns.XSI, "xsi:type", "bpmn:tFormalExpression");
          ce.setTextContent("=false");
          f.appendChild(ce);
        }
        ctx.reportObject()
            .add(ctx.processId(), id, attr(gw, "name"), ln, Level.MANUAL,
                unconditional.size() + " outgoing flows have no condition. Placeholders '=false' "
                    + "were added; write the real conditions.");
      }
    }
  }

  /** Adds bpmn:incoming / bpmn:outgoing refs (schema order: after extensionElements). */
  private static void wireIncomingOutgoing(Element target, ConversionContext ctx) {
    Map<String, List<String>> in = new LinkedHashMap<>();
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Element f : children(target, Ns.BPMN, "sequenceFlow")) {
      out.computeIfAbsent(f.getAttribute("sourceRef"), k -> new ArrayList<>()).add(f.getAttribute("id"));
      in.computeIfAbsent(f.getAttribute("targetRef"), k -> new ArrayList<>()).add(f.getAttribute("id"));
    }
    for (Element node : children(target, Ns.BPMN, null)) {
      String id = node.getAttribute("id");
      if (!in.containsKey(id) && !out.containsKey(id)) {
        continue;
      }
      org.w3c.dom.Node before = null;
      for (Element c : children(node, Ns.BPMN, null)) {
        if (!c.getLocalName().equals("documentation") && !c.getLocalName().equals("extensionElements")) {
          before = c;
          break;
        }
      }
      for (String f : in.getOrDefault(id, List.of())) {
        Element r = ctx.bpmn("incoming");
        r.setTextContent(f);
        node.insertBefore(r, before);
      }
      for (String f : out.getOrDefault(id, List.of())) {
        Element r = ctx.bpmn("outgoing");
        r.setTextContent(f);
        node.insertBefore(r, before);
      }
    }
  }
}
