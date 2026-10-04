package io.github.rahuldandotiya.o2c8.knowledge;

import io.github.rahuldandotiya.o2c8.composite.Composite.RestBinding;
import io.github.rahuldandotiya.o2c8.composite.Composite.WsBinding;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver.ToComponent;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver.ToReference;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Element;

/**
 * Keys under which rules are stored and looked up. A key must identify the same thing in a
 * different Oracle process: the called service, the Oracle human task, the XPath text.
 */
public final class Keys {

  private Keys() {}

  /** Keys for an Oracle activity's implementation, most specific first. */
  public static List<String> call(Element oracleSource, ServiceCallResolver resolver) {
    List<String> keys = new ArrayList<>();
    if (resolver != null) {
      resolver.resolve(oracleSource).ifPresent(c -> {
        String op = c.operation() == null ? "*" : c.operation();
        if (c.target() instanceof ToReference r) {
          keys.add("ref:" + r.reference().name() + "." + op);
          String endpoint = null;
          if (r.reference().binding() instanceof RestBinding rest) {
            endpoint = rest.location();
          } else if (r.reference().binding() instanceof WsBinding ws) {
            endpoint = ws.location();
          }
          if (endpoint != null) {
            keys.add("endpoint:" + normUrl(endpoint) + "#" + op);
          }
        } else if (c.target() instanceof ToComponent comp) {
          keys.add("component:" + comp.component().name() + "." + op);
        } else {
          keys.add("ref:" + c.partner() + "." + op);
        }
      });
    }
    String name = oracleSource.getAttribute("name");
    if (!name.isBlank()) {
      keys.add("name:" + ElementMatcher.norm(name));
    }
    return keys;
  }

  /** Oracle human task (.task) name, or the element name when there is no task reference. */
  public static String humanTask(Element oracleSource) {
    return OracleExtensions.of(oracleSource).humanTaskRef().map(t -> "task:" + t.name())
        .orElse("name:" + ElementMatcher.norm(oracleSource.getAttribute("name")));
  }

  /** XPath normalised: whitespace collapsed, quotes unified, namespace prefixes on steps removed. */
  public static String xpath(String x) {
    if (x == null) {
      return null;
    }
    return x.trim()
        .replace('"', '\'')
        .replaceAll("\\s+", " ")
        .replaceAll("\\s*([=!<>()\\[\\],/])\\s*", "$1")
        .replaceAll("(?<![A-Za-z0-9_'])[A-Za-z_][A-Za-z0-9_.-]*:(?=[A-Za-z_])", "");
  }

  /**
   * Business type of an Oracle data object (e.g. Claim), or null for XML Schema primitives such as
   * string or int, which say nothing about what the variable means.
   */
  public static String dataObjectType(Element dataObject) {
    return OracleExtensions.of(dataObject).typeRef()
        .filter(t -> t.namespace() == null || !t.namespace().contains("XMLSchema"))
        .map(OracleExtensions.TypeRef::name).orElse(null);
  }

  /** Oracle's generated data object suffix: lOProcessAsServiceINPDO → INPDO, xOUTPD → OUTPD; null if none. */
  public static String oracleSuffix(String name) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z0-9]([A-Z]{3,})$").matcher(name);
    return m.find() ? m.group(1) : null;
  }

  static String normUrl(String url) {
    String u = url.trim().toLowerCase(Locale.ROOT);
    u = u.replaceAll("\\?wsdl$", "");
    return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
  }

  /** "Check Fraud" → "check-fraud" (same rule as job types). */
  static String kebab(String s) {
    return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("[^A-Za-z0-9]+", "-")
        .replaceAll("^-|-$", "").toLowerCase(Locale.ROOT);
  }
}
