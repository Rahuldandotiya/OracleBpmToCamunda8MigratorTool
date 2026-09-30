package io.github.rahuldandotiya.o2c8.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
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

  public record Entry(
      String processId, String elementId, String elementName, String elementType, Level level,
      String message) {}

  private final String sourceName;
  private final List<Entry> entries = new ArrayList<>();
  private final List<String> validationIssues = new ArrayList<>();

  public ConversionReport(String sourceName) {
    this.sourceName = sourceName;
  }

  public void add(
      String processId, String elementId, String name, String type, Level level, String message) {
    entries.add(new Entry(processId, elementId, name, type, level, message));
  }

  public void addValidationIssue(String issue) {
    validationIssues.add(issue);
  }

  public String sourceName() {
    return sourceName;
  }

  public List<Entry> entries() {
    return Collections.unmodifiableList(entries);
  }

  public List<String> validationIssues() {
    return Collections.unmodifiableList(validationIssues);
  }

  /** Worst level per element id, counted. INFO entries are not counted. */
  public Map<Level, Integer> elementCounts() {
    Map<String, Level> worst = new java.util.LinkedHashMap<>();
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
    Map<Level, Integer> c = elementCounts();
    int total = c.values().stream().mapToInt(Integer::intValue).sum();
    return total == 0 ? 100 : Math.round(100f * c.get(Level.AUTO) / total);
  }
}
