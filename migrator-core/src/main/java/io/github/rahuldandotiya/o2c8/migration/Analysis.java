package io.github.rahuldandotiya.o2c8.migration;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants;

import io.github.rahuldandotiya.o2c8.report.ConversionReport;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Entry;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.util.Json;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * Effort estimate for a migration, before anyone opens a model: what the converter did, what is
 * left for people (grouped into kinds of work), and a rough number of hours per process. The hour
 * weights are deliberately simple and configurable ({@code analyze.hours.*} in application.properties).
 */
public final class Analysis {

  /** Kinds of follow-up work, with a default effort in hours per item. */
  public enum Category {
    FORM("Camunda forms to build for user tasks", 4),
    JOB_WORKER("Job workers to implement (service, script and send tasks, message throws)", 6),
    INBOUND_TRIGGER("Inbound triggers to set up (JMS, email, file or DB adapters)", 6),
    CORRELATION("Message correlation keys to define", 1),
    UNDEFINED_TASK("Undefined (abstract) tasks to decide", 2),
    EXPRESSION("Expressions, mappings or timers to rewrite by hand", 1),
    UNSUPPORTED("Unsupported constructs to remodel", 4),
    SECRET("Secrets to create (endpoints)", 0.5),
    REVIEW("Other converted items to review", 0.5),
    FAILED_FILE("Files that could not be converted (manual migration)", 16);

    private final String label;
    private final double defaultHours;

    Category(String label, double defaultHours) {
      this.label = label;
      this.defaultHours = defaultHours;
    }

    public String label() {
      return label;
    }

    public double defaultHours() {
      return defaultHours;
    }

    /** Property key suffix, e.g. {@code job-worker}. */
    public String key() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  /** Hours per work item, plus a fixed amount per process for testing and deployment. */
  public record Weights(Map<Category, Double> hours, double perProcess) {
    public static Weights defaults() {
      Map<Category, Double> m = new EnumMap<>(Category.class);
      for (Category c : Category.values()) {
        m.put(c, c.defaultHours());
      }
      return new Weights(m, 4);
    }

    /** Reads {@code analyze.hours.<category>} and {@code analyze.hours.per-process}; missing keys keep defaults. */
    public static Weights from(Map<String, String> properties) {
      Weights d = defaults();
      Map<Category, Double> m = new EnumMap<>(d.hours());
      for (Category c : Category.values()) {
        String v = properties.get("analyze.hours." + c.key());
        if (v != null && !v.isBlank()) {
          m.put(c, Double.parseDouble(v.trim()));
        }
      }
      String pp = properties.get("analyze.hours.per-process");
      return new Weights(m, pp == null || pp.isBlank() ? d.perProcess() : Double.parseDouble(pp.trim()));
    }

    double of(Category c) {
      return hours.getOrDefault(c, c.defaultHours());
    }
  }

  /** One piece of follow-up work. */
  public record WorkItem(Category category, String elementId, String elementName, String note) {}

  /** Size of the Oracle process, to judge complexity. */
  public record Size(int activities, int gateways, int events, int subProcesses, int lanes) {
    public int total() {
      return activities + gateways + events + subProcesses;
    }

    public String complexity() {
      int t = total();
      return t <= 10 ? "low" : t <= 30 ? "medium" : "high";
    }
  }

  /** Estimate for one file. */
  public record ProcessEstimate(String source, ConversionReport.Status status, String failure, Size size,
      int automationPercent, List<WorkItem> work, double hours) {

    public Map<Category, Integer> counts() {
      Map<Category, Integer> m = new EnumMap<>(Category.class);
      work.forEach(w -> m.merge(w.category(), 1, Integer::sum));
      return m;
    }
  }

  /** Estimate for the whole run. */
  public record Result(List<ProcessEstimate> processes, Weights weights) {
    public double totalHours() {
      return processes.stream().mapToDouble(ProcessEstimate::hours).sum();
    }

    public Map<Category, Integer> totals() {
      Map<Category, Integer> m = new EnumMap<>(Category.class);
      processes.forEach(p -> p.counts().forEach((c, n) -> m.merge(c, n, Integer::sum)));
      return m;
    }

    public int averageAutomation() {
      return (int) Math.round(processes.stream().filter(p -> p.status() != ConversionReport.Status.FAILED)
          .mapToInt(ProcessEstimate::automationPercent).average().orElse(0));
    }
  }

