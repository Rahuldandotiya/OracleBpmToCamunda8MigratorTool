package io.github.rahuldandotiya.o2c8.composite;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants;

import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

/** Readers for the files a composite reference points to: WADL, WSDL and JCA adapter configs. */
public final class ServiceDescriptors {

  private ServiceDescriptors() {}

  /** One REST operation from a WADL: HTTP verb, full resource path and parameter names. */
  public record RestOperation(
      String operation, String method, String base, String path, List<String> templateParams, List<String> queryParams) {}

  /** SOAP operation details from a WSDL. */
  public record SoapOperation(String operation, String soapAction, String targetNamespace, String address) {}

  /** Adapter settings from a {@code .jca} file. */
  public record JcaConfig(String adapter, String connectionFactory, String operation, String interactionSpec,
      Map<String, String> properties) {}

  // ------------------------------------------------------------------ WADL

  /** Finds the method whose soa:wsdlOperation (or id) equals {@code operation}; first method if null. */
  public static Optional<RestOperation> wadlOperation(Path wadl, String operation) throws IOException {
    Element app = XmlUtils.parse(wadl).getDocumentElement();
    List<RestOperation> all = new ArrayList<>();
    for (Element resources : children(app, null, "resources")) {
      String base = XmlUtils.attr(resources, "base");
      for (Element r : children(resources, null, "resource")) {
        collect(r, "", base, all);
      }
    }
    if (operation == null) {
      return all.stream().findFirst();
    }
    return all.stream().filter(o -> operation.equals(o.operation())).findFirst()
        .or(() -> all.stream().filter(o -> operation.equalsIgnoreCase(o.operation())).findFirst());
  }

  private static void collect(Element resource, String parentPath, String base, List<RestOperation> out) {
    String path = join(parentPath, resource.getAttribute("path"));
    List<String> template = new ArrayList<>();
    for (Element p : children(resource, null, "param")) {
      if ("template".equals(p.getAttribute("style"))) {
        template.add(p.getAttribute("name"));
      }
    }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^}/]+)}").matcher(path);
    while (m.find()) {
      if (!template.contains(m.group(1))) {
        template.add(m.group(1));
      }
    }
    for (Element method : children(resource, null, "method")) {
      String op = CompositeReader.anyAttr(method, "wsdlOperation");
      if (op == null) {
        op = XmlUtils.attr(method, "id");
      }
      List<String> query = new ArrayList<>();
      for (Element req : children(method, null, "request")) {
        for (Element p : children(req, null, "param")) {
          if ("query".equals(p.getAttribute("style"))) {
            query.add(p.getAttribute("name"));
          }
        }
      }
      out.add(new RestOperation(op, method.getAttribute("name").toUpperCase(), base, path, List.copyOf(template),
          List.copyOf(query)));
    }
    for (Element child : children(resource, null, "resource")) {
      collect(child, path, base, out);
    }
  }

  private static String join(String a, String b) {
    if (b == null || b.isBlank()) {
      return a;
    }
    String left = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
    return left + (b.startsWith("/") ? b : "/" + b);
  }

  // ------------------------------------------------------------------ WSDL

  public static Optional<SoapOperation> wsdlOperation(Path wsdl, String operation) throws IOException {
    Element defs = XmlUtils.parse(wsdl).getDocumentElement();
    String tns = XmlUtils.attr(defs, "targetNamespace");
    String address = descendants(defs, null, "address").stream()
        .map(a -> XmlUtils.attr(a, "location")).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    for (Element binding : children(defs, null, "binding")) {
      for (Element op : children(binding, null, "operation")) {
        if (operation == null || operation.equals(op.getAttribute("name"))) {
          String action = children(op, null, "operation").stream()
              .map(o -> XmlUtils.attr(o, "soapAction")).filter(java.util.Objects::nonNull).findFirst().orElse(null);
          return Optional.of(new SoapOperation(op.getAttribute("name"), action, tns, address));
        }
      }
    }
    boolean declared = descendants(defs, null, "operation").stream()
        .anyMatch(o -> operation == null || operation.equals(o.getAttribute("name")));
    return declared ? Optional.of(new SoapOperation(operation, null, tns, address)) : Optional.empty();
  }

  // ------------------------------------------------------------------ JCA

  public static JcaConfig jca(Path file) throws IOException {
    Element cfg = XmlUtils.parse(file).getDocumentElement();
    String cf = children(cfg, null, "connection-factory").stream()
        .map(c -> XmlUtils.attr(c, "location")).findFirst().orElse(null);
    String op = null;
    String spec = null;
    Map<String, String> props = new LinkedHashMap<>();
    for (Element ea : children(cfg, null, "endpoint-activation")) { // inbound adapters
      op = XmlUtils.attr(ea, "operation");
      for (Element as : children(ea, null, "activation-spec")) {
        spec = XmlUtils.attr(as, "className");
        for (Element p : children(as, null, "property")) {
          props.put(p.getAttribute("name"), p.getAttribute("value"));
        }
      }
    }
    for (Element ei : children(cfg, null, "endpoint-interaction")) {
      op = XmlUtils.attr(ei, "operation");
      for (Element is : children(ei, null, "interaction-spec")) {
        spec = XmlUtils.attr(is, "className");
        for (Element p : children(is, null, "property")) {
          props.put(p.getAttribute("name"), p.getAttribute("value"));
        }
      }
    }
    return new JcaConfig(XmlUtils.attr(cfg, "adapter"), cf, op, spec, props);
  }
}
