package io.github.rahuldandotiya.o2c8.knowledge;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The Camunda-specific part of an activity: its element type (serviceTask, userTask, callActivity…),
 * zeebe attributes (e.g. {@code zeebe:modelerTemplate}) and the zeebe extension elements. This is
 * what a person changes when finishing a migrated model, so it is what the knowledge base stores.
 */
public final class Fragment {

  /** ioMapping entries that configure a connector rather than map process data. */
  private static final Set<String> CONFIG_TARGETS =
      Set.of("method", "url", "headers", "connectionTimeoutInSeconds", "readTimeoutInSeconds", "authentication");

  private final String type;
  private final Map<String, String> zeebeAttributes;
  private final List<Element> zeebe; // detached, owned by an internal document

  private Fragment(String type, Map<String, String> attrs, List<Element> zeebe) {
    this.type = type;
    this.zeebeAttributes = attrs;
    this.zeebe = zeebe;
  }

  public static Fragment of(Element activity) {
    Document holder = XmlUtils.newDocument();
    Map<String, String> attrs = new TreeMap<>();
    var a = activity.getAttributes();
    for (int i = 0; i < a.getLength(); i++) {
      Node n = a.item(i);
      if (Ns.ZEEBE.equals(n.getNamespaceURI())) {
        attrs.put(n.getLocalName(), n.getNodeValue());
      }
    }
    List<Element> z = new ArrayList<>();
    XmlUtils.child(activity, Ns.BPMN, "extensionElements").ifPresent(ext -> {
      for (Element c : children(ext, Ns.ZEEBE, null)) {
        z.add((Element) holder.importNode(c, true));
      }
    });
    return new Fragment(activity.getLocalName(), attrs, z);
  }

  public String type() {
    return type;
  }

  /** zeebe child by local name, or null. */
  public Element zeebe(String localName) {
    return zeebe.stream().filter(e -> localName.equals(e.getLocalName())).findFirst().orElse(null);
  }

  public List<Element> zeebeChildren() {
    return zeebe;
  }

  /** Copy without the given zeebe children (e.g. without ioMapping for human-task rules). */
  public Fragment without(Predicate<Element> drop) {
    return new Fragment(type, zeebeAttributes, zeebe.stream().filter(drop.negate()).toList());
  }

  public boolean isEmpty() {
    return zeebe.isEmpty() && zeebeAttributes.isEmpty();
  }

  // ------------------------------------------------------------------ canonical form & (de)serialisation

  /** Order-independent text used to compare fragments and to detect a human change. */
  public String canonical() {
    List<String> parts = new ArrayList<>();
    parts.add("type=" + type);
    zeebeAttributes.forEach((k, v) -> parts.add("@" + k + "=" + v));
    for (Element z : zeebe) {
      parts.add(canonical(z));
    }
    List<String> sorted = new ArrayList<>(parts.subList(1, parts.size()));
    sorted.sort(null);
    return parts.get(0) + ";" + String.join(";", sorted);
  }

  /**
   * The fragment as a list of small, comparable facts: the type, each zeebe attribute, and each
   * zeebe setting (ioMapping and taskHeaders are split per entry). Used to count edits.
   */
  public List<String> parts() {
    List<String> out = new ArrayList<>();
    out.add("type=" + type);
    zeebeAttributes.forEach((k, v) -> out.add("@" + k + "=" + v));
    for (Element z : zeebe) {
      if (z.getLocalName().equals("ioMapping") || z.getLocalName().equals("taskHeaders")) {
        for (Element c : children(z, null, null)) {
          out.add(z.getLocalName() + "/" + canonical(c));
        }
      } else {
        out.add(canonical(z));
      }
    }
    return out;
  }

  private static String canonical(Element e) {
    StringBuilder sb = new StringBuilder(e.getLocalName()).append('[');
    Map<String, String> attrs = new TreeMap<>();
    var a = e.getAttributes();
    for (int i = 0; i < a.getLength(); i++) {
      if (!"xmlns".equals(a.item(i).getPrefix()) && !"xmlns".equals(a.item(i).getNodeName())) {
        attrs.put(a.item(i).getNodeName(), a.item(i).getNodeValue());
      }
    }
    attrs.forEach((k, v) -> sb.append(k).append('=').append(v).append(','));
    for (Element c : children(e, null, null)) {
      sb.append(canonical(c));
    }
    String text = XmlUtils.ownText(e);
    return sb.append(text).append(']').toString();
  }

  /** XML text stored in the knowledge base JSON. */
  public String toXml() {
    Document d = XmlUtils.newDocument();
    Element root = d.createElementNS(Ns.ZEEBE, "zeebe:fragment");
    root.setAttributeNS(Ns.XMLNS, "xmlns:zeebe", Ns.ZEEBE);
    root.setAttribute("type", type);
    zeebeAttributes.forEach((k, v) -> root.setAttributeNS(Ns.ZEEBE, "zeebe:" + k, v));
    for (Element z : zeebe) {
      root.appendChild(d.importNode(z, true));
    }
    d.appendChild(root);
    return XmlUtils.toString(d).replaceFirst("^<\\?xml[^>]*>\\s*", "");
  }

  public static Fragment fromXml(String xml) {
    try {
      Element root = XmlUtils.parse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
      Map<String, String> attrs = new TreeMap<>();
      var a = root.getAttributes();
      for (int i = 0; i < a.getLength(); i++) {
        if (Ns.ZEEBE.equals(a.item(i).getNamespaceURI())) {
          attrs.put(a.item(i).getLocalName(), a.item(i).getNodeValue());
        }
      }
      return new Fragment(root.getAttribute("type"), attrs, children(root, Ns.ZEEBE, null));
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid fragment in knowledge base: " + e.getMessage(), e);
    }
  }

