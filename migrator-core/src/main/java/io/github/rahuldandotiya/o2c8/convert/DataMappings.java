package io.github.rahuldandotiya.o2c8.convert;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.ownText;

import io.github.rahuldandotiya.o2c8.expression.XPathToFeel;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * Turns Oracle {@code dataInputAssociation}/{@code dataOutputAssociation} (with XPath assignments)
 * into {@code zeebe:ioMapping} input/output mappings.
 */
public final class DataMappings {

  /** Oracle Human Workflow system payload; Camunda keeps task metadata itself. */
  private static final Set<String> SYSTEM_VARIABLES = Set.of("execData");

  private static final java.util.regex.Pattern INSTANCE_ATTRIBUTE = java.util.regex.Pattern.compile(
      "(?:bpmn:)?get(Process|Activity)InstanceAttribute\\(\\s*'([A-Za-z_][A-Za-z0-9_]*)'\\s*\\)");

  public record Mapping(String source, String target) {}

  private DataMappings() {}

  /** Adds input and/or output mappings to {@code target}. Set flags to limit what is written. */
  public static void apply(
      Element source, Element target, ConversionContext ctx, boolean inputs, boolean outputs) {
    List<Mapping> in = inputs ? collect(source, ctx, true) : List.of();
    List<Mapping> out = outputs ? collect(source, ctx, false) : List.of();
    write(target, ctx, in, out);
  }

  /** Mappings that were found but are not written (e.g. none end events cannot hold them). */
  public static List<Mapping> preview(Element source, ConversionContext ctx, boolean input) {
    return collect(source, ctx, input);
  }

  static void write(Element target, ConversionContext ctx, List<Mapping> in, List<Mapping> out) {
    if (in.isEmpty() && out.isEmpty()) {
      return;
    }
    Element io = ctx.addZeebe(target, "ioMapping");
    for (Mapping m : in) {
      Element i = ctx.zeebe("input");
      i.setAttribute("source", m.source());
      i.setAttribute("target", m.target());
      io.appendChild(i);
    }
    for (Mapping m : out) {
      Element o = ctx.zeebe("output");
      o.setAttribute("source", m.source());
      o.setAttribute("target", m.target());
      io.appendChild(o);
    }
  }

