package io.github.rahuldandotiya.o2c8.composite;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants;

import io.github.rahuldandotiya.o2c8.composite.Composite.Component;
import io.github.rahuldandotiya.o2c8.composite.Composite.Reference;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Follows an Oracle service call from the BPMN element to the composite:
 * <pre>service task → (partner, operation) → wire "Process/…partner…" → reference or component</pre>
 *
 * <p>Oracle BPM 12c records the partner in the process-level conversation the element takes part in:
 * {@code service_call} ({@code serviceRef name="Services.Externals.X"}), {@code use_interface}
 * ({@code referenceRef name="X"}) or {@code process_call} ({@code process="X"}); the operation is on
 * the element's {@code Conversational} block or in {@code bpmn:operationRef}. The composite wires
 * generated names such as {@code Process/Services.Externals.X.reference}; matching ignores those
 * decorations. Inbound starts (JMS/email adapters wired into the process) resolve the other way.
 */
public final class ServiceCallResolver {

  private static final Pattern PARTNER_ATTR = Pattern.compile(
      "(?i)(service|serviceName|serviceRef|reference|referenceName|partnerLink|partner|serviceComponent|process)");
  private static final Pattern OPERATION_ATTR = Pattern.compile("(?i)(operation|operationName|wsdlOperation)");
  private static final Set<String> REF_ELEMENTS = Set.of("serviceRef", "referenceRef", "interfaceRef");

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

  /** A start event fed by a composite entry point (inbound JMS/email/file adapter, SOAP endpoint). */
  public record InboundStart(Composite.Service service, String operation) {}

  /** What the Oracle element says about its partner. */
  private record Partner(List<String> candidates, String operation, String via, String conversationType) {}

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

  /** Resolves the outbound call made by an Oracle element (service/send/receive task, message event). */
  public Optional<ServiceCall> resolve(Element source) {
    Partner p = partner(source);
    if (p.candidates().isEmpty() || "define_interface".equals(p.conversationType())) {
      return Optional.empty();
    }
    return Optional.of(new ServiceCall(display(p.candidates().get(0)), p.operation(), p.via(), target(p.candidates())));
  }

  /** Resolves the composite entry point that starts the process through this (start) event. */
  public Optional<InboundStart> resolveInbound(Element startEvent) {
    Partner p = partner(startEvent);
    if (p.candidates().isEmpty() || componentName == null) {
      return Optional.empty();
    }
    return composite.inboundService(componentName, p.candidates()).map(s -> new InboundStart(s, p.operation()));
  }

  private Target target(List<String> candidates) {
    Optional<String> wired = componentName == null ? Optional.empty() : composite.wireTarget(componentName, candidates);
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
    for (String c : candidates) {
      String name = display(c);
      Optional<Target> t = composite.reference(name).<Target>map(ToReference::new)
          .or(() -> composite.component(name).map(x -> new ToComponent(x, null)));
      if (t.isPresent()) {
        return t.get();
      }
    }
    return new NotFound("no wire from " + componentName + " to " + display(candidates.get(0))
        + " and no reference or component with that name");
  }

  // ------------------------------------------------------------------ partner extraction

