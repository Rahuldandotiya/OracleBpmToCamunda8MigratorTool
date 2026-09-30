package io.github.rahuldandotiya.o2c8.convert.elements;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.attr;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.ownText;

import io.github.rahuldandotiya.o2c8.ConverterOptions.InterfaceEvents;
import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.convert.DataMappings;
import io.github.rahuldandotiya.o2c8.convert.ElementConverter;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

/** Start, end, intermediate (catch/throw) and boundary events. */
public final class EventConverter implements ElementConverter {

  private static final Set<String> EVENTS =
      Set.of("startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent");

  @Override
  public boolean canConvert(Element source) {
    return Ns.BPMN.equals(source.getNamespaceURI()) && EVENTS.contains(source.getLocalName());
  }

  @Override
  public Element convert(Element source, Element parent, ConversionContext ctx) {
    String kind = source.getLocalName();
    List<Element> defs =
        children(source, Ns.BPMN, null).stream()
            .filter(e -> e.getLocalName().endsWith("EventDefinition"))
            .toList();
    if (defs.size() > 1) {
      ctx.report(source, Level.MANUAL,
          "Multiple event definitions are not supported by Camunda 8; only the first was converted.");
    }
    Element def = defs.isEmpty() ? null : defs.get(0);
    String defType = def == null ? "none" : def.getLocalName().replace("EventDefinition", "");
    OracleExtensions ox = OracleExtensions.of(source);
    boolean inEventSubProcess = "true".equals(parent.getAttribute("triggeredByEvent"));

    // Oracle "define interface" events: the process is exposed as a (SOAP) service operation
    boolean interfaceEvent = defType.equals("message") && ox.definedInterfaceOperation().isPresent();
    if (interfaceEvent && kind.equals("startEvent")) {
      return interfaceStart(source, parent, ctx, ox);
    }
    if (interfaceEvent && kind.equals("endEvent")) {
      return interfaceEnd(source, parent, ctx, ox);
    }

    Element e = ctx.createLike(source, kind, parent);
    if (kind.equals("startEvent") && inEventSubProcess) {
      e.setAttribute("isInterrupting", String.valueOf(!"false".equals(source.getAttribute("isInterrupting"))));
    }
    if (kind.equals("boundaryEvent")) {
      e.setAttribute("attachedToRef", source.getAttribute("attachedToRef"));
      e.setAttribute("cancelActivity", String.valueOf(!"false".equals(source.getAttribute("cancelActivity"))));
    }

    boolean catching = kind.equals("startEvent") || kind.equals("intermediateCatchEvent")
        || kind.equals("boundaryEvent");
    switch (defType) {
      case "none" -> {
        ctx.report(source, Level.AUTO, "Converted as-is.");
      }
      case "message" -> message(source, e, ctx, ox, catching, kind);
      case "signal" -> {
        String name = OracleExtensions.of(def).eventRef().or(ox::eventRef).map(OracleExtensions.TypeRef::name)
            .orElse(Optional.ofNullable(attr(def, "signalRef")).orElse(source.getAttribute("id")));
        Element sd = eventDefinition(ctx, e, "signal");
        sd.setAttribute("signalRef", ctx.signal(name));
        ctx.report(source, Level.AUTO, "Signal '" + name + "'.");
      }
      case "error" -> {
        String code = attr(def, "errorRef");
        if (code == null) {
          code = OracleExtensions.of(def).eventRef().map(OracleExtensions.TypeRef::name).orElse(null);
        }
        Element ed = eventDefinition(ctx, e, "error");
        if (code != null) {
          ed.setAttribute("errorRef", ctx.error(code));
          ctx.report(source, Level.AUTO, "Error with code '" + code + "'.");
        } else if (catching) {
          ctx.report(source, Level.AUTO, "Catch-all error event (no error code).");
        } else {
          ed.setAttribute("errorRef", ctx.error(source.getAttribute("id")));
          ctx.report(source, Level.PARTIAL,
              "Error end event had no error reference; code '" + source.getAttribute("id") + "' generated.");
        }
      }
      case "timer" -> timer(source, def, e, ctx);
      case "terminate", "escalation", "compensate" -> {
        Element d = eventDefinition(ctx, e, defType);
        copyRefAttribute(def, d, defType);
        ctx.report(source, defType.equals("terminate") ? Level.AUTO : Level.PARTIAL,
            defType.equals("terminate") ? "Terminate event." : "Check " + defType + " references after import.");
      }
      default -> ctx.report(source, Level.MANUAL,
          "Event type '" + defType + "' is not supported by Camunda 8; converted as a none event.");
    }

    if (catching) {
      DataMappings.apply(source, e, ctx, false, true);
    } else if (!kind.equals("endEvent") || defType.equals("message")) {
      DataMappings.apply(source, e, ctx, true, false);
    } else {
      reportDroppedEndMappings(source, ctx);
    }
    return e;
  }

