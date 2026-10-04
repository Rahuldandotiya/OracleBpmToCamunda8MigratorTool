package io.github.rahuldandotiya.o2c8.composite;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An Oracle SOA/BPM {@code composite.xml}: which processes it contains, which external services it
 * calls (references with their bindings) and how they are wired.
 */
public record Composite(
    Path file,
    Path root,
    String name,
    List<Component> components,
    List<Reference> references,
    List<Service> services,
    List<Wire> wires,
    Map<String, Map<String, String>> configPlanOverrides) {

  /** {@code <component>}: a BPMN process, BPEL process, mediator, rule set ... */
  public record Component(String name, String implementationType, String src) {
    public boolean isBpmn() {
      return "bpmn".equals(implementationType);
    }
  }

  /** {@code <reference>}: an outbound service with its binding. */
  public record Reference(String name, String interfaceName, String wsdlLocation, Binding binding) {}

  /** Top-level {@code <service>}: an entry point into the composite (SOAP endpoint, inbound JMS/email adapter...). */
  public record Service(String name, String wsdlLocation, Binding binding) {}

  /** {@code <wire>}: source.uri to target.uri. */
  public record Wire(String source, String target) {}

  /** Binding of a reference. */
  public sealed interface Binding permits RestBinding, WsBinding, JcaBinding, OtherBinding {}

  /** {@code binding.rest}: REST adapter, described by a WADL (or Swagger) file. */
  public record RestBinding(String location, String config) implements Binding {}

  /** {@code binding.ws}: SOAP web service. */
  public record WsBinding(String location, String port, String soapVersion) implements Binding {}

  /** {@code binding.jca}: technology adapter (db, jms, file, ftp, aq, ...), configured in a .jca file. */
  public record JcaBinding(String config) implements Binding {}

  /** Anything else (binding.adf, binding.ejb, binding.direct ...). */
  public record OtherBinding(String type) implements Binding {}

  public Optional<Reference> reference(String name) {
    return references.stream().filter(r -> r.name().equals(name)).findFirst()
        .or(() -> references.stream().filter(r -> r.name().equalsIgnoreCase(name)).findFirst());
  }

  public Optional<Component> component(String name) {
    return components.stream().filter(c -> c.name().equals(name)).findFirst();
  }

  /** The component implemented by this BPMN file (by src path), if any. */
  public Optional<Component> componentForBpmn(Path bpmn, String processId) {
    Path abs = bpmn.toAbsolutePath().normalize();
    return components.stream()
        .filter(Component::isBpmn)
        .filter(c -> c.src() != null && root.resolve(c.src()).normalize().equals(abs))
        .findFirst()
        .or(() -> components.stream().filter(Component::isBpmn).filter(c -> c.name().equals(processId)).findFirst())
        .or(() -> components.stream().filter(Component::isBpmn)
            .filter(c -> c.src() != null && Path.of(c.src()).getFileName().equals(bpmn.getFileName())).findFirst());
  }

  /** Where a component's partner (reference) name is wired to: "Ref" (composite reference) or "Comp/Service". */
  public Optional<String> wireTarget(String component, String partner) {
    return wireTarget(component, List.of(partner));
  }

  /**
   * Outbound wire from {@code component} whose source matches one of the partner names. Oracle
   * generates names such as {@code Services.Externals.FraudCheck.reference} or
   * {@code BackOffice_BackOffice_Feedback.reference}, so matching ignores those decorations.
   */
  public Optional<String> wireTarget(String component, List<String> partners) {
    for (String p : partners) {
      for (Wire w : wires) {
        if (w.source().startsWith(component + "/") && sameName(w.source().substring(component.length() + 1), p)) {
          return Optional.of(w.target());
        }
      }
    }
    return Optional.empty();
  }

  /** Inbound wire into {@code component} from a top-level composite service (adapter or endpoint). */
  public Optional<Service> inboundService(String component, List<String> partners) {
    for (String p : partners) {
      for (Wire w : wires) {
        if (w.target().startsWith(component + "/") && sameName(w.target().substring(component.length() + 1), p)) {
          Optional<Service> s = services.stream().filter(x -> x.name().equals(w.source())).findFirst();
          if (s.isPresent()) {
            return s;
          }
        }
      }
    }
    return Optional.empty();
  }

  /** Compares an Oracle-generated endpoint name with a partner name, ignoring prefixes and suffixes. */
  static boolean sameName(String generated, String partner) {
    String g = strip(generated);
    String p = strip(partner);
    return g.equals(p) || g.endsWith("." + p) || g.endsWith("_" + p) || p.endsWith("." + g);
  }

  private static String strip(String n) {
    String s = n.replaceAll("\\.(reference|service)$", "");
    for (String prefix : new String[] {"Services.Externals.", "References.Externals.", "Services.", "References."}) {
      if (s.startsWith(prefix)) {
        s = s.substring(prefix.length());
      }
    }
    return s;
  }

  /** Resolves a path relative to the composite folder, refusing to leave it. */
  public Optional<Path> resolve(String relative) {
    if (relative == null || relative.isBlank() || relative.contains("://") || relative.startsWith("oramds:")) {
      return Optional.empty();
    }
    Path p = root.resolve(relative).normalize();
    return p.startsWith(root) && java.nio.file.Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
  }
}