  private static Partner partner(Element source) {
    Set<String> candidates = new LinkedHashSet<>();
    String operation = null;
    String via = null;
    String convType = null;

    // 1. Oracle conversation (the normal 12c encoding)
    Element ext = oracleExt(source);
    if (ext != null) {
      for (Element conv : children(ext, Ns.ORACLE, "Conversational")) {
        String op = attrMatching(conv, OPERATION_ATTR);
        operation = operation == null ? op : operation;
        Element process = processOf(source);
        String convId = conv.getAttribute("conversation");
        Element def = process == null ? null : descendants(process, Ns.ORACLE, "Conversation").stream()
            .filter(c -> convId.equals(c.getAttribute("id"))).findFirst().orElse(null);
        if (def != null) {
          convType = def.getAttribute("type");
          for (Element d : descendants(def, Ns.ORACLE, null)) {
            if (REF_ELEMENTS.contains(d.getLocalName()) && !d.getAttribute("name").isBlank()) {
              candidates.add(d.getAttribute("name"));
            }
          }
          String attr = attrMatching(def, PARTNER_ATTR);
          if (attr != null) {
            candidates.add(attr);
          }
          if (!def.getAttribute("name").isBlank()) {
            candidates.add(def.getAttribute("name"));
          }
          via = "Oracle " + convType + " conversation";
        }
      }
    }
    // 2. standard BPMN operationRef (attribute or child element) and messageRef
    String[] fromOpRef = fromOperationRef(source);
    if (fromOpRef[0] != null) {
      candidates.add(fromOpRef[0]);
      via = via == null ? "operationRef" : via;
    }
    operation = operation == null ? fromOpRef[1] : operation;
    for (Element d : children(source, Ns.BPMN, "messageEventDefinition")) {
      String ref = XmlUtils.attr(d, "messageRef");
      if (ref != null && ref.contains(".")) {
        candidates.add(local(ref));
      }
    }
    // 3. any Oracle extension attribute naming a partner
    if (candidates.isEmpty() && ext != null) {
      String attr = attrMatching(ext, PARTNER_ATTR);
      if (attr != null) {
        candidates.add(attr);
        via = "Oracle extension";
      }
      operation = operation == null ? attrMatching(ext, OPERATION_ATTR) : operation;
    }
    return new Partner(new ArrayList<>(candidates), operation, via, convType);
  }

  /** operationRef (attribute or child element, on the element or its event definition). */
  private static String[] fromOperationRef(Element source) {
    String ref = XmlUtils.attr(source, "operationRef");
    List<Element> holders = new ArrayList<>();
    holders.add(source);
    holders.addAll(children(source, Ns.BPMN, "messageEventDefinition"));
    for (Element h : holders) {
      if (ref == null) {
        ref = XmlUtils.attr(h, "operationRef");
      }
      if (ref == null) {
        ref = XmlUtils.child(h, Ns.BPMN, "operationRef").map(e -> e.getTextContent().trim()).filter(t -> !t.isEmpty())
            .orElse(null);
      }
    }
    if (ref == null) {
      return new String[2];
    }
    String id = local(ref);
    Element defs = source.getOwnerDocument().getDocumentElement();
    for (Element iface : children(defs, Ns.BPMN, "interface")) {
      for (Element op : children(iface, Ns.BPMN, "operation")) {
        if (id.equals(op.getAttribute("id")) || id.equals(op.getAttribute("name"))) {
          String partner = XmlUtils.attr(iface, "name");
          if (partner == null) {
            partner = local(XmlUtils.attr(iface, "implementationRef"));
          }
          return new String[] {partner, first(XmlUtils.attr(op, "name"), local(XmlUtils.attr(op, "implementationRef")))};
        }
      }
    }
    return new String[] {null, id}; // operation name only
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

  /** First attribute (on e or its descendants) whose local name matches, skipping FeatureSet noise. */
  private static String attrMatching(Element e, Pattern p) {
    List<Element> all = new ArrayList<>();
    all.add(e);
    all.addAll(descendants(e, null, null));
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

  /** "Services.Externals.FraudCheck" → "FraudCheck"; other names unchanged. */
  static String display(String partner) {
    String s = partner;
    for (String prefix : new String[] {"Services.Externals.", "References.Externals."}) {
      if (s.startsWith(prefix)) {
        s = s.substring(prefix.length());
      }
    }
    return s;
  }

  private static String local(String qname) {
    if (qname == null) {
      return null;
    }
    if (qname.contains("://")) {
      return qname;
    }
    int i = qname.indexOf(':');
    return i >= 0 ? qname.substring(i + 1) : qname;
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
