package io.github.rahuldandotiya.o2c8.composite;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.composite.Composite.Binding;
import io.github.rahuldandotiya.o2c8.composite.Composite.Component;
import io.github.rahuldandotiya.o2c8.composite.Composite.JcaBinding;
import io.github.rahuldandotiya.o2c8.composite.Composite.OtherBinding;
import io.github.rahuldandotiya.o2c8.composite.Composite.Reference;
import io.github.rahuldandotiya.o2c8.composite.Composite.RestBinding;
import io.github.rahuldandotiya.o2c8.composite.Composite.Wire;
import io.github.rahuldandotiya.o2c8.composite.Composite.WsBinding;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * Reads {@code composite.xml} (SCA 1.0, Oracle flavour). Elements are matched by local name, so
 * the reader works across 11g/12c namespace variants.
 */
public final class CompositeReader {

  private CompositeReader() {}

  public static Composite read(Path file) throws IOException {
    Element root = XmlUtils.parse(file).getDocumentElement();
    if (!"composite".equals(root.getLocalName())) {
      throw new IOException("not an SCA composite (root element is " + root.getLocalName() + ")");
    }
    Path dir = file.toAbsolutePath().normalize().getParent();

    List<Component> components = new ArrayList<>();
    for (Element c : children(root, null, "component")) {
      String type = null;
      String src = null;
      for (Element impl : children(c, null, null)) {
        String ln = impl.getLocalName();
        if (ln.startsWith("implementation.")) {
          type = ln.substring("implementation.".length());
          src = XmlUtils.attr(impl, "src");
        }
      }
      components.add(new Component(c.getAttribute("name"), type, src));
    }

    List<Reference> references = new ArrayList<>();
    for (Element r : children(root, null, "reference")) {
      String iface = children(r, null, "interface.wsdl").stream()
          .map(i -> i.getAttribute("interface")).findFirst().orElse(null);
      references.add(new Reference(r.getAttribute("name"), iface, anyAttr(r, "wsdlLocation"), binding(r)));
    }

    List<Wire> wires = new ArrayList<>();
    for (Element w : children(root, null, "wire")) {
      String s = children(w, null, "source.uri").stream().map(e -> e.getTextContent().trim()).findFirst().orElse("");
      String t = children(w, null, "target.uri").stream().map(e -> e.getTextContent().trim()).findFirst().orElse("");
      wires.add(new Wire(s, t));
    }

    Map<String, Map<String, String>> plan = readConfigPlans(dir);
    return new Composite(file.toAbsolutePath().normalize(), dir, root.getAttribute("name"),
        List.copyOf(components), List.copyOf(references), List.copyOf(wires), plan);
  }

  private static Binding binding(Element reference) {
    for (Element b : children(reference, null, null)) {
      String ln = b.getLocalName();
      switch (ln) {
        case "binding.rest":
          return new RestBinding(XmlUtils.attr(b, "location"), XmlUtils.attr(b, "config"));
        case "binding.ws":
          return new WsBinding(XmlUtils.attr(b, "location"), XmlUtils.attr(b, "port"), XmlUtils.attr(b, "soapVersion"));
        case "binding.jca":
          return new JcaBinding(XmlUtils.attr(b, "config"));
        default:
          if (ln.startsWith("binding.")) {
            return new OtherBinding(ln.substring("binding.".length()));
          }
      }
    }
    return new OtherBinding("none");
  }

  /** Attribute by local name in any namespace (Oracle puts wsdlLocation in the ui: namespace). */
  static String anyAttr(Element e, String localName) {
    var attrs = e.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      var a = attrs.item(i);
      String ln = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
      if (localName.equals(ln) && !a.getNodeValue().isBlank()) {
        return a.getNodeValue();
      }
    }
    return null;
  }

  /**
   * Reads {@code *configplan*.xml} next to the composite: reference name to (attribute → replace
   * value), e.g. FraudService → {location: http://prod-host/...}. Used to name environment secrets.
   */
  static Map<String, Map<String, String>> readConfigPlans(Path dir) {
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    try (var files = Files.list(dir)) {
      for (Path p : files.filter(f -> (f.getFileName().toString().toLowerCase().contains("configplan")
              || f.getFileName().toString().toLowerCase().contains("cfgplan"))
          && f.getFileName().toString().endsWith(".xml")).sorted().toList()) {
        Element root = XmlUtils.parse(p).getDocumentElement();
        for (Element ref : XmlUtils.descendants(root, null, "reference")) {
          Map<String, String> attrs = out.computeIfAbsent(ref.getAttribute("name"), k -> new LinkedHashMap<>());
          for (Element attr : XmlUtils.descendants(ref, null, "attribute")) {
            children(attr, null, "replace").stream().findFirst()
                .ifPresent(r -> attrs.put(attr.getAttribute("name") + " (" + p.getFileName() + ")",
                    r.getTextContent().trim()));
          }
        }
      }
    } catch (IOException | RuntimeException ignored) {
      // config plans are optional
    }
    return out;
  }
}