  private static List<Mapping> collect(Element source, ConversionContext ctx, boolean input) {
    String assocName = input ? "dataInputAssociation" : "dataOutputAssociation";
    Map<String, String> ioNames = ioNames(source);
    Set<String> loopRefs = new java.util.HashSet<>();
    io.github.rahuldandotiya.o2c8.convert.elements.ActivityConverter.multiInstance(source).ifPresent(mi -> {
      child(mi, Ns.BPMN, "loopDataInputRef").ifPresent(r -> loopRefs.add(r.getTextContent().trim()));
      child(mi, Ns.BPMN, "loopDataOutputRef").ifPresent(r -> loopRefs.add(r.getTextContent().trim()));
    });
    List<Mapping> result = new ArrayList<>();
    for (Element assoc : children(source, Ns.BPMN, assocName)) {
      String own = refText(assoc, input ? "targetRef" : "sourceRef");
      if (own != null && loopRefs.contains(own)) {
        continue; // the multi-instance collection is mapped by zeebe:loopCharacteristics
      }
      List<Element> assignments = children(assoc, Ns.BPMN, "assignment");
      if (assignments.isEmpty()) {
        // plain reference association: sourceRef -> targetRef
        String s = refText(assoc, "sourceRef");
        String t = refText(assoc, "targetRef");
        if (s == null || t == null) {
          continue;
        }
        s = ioNames.getOrDefault(s, s);
        t = ioNames.getOrDefault(t, t);
        if (isSystem(s) || isSystem(t)) {
          continue;
        }
        result.add(new Mapping("=" + XPathToFeelNames.feel(s), XPathToFeelNames.path(t)));
        continue;
      }
      for (Element a : assignments) {
        Element from = child(a, Ns.BPMN, "from").orElse(null);
        Element to = child(a, Ns.BPMN, "to").orElse(null);
        if (from == null || to == null) {
          continue;
        }
        String fromX = ownText(from);
        String toX = ownText(to);
        if (mentionsSystem(fromX) || mentionsSystem(toX)) {
          ctx.report(
              source, Level.INFO, "Dropped Oracle system mapping '" + toX + "' (execData payload).");
          continue;
        }
        String operation = firstDataAssignmentOp(a).orElse("copy");
        String mode = OracleExtensions.of(from).expressionMode().orElse("simple");
        XPathToFeel.Result f =
            "text".equals(mode) && !fromX.startsWith("'") && !fromX.startsWith("\"")
                ? XPathToFeel.translate("'" + fromX.replace("'", "") + "'")
                : XPathToFeel.translate(fromX);
        XPathToFeel.Result t = XPathToFeel.translateTarget(toX);
        java.util.regex.Matcher attr = INSTANCE_ATTRIBUTE.matcher(toX.trim());
        if (!t.ok() && attr.matches()) {
          // Oracle predefined instance attribute (organizationalUnit, title, priority...): keep the value
          t = XPathToFeel.translateTarget("bpmn:getDataObject('" + attr.group(2) + "')");
          ctx.report(source, Level.INFO,
              "Oracle instance attribute '" + attr.group(2) + "' is stored in process variable '"
                  + attr.group(2) + "'. Use it for tenant or candidate-group routing if needed.");
        }
        if (!f.ok() || !t.ok()) {
          ctx.report(
              source,
              Level.MANUAL,
              "Data assignment not converted: " + fromX + " -> " + toX + " ("
                  + (f.ok() ? t.problem() : f.problem()) + "). Add the mapping by hand.");
          continue;
        }
        if (!"copy".equals(operation)) {
          ctx.report(
              source,
              Level.PARTIAL,
              "Oracle assignment operation '" + operation + "' converted as a plain copy: "
                  + fromX + " -> " + toX + ". Check list/append semantics.");
        }
        result.add(new Mapping("=" + f.feel(), t.feel()));
      }
    }
    return result;
  }

  private static java.util.Optional<String> firstDataAssignmentOp(Element assignment) {
    return child(assignment, Ns.BPMN, "extensionElements")
        .flatMap(ee -> child(ee, Ns.ORACLE, "OracleExtensions"))
        .flatMap(oe -> child(oe, Ns.ORACLE, "DataAssignment"))
        .map(da -> da.getAttribute("operation"))
        .filter(s -> !s.isBlank());
  }

  /** dataInput/dataOutput id -> name (Oracle uses both; ids may be duplicated across in/out). */
  private static Map<String, String> ioNames(Element source) {
    Map<String, String> m = new HashMap<>();
    List<Element> all = new ArrayList<>(children(source, Ns.BPMN, "dataInput"));
    all.addAll(children(source, Ns.BPMN, "dataOutput"));
    child(source, Ns.BPMN, "ioSpecification")
        .ifPresent(
            io -> {
              all.addAll(children(io, Ns.BPMN, "dataInput"));
              all.addAll(children(io, Ns.BPMN, "dataOutput"));
            });
    for (Element d : all) {
      if (d.hasAttribute("id") && d.hasAttribute("name")) {
        m.put(d.getAttribute("id"), d.getAttribute("name"));
      }
    }
    return m;
  }

  private static String refText(Element assoc, String ref) {
    return child(assoc, Ns.BPMN, ref).map(e -> e.getTextContent().trim()).filter(s -> !s.isEmpty()).orElse(null);
  }

  private static boolean isSystem(String name) {
    return SYSTEM_VARIABLES.contains(name);
  }

  private static boolean mentionsSystem(String xpath) {
    return SYSTEM_VARIABLES.stream().anyMatch(s -> xpath.contains("'" + s + "'"));
  }

  /** Name helpers shared with converters. */
  static final class XPathToFeelNames {
    static String feel(String name) {
      return XPathToFeel.translate("bpmn:getDataObject('" + name + "')").feel();
    }

    static String path(String name) {
      return name;
    }
  }
}