  private static final Set<String> JOB_ELEMENTS = Set.of("serviceTask", "sendTask", "scriptTask",
      "businessRuleTask", "intermediateThrowEvent", "endEvent");

  private Analysis() {}

  public static Result analyze(Migration.Run run, Weights weights) {
    List<ProcessEstimate> list = new ArrayList<>();
    for (Migration.Outcome o : run.outcomes()) {
      ConversionReport r = o.report();
      if (r.status() == ConversionReport.Status.FAILED) {
        List<WorkItem> work = List.of(new WorkItem(Category.FAILED_FILE, null, o.sourcePath(), r.failure()));
        list.add(new ProcessEstimate(o.sourcePath(), r.status(), r.failure(), new Size(0, 0, 0, 0, 0), 0, work,
            weights.of(Category.FAILED_FILE)));
        continue;
      }
      List<WorkItem> work = workItems(r);
      Size size = o.result().map(res -> size(res.source().getDocumentElement())).orElse(new Size(0, 0, 0, 0, 0));
      double hours = weights.perProcess() + work.stream().mapToDouble(w -> weights.of(w.category())).sum();
      list.add(new ProcessEstimate(o.sourcePath(), r.status(), null, size, r.automationPercent(), work, hours));
    }
    return new Result(List.copyOf(list), weights);
  }

  /** One work item per element that still needs a person (its most severe note decides the kind). */
  static List<WorkItem> workItems(ConversionReport r) {
    Map<String, Entry> worst = new LinkedHashMap<>();
    for (Entry e : r.entries()) {
      if (e.level() != Level.PARTIAL && e.level() != Level.MANUAL) {
        continue;
      }
      worst.merge(e.elementId(), e, (a, b) -> b.level().ordinal() > a.level().ordinal() ? b : a);
    }
    List<WorkItem> items = new ArrayList<>();
    for (Entry e : worst.values()) {
      items.add(new WorkItem(categorize(e), e.elementId(), e.elementName(), e.message()));
    }
    r.secrets().keySet().forEach(s -> items.add(new WorkItem(Category.SECRET, null, s, "Create secret " + s)));
    return items;
  }

  static Category categorize(Entry e) {
    String type = e.elementType() == null ? "" : e.elementType();
    String msg = e.message() == null ? "" : e.message().toLowerCase(Locale.ROOT);
    if (type.equals("userTask")) {
      return Category.FORM;
    }
    if (type.equals("startEvent") && e.source() == ConversionReport.Source.COMPOSITE) {
      return Category.INBOUND_TRIGGER;
    }
    if (msg.contains("correlation key")) {
      return Category.CORRELATION;
    }
    if (type.equals("task") && msg.contains("abstract")) {
      return Category.UNDEFINED_TASK;
    }
    if (e.level() == Level.MANUAL) {
      return msg.contains("expression") || msg.contains("assignment") || msg.contains("mapping")
          || msg.contains("timer") || msg.contains("condition") ? Category.EXPRESSION : Category.UNSUPPORTED;
    }
    if (JOB_ELEMENTS.contains(type) && (msg.contains("worker") || msg.contains("job"))) {
      return Category.JOB_WORKER;
    }
    return Category.REVIEW;
  }

  static Size size(Element defs) {
    int activities = 0;
    int gateways = 0;
    int events = 0;
    int subs = 0;
    for (Element e : descendants(defs, Ns.BPMN, null)) {
      String k = e.getLocalName();
      if (k.equals("subProcess") || k.equals("transaction") || k.equals("adHocSubProcess")) {
        subs++;
      } else if (k.endsWith("Task") || k.equals("task") || k.equals("callActivity")) {
        activities++;
      } else if (k.endsWith("Gateway")) {
        gateways++;
      } else if (k.endsWith("Event")) {
        events++;
      }
    }
    return new Size(activities, gateways, events, subs, descendants(defs, Ns.BPMN, "lane").size());
  }

  // ------------------------------------------------------------------ output

