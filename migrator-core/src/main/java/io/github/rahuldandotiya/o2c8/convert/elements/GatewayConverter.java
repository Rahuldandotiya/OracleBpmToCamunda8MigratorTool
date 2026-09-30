package io.github.rahuldandotiya.o2c8.convert.elements;

import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.convert.ElementConverter;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.Set;
import org.w3c.dom.Element;

/** Exclusive, parallel, inclusive and event-based gateways. Default flows are fixed later. */
public final class GatewayConverter implements ElementConverter {

  private static final Set<String> GATEWAYS =
      Set.of("exclusiveGateway", "parallelGateway", "inclusiveGateway", "eventBasedGateway", "complexGateway");

  @Override
  public boolean canConvert(Element source) {
    return Ns.BPMN.equals(source.getNamespaceURI()) && GATEWAYS.contains(source.getLocalName());
  }

  @Override
  public Element convert(Element source, Element parent, ConversionContext ctx) {
    String kind = source.getLocalName();
    if (kind.equals("complexGateway")) {
      Element e = ctx.createLike(source, "inclusiveGateway", parent);
      ctx.report(source, Level.MANUAL,
          "Complex gateway is not supported by Camunda 8; converted to an inclusive gateway. Re-model the join rule.");
      return e;
    }
    Element e = ctx.createLike(source, kind, parent);
    if (source.hasAttribute("default")) {
      e.setAttribute("default", source.getAttribute("default"));
    }
    ctx.report(source, Level.AUTO, "Converted as-is.");
    return e;
  }
}
