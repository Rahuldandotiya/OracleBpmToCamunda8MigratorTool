package io.github.rahuldandotiya.o2c8.convert;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.ConverterOptions;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.report.ConversionReport;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Shared state for converting one source file: target document, registries and the report. */
public final class ConversionContext {

  private final Document target;
  private final Element definitions;
  private final ConverterOptions options;
  private final ConversionReport report;
  private final ElementConverters converters;

  private final Set<String> ids = new HashSet<>();
  private final Map<String, String> messages = new LinkedHashMap<>(); // name -> id
  private final Map<String, String> signals = new LinkedHashMap<>();
  private final Map<String, String> errors = new LinkedHashMap<>(); // code -> id
  private final Map<String, String> laneRoleByNode = new LinkedHashMap<>();

  private String processId;

  public ConversionContext(
      Document target,
      Element definitions,
      ConverterOptions options,
      ConversionReport report,
      ElementConverters converters) {
    this.target = target;
    this.definitions = definitions;
    this.options = options;
    this.report = report;
    this.converters = converters;
  }

  public ConverterOptions options() {
    return options;
  }

  public ConversionReport reportObject() {
    return report;
  }

  public ElementConverters converters() {
    return converters;
  }

  public String processId() {
    return processId;
  }

  void processId(String id) {
    this.processId = id;
  }

  public Document document() {
    return target;
  }

  // ------------------------------------------------------------------ report

  public void report(Element source, Level level, String message) {
    report(source, level, message, ConversionReport.Source.BUILT_IN);
  }

  public void report(Element source, Level level, String message, ConversionReport.Source from) {
    report.add(new ConversionReport.Entry(
        processId,
        source.getAttribute("id"),
        source.hasAttribute("name") ? source.getAttribute("name") : null,
        source.getLocalName(),
        level,
        message,
        from));
  }

  // ------------------------------------------------------------------ project context

  private io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver serviceCalls;

  /** Resolver for calls through composite.xml, or null when the process has no composite. */
  public io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver serviceCalls() {
    return serviceCalls;
  }

  public void serviceCalls(io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver resolver) {
    this.serviceCalls = resolver;
  }

  // ------------------------------------------------------------------ element creation

  /** New {@code bpmn:<localName>} element (not yet attached). */
  public Element bpmn(String localName) {
    return target.createElementNS(Ns.BPMN, "bpmn:" + localName);
  }

  public Element zeebe(String localName) {
    return target.createElementNS(Ns.ZEEBE, "zeebe:" + localName);
  }

  /** Registers an id as used; returns it unchanged. */
  public String claimId(String id) {
    ids.add(id);
    return id;
  }

  /** Returns {@code base} or {@code base_2}, {@code base_3}… so ids stay unique. */
  public String uniqueId(String base) {
    String clean = base.replaceAll("[^A-Za-z0-9_.-]", "_");
    if (!Character.isLetter(clean.charAt(0)) && clean.charAt(0) != '_') {
      clean = "_" + clean;
    }
    String id = clean;
    for (int i = 2; ids.contains(id); i++) {
      id = clean + "_" + i;
    }
    ids.add(id);
    return id;
  }

  /**
   * Creates the target element for {@code source} with the same id and name, copies documentation
   * (BPMN documentation plus the Oracle localized description) and appends it to {@code parent}.
   */
  public Element createLike(Element source, String localName, Element parent) {
    Element e = bpmn(localName);
    String id = source.getAttribute("id");
    e.setAttribute("id", id.isBlank() ? uniqueId(localName) : claimId(id));
    if (source.hasAttribute("name") && !source.getAttribute("name").isBlank()) {
      e.setAttribute("name", source.getAttribute("name"));
    }
    StringBuilder doc = new StringBuilder();
    for (Element d : children(source, Ns.BPMN, "documentation")) {
      doc.append(d.getTextContent().trim());
    }
    OracleExtensions.of(source)
        .description()
        .ifPresent(d -> doc.append(doc.length() > 0 ? "\n" : "").append(d));
    if (doc.length() > 0) {
      addDocumentation(e, doc.toString());
    }
    parent.appendChild(e);
    return e;
  }

