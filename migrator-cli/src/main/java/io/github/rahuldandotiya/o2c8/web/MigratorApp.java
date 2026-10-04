package io.github.rahuldandotiya.o2c8.web;

import io.github.rahuldandotiya.o2c8.ConverterOptions;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBaseBuilder;
import io.github.rahuldandotiya.o2c8.layout.OracleDiagramRenderer;
import io.github.rahuldandotiya.o2c8.migration.Analysis;
import io.github.rahuldandotiya.o2c8.migration.Migration;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.report.ConversionReport;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Entry;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.report.ReportWriter;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** What the web UI can do; HTTP-free so it can be tested directly. */
public final class MigratorApp {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,79}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final AppConfig config;
  private final Path workspacesRoot;
  private final Path knowledgeBaseDir;
  private final Workspace.Limits limits;
  private final Duration ttl;
  private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
  private final WebModelerClient webModeler;
  private final WebModelerException webModelerConfigError;

  MigratorApp(AppConfig config) throws IOException {
    this.config = config;
    this.workspacesRoot = Files.createTempDirectory("oracle2c8-web-");
    this.knowledgeBaseDir = Path.of(config.get("migrator.knowledge-base-dir", "knowledge-base")).toAbsolutePath().normalize();
    this.limits = new Workspace.Limits((long) config.getInt("server.max-upload-mb", 200) << 20,
        config.getInt("server.max-files", 5000));
    this.ttl = Duration.ofMinutes(config.getInt("server.workspace-ttl-minutes", 240));
    WebModelerClient client = null;
    WebModelerException error = null;
    try {
      client = new WebModelerClient(WebModelerClient.Settings.from(config));
    } catch (WebModelerException e) {
      error = e;
    }
    this.webModeler = client;
    this.webModelerConfigError = error;
  }

  public Path knowledgeBaseDir() {
    return knowledgeBaseDir;
  }

  // ------------------------------------------------------------------ workspaces

  Workspace create() throws IOException {
    String id = HexFormat.of().formatHex(randomBytes());
    Workspace w = new Workspace(id, workspacesRoot.resolve(id), limits);
    workspaces.put(id, w);
    return w;
  }

  private static byte[] randomBytes() {
    byte[] b = new byte[12];
    RANDOM.nextBytes(b);
    return b;
  }

  Workspace workspace(String id) {
    Workspace w = workspaces.get(id);
    if (w == null) {
      throw new WebError(404, "This session has expired or does not exist. Reload the page to start a new one.");
    }
    w.touch();
    return w;
  }

  /** Deletes workspaces idle for longer than server.workspace-ttl-minutes. */
  void expireIdle() {
    Instant limit = Instant.now().minus(ttl);
    workspaces.values().removeIf(w -> {
      if (w.lastUsed().isBefore(limit)) {
        w.delete();
        return true;
      }
      return false;
    });
  }

  void shutdown() {
    workspaces.values().forEach(Workspace::delete);
    workspaces.clear();
    try {
      Workspace.deleteTree(workspacesRoot);
    } catch (IOException ignored) {
      // best effort
    }
  }

  // ------------------------------------------------------------------ status

  Map<String, Object> status() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("version", OracleToCamundaConverter.EXPORTER_VERSION);
    m.put("config", config.describeSource());
    m.put("knowledgeBaseDir", knowledgeBaseDir.toString());
    m.put("maxUploadMb", limits.maxBytes() >> 20);
    m.put("maxFiles", limits.maxFiles());
    m.put("defaultPlatformVersion", config.get("migrator.platform-version", "8.6.0"));
    m.put("defaultUserTasks", config.get("migrator.user-tasks", "camunda"));
    if (webModeler != null) {
      m.put("webModeler", webModeler.settings().describe());
    } else {
      m.put("webModeler", Map.of("configured", false, "error", webModelerConfigError.getMessage()));
    }
    return m;
  }

  // ------------------------------------------------------------------ convert / analyze

  /** Options chosen in the UI, falling back to application.properties. */
  record RunOptions(String userTasks, String platformVersion, boolean useKnowledgeBase) {
    static RunOptions from(Map<String, Object> body, AppConfig c) {
      Object kb = body.get("useKnowledgeBase");
      return new RunOptions(
          str(body.get("userTasks"), c.get("migrator.user-tasks", "camunda")),
          str(body.get("platformVersion"), c.get("migrator.platform-version", "8.6.0")),
          !(kb instanceof Boolean b) || b);
    }

    private static String str(Object o, String fallback) {
      return o instanceof String s && !s.isBlank() ? s.trim() : fallback;
    }
  }

  RunOptions options(Map<String, Object> body) {
    return RunOptions.from(body, config);
  }

  /** Converts everything uploaded to the "migrate" area; returns the result document for the UI. */
  Map<String, Object> convert(Workspace w, RunOptions o) throws IOException {
    synchronized (w) {
      runMigration(w, o);
      Migration.write(w.run, w.out());
      return result(w);
    }
  }

  /** Effort estimate for the uploaded processes (also keeps the conversion for previews). */
  Map<String, Object> analyze(Workspace w, RunOptions o) throws IOException {
    synchronized (w) {
      runMigration(w, o);
      Migration.write(w.run, w.out());
      w.analysis = Analysis.analyze(w.run, Analysis.Weights.from(config.withPrefix("analyze.hours.")));
      Files.writeString(w.out().resolve("migration-analysis.md"), Analysis.markdown(w.analysis));
      Files.writeString(w.out().resolve("migration-analysis.json"), Analysis.json(w.analysis));
      Map<String, Object> m = new LinkedHashMap<>(result(w));
      m.put("analysis", io.github.rahuldandotiya.o2c8.util.Json.parse(Analysis.json(w.analysis)));
      return m;
    }
  }

  private void runMigration(Workspace w, RunOptions o) throws IOException {
    if (w.list(Workspace.Area.MIGRATE).isEmpty()) {
      throw new WebError(400, "Upload the Oracle processes or projects to convert first.");
    }
    ConverterOptions opts = ConverterOptions.defaults()
        .withExecutionPlatformVersion(o.platformVersion())
        .withUserTaskImplementation(switch (o.userTasks()) {
          case "camunda" -> ConverterOptions.UserTaskImplementation.CAMUNDA_USER_TASK;
          case "job-worker" -> ConverterOptions.UserTaskImplementation.JOB_WORKER;
          default -> throw new WebError(400, "User task mode must be camunda or job-worker.");
        });
    OracleToCamundaConverter converter = new OracleToCamundaConverter(opts);
    KnowledgeBase kb = o.useKnowledgeBase() ? learn(w, converter) : KnowledgeBase.empty();
    Workspace.deleteTree(w.out());
    DropFolder drop = DropFolder.load(w.area(Workspace.Area.MIGRATE));
    try {
      w.run = Migration.run(drop, converter, kb);
      w.analysis = null;
      if (w.run.outcomes().isEmpty()) {
        throw new WebError(400, "No Oracle .bpmn files were found in the upload"
            + (w.run.skipped().isEmpty() ? "." : " (only Camunda 8 models, which are skipped)."));
      }
    } finally {
      // processes read from archives were extracted to temp folders; keep the Run's documents in memory
      drop.close();
    }
  }

  /** Knowledge from the saved knowledge-base folder plus anything uploaded to this session's kb area. */
  private KnowledgeBase learn(Workspace w, OracleToCamundaConverter converter) throws IOException {
    List<Path> sources = new ArrayList<>();
    if (Files.isDirectory(knowledgeBaseDir) && hasBpmn(knowledgeBaseDir)) {
      sources.add(knowledgeBaseDir);
    }
    if (!w.list(Workspace.Area.KB).isEmpty()) {
      sources.add(w.area(Workspace.Area.KB));
    }
    if (sources.isEmpty()) {
      return KnowledgeBase.empty();
    }
    DropFolder drop = DropFolder.load(sources);
    try {
      return new KnowledgeBaseBuilder(converter).build(drop);
    } finally {
      drop.close();
    }
  }

  /** The document the results page is built from. */
  Map<String, Object> result(Workspace w) {
    if (w.run == null) {
      throw new WebError(404, "Nothing converted yet in this session.");
    }
    Migration.Run run = w.run;
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("text", run.summary());
    summary.put("files", run.outcomes().size());
    summary.put("converted", run.count(ConversionReport.Status.CONVERTED));
    summary.put("convertedWithIssues", run.count(ConversionReport.Status.CONVERTED_WITH_ISSUES));
    summary.put("failed", run.count(ConversionReport.Status.FAILED));
    summary.put("composites", run.composites());
    KnowledgeBase kb = run.knowledgeBase();
    summary.put("knowledgeBasePairs", kb == null ? 0 : kb.pairs().size());
    summary.put("knowledgeBaseRules", kb == null ? 0 : kb.size());
    m.put("summary", summary);
    m.put("skipped", run.skipped());
    m.put("warnings", run.warnings());
    List<Object> files = new ArrayList<>();
    for (Migration.Outcome o : run.outcomes()) {
      ConversionReport r = o.report();
      Map<String, Object> f = new LinkedHashMap<>();
      f.put("path", o.sourcePath());
      f.put("processes", o.file().map(pf -> pf.processNames().stream().map(n -> n == null ? "" : n).toList())
          .orElse(List.of()));
      f.put("status", r.status().name());
      f.put("failure", r.failure());
      f.put("automationPercent", r.automationPercent());
      Map<Level, Integer> c = r.elementCounts();
      f.put("auto", c.get(Level.AUTO));
      f.put("partial", c.get(Level.PARTIAL));
      f.put("manual", c.get(Level.MANUAL));
      f.put("fromKnowledgeBase", r.elementsFrom(ConversionReport.Source.KNOWLEDGE_BASE));
      f.put("fromComposite", r.elementsFrom(ConversionReport.Source.COMPOSITE));
      f.put("validationIssues", r.validationIssues());
      f.put("secrets", new ArrayList<>(r.secrets().keySet()));
      f.put("review", reviewItems(o.sourcePath(), r, w.checklist));
      files.add(f);
    }
    m.put("files", files);
    return m;
  }

  /** PARTIAL/MANUAL notes grouped per element, with the checklist state. */
  private static List<Object> reviewItems(String path, ConversionReport r, Map<String, Object> checklist) {
    Map<String, List<Entry>> byElement = new LinkedHashMap<>();
    for (Entry e : r.entries()) {
      if (e.level() == Level.PARTIAL || e.level() == Level.MANUAL) {
        byElement.computeIfAbsent(e.elementId(), k -> new ArrayList<>()).add(e);
      }
    }
    List<Object> items = new ArrayList<>();
    byElement.forEach((id, es) -> {
      Map<String, Object> i = new LinkedHashMap<>();
      String key = path + "::" + id;
      i.put("key", key);
      i.put("elementId", id);
      i.put("name", es.get(0).elementName());
      i.put("type", es.get(0).elementType());
      i.put("level", es.stream().anyMatch(e -> e.level() == Level.MANUAL) ? "MANUAL" : "PARTIAL");
      i.put("source", es.get(0).source().label());
      i.put("notes", es.stream().map(Entry::message).toList());
      Object state = checklist.get(key);
      if (state instanceof Map<?, ?> s) {
        i.put("done", Boolean.TRUE.equals(s.get("done")));
        i.put("comment", s.get("comment") == null ? "" : String.valueOf(s.get("comment")));
      } else {
        i.put("done", false);
        i.put("comment", "");
      }
      items.add(i);
    });
    return items;
  }

  // ------------------------------------------------------------------ previews and downloads

  Migration.Outcome outcome(Workspace w, String path) {
    if (w.run == null) {
      throw new WebError(404, "Nothing converted yet in this session.");
    }
    return w.run.outcomes().stream().filter(o -> o.sourcePath().equals(path)).findFirst()
        .orElseThrow(() -> new WebError(404, "No converted file '" + path + "' in this session."));
  }

  String camundaXml(Workspace w, String path) {
    return outcome(w, path).result()
        .orElseThrow(() -> new WebError(404, "'" + path + "' could not be converted, so there is no Camunda model."))
        .bpmnXml();
  }

  String oracleSvg(Workspace w, String path) {
    Migration.Outcome o = outcome(w, path);
    if (o.result().isPresent()) {
      return OracleDiagramRenderer.render(o.result().get().source());
    }
    Path file = Workspace.safeResolve(w.area(Workspace.Area.MIGRATE), path);
    try {
      return OracleDiagramRenderer.render(XmlUtils.parse(file));
    } catch (IOException | RuntimeException e) {
      throw new WebError(404, "The Oracle diagram cannot be drawn: " + e.getMessage());
    }
  }

  String reportMarkdown(Workspace w) {
    if (w.run == null) {
      throw new WebError(404, "Nothing converted yet in this session.");
    }
    KnowledgeBase kb = w.run.knowledgeBase();
    return ReportWriter.markdown(w.run.reports(), kb == null || kb.pairs().isEmpty() ? null : kb);
  }

  String analysisMarkdown(Workspace w) {
    if (w.analysis == null) {
      throw new WebError(404, "Run Analyze first.");
    }
    return Analysis.markdown(w.analysis);
  }

  /** Review checklist as Markdown: one line per item, ticked or not, with the reviewer's comment. */
  String checklistMarkdown(Workspace w) {
    Map<String, Object> res = result(w);
    StringBuilder sb = new StringBuilder("# Review checklist\n\n");
    int done = 0;
    int total = 0;
    StringBuilder body = new StringBuilder();
    for (Object fo : (List<?>) res.get("files")) {
      Map<?, ?> f = (Map<?, ?>) fo;
      List<?> items = (List<?>) f.get("review");
      if (items.isEmpty()) {
        continue;
      }
      body.append("\n## ").append(f.get("path")).append("\n\n");
      for (Object io : items) {
        Map<?, ?> i = (Map<?, ?>) io;
        boolean d = Boolean.TRUE.equals(i.get("done"));
        total++;
        done += d ? 1 : 0;
        body.append("- [").append(d ? "x" : " ").append("] **").append(i.get("level")).append("** ")
            .append(i.get("name") == null ? i.get("elementId") : i.get("name")).append(" (").append(i.get("type"))
            .append("): ").append(String.join(" ", ((List<?>) i.get("notes")).stream().map(String::valueOf).toList()));
        String comment = String.valueOf(i.get("comment"));
        if (!comment.isBlank()) {
          body.append("\n  - Reviewer: ").append(comment.replace('\n', ' '));
        }
        body.append('\n');
      }
    }
    sb.append(done).append(" of ").append(total).append(" item(s) done.\n").append(body);
    return sb.toString();
  }

  void updateChecklist(Workspace w, Map<String, Object> body) throws IOException {
    synchronized (w) {
      Object items = body.get("items");
      if (!(items instanceof Map<?, ?> m)) {
        throw new WebError(400, "Expected {\"items\": {key: {done, comment}}}.");
      }
      m.forEach((k, v) -> {
        if (v instanceof Map<?, ?> s) {
          Map<String, Object> st = new LinkedHashMap<>();
          st.put("done", Boolean.TRUE.equals(s.get("done")));
          Object c = s.get("comment");
          st.put("comment", c == null ? "" : String.valueOf(c).substring(0, Math.min(2000, String.valueOf(c).length())));
          w.checklist.put(String.valueOf(k), st);
        }
      });
      w.saveChecklist();
    }
  }

  /** Zip with the converted models (input folder structure), reports, checklist and analysis. */
  void writeZip(Workspace w, OutputStream os) throws IOException {
    if (w.run == null) {
      throw new WebError(404, "Nothing converted yet in this session.");
    }
    try (ZipOutputStream zip = new ZipOutputStream(os)) {
      for (Migration.Outcome o : w.run.outcomes()) {
        if (o.result().isPresent()) {
          put(zip, "camunda8/" + o.outputPath(), o.result().get().bpmnXml());
        }
      }
      put(zip, "conversion-report.md", reportMarkdown(w));
      put(zip, "conversion-report.json", ReportWriter.json(w.run.reports()));
      put(zip, "review-checklist.md", checklistMarkdown(w));
      if (w.analysis != null) {
        put(zip, "migration-analysis.md", Analysis.markdown(w.analysis));
        put(zip, "migration-analysis.json", Analysis.json(w.analysis));
      }
    }
  }

  private static void put(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  // ------------------------------------------------------------------ knowledge base

  /** Pairs and rules in the saved knowledge-base folder. */
  Map<String, Object> knowledgeBase() throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("dir", knowledgeBaseDir.toString());
    List<Object> entries = new ArrayList<>();
    if (Files.isDirectory(knowledgeBaseDir)) {
      try (Stream<Path> s = Files.list(knowledgeBaseDir)) {
        for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
          Map<String, Object> e = new LinkedHashMap<>();
          e.put("name", p.getFileName().toString());
          try (Stream<Path> f = Files.walk(p)) {
            e.put("files", f.filter(Files::isRegularFile).count());
          }
          entries.add(e);
        }
      }
    }
    m.put("entries", entries);
    KnowledgeBase kb = KnowledgeBase.empty();
    if (Files.isDirectory(knowledgeBaseDir) && hasBpmn(knowledgeBaseDir)) {
      DropFolder drop = DropFolder.load(knowledgeBaseDir);
      try {
        kb = new KnowledgeBaseBuilder(new OracleToCamundaConverter()).build(drop);
      } finally {
        drop.close();
      }
    }
    List<Object> pairs = new ArrayList<>();
    for (KnowledgeBase.Pair p : kb.pairs()) {
      pairs.add(Map.of("name", p.name(), "oracle", p.oracle(), "camunda", p.camunda(), "matchedBy", p.matchedBy()));
    }
    m.put("pairs", pairs);
    Map<String, Object> rules = new LinkedHashMap<>();
    for (KnowledgeBase.Kind k : KnowledgeBase.Kind.values()) {
      if (!kb.rules(k).isEmpty()) {
        rules.put(k.name(), kb.rules(k).size());
      }
    }
    m.put("rules", rules);
    m.put("notes", kb.notes());
    return m;
  }

  /** Copies this session's uploaded finished migrations into knowledge-base/&lt;name&gt;. */
  Map<String, Object> saveKnowledge(Workspace w, String name, boolean overwrite) throws IOException {
    if (name == null || !SAFE_NAME.matcher(name.trim()).matches()) {
      throw new WebError(400, "Give the knowledge-base entry a name: letters, digits, '.', '_', '-' or spaces "
          + "(max 80 characters), starting with a letter or digit.");
    }
    String n = name.trim();
    Path kbArea = w.area(Workspace.Area.KB);
    if (w.list(Workspace.Area.KB).isEmpty()) {
      throw new WebError(400, "Upload finished migrations (Oracle files plus their Camunda 8 models) first.");
    }
    DropFolder drop = DropFolder.load(kbArea);
    int pairs;
    try {
      pairs = KnowledgeBaseBuilder.pairs(drop).size();
    } finally {
      drop.close();
    }
    if (pairs == 0) {
      throw new WebError(400, "No Oracle process could be paired with a Camunda 8 model in the upload. Pairs are "
          + "matched by process id, file name, element ids or process name.");
    }
    Path target = knowledgeBaseDir.resolve(n).normalize();
    if (!target.startsWith(knowledgeBaseDir)) {
      throw new WebError(400, "Invalid name.");
    }
    if (Files.exists(target)) {
      if (!overwrite) {
        throw new WebError(409, "A knowledge-base entry called '" + n + "' already exists. Choose another name or replace it.");
      }
      Workspace.deleteTree(target);
    }
    Files.createDirectories(target);
    try (Stream<Path> s = Files.walk(kbArea)) {
      for (Path p : s.filter(Files::isRegularFile).toList()) {
        Path dest = target.resolve(kbArea.relativize(p).toString()).normalize();
        if (!dest.startsWith(target)) {
          continue;
        }
        Files.createDirectories(dest.getParent());
        Files.copy(p, dest);
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("saved", n);
    m.put("pairs", pairs);
    m.put("path", target.toString());
    return m;
  }

  void deleteKnowledge(String name) throws IOException {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new WebError(400, "Invalid name.");
    }
    Path target = knowledgeBaseDir.resolve(name).normalize();
    if (!target.startsWith(knowledgeBaseDir) || !Files.isDirectory(target)) {
      throw new WebError(404, "No knowledge-base entry called '" + name + "'.");
    }
    Workspace.deleteTree(target);
  }

  private static boolean hasBpmn(Path dir) throws IOException {
    try (Stream<Path> s = Files.walk(dir, 30)) {
      return s.anyMatch(p -> {
        String f = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return f.endsWith(".bpmn") || f.endsWith(".zip") || f.endsWith(".sar");
      });
    }
  }

  // ------------------------------------------------------------------ Web Modeler

  private WebModelerClient webModeler() {
    if (webModeler == null) {
      throw webModelerConfigError;
    }
    return webModeler;
  }

  Map<String, Object> webModelerCheck() {
    WebModelerClient c = webModeler();
    Map<String, Object> info = c.check();
    Map<String, Object> m = new LinkedHashMap<>(c.settings().describe());
    m.put("connected", true);
    m.put("organization", info.get("authorizedOrganization"));
    m.put("apiVersion", info.get("version"));
    return m;
  }

  Map<String, Object> pushToWebModeler(Workspace w, String folder) {
    WebModelerClient c = webModeler();
    if (w.run == null) {
      throw new WebError(400, "Convert the processes first.");
    }
    String folderName = folder == null || folder.isBlank()
        ? "Migration " + java.time.LocalDate.now() : folder.trim();
    if (folderName.length() > 100) {
      throw new WebError(400, "Folder name is too long (max 100 characters).");
    }
    List<WebModelerClient.ModelFile> files = new ArrayList<>();
    Map<String, Integer> seen = new LinkedHashMap<>();
    for (Migration.Outcome o : w.run.outcomes()) {
      Optional<OracleToCamundaConverter.ConversionResult> r = o.result();
      if (r.isEmpty()) {
        continue;
      }
      String base = Path.of(o.outputPath()).getFileName().toString().replaceAll("\\.bpmn2?$", "");
      int n = seen.merge(base, 1, Integer::sum);
      files.add(new WebModelerClient.ModelFile(n == 1 ? base : base + " (" + n + ")", r.get().bpmnXml()));
    }
    WebModelerClient.PushResult p = c.push(folderName, files);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("project", p.projectName());
    m.put("projectId", p.projectId());
    m.put("folder", p.folderName());
    m.put("created", p.created());
    m.put("updated", p.updated());
    m.put("skippedFailed", w.run.count(ConversionReport.Status.FAILED));
    m.put("url", p.webUrl());
    return m;
  }
}
