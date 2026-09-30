package io.github.rahuldandotiya.o2c8.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates the XPath 1.0/2.0 subset used in Oracle BPM expressions into FEEL (Camunda 8).
 *
 * <p>Handles Oracle data accessors ({@code bpmn:getDataObject('x')}, {@code getDataInput},
 * {@code getDataOutput}), child paths with namespace prefixes ({@code /ns:FNOL/ns:sensitivity} →
 * {@code .FNOL.sensitivity}), predicates, BPEL-style {@code $variables}, operators and the common
 * string/number/date functions. Anything else yields a failed {@link Result} with the reason, so the
 * caller can keep the original expression and flag it for manual work.
 */
public final class XPathToFeel {

  /** Outcome of a translation. {@code feel} has no leading '='. */
  public record Result(String feel, boolean ok, String problem) {
    static Result ok(String feel) {
      return new Result(feel, true, null);
    }

    static Result fail(String problem) {
      return new Result(null, false, problem);
    }
  }

  private static final Pattern FEEL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private static final Map<String, String> SIMPLE_FUNCTIONS =
      Map.ofEntries(
          Map.entry("string-length", "string length"),
          Map.entry("contains", "contains"),
          Map.entry("starts-with", "starts with"),
          Map.entry("ends-with", "ends with"),
          Map.entry("substring", "substring"),
          Map.entry("substring-before", "substring before"),
          Map.entry("substring-after", "substring after"),
          Map.entry("upper-case", "upper case"),
          Map.entry("lower-case", "lower case"),
          Map.entry("normalize-space", "trim"),
          Map.entry("not", "not"),
          Map.entry("count", "count"),
          Map.entry("sum", "sum"),
          Map.entry("number", "number"),
          Map.entry("string", "string"),
          Map.entry("floor", "floor"),
          Map.entry("ceiling", "ceiling"),
          Map.entry("abs", "abs"),
          Map.entry("matches", "matches"),
          Map.entry("replace", "replace"),
          Map.entry("exists", "is defined"));

  private XPathToFeel() {}

  /** Translate an XPath expression to FEEL. Never throws. */
  public static Result translate(String xpath) {
    if (xpath == null || xpath.isBlank()) {
      return Result.fail("empty expression");
    }
    try {
      Parser p = new Parser(tokenize(xpath.trim()));
      String feel = p.expr();
      if (!p.atEnd()) {
        return Result.fail("unexpected token '" + p.peek().text + "'");
      }
      return Result.ok(feel);
    } catch (Unsupported e) {
      return Result.fail(e.getMessage());
    }
  }

  /**
   * Translates the target side of an assignment ({@code bpmn:getDataObject('x')/ns:a}) into a Zeebe
   * mapping target ({@code x.a}). Zeebe targets are dotted variable paths, not expressions.
   */
  public static Result translateTarget(String xpath) {
    Result r = translate(xpath);
    if (!r.ok()) {
      return r;
    }
    if (!r.feel().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
      return Result.fail("target is not a simple variable path: " + r.feel());
    }
    return r;
  }

  // ---------------------------------------------------------------- tokenizer

  private enum T {
    NAME,
    STRING,
    NUMBER,
    SYM,
    VAR,
    EOF
  }

  private record Tok(T type, String text) {}

