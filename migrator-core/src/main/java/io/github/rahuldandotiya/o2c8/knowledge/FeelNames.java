package io.github.rahuldandotiya.o2c8.knowledge;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds and renames root variable names in FEEL expressions and mapping targets. */
public final class FeelNames {

  private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private FeelNames() {}

  /**
   * Renames root variables in a FEEL expression (with or without the leading '='). Names inside
   * string literals, after a '.', or used as function names are left alone.
   */
  public static String rename(String expr, Map<String, String> renames) {
    if (expr == null || renames.isEmpty()) {
      return expr;
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < expr.length()) {
      char c = expr.charAt(i);
      if (c == '"') {
        int j = i + 1;
        while (j < expr.length() && expr.charAt(j) != '"') {
          j += expr.charAt(j) == '\\' ? 2 : 1;
        }
        j = Math.min(j + 1, expr.length());
        out.append(expr, i, j);
        i = j;
        continue;
      }
      Matcher m = IDENT.matcher(expr);
      if ((Character.isLetter(c) || c == '_') && m.find(i) && m.start() == i) {
        String name = m.group();
        boolean afterDot = previousNonSpace(expr, i) == '.';
        boolean isCall = nextNonSpace(expr, m.end()) == '(';
        boolean isKey = nextNonSpace(expr, m.end()) == ':' && previousNonSpace(expr, i) != '?';
        String replacement = afterDot || isCall || isKey ? null : renames.get(name);
        out.append(replacement != null ? replacement : name);
        i = m.end();
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  /** Renames the first segment of a dotted mapping target ("claim.status"). */
  public static String renameTarget(String target, Map<String, String> renames) {
    if (target == null) {
      return null;
    }
    int dot = target.indexOf('.');
    String root = dot < 0 ? target : target.substring(0, dot);
    String r = renames.get(root);
    return r == null ? target : r + (dot < 0 ? "" : target.substring(dot));
  }

  /** Root variable of a simple path expression ("=a.b[1].c" → a), or null when it is not a path. */
  public static String rootOf(String expr) {
    if (expr == null) {
      return null;
    }
    String e = expr.startsWith("=") ? expr.substring(1).trim() : expr.trim();
    Matcher m = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(\\.[A-Za-z_][A-Za-z0-9_]*|\\[[^\\]]*])*$").matcher(e);
    return m.matches() ? m.group(1) : null;
  }

  /** All root variable names in an expression. */
  public static Set<String> roots(String expr) {
    Set<String> out = new LinkedHashSet<>();
    rename(expr, new java.util.AbstractMap<>() {
      @Override
      public String get(Object key) {
        out.add((String) key);
        return null;
      }

      @Override
      public boolean isEmpty() {
        return false;
      }

      @Override
      public java.util.Set<Entry<String, String>> entrySet() {
        return java.util.Set.of();
      }
    });
    out.removeAll(Set.of("true", "false", "null", "and", "or", "not", "if", "then", "else", "for", "in",
        "return", "some", "every", "satisfies", "between", "instance", "of", "item"));
    return out;
  }

  private static char previousNonSpace(String s, int i) {
    for (int k = i - 1; k >= 0; k--) {
      if (!Character.isWhitespace(s.charAt(k))) {
        return s.charAt(k);
      }
    }
    return 0;
  }

  private static char nextNonSpace(String s, int i) {
    for (int k = i; k < s.length(); k++) {
      if (!Character.isWhitespace(s.charAt(k))) {
        return s.charAt(k);
      }
    }
    return 0;
  }
}