  public static String markdown(Result a) {
    StringBuilder sb = new StringBuilder("# Migration analysis\n\n");
    long failed = a.processes().stream().filter(p -> p.status() == ConversionReport.Status.FAILED).count();
    sb.append(String.format(Locale.ROOT, "**%d process file(s) · average automation %d%% · rough effort %.1f hours"
        + "%s**%n%n", a.processes().size(), a.averageAutomation(), a.totalHours(),
        failed == 0 ? "" : " · " + failed + " file(s) could not be converted"));
    sb.append("The estimate counts the follow-up work the converter left, using the hour weights below ")
        .append("(set them with `analyze.hours.*` in application.properties). It is a starting point for planning, ")
        .append("not a quote.\n\n");
    sb.append("## Work by kind\n\n| Kind of work | Items | Hours each | Hours |\n| --- | ---: | ---: | ---: |\n");
    Map<Category, Integer> totals = a.totals();
    for (Category c : Category.values()) {
      int n = totals.getOrDefault(c, 0);
      if (n > 0) {
        sb.append(String.format(Locale.ROOT, "| %s | %d | %s | %s |%n", c.label(), n, num(a.weights().of(c)),
            num(n * a.weights().of(c))));
      }
    }
    long converted = a.processes().stream().filter(p -> p.status() != ConversionReport.Status.FAILED).count();
    sb.append(String.format(Locale.ROOT, "| Testing and deployment, per converted process | %d | %s | %s |%n",
        converted, num(a.weights().perProcess()), num(converted * a.weights().perProcess())));
    sb.append(String.format(Locale.ROOT, "| **Total** | | | **%s** |%n", num(a.totalHours())));

    sb.append("\n## By process\n\n| Process | Status | Size (complexity) | Automated | Work items | Hours |\n")
        .append("| --- | --- | --- | ---: | ---: | ---: |\n");
    for (ProcessEstimate p : a.processes()) {
      String size = p.status() == ConversionReport.Status.FAILED ? "-"
          : p.size().total() + " elements, " + p.size().lanes() + " lane(s) (" + p.size().complexity() + ")";
      sb.append("| ").append(md(p.source())).append(" | ").append(statusLabel(p.status())).append(" | ")
          .append(size).append(" | ").append(p.status() == ConversionReport.Status.FAILED ? "-" : p.automationPercent() + "%")
          .append(" | ").append(p.work().size()).append(" | ").append(num(p.hours())).append(" |\n");
    }
    for (ProcessEstimate p : a.processes()) {
      if (p.work().isEmpty()) {
        continue;
      }
      sb.append("\n### ").append(md(p.source())).append("\n\n| Kind | Element | Note |\n| --- | --- | --- |\n");
      for (WorkItem w : p.work()) {
        sb.append("| ").append(w.category().label()).append(" | ").append(md(w.elementName() == null ? "" : w.elementName()))
            .append(" | ").append(md(w.note())).append(" |\n");
      }
    }
    return sb.toString();
  }

  public static String json(Result a) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("files", a.processes().size());
    root.put("averageAutomationPercent", a.averageAutomation());
    root.put("totalHours", a.totalHours());
    Map<String, Object> weights = new LinkedHashMap<>();
    a.weights().hours().forEach((c, h) -> weights.put(c.key(), h));
    weights.put("per-process", a.weights().perProcess());
    root.put("weights", weights);
    Map<String, Object> totals = new LinkedHashMap<>();
    a.totals().forEach((c, n) -> totals.put(c.key(), n));
    root.put("workByKind", totals);
    List<Object> ps = new ArrayList<>();
    for (ProcessEstimate p : a.processes()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("source", p.source());
      m.put("status", p.status().name());
      if (p.failure() != null) {
        m.put("failure", p.failure());
      }
      m.put("elements", p.size().total());
      m.put("lanes", p.size().lanes());
      m.put("complexity", p.status() == ConversionReport.Status.FAILED ? null : p.size().complexity());
      m.put("automationPercent", p.automationPercent());
      m.put("hours", p.hours());
      List<Object> ws = new ArrayList<>();
      for (WorkItem w : p.work()) {
        Map<String, Object> wm = new LinkedHashMap<>();
        wm.put("kind", w.category().key());
        wm.put("elementId", w.elementId());
        wm.put("elementName", w.elementName());
        wm.put("note", w.note());
        ws.add(wm);
      }
      m.put("work", ws);
      ps.add(m);
    }
    root.put("processes", ps);
    return Json.write(root);
  }

  private static String statusLabel(ConversionReport.Status s) {
    return switch (s) {
      case CONVERTED -> "Converted";
      case CONVERTED_WITH_ISSUES -> "Converted, with issues";
      case FAILED -> "FAILED";
    };
  }

  private static String num(double d) {
    return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
  }

  private static String md(String s) {
    return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
  }
}
