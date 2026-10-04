package io.github.rahuldandotiya.o2c8.knowledge;

import io.github.rahuldandotiya.o2c8.util.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rules learned from finished migrations. Each rule has a kind, a key that must match exactly in a
 * new process, and one or more candidate values; each candidate records which pairs voted for it.
 * The value with most votes wins; more than one candidate means the pairs disagreed (a conflict).
 */
public final class KnowledgeBase {

  public static final int FORMAT_VERSION = 1;

  /** Rule kinds, in the order the report explains them. */
  public enum Kind {
    /** processId/elementId → full fragment (same process converted again). */
    EXACT,
    /** composite reference.operation, endpoint, or name:type:name → implementation fragment. */
    CALL,
    /** Oracle .task name → user task settings. */
    HUMAN_TASK,
    /** normalised XPath → FEEL (conditions). */
    EXPRESSION,
    /** Oracle data object name → Camunda variable name. */
    VARIABLE,
    /** Oracle data object type (e.g. Claim) → Camunda variable name. */
    VARIABLE_TYPE,
    /** lane / role / candidate group → candidate group. */
    ROLE,
    /** message name → "newName|correlationKey". */
    MESSAGE,
    /** naming conventions, e.g. jobTypePrefix → "claims.". */
    CONVENTION
  }

  /** One candidate value and the pairs (and element ids) that support it. */
  public record Candidate(String value, List<String> sources) {
    public int votes() {
      return (int) sources.stream().map(s -> s.contains("#") ? s.substring(0, s.indexOf('#')) : s).distinct().count();
    }
  }

  /** A rule: best candidate plus alternatives. */
  public record Rule(Kind kind, String key, List<Candidate> candidates) {
    public Candidate best() {
      return candidates.stream().max(Comparator.comparingInt(Candidate::votes)).orElseThrow();
    }

    public boolean conflict() {
      return candidates.size() > 1;
    }

    /** Pair names (without element ids) that support the best value. */
    public String origin() {
      return String.join(", ", best().sources().stream()
          .map(s -> s.contains("#") ? s.substring(0, s.indexOf('#')) : s).distinct().toList());
    }
  }

  /** A learned pair, for the record. */
  public record Pair(String name, String oracle, String camunda, String matchedBy) {}

  private final Map<Kind, Map<String, Rule>> rules = new LinkedHashMap<>();
  private final List<Pair> pairs = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();

  public static KnowledgeBase empty() {
    return new KnowledgeBase();
  }

  public boolean isEmpty() {
    return rules.values().stream().allMatch(Map::isEmpty);
  }

  // ------------------------------------------------------------------ learning API

  public void add(Kind kind, String key, String value, String source) {
    if (key == null || key.isBlank() || value == null) {
      return;
    }
    Map<String, Rule> byKey = rules.computeIfAbsent(kind, k -> new LinkedHashMap<>());
    Rule r = byKey.get(key);
    List<Candidate> cands = r == null ? new ArrayList<>() : new ArrayList<>(r.candidates());
    boolean found = false;
    for (int i = 0; i < cands.size(); i++) {
      Candidate c = cands.get(i);
      if (c.value().equals(value)) {
        List<String> s = new ArrayList<>(c.sources());
        if (!s.contains(source)) {
          s.add(source);
        }
        cands.set(i, new Candidate(value, List.copyOf(s)));
        found = true;
      }
    }
    if (!found) {
      cands.add(new Candidate(value, List.of(source)));
    }
    byKey.put(key, new Rule(kind, key, List.copyOf(cands)));
  }

  public void remove(Kind kind, String key) {
    Map<String, Rule> byKey = rules.get(kind);
    if (byKey != null) {
      byKey.remove(key);
    }
  }

  public void addPair(Pair p) {
    pairs.add(p);
  }

  public void note(String n) {
    notes.add(n);
  }

  // ------------------------------------------------------------------ lookup

  public Optional<Rule> rule(Kind kind, String key) {
    if (key == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(rules.getOrDefault(kind, Map.of()).get(key));
  }

  public Map<String, Rule> rules(Kind kind) {
    return rules.getOrDefault(kind, Map.of());
  }

  public List<Pair> pairs() {
    return pairs;
  }

  public List<String> notes() {
    return notes;
  }

  public int size() {
    return rules.values().stream().mapToInt(Map::size).sum();
  }

  // ------------------------------------------------------------------ JSON

  public String toJson() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("format", "oracle2c8-knowledge-base");
    root.put("version", FORMAT_VERSION);
    List<Object> ps = new ArrayList<>();
    for (Pair p : pairs) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", p.name());
      m.put("oracle", p.oracle());
      m.put("camunda", p.camunda());
      m.put("matchedBy", p.matchedBy());
      ps.add(m);
    }
    root.put("pairs", ps);
    List<Object> rs = new ArrayList<>();
    for (var kind : rules.entrySet()) {
      for (Rule r : kind.getValue().values()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", r.kind().name());
        m.put("key", r.key());
        List<Object> cs = new ArrayList<>();
        for (Candidate c : r.candidates()) {
          Map<String, Object> cm = new LinkedHashMap<>();
          cm.put("value", c.value());
          cm.put("sources", c.sources());
          cs.add(cm);
        }
        m.put("candidates", cs);
        rs.add(m);
      }
    }
    root.put("rules", rs);
    root.put("notes", notes);
    return Json.write(root);
  }

  @SuppressWarnings("unchecked")
  public static KnowledgeBase fromJson(String json) {
    Map<String, Object> root = Json.parseObject(json);
    if (!"oracle2c8-knowledge-base".equals(root.get("format"))) {
      throw new IllegalArgumentException("not an oracle2c8 knowledge base file");
    }
    KnowledgeBase kb = new KnowledgeBase();
    for (Object o : (List<Object>) root.getOrDefault("pairs", List.of())) {
      Map<String, Object> m = (Map<String, Object>) o;
      kb.addPair(new Pair((String) m.get("name"), (String) m.get("oracle"), (String) m.get("camunda"),
          (String) m.get("matchedBy")));
    }
    for (Object o : (List<Object>) root.getOrDefault("rules", List.of())) {
      Map<String, Object> m = (Map<String, Object>) o;
      Kind kind = Kind.valueOf((String) m.get("kind"));
      String key = (String) m.get("key");
      for (Object c : (List<Object>) m.getOrDefault("candidates", List.of())) {
        Map<String, Object> cm = (Map<String, Object>) c;
        for (Object s : (List<Object>) cm.getOrDefault("sources", List.of("manual"))) {
          kb.add(kind, key, (String) cm.get("value"), String.valueOf(s));
        }
      }
    }
    for (Object n : (List<Object>) root.getOrDefault("notes", List.of())) {
      kb.note(String.valueOf(n));
    }
    return kb;
  }
}
