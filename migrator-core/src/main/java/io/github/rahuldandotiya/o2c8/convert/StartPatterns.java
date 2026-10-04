package io.github.rahuldandotiya.o2c8.convert;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Oracle ways of starting a process that Camunda 8 expresses differently:
 * <ul>
 *   <li>an <b>instantiating event-based gateway</b> (no start event, {@code instantiate="true"})
 *       becomes one message start event per event after the gateway;</li>
 *   <li>a <b>none start event followed by a receive task with {@code instantiate="true"}</b>
 *       becomes a single message start event.</li>
 * </ul>
 * Runs on the converted container before incoming/outgoing references are written.
 */
final class StartPatterns {

  private StartPatterns() {}

  static void apply(Element source, Element target, ConversionContext ctx) {
    instantiatingEventGateways(source, target, ctx);
    instantiatingReceiveTasks(source, target, ctx);
  }

  private static void instantiatingEventGateways(Element source, Element target, ConversionContext ctx) {
    for (Element gw : children(target, Ns.BPMN, "eventBasedGateway")) {
      Element src = byId(source, gw.getAttribute("id"));
      boolean instantiate = src != null && "true".equals(src.getAttribute("instantiate"));
      if (!instantiate || !incoming(target, gw).isEmpty()) {
        continue;
      }
      List<String> converted = new ArrayList<>();
      for (Element flow : outgoing(target, gw)) {
        Element ev = byId(target, flow.getAttribute("targetRef"));
        if (ev == null || !ev.getLocalName().equals("intermediateCatchEvent")) {
          continue;
        }
        Element start = retype(ev, "startEvent", ctx);
        target.removeChild(flow);
        converted.add(XmlUtils.attr(start, "name") == null ? start.getAttribute("id") : start.getAttribute("name"));
        children(start, Ns.BPMN, "messageEventDefinition").forEach(md -> ctx.dropSubscription(md.getAttribute("messageRef")));
        ctx.reportObject().resolve(start.getAttribute("id"), "converting it into a message start event");
        ctx.report(byId(source, start.getAttribute("id")) == null ? start : byId(source, start.getAttribute("id")),
            Level.AUTO, "Became a message start event (it followed an instantiating event-based gateway); "
                + "no correlation key is needed to start an instance.");
      }
      if (!converted.isEmpty()) {
        target.removeChild(gw);
        ctx.reportObject().resolve(gw.getAttribute("id"), "start pattern conversion");
        ctx.report(src, Level.AUTO, "Instantiating event-based gateway replaced by " + converted.size()
            + " message start events (" + String.join(", ", converted) + "): Camunda 8 does not support "
            + "instantiating gateways, and alternative message starts have the same effect.");
      }
    }
  }

  private static void instantiatingReceiveTasks(Element source, Element target, ConversionContext ctx) {
    for (Element rt : children(target, Ns.BPMN, "receiveTask")) {
      Element src = byId(source, rt.getAttribute("id"));
      if (src == null || !"true".equals(src.getAttribute("instantiate"))) {
        continue;
      }
      List<Element> in = incoming(target, rt);
      Element start = in.size() == 1 ? byId(target, in.get(0).getAttribute("sourceRef")) : null;
      boolean plainStart = start != null && start.getLocalName().equals("startEvent")
          && children(start, Ns.BPMN, null).stream().noneMatch(c -> c.getLocalName().endsWith("EventDefinition"))
          && outgoing(target, start).size() == 1;
      if (!plainStart) {
        ctx.report(src, Level.PARTIAL, "Oracle creates the instance when this message arrives (instantiate=true). "
            + "In Camunda, start the process with a message start event for '" + rt.getAttribute("name") + "'.");
        continue;
      }
      // the start event takes over the receive task's message and data mappings
      Element md = ctx.bpmn("messageEventDefinition");
      md.setAttribute("id", ctx.uniqueId(start.getAttribute("id") + "_message"));
      md.setAttribute("messageRef", rt.getAttribute("messageRef"));
      start.appendChild(md);
      XmlUtils.child(rt, Ns.BPMN, "extensionElements").flatMap(x -> XmlUtils.child(x, Ns.ZEEBE, "ioMapping"))
          .ifPresent(io -> {
            Element outIo = ctx.zeebe("ioMapping");
            children(io, Ns.ZEEBE, "output").forEach(o -> outIo.appendChild(o.cloneNode(true)));
            if (outIo.hasChildNodes()) {
              ctx.extensions(start).appendChild(outIo);
            }
          });
      ctx.dropSubscription(rt.getAttribute("messageRef"));
      target.removeChild(in.get(0));
      for (Element f : outgoing(target, rt)) {
        f.setAttribute("sourceRef", start.getAttribute("id"));
      }
      target.removeChild(rt);
      ctx.reportObject().resolve(rt.getAttribute("id"), "merging it into the start event");
      ctx.report(src, Level.AUTO, "Receive task that creates the instance (instantiate=true) merged into message start "
          + "event '" + XmlUtils.attr(start, "name") + "'.");
    }
  }

  // ------------------------------------------------------------------ helpers

  /** Replaces an event element by one of another kind, keeping id, name, documentation and definitions. */
  private static Element retype(Element ev, String kind, ConversionContext ctx) {
    Element e = ctx.bpmn(kind);
    var attrs = ev.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      Node a = attrs.item(i);
      e.setAttributeNode((org.w3c.dom.Attr) a.cloneNode(true));
    }
    for (Element c : children(ev, null, null)) {
      e.appendChild(c.cloneNode(true));
    }
    ev.getParentNode().replaceChild(e, ev);
    return e;
  }

  private static List<Element> incoming(Element container, Element node) {
    return children(container, Ns.BPMN, "sequenceFlow").stream()
        .filter(f -> node.getAttribute("id").equals(f.getAttribute("targetRef"))).toList();
  }

  private static List<Element> outgoing(Element container, Element node) {
    return children(container, Ns.BPMN, "sequenceFlow").stream()
        .filter(f -> node.getAttribute("id").equals(f.getAttribute("sourceRef"))).toList();
  }

  private static Element byId(Element container, String id) {
    for (Element e : children(container, Ns.BPMN, null)) {
      if (id.equals(e.getAttribute("id"))) {
        return e;
      }
    }
    return null;
  }
}
