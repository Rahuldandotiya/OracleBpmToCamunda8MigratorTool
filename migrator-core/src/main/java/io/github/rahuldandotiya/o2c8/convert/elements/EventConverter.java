package io.github.rahuldandotiya.o2c8.convert.elements;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.attr;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.ownText;

import io.github.rahuldandotiya.o2c8.ConverterOptions.InterfaceEvents;
import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.convert.DataMappings;
import io.github.rahuldandotiya.o2c8.convert.ElementConverter;
import io.github.rahuldandotiya.o2c8.expression.XPathToFeel;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
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

    // started through a composite entry point (inbound JMS/email/file adapter or SOAP endpoint)
    if (kind.equals("startEvent") && defType.equals("message") && ctx.serviceCalls() != null && !inEventSubProcess) {
      var inbound = ctx.serviceCalls().resolveInbound(source);
      if (inbound.isPresent()) {
        return inboundStart(source, parent, ctx, ox, inbound.get());
      }
    }

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
      e.setAttribute("attachedToRef", localRef(source.getAttribute("attachedToRef")));
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

  /** Start event fed by an inbound adapter (JMS, email, file...) or a SOAP endpoint in the composite. */
  private Element inboundStart(Element source, Element parent, ConversionContext ctx, OracleExtensions ox,
      io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver.InboundStart inbound) {
    var service = inbound.service();
    if (!(service.binding() instanceof io.github.rahuldandotiya.o2c8.composite.Composite.JcaBinding jca)) {
      return interfaceStart(source, parent, ctx, ox); // SOAP/HTTP endpoint: start through the API
    }
    var composite = ctx.serviceCalls().composite();
    io.github.rahuldandotiya.o2c8.composite.ServiceDescriptors.JcaConfig cfg = composite.resolve(jca.config())
        .map(f -> {
          try {
            return io.github.rahuldandotiya.o2c8.composite.ServiceDescriptors.jca(f);
          } catch (java.io.IOException ex) {
            return null;
          }
        }).orElse(null);
    String adapter = cfg == null || cfg.adapter() == null ? "adapter" : cfg.adapter().toLowerCase(java.util.Locale.ROOT);
    Element e = ctx.createLike(source, "startEvent", parent);
    String messageName = service.name();
    eventDefinition(ctx, e, "message").setAttribute("messageRef", ctx.message(messageName));
    java.util.Map<String, String> p = cfg == null ? java.util.Map.of() : cfg.properties();
    StringBuilder doc = new StringBuilder("Oracle inbound " + adapter + " adapter '" + service.name() + "'");
    if (cfg != null && cfg.connectionFactory() != null) {
      doc.append(", connection ").append(cfg.connectionFactory());
    }
    p.forEach((k, v) -> doc.append(", ").append(k).append('=').append(v));
    ctx.addDocumentation(e, doc.toString());
    String hint = switch (adapter) {
      case "jms", "aq" -> "Started by JMS destination " + p.getOrDefault("DestinationName", p.getOrDefault("QueueName", "?"))
          + ". Camunda 8 has no JMS inbound connector: forward each JMS message with a small bridge that publishes "
          + "message '" + messageName + "' (or move the queue to Kafka/RabbitMQ and use that inbound connector).";
      case "ums" -> "Started by an email" + (p.containsKey("To") ? " to " + p.get("To") : "")
          + ". Use the Camunda Email inbound connector (IMAP polling) or a bridge that publishes message '"
          + messageName + "'.";
      case "file", "ftp" -> "Started by files in " + p.getOrDefault("PhysicalDirectory", p.getOrDefault("Directory", "?"))
          + ". Use a file-watching bridge that publishes message '" + messageName + "'.";
      case "db" -> "Started by database polling (" + p.getOrDefault("DescriptorName", "?") + "). Use a polling worker "
          + "that publishes message '" + messageName + "'.";
      default -> "Started by inbound " + adapter + " adapter; publish message '" + messageName + "' from a bridge.";
    };
    ctx.report(source, Level.PARTIAL, "Message start event '" + messageName + "'. " + hint,
        io.github.rahuldandotiya.o2c8.report.ConversionReport.Source.COMPOSITE);
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
        .or(() -> ox.definedInterfaceOperation().map(op -> ctx.processId() + "." + op))
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
      // Zeebe implements message throw events with a job worker; they reference no bpmn:message
      // (a referenced message would need a subscription, which only catch events have)
      eventDefinition(ctx, e, "message");
      Element td = ctx.addZeebe(e, "taskDefinition");
      td.setAttribute("type", ActivityConverter.jobType("send-" + name));
      ctx.report(source, Level.PARTIAL,
          "Message throw '" + name + "' needs a job worker for type '" + td.getAttribute("type")
              + "' (or apply a connector template).");
    }
  }

  private void timer(Element source, Element def, Element e, ConversionContext ctx) {
    Element td = eventDefinition(ctx, e, "timer");
    for (String kind : new String[] {"timeDate", "timeDuration", "timeCycle"}) {
      String k = kind;
      Optional<Element> t = child(def, Ns.BPMN, k);
      if (t.isEmpty()) {
        continue;
      }
      String text = ownText(t.get());
      Optional<Element> schedule = XmlUtils.descendants(t.get(), Ns.ORACLE, "Schedule").stream().findFirst();
      String value;
      Level level = Level.AUTO;
      String note;
      if (text.isBlank() && schedule.isPresent()) {
        TimerSchedules.Cron cron = TimerSchedules.toCron(schedule.get());
        value = cron.expression();
        level = cron.exact() ? Level.AUTO : Level.PARTIAL;
        note = "Oracle schedule converted to cron '" + value + "' (" + cron.description() + ")";
      } else {
        String literal = text.replaceAll("^['\"]|['\"]$", "").trim();
        if (literal.matches("^(R\\d*/)?P.*|^\\d{4}-\\d{2}-\\d{2}.*")) {
          value = k.equals("timeCycle") && !literal.startsWith("R") ? "R/" + literal : literal;
          note = "Timer " + k + " '" + value + "'";
        } else {
          XPathToFeel.Result r = XPathToFeel.translate(text);
          if (r.ok()) {
            value = "=" + r.feel();
            note = "Timer " + k + " expression converted to FEEL: " + value;
          } else {
            value = k.equals("timeCycle") ? "R/PT1H" : k.equals("timeDate") ? "2099-01-01T00:00:00Z" : "PT1H";
            level = Level.MANUAL;
            note = "Timer " + k + " '" + text + "' could not be converted (" + r.problem() + "); placeholder '" + value
                + "' written";
          }
        }
      }
      java.util.Set<String> allowed = allowedTimerProperties(e, parent(e));
      if (!allowed.contains(k)) {
        java.util.regex.Matcher iso = java.util.regex.Pattern.compile("^R(\\d*)/(P.+)$").matcher(value);
        if (k.equals("timeCycle") && iso.matches() && allowed.contains("timeDuration")) {
          boolean repeats = !iso.group(1).equals("1") && text.replaceAll("^['\"]|['\"]$", "").trim().startsWith("R");
          k = "timeDuration";
          value = iso.group(2);
          note = "Oracle timer cycle used as a delay: waits " + value + " once (timeDuration)";
          if (repeats) {
            level = level == Level.AUTO ? Level.PARTIAL : level;
            note += ". Oracle repeats it, but this event type fires only once in Camunda 8; "
                + "use a non-interrupting boundary timer or a loop if the repetition matters";
          }
        } else if (k.equals("timeDuration") && value.startsWith("P") && allowed.contains("timeCycle")) {
          k = "timeCycle";
          value = "R1/" + value;
          level = level == Level.AUTO ? Level.PARTIAL : level;
          note = "Timer duration on a start event became cycle '" + value + "' (fires once, counted from deployment)";
        } else {
          String fallback = allowed.contains("timeDuration") ? "timeDuration" : "timeCycle";
          note = "Timer " + k + " '" + value + "' is not allowed on this event in Camunda 8 (allowed: "
              + String.join(", ", allowed) + "); placeholder written";
          k = fallback;
          value = fallback.equals("timeDuration") ? "PT1H" : "R/PT1H";
          level = Level.MANUAL;
        }
      }
      Element n = ctx.bpmn(k);
      n.setAttributeNS(Ns.XSI, "xsi:type", "bpmn:tFormalExpression");
      n.setTextContent(value);
      td.appendChild(n);
      String window = TimerSchedules.window(def);
      if (window != null) {
        level = level == Level.AUTO ? Level.PARTIAL : level;
        note += ". Oracle active window " + window + " is not carried over; add it to the expression or end the cycle";
      }
      ctx.report(source, level, note + ".");
      return;
    }
    Element n = ctx.bpmn("timeDuration");
    n.setAttributeNS(Ns.XSI, "xsi:type", "bpmn:tFormalExpression");
    n.setTextContent("PT1H");
    td.appendChild(n);
    ctx.report(source, Level.MANUAL, "Oracle timer settings not found in standard BPMN; placeholder duration PT1H written.");
  }

  /**
   * Timer properties Zeebe accepts on an event (same matrix as Camunda's own lint rules):
   * start events take a cycle (unless interrupting in an event sub-process), a date, and a duration
   * only inside an event sub-process; boundary events a duration, a date, and a cycle only when
   * non-interrupting; intermediate catch events a duration or a date.
   */
  public static java.util.Set<String> allowedTimerProperties(Element event, Element container) {
    boolean inEventSubProcess = container != null && container.getLocalName().equals("subProcess")
        && "true".equals(container.getAttribute("triggeredByEvent"));
    return switch (event.getLocalName()) {
      case "startEvent" -> {
        boolean interrupting = !"false".equals(event.getAttribute("isInterrupting"));
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        if (!interrupting || !inEventSubProcess) {
          s.add("timeCycle");
        }
        s.add("timeDate");
        if (inEventSubProcess) {
          s.add("timeDuration");
        }
        yield s;
      }
      case "boundaryEvent" -> "false".equals(event.getAttribute("cancelActivity"))
          ? java.util.Set.of("timeDuration", "timeDate", "timeCycle")
          : java.util.Set.of("timeDuration", "timeDate");
      default -> java.util.Set.of("timeDuration", "timeDate");
    };
  }

  private static Element parent(Element e) {
    return e.getParentNode() instanceof Element p ? p : null;
  }

  /** "bpmn:ACT1" → "ACT1": Oracle sometimes writes element references as QNames. */
  public static String localRef(String ref) {
    if (ref == null) {
      return null;
    }
    int i = ref.indexOf(':');
    return i >= 0 && !ref.contains("://") ? ref.substring(i + 1) : ref;
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
