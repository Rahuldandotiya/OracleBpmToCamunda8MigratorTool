package io.github.rahuldandotiya.o2c8.report;

import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Entry;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Secret;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Source;
import io.github.rahuldandotiya.o2c8.util.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders reports as Markdown (for people) and JSON (for tools). No third-party libraries. */
public final class ReportWriter {

  private ReportWriter() {}

  public static String markdown(List<ConversionReport> reports) {
    return markdown(reports, null);
  }

  public static String markdown(List<ConversionReport> reports, KnowledgeBase kb) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Oracle BPM to Camunda 8 conversion report\n\n");
    sb.append("| Source file | Elements | Auto | Partial | Manual | Automated | From knowledge base | From composite | Validation |\n");
    sb.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
    for (ConversionReport r : reports) {
      Map<Level, Integer> c = r.elementCounts();
      int total = c.values().stream().mapToInt(Integer::intValue).sum();
      sb.append("| ").append(md(r.sourceName()))
          .append(" | ").append(total)
          .append(" | ").append(c.get(Level.AUTO))
          .append(" | ").append(c.get(Level.PARTIAL))
          .append(" | ").append(c.get(Level.MANUAL))
          .append(" | ").append(r.automationPercent()).append('%')
          .append(" | ").append(r.elementsFrom(Source.KNOWLEDGE_BASE))
          .append(" | ").append(r.elementsFrom(Source.COMPOSITE))
          .append(" | ").append(r.validationIssues().isEmpty() ? "OK" : r.validationIssues().size() + " issue(s)")
          .append(" |\n");
    }
    sb.append("\nLevels: **AUTO** = nothing to do, **PARTIAL** = converted but review the note, ")
        .append("**MANUAL** = needs implementation, **INFO** = note only (dropped Oracle setting, resolved item).\n");

    if (kb != null) {
      sb.append("\n").append(knowledgeSummary(kb));
    }

    Map<String, Secret> secrets = new LinkedHashMap<>();
    reports.forEach(r -> r.secrets().forEach(secrets::putIfAbsent));
    if (!secrets.isEmpty()) {
      sb.append("\n## Secrets to create in Camunda\n\n")
          .append("Endpoints are referenced as `{{secrets.NAME}}`. Create these secrets per environment ")
          .append("(Console → Cluster → Connector secrets, or the connectors runtime environment).\n\n")
          .append("| Secret | Oracle reference | Value in composite | Environment values (config plans) |\n")
          .append("| --- | --- | --- | --- |\n");
      for (Secret s : secrets.values()) {
        sb.append("| ").append(s.name()).append(" | ").append(md(s.reference())).append(" | ")
            .append(md(s.value() == null ? "(not set)" : s.value())).append(" | ")
            .append(md(String.join("; ", s.environmentValues().entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue()).toList())))
            .append(" |\n");
      }
    }

    for (ConversionReport r : reports) {
      sb.append("\n## ").append(md(r.sourceName())).append("\n\n");
      if (!r.validationIssues().isEmpty()) {
        sb.append("**Validation issues**\n\n");
        r.validationIssues().forEach(i -> sb.append("- ").append(md(i)).append('\n'));
        sb.append('\n');
      }
      sb.append("| Level | Element | Type | Source | Note |\n| --- | --- | --- | --- | --- |\n");
      r.entries().stream()
          .sorted(Comparator.comparingInt(ReportWriter::levelRank))
          .forEach(e -> sb.append("| ").append(e.level())
              .append(" | ").append(md(label(e)))
              .append(" | ").append(md(e.elementType()))
              .append(" | ").append(e.source().label())
              .append(" | ").append(md(e.message()))
              .append(" |\n"));
    }
    return sb.toString();
  }

  /** Markdown section describing what the knowledge base contains. */
  public static String knowledgeSummary(KnowledgeBase kb) {
    StringBuilder sb = new StringBuilder("## Knowledge base\n\n");
    if (kb.pairs().isEmpty()) {
      sb.append("No finished migrations were found, so the built-in conversion was used.\n");
    } else {
      sb.append(kb.pairs().size()).append(" pair(s), ").append(kb.size()).append(" rule(s).\n\n")
          .append("| Pair | Oracle | Camunda 8 | Matched by |\n| --- | --- | --- | --- |\n");
      for (KnowledgeBase.Pair p : kb.pairs()) {
        sb.append("| ").append(md(p.name())).append(" | ").append(md(p.oracle())).append(" | ")
            .append(md(p.camunda())).append(" | ").append(md(p.matchedBy())).append(" |\n");
      }
      sb.append("\n| Rule kind | Rules | With conflicts |\n| --- | ---: | ---: |\n");
      for (KnowledgeBase.Kind k : KnowledgeBase.Kind.values()) {
        var rules = kb.rules(k);
        if (!rules.isEmpty()) {
          sb.append("| ").append(k.name()).append(" | ").append(rules.size()).append(" | ")
              .append(rules.values().stream().filter(KnowledgeBase.Rule::conflict).count()).append(" |\n");
        }
      }
    }
    if (!kb.notes().isEmpty()) {
      sb.append("\nNotes:\n\n");
      kb.notes().forEach(n -> sb.append("- ").append(md(n)).append('\n'));
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
    List<Object> rs = new ArrayList<>();
    for (ConversionReport r : reports) {
      Map<Level, Integer> c = r.elementCounts();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("source", r.sourceName());
      m.put("automationPercent", r.automationPercent());
      Map<String, Object> counts = new LinkedHashMap<>();
      counts.put("auto", c.get(Level.AUTO));
      counts.put("partial", c.get(Level.PARTIAL));
      counts.put("manual", c.get(Level.MANUAL));
      m.put("counts", counts);
      m.put("fromKnowledgeBase", r.elementsFrom(Source.KNOWLEDGE_BASE));
      m.put("fromComposite", r.elementsFrom(Source.COMPOSITE));
      m.put("validationIssues", r.validationIssues());
      List<Object> secrets = new ArrayList<>();
      for (Secret s : r.secrets().values()) {
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("name", s.name());
        sm.put("reference", s.reference());
        sm.put("value", s.value());
        sm.put("environmentValues", s.environmentValues());
        secrets.add(sm);
      }
      m.put("secrets", secrets);
      List<Object> es = new ArrayList<>();
      for (Entry e : r.entries()) {
        Map<String, Object> em = new LinkedHashMap<>();
        em.put("process", e.processId());
        em.put("id", e.elementId());
        em.put("name", e.elementName());
        em.put("type", e.elementType());
        em.put("level", e.level().name());
        em.put("source", e.source().name());
        em.put("message", e.message());
        es.add(em);
      }
      m.put("entries", es);
      rs.add(m);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("reports", rs);
    return Json.write(root);
  }

  private static String md(String s) {
    return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
  }

  static String js(String s) {
    StringBuilder sb = new StringBuilder();
    if (s == null) {
      return "null";
    }
    Json.quote(s, sb);
    return sb.toString();
  }
}