  private Element interfaceStart(Element source, Element parent, ConversionContext ctx, OracleExtensions ox) {
    String op = ox.definedInterfaceOperation().orElse("start");
    Element e = ctx.createLike(source, "startEvent", parent);
    if (ctx.options().interfaceEvents() == InterfaceEvents.MESSAGE) {
      String name = ctx.processId() + "." + op;
      eventDefinition(ctx, e, "message").setAttribute("messageRef", ctx.message(name));
      ctx.report(source, Level.PARTIAL,
          "Oracle service operation '" + op + "' became message start event '" + name
              + "'. Publish this message (or use an inbound connector) to start the process.");
    } else {
      ctx.report(source, Level.AUTO,
          "Oracle service operation '" + op + "' (SOAP interface) became a none start event. "
              + "Start instances via the Camunda API, a REST/SOAP inbound connector, or a call activity.");
    }
    DataMappings.apply(source, e, ctx, false, true);
    return e;
  }

  private Element interfaceEnd(Element source, Element parent, ConversionContext ctx, OracleExtensions ox) {
    String op = ox.definedInterfaceOperation().orElse("end");
    Element e = ctx.createLike(source, "endEvent", parent);
    List<DataMappings.Mapping> reply = DataMappings.preview(source, ctx, true);
    String vars = reply.isEmpty() ? "" : " Reply variables in Oracle: "
        + String.join(", ", reply.stream().map(m -> m.target() + " = " + m.source().substring(1)).toList()) + ".";
    ctx.report(source, Level.PARTIAL,
        "Oracle reply operation '" + op + "' became a none end event. Callers get the result by "
            + "creating the instance 'with result' (awaitCompletion) or via a callback." + vars);
    return e;
  }

  private void message(Element source, Element e, ConversionContext ctx, OracleExtensions ox,
      boolean catching, String kind) {
    Element def = children(source, Ns.BPMN, "messageEventDefinition").get(0);
    String name = OracleExtensions.of(def).eventRef().or(ox::eventRef).map(OracleExtensions.TypeRef::name)
        .orElse(source.getAttribute("name"));
    if (name == null || name.isBlank()) {
      name = source.getAttribute("id");
    }
    if (catching) {
      Element md = eventDefinition(ctx, e, "message");
      String msgId = ctx.message(name);
      md.setAttribute("messageRef", msgId);
      if (kind.equals("startEvent")) {
        ctx.report(source, Level.AUTO, "Message start event '" + name + "'.");
      } else {
        ctx.messageSubscription(msgId, ctx.options().correlationKeyPlaceholder());
        ctx.report(source, Level.PARTIAL,
            "Message catch '" + name + "' needs a correlation key; placeholder '"
                + ctx.options().correlationKeyPlaceholder() + "' written. Replace it with the business key "
                + "(Oracle correlated via conversation/correlation sets).");
      }
    } else {
      // Zeebe implements message throw events with a job worker
      Element md = eventDefinition(ctx, e, "message");
      md.setAttribute("messageRef", ctx.message(name));
      Element td = ctx.addZeebe(e, "taskDefinition");
      td.setAttribute("type", ActivityConverter.jobType("send-" + name));
      ctx.report(source, Level.PARTIAL,
          "Message throw '" + name + "' needs a job worker for type '" + td.getAttribute("type")
              + "' (or apply a connector template).");
    }
  }

  private void timer(Element source, Element def, Element e, ConversionContext ctx) {
    Element td = eventDefinition(ctx, e, "timer");
    boolean converted = false;
    for (String k : new String[] {"timeDate", "timeDuration", "timeCycle"}) {
      Optional<Element> t = child(def, Ns.BPMN, k);
      if (t.isPresent()) {
        String raw = ownText(t.get()).replace("'", "").replace("\"", "").trim();
        Element n = ctx.bpmn(k);
        n.setAttributeNS(Ns.XSI, "xsi:type", "bpmn:tFormalExpression");
        n.setTextContent(raw);
        td.appendChild(n);
        converted = raw.matches("^(R\\d*/)?P.*|^\\d{4}-\\d{2}-\\d{2}.*");
        ctx.report(source, converted ? Level.AUTO : Level.PARTIAL,
            converted ? "Timer " + k + " '" + raw + "'."
                : "Timer " + k + " '" + raw + "' is not ISO 8601; rewrite it as ISO 8601 or a FEEL expression.");
      }
    }
    if (!converted && td.getChildNodes().getLength() == 0) {
      Element n = ctx.bpmn("timeDuration");
      n.setAttributeNS(Ns.XSI, "xsi:type", "bpmn:tFormalExpression");
      n.setTextContent("PT1H");
      td.appendChild(n);
      ctx.report(source, Level.MANUAL,
          "Oracle timer settings not found in standard BPMN; placeholder duration PT1H written.");
    }
  }

  private static void copyRefAttribute(Element from, Element to, String defType) {
    String ref = switch (defType) {
      case "escalation" -> "escalationRef";
      case "compensate" -> "activityRef";
      default -> null;
    };
    if (ref != null && from.hasAttribute(ref)) {
      to.setAttribute(ref, from.getAttribute(ref));
    }
  }

  private static void reportDroppedEndMappings(Element source, ConversionContext ctx) {
    if (!DataMappings.preview(source, ctx, true).isEmpty()) {
      ctx.report(source, Level.INFO, "Data mappings on this end event were dropped (not supported on none end events).");
    }
  }

  static Element eventDefinition(ConversionContext ctx, Element event, String type) {
    Element d = ctx.bpmn(type + "EventDefinition");
    d.setAttribute("id", ctx.uniqueId(event.getAttribute("id") + "_" + type));
    event.appendChild(d);
    return d;
  }
}
