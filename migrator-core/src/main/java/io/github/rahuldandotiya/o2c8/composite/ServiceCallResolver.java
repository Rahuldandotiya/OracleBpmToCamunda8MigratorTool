package io.github.rahuldandotiya.o2c8.composite;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants;

import io.github.rahuldandotiya.o2c8.composite.Composite.Component;
import io.github.rahuldandotiya.o2c8.composite.Composite.Reference;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Follows an Oracle service call from the BPMN element to the composite:
 * <pre>service task → (partner, operation) → wire "Process/partner" → reference or component</pre>
 *
 * <p>The partner and operation are taken, in order, from the BPMN {@code operationRef} (standard
 * {@code bpmn:interface/bpmn:operation}), from the Oracle conversation the element takes part in, or
 * from any Oracle extension attribute that names a service/reference and an operation.
 */
public final class ServiceCallResolver {

  private static final Pattern PARTNER_ATTR =
      Pattern.compile("(?i)(service|serviceName|serviceRef|reference|referenceName|partnerLink|partner|serviceComponent)");
  private static final Pattern OPERATION_ATTR = Pattern.compile("(?i)(operation|operationName|operationRef|wsdlOperation)");

  /** Where a call goes. */
  public sealed interface Target permits ToReference, ToComponent, NotFound {}

  public record ToReference(Reference reference) implements Target {}

  public record ToComponent(Component component, String service) implements Target {}

  public record NotFound(String reason) implements Target {}

  /** A resolved call: the partner/operation as named in the process, how we found it, and the target. */
  public record ServiceCall(String partner, String operation, String foundVia, Target target) {
    /** Knowledge-base key for this call, e.g. {@code FraudService.check}. */
    public String key() {
      String name = target instanceof ToReference r ? r.reference().name()
          : target instanceof ToComponent c ? c.component().name() : partner;
      return name + "." + (operation == null ? "*" : operation);
    }
  }

  private final Composite composite;
  private final String componentName;

  public ServiceCallResolver(Composite composite, String componentName) {
    this.composite = composite;
    this.componentName = componentName;
  }

  public Composite composite() {
    return composite;
  }

  public String componentName() {
    return componentName;
  }

  /** Resolves the call made by an Oracle element (service/send/receive task, message event). */
  public Optional<ServiceCall> resolve(Element source) {
    String[] fromOpRef = fromOperationRef(source);
    String[] fromConv = fromConversation(source);
    String[] fromExt = fromExtensionAttributes(source);
    String partner = first(fromOpRef[0], fromConv[0], fromExt[0]);
    String operation = first(fromOpRef[1], fromConv[1], fromExt[1]);
    String via = fromOpRef[0] != null ? "operationRef" : fromConv[0] != null ? "Oracle conversation"
        : fromExt[0] != null ? "Oracle extension" : null;
    if (partner == null) {
      return Optional.empty();
    }
    return Optional.of(new ServiceCall(partner, operation, via, target(partner)));
  }

  private Target target(String partner) {
    Optional<String> wired = componentName == null ? Optional.empty() : composite.wireTarget(componentName, partner);
    if (wired.isPresent()) {
      String t = wired.get();
      int slash = t.indexOf('/');
      if (slash < 0) {
        return composite.reference(t).<Target>map(ToReference::new)
            .orElse(new NotFound("wire target '" + t + "' is not a reference in " + composite.file().getFileName()));
      }
      String comp = t.substring(0, slash);
      return composite.component(comp).<Target>map(c -> new ToComponent(c, t.substring(slash + 1)))
          .orElse(new NotFound("wire target component '" + comp + "' not found"));
    }
    return composite.reference(partner).<Target>map(ToReference::new)
        .or(() -> composite.component(partner).map(c -> new ToComponent(c, null)))
        .orElse(new NotFound("no wire from " + componentName + "/" + partner + " and no reference named " + partner));
  }