  private static List<Tok> tokenize(String s) {
    List<Tok> out = new ArrayList<>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '\'' || c == '"') {
        int j = s.indexOf(c, i + 1);
        if (j < 0) {
          throw new Unsupported("unterminated string literal");
        }
        out.add(new Tok(T.STRING, s.substring(i + 1, j)));
        i = j + 1;
      } else if (Character.isDigit(c)
          || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
        int j = i;
        while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
          j++;
        }
        out.add(new Tok(T.NUMBER, s.substring(i, j)));
        i = j;
      } else if (c == '$') {
        int j = i + 1;
        while (j < s.length() && isNameChar(s.charAt(j))) {
          j++;
        }
        out.add(new Tok(T.VAR, s.substring(i + 1, j)));
        i = j;
      } else if (isNameStart(c)) {
        int j = i;
        while (j < s.length() && (isNameChar(s.charAt(j)) || s.charAt(j) == ':')) {
          // a single ':' belongs to a QName; '::' is an axis, not supported
          if (s.charAt(j) == ':' && j + 1 < s.length() && s.charAt(j + 1) == ':') {
            throw new Unsupported("XPath axes (::) are not supported");
          }
          j++;
        }
        out.add(new Tok(T.NAME, s.substring(i, j)));
        i = j;
      } else {
        String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
        if (two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("//")) {
          out.add(new Tok(T.SYM, two));
          i += 2;
        } else if ("()[],/@=<>+-*|.".indexOf(c) >= 0) {
          out.add(new Tok(T.SYM, String.valueOf(c)));
          i++;
        } else {
          throw new Unsupported("unexpected character '" + c + "'");
        }
      }
    }
    out.add(new Tok(T.EOF, ""));
    return out;
  }

  private static boolean isNameStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
  }

  // ---------------------------------------------------------------- parser

  private static final class Parser {
    private final List<Tok> toks;
    private int pos;

    Parser(List<Tok> toks) {
      this.toks = toks;
    }

    Tok peek() {
      return toks.get(pos);
    }

    boolean atEnd() {
      return peek().type == T.EOF;
    }

    private boolean sym(String s) {
      return peek().type == T.SYM && peek().text.equals(s);
    }

    private boolean word(String w) {
      return peek().type == T.NAME && peek().text.equals(w);
    }

    private Tok next() {
      return toks.get(pos++);
    }

    private void expect(String s) {
      if (!sym(s)) {
        throw new Unsupported("expected '" + s + "' but found '" + peek().text + "'");
      }
      pos++;
    }

    String expr() {
      return or();
    }

    private String or() {
      String l = and();
      while (word("or")) {
        next();
        l = l + " or " + and();
      }
      return l;
    }

    private String and() {
      String l = eq();
      while (word("and")) {
        next();
        l = l + " and " + eq();
      }
      return l;
    }

    private String eq() {
      String l = rel();
      while (sym("=") || sym("!=")) {
        String op = next().text;
        l = l + " " + op + " " + rel();
      }
      return l;
    }

    private String rel() {
      String l = add();
      while (sym("<") || sym(">") || sym("<=") || sym(">=")) {
        String op = next().text;
        l = l + " " + op + " " + add();
      }
      return l;
    }

    private String add() {
      String l = mul();
      while (sym("+") || sym("-")) {
        String op = next().text;
        l = l + " " + op + " " + mul();
      }
      return l;
    }

    private String mul() {
      String l = unary();
      while (sym("*") || word("div") || word("mod")) {
        String op = next().text;
        String r = unary();
        l = switch (op) {
          case "*" -> l + " * " + r;
          case "div" -> l + " / " + r;
          default -> "modulo(" + l + ", " + r + ")";
        };
      }
      return l;
    }

    private String unary() {
      if (sym("-")) {
        next();
        return "-" + unary();
      }
      return union();
    }

    private String union() {
      String l = path();
      if (sym("|")) {
        throw new Unsupported("node-set union (|) is not supported");
      }
      return l;
    }

    /** primary ( predicate )* ( '/' step ( predicate )* )* */
    private String path() {
      if (sym("/") || sym("//")) {
        throw new Unsupported("absolute paths are not supported; use bpmn:getDataObject()");
      }
      String base = primary();
      base = predicates(base);
      while (sym("/") || sym("//")) {
        if (sym("//")) {
          throw new Unsupported("descendant paths (//) are not supported");
        }
        next();
        base = step(base);
        base = predicates(base);
      }
      return base;
    }

    private String step(String base) {
      if (sym("@")) {
        next();
        return base + "." + feelName(localName(expectName()));
      }
      if (sym("*")) {
        throw new Unsupported("wildcard steps (*) are not supported");
      }
      if (word("text") && toks.get(pos + 1).text.equals("(")) {
        next();
        expect("(");
        expect(")");
        return base; // element text == value in JSON
      }
      return base + "." + feelName(localName(expectName()));
    }

    private String predicates(String base) {
      while (sym("[")) {
        next();
        String inner = expr();
        expect("]");
        base = base + "[" + inner + "]"; // XPath and FEEL are both 1-based
      }
      return base;
    }

    private String expectName() {
      if (peek().type != T.NAME) {
        throw new Unsupported("expected a name but found '" + peek().text + "'");
      }
      return next().text;
    }

    private String primary() {
      Tok t = peek();
      switch (t.type) {
        case STRING -> {
          next();
          return "\"" + t.text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        case NUMBER -> {
          next();
          return t.text;
        }
        case VAR -> {
          next(); // BPEL style $variable.part -> variable.part
          return String.join(".", java.util.Arrays.stream(t.text.split("\\.")).map(XPathToFeel::feelName).toList());
        }
        case SYM -> {
          if (sym("(")) {
            next();
            String inner = expr();
            expect(")");
            return "(" + inner + ")";
          }
          if (sym("@")) {
            next();
            return feelName(localName(expectName()));
          }
          if (sym(".")) {
            next();
            return "item";
          }
          throw new Unsupported("unexpected '" + t.text + "'");
        }
        case NAME -> {
          next();
          if (sym("(")) {
            return function(t.text);
          }
          // relative child step, e.g. inside a predicate: [ns:status = 'x']
          return feelName(localName(t.text));
        }
        default -> throw new Unsupported("unexpected end of expression");
      }
    }

    private List<String> args() {
      expect("(");
      List<String> a = new ArrayList<>();
      if (!sym(")")) {
        a.add(expr());
        while (sym(",")) {
          next();
          a.add(expr());
        }
      }
      expect(")");
      return a;
    }

    private String function(String qname) {
      String prefix = qname.contains(":") ? qname.substring(0, qname.indexOf(':')) : "";
      String name = localName(qname);
      // Oracle BPM data accessors: the argument names the variable
      if (name.equals("getDataObject")
          || name.equals("getDataInput")
          || name.equals("getDataOutput")) {
        List<String> a = args();
        if (a.size() != 1 || !a.get(0).startsWith("\"")) {
          throw new Unsupported(name + "() needs one literal argument");
        }
        return feelName(a.get(0).substring(1, a.get(0).length() - 1));
      }
      if (!prefix.isEmpty()
          && !prefix.equals("fn")
          && !prefix.equals("xp20")
          && !prefix.equals("xpath20")
          && !prefix.equals("bpmn")) {
        throw new Unsupported("vendor function " + qname + "() has no FEEL equivalent");
      }
      List<String> a = args();
      switch (name) {
        case "true":
          return "true";
        case "false":
          return "false";
        case "concat":
          return "(" + String.join(" + ", a.stream().map(x -> "string(" + x + ")").toList()) + ")";
        case "current-date":
          return "today()";
        case "current-dateTime":
          return "now()";
        case "current-time":
          return "time(now())";
        case "round":
          return "round half up(" + a.get(0) + ", 0)";
        case "boolean":
          return "(" + a.get(0) + " != null)";
        case "empty":
          return "(" + a.get(0) + " = null)";
        default:
          break;
      }
      String feelFn = SIMPLE_FUNCTIONS.get(name);
      if (feelFn == null) {
        throw new Unsupported("function " + qname + "() has no FEEL mapping yet");
      }
      return feelFn + "(" + String.join(", ", a) + ")";
    }
  }

  static String localName(String qname) {
    int i = qname.indexOf(':');
    return i < 0 ? qname : qname.substring(i + 1);
  }

  /** FEEL name, backtick-quoted when it is not a plain identifier (e.g. contains '-'). */
  static String feelName(String n) {
    if (FEEL_NAME.matcher(n).matches() && !FEEL_KEYWORDS.contains(n.toLowerCase(Locale.ROOT))) {
      return n;
    }
    return "`" + n + "`";
  }

  private static final java.util.Set<String> FEEL_KEYWORDS =
      java.util.Set.of(
          "and", "or", "not", "null", "true", "false", "if", "then", "else", "for", "in", "return",
          "some", "every", "satisfies", "between", "instance", "of", "function");

  private static final class Unsupported extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unsupported(String m) {
      super(m);
    }
  }
}
