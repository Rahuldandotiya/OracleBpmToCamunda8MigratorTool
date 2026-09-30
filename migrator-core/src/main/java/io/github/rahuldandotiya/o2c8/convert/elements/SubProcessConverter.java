package io.github.rahuldandotiya.o2c8.convert.elements;

import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.convert.DataMappings;
import io.github.rahuldandotiya.o2c8.convert.ElementConverter;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import org.w3c.dom.Element;

/** Embedded and event sub-processes (their contents are converted recursively). */
public final class SubProcessConverter implements ElementConverter {

  @Override
  public boolean canConvert(Element source) {
    return Ns.BPMN.equals(source.getNamespaceURI())
        && (source.getLocalName().equals("subProcess") || source.getLocalName().equals("transaction")
            || source.getLocalName().equals("adHocSubProcess"));
  }

  @Override
  public Element convert(Element source, Element parent, ConversionContext ctx) {
    String kind = source.getLocalName();
    Element e = ctx.createLike(source, kind.equals("adHocSubProcess") ? "adHocSubProcess" : "subProcess", parent);
    boolean eventSub = "true".equals(source.getAttribute("triggeredByEvent"));
    if (eventSub) {
      e.setAttribute("triggeredByEvent", "true");
    }
    ctx.convertChildren(source, e);
    if (!eventSub) {
      DataMappings.apply(source, e, ctx, true, true);
    }
    switch (kind) {
      case "transaction" -> ctx.report(source, Level.MANUAL,
          "Transaction sub-process converted to an embedded sub-process; model compensation explicitly.");
      case "adHocSubProcess" -> ctx.report(source, Level.PARTIAL,
          "Ad-hoc sub-process needs Camunda 8.7+ and an activeElementsCollection expression.");
      default -> ctx.report(source, Level.AUTO, eventSub ? "Event sub-process." : "Embedded sub-process.");
    }
    return e;
  }
}