  /** Appends text to the element's documentation (creating it if needed, as the first child). */
  public void addDocumentation(Element e, String text) {
    Element d = children(e, Ns.BPMN, "documentation").stream().findFirst().orElse(null);
    if (d == null) {
      d = bpmn("documentation");
      e.insertBefore(d, e.getFirstChild());
      d.setTextContent(text);
    } else {
      d.setTextContent(d.getTextContent() + "\n" + text);
    }
  }

  /** The element's {@code bpmn:extensionElements}, created right after documentation if missing. */
  public Element extensions(Element e) {
    var existing = children(e, Ns.BPMN, "extensionElements");
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    Element ext = bpmn("extensionElements");
    Node after = null;
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element c && "documentation".equals(c.getLocalName())) {
        after = c;
      }
    }
    e.insertBefore(ext, after == null ? e.getFirstChild() : after.getNextSibling());
    return ext;
  }

  /** Appends a {@code zeebe:<localName>} element inside the element's extensionElements. */
  public Element addZeebe(Element e, String localName) {
    Element z = zeebe(localName);
    extensions(e).appendChild(z);
    return z;
  }

  // ------------------------------------------------------------------ root definitions

  /** id of a {@code bpmn:message} with this name, created on first use. */
  public String message(String name) {
    return messages.computeIfAbsent(name, n -> uniqueId("Message_" + n));
  }

  public String signal(String name) {
    return signals.computeIfAbsent(name, n -> uniqueId("Signal_" + n));
  }

  public String error(String code) {
    return errors.computeIfAbsent(code, c -> uniqueId("Error_" + c));
  }

  /** Writes collected message/signal/error definitions into the target definitions. */
  void writeRootElements(Map<String, Element> messageSubscriptions) {
    messages.forEach(
        (name, id) -> {
          Element m = bpmn("message");
          m.setAttribute("id", id);
          m.setAttribute("name", name);
          Element sub = messageSubscriptions.get(id);
          if (sub != null) {
            Element ext = bpmn("extensionElements");
            ext.appendChild(sub);
            m.appendChild(ext);
          }
          definitions.appendChild(m);
        });
    signals.forEach(
        (name, id) -> {
          Element s = bpmn("signal");
          s.setAttribute("id", id);
          s.setAttribute("name", name);
          definitions.appendChild(s);
        });
    errors.forEach(
        (code, id) -> {
          Element er = bpmn("error");
          er.setAttribute("id", id);
          er.setAttribute("name", code);
          er.setAttribute("errorCode", code);
          definitions.appendChild(er);
        });
  }

  private final Map<String, Element> subscriptions = new LinkedHashMap<>();

  /** Requests a {@code zeebe:subscription correlationKey} on the message with this id. */
  public void messageSubscription(String messageId, String correlationKey) {
    subscriptions.computeIfAbsent(
        messageId,
        id -> {
          Element s = zeebe("subscription");
          s.setAttribute("correlationKey", correlationKey);
          return s;
        });
  }

  Map<String, Element> subscriptions() {
    return subscriptions;
  }

  // ------------------------------------------------------------------ lanes

  /** Role (lane name / Oracle roleId) that owns a flow node, used for candidate groups. */
  public String laneRole(String nodeId) {
    return laneRoleByNode.get(nodeId);
  }

  void laneRole(String nodeId, String role) {
    laneRoleByNode.put(nodeId, role);
  }

  /** Converts all flow elements of a container (process or sub-process) into {@code target}. */
  public void convertChildren(Element sourceContainer, Element targetContainer) {
    ProcessConverter.convertFlowElements(sourceContainer, targetContainer, this);
  }
}
