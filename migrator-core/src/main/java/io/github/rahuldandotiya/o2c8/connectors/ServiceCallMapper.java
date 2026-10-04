package io.github.rahuldandotiya.o2c8.connectors;

import io.github.rahuldandotiya.o2c8.composite.Composite;
import io.github.rahuldandotiya.o2c8.composite.Composite.JcaBinding;
import io.github.rahuldandotiya.o2c8.composite.Composite.Reference;
import io.github.rahuldandotiya.o2c8.composite.Composite.RestBinding;
import io.github.rahuldandotiya.o2c8.composite.Composite.WsBinding;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver.NotFound;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver.ServiceCall;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver.ToComponent;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver.ToReference;
import io.github.rahuldandotiya.o2c8.composite.ServiceDescriptors;
import io.github.rahuldandotiya.o2c8.composite.ServiceDescriptors.JcaConfig;
import io.github.rahuldandotiya.o2c8.composite.ServiceDescriptors.RestOperation;
import io.github.rahuldandotiya.o2c8.composite.ServiceDescriptors.SoapOperation;
import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.convert.DataMappings;
import io.github.rahuldandotiya.o2c8.convert.DataMappings.Mapping;
import io.github.rahuldandotiya.o2c8.convert.elements.ActivityConverter;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Source;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

/**
 * Configures a service call resolved through {@code composite.xml}:
 * REST binding → Camunda REST outbound connector, SOAP binding → job worker with SOAP metadata,
 * JCA adapter → job worker with adapter metadata, another BPMN component → call activity.
 * Endpoints are always written as Camunda secrets.
 */
public final class ServiceCallMapper {

  /** Task type of the Camunda REST (HTTP JSON) outbound connector. */
  public static final String REST_CONNECTOR_TYPE = "io.camunda:http-json:1";

  private ServiceCallMapper() {}

  /**
   * Creates the target element for {@code source}. Returns empty when the call cannot be used (not
   * found in the composite); the caller then falls back to the built-in job-worker mapping.
   */
  public static Optional<Element> map(Element source, Element parent, ConversionContext ctx, ServiceCall call) {
    if (call.target() instanceof NotFound nf) {
      ctx.report(source, Level.PARTIAL,
          "Calls '" + call.partner() + "." + call.operation() + "', but " + nf.reason() + ". Built-in mapping used.",
          Source.COMPOSITE);
      return Optional.empty();
    }
    Composite composite = ctx.serviceCalls().composite();
    if (call.target() instanceof ToComponent tc) {
      return Optional.of(componentCall(source, parent, ctx, call, tc, composite));
    }
    Reference ref = ((ToReference) call.target()).reference();
    if (ref.binding() instanceof RestBinding rest) {
      return Optional.of(rest(source, parent, ctx, call, ref, rest, composite));
    }
    if (ref.binding() instanceof WsBinding ws) {
      return Optional.of(soap(source, parent, ctx, call, ref, ws, composite));
    }
    if (ref.binding() instanceof JcaBinding jca) {
      return Optional.of(adapter(source, parent, ctx, call, ref, jca, composite));
    }
    Element e = worker(source, parent, ctx, jobType(ref.name(), call.operation()));
    ctx.report(source, Level.PARTIAL, "Reference '" + ref.name() + "' uses an unsupported binding; job worker '"
        + ActivityConverter.jobType(ref.name() + "-" + call.operation()) + "' generated.", Source.COMPOSITE);
    return Optional.of(e);
  }

  // ------------------------------------------------------------------ REST

