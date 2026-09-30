package io.github.rahuldandotiya.o2c8.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer (objects, arrays, strings, numbers, booleans, null) so the core keeps
 * zero runtime dependencies. Objects become {@code LinkedHashMap<String,Object>}, arrays
 * {@code List<Object>}, numbers {@code Long} or {@code Double}.
 */
public final class Json {

  private Json() {}

  // ------------------------------------------------------------------ writing

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    write(value, sb, 0);
    return sb.append('\n').toString();
  }

  private static void write(Object v, StringBuilder sb, int indent) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String s) {
      quote(s, sb);
    } else if (v instanceof Number || v instanceof Boolean) {
      sb.append(v);
    } else if (v instanceof Map<?, ?> m) {
      if (m.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{\n");
      int i = 0;
      for (var e : m.entrySet()) {
        pad(sb, indent + 2);
        quote(String.valueOf(e.getKey()), sb);
        sb.append(": ");
        write(e.getValue(), sb, indent + 2);
        sb.append(++i < m.size() ? ",\n" : "\n");
      }
      pad(sb, indent);
      sb.append('}');
    } else if (v instanceof Iterable<?> it) {
      List<Object> l = new ArrayList<>();
      it.forEach(l::add);
      if (l.isEmpty()) {
        sb.append("[]");
        return;
      }
      boolean simple = l.stream().allMatch(x -> x == null || x instanceof String || x instanceof Number);
      if (simple && l.size() <= 8) {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
          write(l.get(i), sb, indent);
          sb.append(i + 1 < l.size() ? ", " : "");
        }
        sb.append(']');
        return;
      }
      sb.append("[\n");
      for (int i = 0; i < l.size(); i++) {
        pad(sb, indent + 2);
        write(l.get(i), sb, indent + 2);
        sb.append(i + 1 < l.size() ? ",\n" : "\n");
      }
      pad(sb, indent);
      sb.append(']');
    } else {
      quote(v.toString(), sb);
    }
  }

  private static void pad(StringBuilder sb, int n) {
    sb.append(" ".repeat(n));
  }

  public static void quote(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  // ------------------------------------------------------------------ reading

  public static Object parse(String text) {
    Parser p = new Parser(text);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != text.length()) {
      throw p.err("trailing content");
    }
    return v;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    Object v = parse(text);
    if (!(v instanceof Map)) {
      throw new IllegalArgumentException("JSON document is not an object");
    }
    return (Map<String, Object>) v;
  }

  private static final class Parser {
    private final String s;
    private int i;
    private int depth;

    Parser(String s) {
      this.s = s;
    }

    IllegalArgumentException err(String m) {
      return new IllegalArgumentException("Invalid JSON at offset " + i + ": " + m);
    }

    void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    Object value() {
      if (i >= s.length()) {
        throw err("unexpected end");
      }
      char c = s.charAt(i);
      return switch (c) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Object literal(String word, Object v) {
      if (!s.startsWith(word, i)) {
        throw err("expected " + word);
      }
      i += word.length();
      return v;
    }

    private Map<String, Object> object() {
      if (++depth > 200) {
        throw err("nesting too deep");
      }
      Map<String, Object> m = new LinkedHashMap<>();
      i++;
      ws();
      if (peek('}')) {
        i++;
        depth--;
        return m;
      }
      while (true) {
        ws();
        if (!peek('"')) {
          throw err("expected key");
        }
        String k = string();
        ws();
        expect(':');
        ws();
        m.put(k, value());
        ws();
        if (peek(',')) {
          i++;
          continue;
        }
        expect('}');
        depth--;
        return m;
      }
    }

    private List<Object> array() {
      if (++depth > 200) {
        throw err("nesting too deep");
      }
      List<Object> l = new ArrayList<>();
      i++;
      ws();
      if (peek(']')) {
        i++;
        depth--;
        return l;
      }
      while (true) {
        ws();
        l.add(value());
        ws();
        if (peek(',')) {
          i++;
          continue;
        }
        expect(']');
        depth--;
        return l;
      }
    }

    private String string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (i >= s.length()) {
          throw err("unterminated string");
        }
        char c = s.charAt(i++);
        if (c == '"') {
          return sb.toString();
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        char e = s.charAt(i++);
        switch (e) {
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'u' -> {
            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> sb.append(e);
        }
      }
    }

    private Number number() {
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      String n = s.substring(start, i);
      if (n.isEmpty()) {
        throw err("unexpected character '" + s.charAt(i) + "'");
      }
      return n.contains(".") || n.contains("e") || n.contains("E") ? Double.parseDouble(n) : Long.parseLong(n);
    }

    private boolean peek(char c) {
      return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
      if (!peek(c)) {
        throw err("expected '" + c + "'");
      }
      i++;
    }
  }
}