  /** operationRef="ns:opId" → bpmn:operation[@id] inside bpmn:interface. */
  private static String[] fromOperationRef(Element source) {
    String ref = XmlUtils.attr(source, "operationRef");
    if (ref == null) {
      for (Element d : children(source, Ns.BPMN, null)) {
        if (d.getLocalName().endsWith("EventDefinition") && XmlUtils.attr(d, "operationRef") != null) {
          ref = d.getAttribute("operationRef");
        }
      }
    }
    if (ref == null) {
      return new String[2];
    }
    String id = ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) : ref;
    Element defs = source.getOwnerDocument().getDocumentElement();
    for (Element iface : children(defs, Ns.BPMN, "interface")) {
      for (Element op : children(iface, Ns.BPMN, "operation")) {
        if (id.equals(op.getAttribute("id"))) {
          String partner = XmlUtils.attr(iface, "name");
          if (partner == null) {
            partner = local(XmlUtils.attr(iface, "implementationRef"));
          }
          return new String[] {partner, first(XmlUtils.attr(op, "name"), local(XmlUtils.attr(op, "implementationRef")))};
        }
      }
    }
    return new String[2];
  }

  /** Oracle: element's Conversational@conversation → process-level Conversation definition. */
  private static String[] fromConversation(Element source) {
    Element ext = oracleExt(source);
    if (ext == null) {
      return new String[2];
    }
    for (Element conv : children(ext, Ns.ORACLE, "Conversational")) {
      String convId = conv.getAttribute("conversation");
      String op = attrMatching(conv, OPERATION_ATTR, true);
      Element process = processOf(source);
      Element definition = process == null ? null : descendants(process, Ns.ORACLE, "Conversation").stream()
          .filter(c -> convId.equals(c.getAttribute("id"))).findFirst().orElse(null);
      if (definition != null && "define_interface".equals(definition.getAttribute("type"))) {
        continue; // the process's own interface, not an outbound call
      }
      String partner = definition == null ? null : attrMatching(definition, PARTNER_ATTR, true);
      if (partner == null) {
        partner = attrMatching(conv, PARTNER_ATTR, true);
      }
      if (partner != null) {
        return new String[] {partner, op};
      }
    }
    return new String[2];
  }

  private static String[] fromExtensionAttributes(Element source) {
    Element ext = oracleExt(source);
    if (ext == null) {
      return new String[2];
    }
    return new String[] {attrMatching(ext, PARTNER_ATTR, true), attrMatching(ext, OPERATION_ATTR, true)};
  }

  private static Element oracleExt(Element source) {
    return XmlUtils.path(source, Ns.BPMN, "extensionElements")
        .flatMap(ee -> XmlUtils.child(ee, Ns.ORACLE, "OracleExtensions")).orElse(null);
  }

  private static Element processOf(Element e) {
    Node n = e;
    while (n != null && !(n instanceof Element el && "process".equals(el.getLocalName()))) {
      n = n.getParentNode();
    }
    return (Element) n;
  }

  /** First attribute (on e or, if deep, its descendants) whose local name matches, skipping FeatureSet noise. */
  private static String attrMatching(Element e, Pattern p, boolean deep) {
    List<Element> all = new java.util.ArrayList<>();
    all.add(e);
    if (deep) {
      all.addAll(descendants(e, null, null));
    }
    for (Element x : all) {
      if (x.getLocalName().contains("Feature") || "extensionElements".equals(x.getLocalName())) {
        continue;
      }
      var attrs = x.getAttributes();
      for (int i = 0; i < attrs.getLength(); i++) {
        var a = attrs.item(i);
        String ln = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
        if (p.matcher(ln).matches() && !a.getNodeValue().isBlank()) {
          return local(a.getNodeValue());
        }
      }
    }
    return null;
  }

  private static String local(String qname) {
    if (qname == null) {
      return null;
    }
    int i = qname.lastIndexOf(':');
    return i >= 0 && !qname.contains("://") ? qname.substring(i + 1) : qname;
  }

  private static String first(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }
}