  private static Element rest(Element source, Element parent, ConversionContext ctx, ServiceCall call,
      Reference ref, RestBinding rest, Composite composite) {
    RestOperation op = null;
    String problem = null;
    Optional<java.nio.file.Path> wadl = composite.resolve(rest.config());
    if (wadl.isPresent()) {
      try {
        op = ServiceDescriptors.wadlOperation(wadl.get(), call.operation()).orElse(null);
        if (op == null) {
          problem = "operation '" + call.operation() + "' not found in " + wadl.get().getFileName();
        }
      } catch (IOException e) {
        problem = "cannot read " + rest.config() + ": " + e.getMessage();
      }
    } else {
      problem = rest.config() == null ? "no WADL/Swagger configured" : rest.config() + " not found in the project";
    }
    String method = op != null ? op.method() : "POST";
    String path = op != null && op.path() != null ? op.path() : "";
    String base = firstNonBlank(rest.location(), op == null ? null : op.base());
    String secret = SecretNames.url(ref.name());
    ctx.reportObject().addSecret(secret, base, ref.name(), composite.configPlanOverrides().get(ref.name()));

    Element e = ctx.createLike(source, "serviceTask", parent);
    Element td = ctx.addZeebe(e, "taskDefinition");
    td.setAttribute("type", REST_CONNECTOR_TYPE);
    td.setAttribute("retries", "3");

    List<Mapping> inputs = new ArrayList<>(DataMappings.preview(source, ctx, true));
    List<Mapping> outputs = DataMappings.preview(source, ctx, false);
    Element io = ctx.addZeebe(e, "ioMapping");
    input(ctx, io, "noAuth", "authentication.type");
    input(ctx, io, method, "method");
    input(ctx, io, url(secret, path, op == null ? List.of() : op.templateParams(), inputs), "url");
    if (op != null && !op.queryParams().isEmpty()) {
      Map<String, String> q = new LinkedHashMap<>();
      for (String qp : op.queryParams()) {
        take(inputs, qp).ifPresent(m -> q.put(qp, m.source().substring(1)));
      }
      if (!q.isEmpty()) {
        input(ctx, io, "=" + FeelContext.of(q), "queryParameters");
      }
    }
    if (!method.equals("GET") && !method.equals("DELETE") && !inputs.isEmpty()) {
      input(ctx, io, body(inputs), "body");
    }
    input(ctx, io, "20", "connectionTimeoutInSeconds");

    String result = resultExpression(outputs, ctx, source);
    if (result != null) {
      Element headers = ctx.addZeebe(e, "taskHeaders");
      Element h = ctx.zeebe("header");
      h.setAttribute("key", "resultExpression");
      h.setAttribute("value", result);
      headers.appendChild(h);
    }
    ctx.addDocumentation(e, "Oracle reference " + ref.name() + " (binding.rest), operation " + call.operation()
        + ". Apply the 'REST Outbound Connector' template in Camunda Modeler to edit it in form view.");
    boolean auth = composite.configPlanOverrides().getOrDefault(ref.name(), Map.of()).keySet().stream()
        .anyMatch(k -> k.toLowerCase(Locale.ROOT).contains("policy") || k.toLowerCase(Locale.ROOT).contains("csf"));
    Level level = problem == null && !auth ? Level.AUTO : Level.PARTIAL;
    ctx.report(source, level,
        "REST connector: " + method + " {{secrets." + secret + "}}" + path + " (reference " + ref.name() + "."
            + call.operation() + ", found via " + call.foundVia() + ")."
            + (problem == null ? "" : " Check: " + problem + "; method defaulted to POST.")
            + (auth ? " The reference has a security policy: set authentication." : ""),
        Source.COMPOSITE);
    return e;
  }

