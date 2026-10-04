package io.github.rahuldandotiya.o2c8.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the converter did, or could not do, for one source file. */
public final class ConversionReport {

  /** How much human work an element still needs after conversion. */
  public enum Level {
    /** Converted fully; nothing to do. */
    AUTO,
    /** Converted, but a person must review or complete something (see message). */
    PARTIAL,
    /** Not converted automatically; placeholder or nothing was generated. */
    MANUAL,
    /** Informational note, e.g. an Oracle-only setting that was dropped. */
    INFO
  }

  /** Where a decision came from. */
  public enum Source {
    BUILT_IN("built-in"),
    COMPOSITE("composite"),
    KNOWLEDGE_BASE("knowledge base");

    private final String label;

    Source(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public record Entry(
      String processId, String elementId, String elementName, String elementType, Level level,
      String message, Source source) {

    public Entry(String processId, String elementId, String elementName, String elementType, Level level,
        String message) {
      this(processId, elementId, elementName, elementType, level, message, Source.BUILT_IN);
    }
  }

  /** A Camunda secret the converted models expect, with the value(s) found in the Oracle project. */
  public record Secret(String name, String value, String reference, Map<String, String> environmentValues) {}

  /** Outcome of converting one file. */
  public enum Status {
    /** Converted; the model passed all checks. */
    CONVERTED,
    /** Converted, but the model has validation issues to fix before deployment. */
    CONVERTED_WITH_ISSUES,
    /** No model was produced; {@link #failure()} says why. */
    FAILED
  }

  private final String sourceName;
  private String failure;
  private final List<Entry> entries = new ArrayList<>();
  private final List<String> validationIssues = new ArrayList<>();
  private final Map<String, Secret> secrets = new LinkedHashMap<>();

  public ConversionReport(String sourceName) {
    this.sourceName = sourceName;
  }

  public void add(
      String processId, String elementId, String name, String type, Level level, String message) {
    entries.add(new Entry(processId, elementId, name, type, level, message));
  }

  public void add(Entry e) {
    entries.add(e);
  }

  /**
   * Marks earlier PARTIAL/MANUAL notes of an element as resolved (they become INFO), used when the
   * knowledge base supplied what those notes asked for.
   */
  public void resolve(String elementId, String because) {
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      if (elementId.equals(e.elementId()) && (e.level() == Level.PARTIAL || e.level() == Level.MANUAL)) {
        entries.set(i, new Entry(e.processId(), e.elementId(), e.elementName(), e.elementType(), Level.INFO,
            "Resolved by " + because + ". Was: " + e.message(), e.source()));
      }
    }
  }

  public void addValidationIssue(String issue) {
    validationIssues.add(issue);
  }

  public void addSecret(String name, String value, String reference, Map<String, String> environmentValues) {
    secrets.putIfAbsent(name, new Secret(name, value, reference,
        environmentValues == null ? Map.of() : Map.copyOf(environmentValues)));
  }

  /** Report for a file that could not be converted at all. */
  public static ConversionReport failed(String sourceName, String reason) {
    ConversionReport r = new ConversionReport(sourceName);
    r.failure = reason == null || reason.isBlank() ? "unknown error" : reason;
    return r;
  }

  public String sourceName() {
    return sourceName;
  }

  /** Why the file could not be converted, or null when a model was produced. */
  public String failure() {
    return failure;
  }

  public Status status() {
    if (failure != null) {
      return Status.FAILED;
    }
    return validationIssues.isEmpty() ? Status.CONVERTED : Status.CONVERTED_WITH_ISSUES;
  }

  public List<Entry> entries() {
    return Collections.unmodifiableList(entries);
  }

  public List<String> validationIssues() {
    return Collections.unmodifiableList(validationIssues);
  }

  public Map<String, Secret> secrets() {
    return Collections.unmodifiableMap(secrets);
  }

  /** Worst level per element id, counted. INFO entries are not counted. */
  public Map<Level, Integer> elementCounts() {
    Map<String, Level> worst = new LinkedHashMap<>();
    for (Entry e : entries) {
      if (e.level() == Level.INFO) {
        continue;
      }
      worst.merge(e.elementId(), e.level(), (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
    }
    Map<Level, Integer> counts = new EnumMap<>(Level.class);
    for (Level l : List.of(Level.AUTO, Level.PARTIAL, Level.MANUAL)) {
      counts.put(l, 0);
    }
    worst.values().forEach(l -> counts.merge(l, 1, Integer::sum));
    return counts;
  }

  /** Share of converted elements needing no human work, 0..100. */
  public int automationPercent() {
    if (failure != null) {
      return 0;
    }
    Map<Level, Integer> c = elementCounts();
    int total = c.values().stream().mapToInt(Integer::intValue).sum();
    return total == 0 ? 100 : Math.round(100f * c.get(Level.AUTO) / total);
  }

  /** Number of elements where at least one decision came from the given source. */
  public long elementsFrom(Source source) {
    return entries.stream().filter(e -> e.source() == source).map(Entry::elementId).distinct().count();
  }
}