  // ------------------------------------------------------------------ applying

  /** What to take from the fragment when applying it. */
  public enum Scope {
    /** Everything, including data mappings (re-conversion of the same process). */
    FULL,
    /** Implementation only: type, task definition, headers, template, connector settings; data mappings stay. */
    IMPLEMENTATION,
    /** User task settings: form, assignment, priority, schedule; data mappings stay. */
    HUMAN_TASK
  }

  /**
   * Applies this fragment to {@code target} (an element of the converted document). The element may
   * be replaced by one of another type; the (possibly new) element is returned.
   */
  public Element applyTo(Element target, Scope scope) {
    Element e = retype(target, type);
    Document doc = e.getOwnerDocument();
    Element ext = XmlUtils.child(e, Ns.BPMN, "extensionElements").orElse(null);
    if (ext == null) {
      ext = doc.createElementNS(Ns.BPMN, "bpmn:extensionElements");
      Node after = null;
      for (Element c : children(e, Ns.BPMN, "documentation")) {
        after = c;
      }
      e.insertBefore(ext, after == null ? e.getFirstChild() : after.getNextSibling());
    }
    Element draftIo = XmlUtils.child(ext, Ns.ZEEBE, "ioMapping").orElse(null);
    // remove the draft's zeebe config that the fragment replaces
    for (Element z : children(ext, Ns.ZEEBE, null)) {
      if (scope == Scope.FULL || !z.getLocalName().equals("ioMapping")) {
        if (scope != Scope.HUMAN_TASK || HUMAN_KEYS.contains(z.getLocalName())) {
          ext.removeChild(z);
        }
      }
    }
    zeebeAttributes.forEach((k, v) -> e.setAttributeNS(Ns.ZEEBE, "zeebe:" + k, v));
    for (Element z : zeebe) {
      String ln = z.getLocalName();
      if (scope == Scope.HUMAN_TASK && !HUMAN_KEYS.contains(ln)) {
        continue;
      }
      if (ln.equals("ioMapping") && scope != Scope.FULL) {
        mergeConnectorInputs(z, ext, draftIo);
        continue;
      }
      ext.appendChild(doc.importNode(z, true));
    }
    return e;
  }

  static final Set<String> HUMAN_KEYS =
      Set.of("userTask", "formDefinition", "assignmentDefinition", "priorityDefinition", "taskSchedule",
          "taskListeners", "properties");

  /** Keeps the draft's data mappings and adds the fragment's static/connector-config inputs. */
  private static void mergeConnectorInputs(Element fragmentIo, Element ext, Element draftIo) {
    Document doc = ext.getOwnerDocument();
    Element io = draftIo != null && draftIo.getParentNode() == ext ? draftIo : null;
    Node insertAt = io == null ? null : io.getFirstChild();
    for (Element in : children(fragmentIo, Ns.ZEEBE, "input")) {
      if (!isConfig(in)) {
        continue;
      }
      if (io == null) {
        io = doc.createElementNS(Ns.ZEEBE, "zeebe:ioMapping");
        ext.appendChild(io);
      }
      String target = in.getAttribute("target");
      for (Element existing : children(io, Ns.ZEEBE, "input")) {
        if (existing.getAttribute("target").equals(target)) {
          if (existing == insertAt) {
            insertAt = existing.getNextSibling();
          }
          io.removeChild(existing);
        }
      }
      io.insertBefore(doc.importNode(in, true), insertAt); // keeps the fragment's order, before data mappings
    }
  }

  static boolean isConfig(Element input) {
    String target = input.getAttribute("target");
    String root = target.contains(".") ? target.substring(0, target.indexOf('.')) : target;
    return CONFIG_TARGETS.contains(root) || !input.getAttribute("source").startsWith("=");
  }

  /** Returns target itself, or a new element of the given type that replaces it in the tree. */
  static Element retype(Element target, String type) {
    if (target.getLocalName().equals(type)) {
      return target;
    }
    Document doc = target.getOwnerDocument();
    Element e = doc.createElementNS(Ns.BPMN, "bpmn:" + type);
    var attrs = target.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      Node a = attrs.item(i);
      if (a.getNamespaceURI() == null || !Ns.ZEEBE.equals(a.getNamespaceURI())) {
        e.setAttributeNode((org.w3c.dom.Attr) a.cloneNode(true));
      }
    }
    for (Element c : children(target, null, null)) {
      String ln = c.getLocalName();
      boolean keep = ln.equals("documentation") || ln.equals("extensionElements") || ln.equals("incoming")
          || ln.equals("outgoing") || ln.equals("multiInstanceLoopCharacteristics");
      if (keep) {
        e.appendChild(c.cloneNode(true));
      }
    }
    if (!type.equals("receiveTask")) {
      e.removeAttribute("messageRef");
    }
    target.getParentNode().replaceChild(e, target);
    return e;
  }

  /** Candidate groups of a user task fragment, or null. */
  public String candidateGroups() {
    Element a = zeebe("assignmentDefinition");
    return a == null ? null : XmlUtils.attr(a, "candidateGroups");
  }

  /** Job type from zeebe:taskDefinition, or null. */
  public String jobType() {
    Element t = zeebe("taskDefinition");
    return t == null ? null : XmlUtils.attr(t, "type");
  }
}
