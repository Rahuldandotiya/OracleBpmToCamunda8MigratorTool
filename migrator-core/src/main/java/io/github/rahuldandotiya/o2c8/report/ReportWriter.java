package io.github.rahuldandotiya.o2c8.report;

import io.github.rahuldandotiya.o2c8.report.ConversionReport.Entry;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import java.util.List;
import java.util.Map;

/** Renders reports as Markdown (for people) and JSON (for tools). No third-party libraries. */
public final class ReportWriter {

  private ReportWriter() {}

  public static String markdown(List<ConversionReport> reports) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Oracle BPM to Camunda 8 conversion report\n\n");
    sb.append("| Source file | Elements | Auto | Partial | Manual | Automated | Validation |\n");
    sb.append("| --- | ---: | ---: | ---: | ---: | ---: | --- |\n");
    for (ConversionReport r : reports) {
      Map<Level, Integer> c = r.elementCounts();
      int total = c.values().stream().mapToInt(Integer::intValue).sum();
      sb.append("| ")
          .append(md(r.sourceName()))
          .append(" | ")
          .append(total)
          .append(" | ")
          .append(c.get(Level.AUTO))
          .append(" | ")
          .append(c.get(Level.PARTIAL))
          .append(" | ")
          .append(c.get(Level.MANUAL))
          .append(" | ")
          .append(r.automationPercent())
          .append("% | ")
          .append(r.validationIssues().isEmpty() ? "OK" : r.validationIssues().size() + " issue(s)")
          .append(" |\n");
    }
    sb.append("\nLevels: **AUTO** = nothing to do, **PARTIAL** = converted but review the note, ")
        .append("**MANUAL** = needs implementation, **INFO** = Oracle-only setting dropped.\n");
    for (ConversionReport r : reports) {
      sb.append("\n## ").append(md(r.sourceName())).append("\n\n");
      if (!r.validationIssues().isEmpty()) {
        sb.append("**Validation issues**\n\n");
        r.validationIssues().forEach(i -> sb.append("- ").append(md(i)).append('\n'));
        sb.append('\n');
      }
      sb.append("| Level | Element | Type | Note |\n| --- | --- | --- | --- |\n");
      r.entries().stream()
          .sorted(java.util.Comparator.comparingInt(ReportWriter::levelRank))
          .forEach(
              e ->
                  sb.append("| ")
                      .append(e.level())
                      .append(" | ")
                      .append(md(label(e)))
                      .append(" | ")
                      .append(md(e.elementType()))
                      .append(" | ")
                      .append(md(e.message()))
                      .append(" |\n"));
    }
    return sb.toString();
  }

  private static int levelRank(Entry e) {
    return switch (e.level()) {
      case MANUAL -> 0;
      case PARTIAL -> 1;
      case AUTO -> 2;
      case INFO -> 3;
    };
  }

  private static String label(Entry e) {
    return e.elementName() == null || e.elementName().equals(e.elementId())
        ? e.elementId()
        : e.elementName() + " (" + e.elementId() + ")";
  }

  public static String json(List<ConversionReport> reports) {
    StringBuilder sb = new StringBuilder("{\n  \"reports\": [");
    for (int i = 0; i < reports.size(); i++) {
      ConversionReport r = reports.get(i);
      Map<Level, Integer> c = r.elementCounts();
      sb.append(i == 0 ? "\n" : ",\n")
          .append("    {\n      \"source\": ")
          .append(js(r.sourceName()))
          .append(",\n      \"automationPercent\": ")
          .append(r.automationPercent())
          .append(",\n      \"counts\": {\"auto\": ")
          .append(c.get(Level.AUTO))
          .append(", \"partial\": ")
          .append(c.get(Level.PARTIAL))
          .append(", \"manual\": ")
          .append(c.get(Level.MANUAL))
          .append("},\n      \"validationIssues\": [");
      for (int j = 0; j < r.validationIssues().size(); j++) {
        sb.append(j == 0 ? "" : ", ").append(js(r.validationIssues().get(j)));
      }
      sb.append("],\n      \"entries\": [");
      List<Entry> es = r.entries();
      for (int j = 0; j < es.size(); j++) {
        Entry e = es.get(j);
        sb.append(j == 0 ? "\n" : ",\n")
            .append("        {\"process\": ")
            .append(js(e.processId()))
            .append(", \"id\": ")
            .append(js(e.elementId()))
            .append(", \"name\": ")
            .append(js(e.elementName()))
            .append(", \"type\": ")
            .append(js(e.elementType()))
            .append(", \"level\": ")
            .append(js(e.level().name()))
            .append(", \"message\": ")
            .append(js(e.message()))
            .append('}');
      }
      sb.append(es.isEmpty() ? "]\n    }" : "\n      ]\n    }");
    }
    return sb.append("\n  ]\n}\n").toString();
  }

  private static String md(String s) {
    return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
  }

  static String js(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) {
            sb.append(String.format("\\u%04x", (int) ch));
          } else {
            sb.append(ch);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