  private static String url(String secret, String path, List<String> templateParams, List<Mapping> inputs) {
    String p = path == null ? "" : path;
    if (templateParams.isEmpty() || !p.contains("{")) {
      return "{{secrets." + secret + "}}" + p;
    }
    StringBuilder feel = new StringBuilder("=\"{{secrets." + secret + "}}");
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^}/]+)}").matcher(p);
    int last = 0;
    while (m.find()) {
      feel.append(p, last, m.start()).append("\" + string(");
      String param = m.group(1);
      feel.append(take(inputs, param).map(x -> x.source().substring(1)).orElse(param));
      feel.append(") + \"");
      last = m.end();
    }
    feel.append(p.substring(last)).append('"');
    return feel.toString().replace(" + \"\"", "");
  }

  /** Removes and returns the input mapping whose target ends with this name. */
  private static Optional<Mapping> take(List<Mapping> inputs, String name) {
    for (Mapping m : inputs) {
      String t = m.target();
      if (t.equals(name) || t.endsWith("." + name)) {
        inputs.remove(m);
        return Optional.of(m);
      }
    }
    return Optional.empty();
  }

  /** Request body from the Oracle input associations (targets are message parts / their fields). */
  static String body(List<Mapping> inputs) {
    if (inputs.size() == 1 && !inputs.get(0).target().contains(".")) {
      return inputs.get(0).source();
    }
    String common = commonRoot(inputs.stream().map(Mapping::target).toList());
    Map<String, String> ctxMap = new LinkedHashMap<>();
    for (Mapping m : inputs) {
      String key = common != null ? m.target().substring(common.length() + 1) : m.target();
      ctxMap.put(key, m.source().substring(1));
    }
    return "=" + FeelContext.of(ctxMap);
  }

  private static String commonRoot(List<String> targets) {
    String root = null;
    for (String t : targets) {
      int dot = t.indexOf('.');
      if (dot < 0) {
        return null;
      }
      String r = t.substring(0, dot);
      if (root != null && !root.equals(r)) {
        return null;
      }
      root = r;
    }
    return root;
  }

  /**
   * Output associations (response part → process variable) as the connector's resultExpression.
   * The expression only sees {@code response}, not process variables, so a mapping into a field of
   * an existing variable ("claim.score") produces a nested context that replaces that variable.
   */
  private static String resultExpression(List<Mapping> outputs, ConversionContext ctx, Element source) {
    if (outputs.isEmpty()) {
      return null;
    }
    Map<String, String> result = new LinkedHashMap<>();
    List<String> partial = new ArrayList<>();
    for (Mapping m : outputs) {
      String expr = m.source().substring(1).replaceFirst("^[A-Za-z_][A-Za-z0-9_]*", "response.body");
      result.put(m.target(), expr);
      if (m.target().contains(".")) {
        partial.add(m.target());
      }
    }
    if (!partial.isEmpty()) {
      ctx.report(source, Level.PARTIAL, "Oracle copied the response into part of a variable (" + String.join(", ", partial)
          + "). The connector result replaces the whole variable; merge it in an output mapping if other fields matter.",
          Source.COMPOSITE);
    }
    return "=" + FeelContext.of(result);
  }

  // ------------------------------------------------------------------ SOAP, adapters, components

  private static Element soap(Element source, Element parent, ConversionContext ctx, ServiceCall call,
      Reference ref, WsBinding ws, Composite composite) {
    SoapOperation op = null;
    Optional<java.nio.file.Path> wsdl = composite.resolve(ref.wsdlLocation());
    if (wsdl.isPresent()) {
      try {
        op = ServiceDescriptors.wsdlOperation(wsdl.get(), call.operation()).orElse(null);
      } catch (IOException ignored) {
        // reported below as unresolved
      }
    }
    String secret = SecretNames.url(ref.name());
    String endpoint = firstNonBlank(ws.location(), op == null ? null : op.address());
    ctx.reportObject().addSecret(secret, endpoint, ref.name(), composite.configPlanOverrides().get(ref.name()));
    String type = jobType("soap-" + ref.name(), call.operation());
    Element e = worker(source, parent, ctx, type);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("endpoint", "{{secrets." + secret + "}}");
    headers.put("operation", call.operation());
    if (op != null && op.soapAction() != null) {
      headers.put("soapAction", op.soapAction());
    }
    if (op != null && op.targetNamespace() != null) {
      headers.put("targetNamespace", op.targetNamespace());
    }
    if (ws.soapVersion() != null) {
      headers.put("soapVersion", ws.soapVersion());
    }
    headers(ctx, e, headers);
    DataMappings.apply(source, e, ctx, true, true);
    ctx.report(source, Level.PARTIAL,
        "SOAP call " + ref.name() + "." + call.operation() + ": job worker '" + type + "' with endpoint secret "
            + secret + (op == null ? " (operation not found in WSDL)" : "")
            + ". Implement the worker, or apply the Camunda SOAP connector template if your version has it.",
        Source.COMPOSITE);
    return e;
  }

  private static Element adapter(Element source, Element parent, ConversionContext ctx, ServiceCall call,
      Reference ref, JcaBinding jca, Composite composite) {
    JcaConfig cfg = null;
    Optional<java.nio.file.Path> file = composite.resolve(jca.config());
    if (file.isPresent()) {
      try {
        cfg = ServiceDescriptors.jca(file.get());
      } catch (IOException ignored) {
        // reported below
      }
    }
    String adapterType = cfg != null && cfg.adapter() != null ? cfg.adapter() : "adapter";
    String type = jobType(adapterType + "-" + ref.name(), call.operation());
    Element e = worker(source, parent, ctx, type);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("adapter", adapterType);
    if (cfg != null) {
      if (cfg.connectionFactory() != null) {
        headers.put("connectionFactory", cfg.connectionFactory());
      }
      cfg.properties().forEach((k, v) -> {
        if (headers.size() < 12 && v != null && v.length() <= 500) {
          headers.put(k, v);
        }
      });
    }
    headers(ctx, e, headers);
    DataMappings.apply(source, e, ctx, true, true);
    String hint = switch (adapterType.toLowerCase(Locale.ROOT)) {
      case "jms", "aq" -> " Consider a messaging connector (Kafka/RabbitMQ/AMQP) or a worker.";
      case "db" -> " Implement the SQL in a worker (connection " + (cfg == null ? "?" : cfg.connectionFactory()) + ").";
      case "file", "ftp" -> " Implement file handling in a worker.";
      default -> "";
    };
    ctx.report(source, cfg == null ? Level.MANUAL : Level.PARTIAL,
        "JCA " + adapterType + " adapter " + ref.name() + "." + call.operation() + ": job worker '" + type
            + "', adapter settings copied to task headers." + hint
            + (cfg == null ? " Adapter file " + jca.config() + " not found." : ""),
        Source.COMPOSITE);
    return e;
  }

  private static Element componentCall(Element source, Element parent, ConversionContext ctx, ServiceCall call,
      ToComponent tc, Composite composite) {
    var comp = tc.component();
    if (!comp.isBpmn()) {
      String type = jobType(comp.implementationType() + "-" + comp.name(), call.operation());
      Element e = worker(source, parent, ctx, type);
      DataMappings.apply(source, e, ctx, true, true);
      ctx.report(source, Level.MANUAL,
          "Calls " + comp.implementationType() + " component '" + comp.name() + "'. Migrate that component separately; "
              + "job worker '" + type + "' generated as a placeholder.", Source.COMPOSITE);
      return e;
    }
    String processId = composite.resolve(comp.src()).flatMap(ServiceCallMapper::processId).orElse(comp.name());
    Element e = ctx.createLike(source, "callActivity", parent);
    Element ce = ctx.addZeebe(e, "calledElement");
    ce.setAttribute("processId", processId);
    ce.setAttribute("propagateAllChildVariables", "false");
    DataMappings.apply(source, e, ctx, true, true);
    ctx.report(source, Level.AUTO,
        "Calls BPMN component '" + comp.name() + "' in the same composite: call activity to process '" + processId + "'.",
        Source.COMPOSITE);
    return e;
  }

  private static Optional<String> processId(java.nio.file.Path bpmn) {
    try {
      return XmlUtils.children(XmlUtils.parse(bpmn).getDocumentElement(), Ns.BPMN, "process").stream()
          .map(p -> p.getAttribute("id")).findFirst();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Element worker(Element source, Element parent, ConversionContext ctx, String type) {
    Element e = ctx.createLike(source, "serviceTask", parent);
    ctx.addZeebe(e, "taskDefinition").setAttribute("type", type);
    return e;
  }

  private static void headers(ConversionContext ctx, Element e, Map<String, String> values) {
    Element headers = ctx.addZeebe(e, "taskHeaders");
    values.forEach((k, v) -> {
      if (v != null) {
        Element h = ctx.zeebe("header");
        h.setAttribute("key", k);
        h.setAttribute("value", v);
        headers.appendChild(h);
      }
    });
  }

  private static void input(ConversionContext ctx, Element io, String source, String target) {
    Element i = ctx.zeebe("input");
    i.setAttribute("source", source);
    i.setAttribute("target", target);
    io.appendChild(i);
  }

  static String jobType(String reference, String operation) {
    return ActivityConverter.jobType(reference + (operation == null ? "" : "-" + operation));
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : b;
  }
}
