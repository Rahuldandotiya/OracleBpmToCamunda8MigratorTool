package io.github.rahuldandotiya.o2c8.connectors;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds FEEL context literals from dotted paths: {"a.b": "x", "a.c": "y"} → {a: {b: x, c: y}}. */
final class FeelContext {

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private FeelContext() {}

  static String of(Map<String, String> pathToExpr) {
    Map<String, Object> tree = new LinkedHashMap<>();
    for (var e : pathToExpr.entrySet()) {
      String[] parts = e.getKey().split("\\.");
      Map<String, Object> node = tree;
      for (int i = 0; i < parts.length - 1; i++) {
        Object next = node.get(parts[i]);
        if (!(next instanceof Map)) {
          next = new LinkedHashMap<String, Object>();
          node.put(parts[i], next);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) next;
        node = m;
      }
      node.put(parts[parts.length - 1], e.getValue());
    }
    return render(tree);
  }

  private static String render(Map<String, Object> tree) {
    StringBuilder sb = new StringBuilder("{");
    int i = 0;
    for (var e : tree.entrySet()) {
      if (i++ > 0) {
        sb.append(", ");
      }
      sb.append(key(e.getKey())).append(": ");
      if (e.getValue() instanceof Map<?, ?> m) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sub = (Map<String, Object>) m;
        sb.append(render(sub));
      } else {
        sb.append(e.getValue());
      }
    }
    return sb.append('}').toString();
  }

  static String key(String k) {
    return NAME.matcher(k).matches() ? k : "\"" + k.replace("\"", "\\\"") + "\"";
  }
}
