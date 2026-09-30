package io.github.rahuldandotiya.o2c8.cli;

import io.github.rahuldandotiya.o2c8.ConverterOptions;
import io.github.rahuldandotiya.o2c8.ConverterOptions.InterfaceEvents;
import io.github.rahuldandotiya.o2c8.ConverterOptions.UserTaskImplementation;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.knowledge.Evaluator;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBaseBuilder;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.project.ProcessFile;
import io.github.rahuldandotiya.o2c8.report.ConversionReport;
import io.github.rahuldandotiya.o2c8.report.ReportWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line.
 *
 * <pre>
 * oracle2c8 migrate                      knowledge-base/ + processToMigrate/ → camunda8-output/
 * oracle2c8 convert &lt;files|folders&gt;      convert anything, optionally with --kb
 * oracle2c8 learn &lt;folder&gt;              build knowledge-base.json from finished migrations
 * oracle2c8 evaluate &lt;folder&gt;           leave-one-out: how many edits the knowledge base saves
 * </pre>
 *
 * <p>Deliberately dependency-free (no picocli) so the shaded jar has zero third-party code.
 */
public final class Oracle2C8 {

  static final String DEFAULT_KB = "knowledge-base";
  static final String DEFAULT_IN = "processToMigrate";
  static final String DEFAULT_OUT = "camunda8-output";

  static final String USAGE = """
      Oracle BPM to Camunda 8 Migrator %s

      Usage:
        oracle2c8 migrate [options]
            Learns from the finished migrations in ./knowledge-base (if present) and converts
            everything in ./processToMigrate into ./camunda8-output.
        oracle2c8 convert <file-or-folder>... [options]
            Converts Oracle .bpmn files, Oracle projects (with composite.xml) or zip/SAR archives.
        oracle2c8 learn <folder> [-o knowledge-base.json]
            Builds a knowledge base from Oracle processes + the Camunda 8 models finished for them.
        oracle2c8 evaluate <folder>
            Leave-one-out test of a knowledge-base folder: edits needed with and without it.

      Options:
            --kb <folder|file.json>    Knowledge base (migrate default: ./knowledge-base)
            --in <folder>              Processes to convert (migrate default: ./processToMigrate)
        -o, --output <dir>             Output folder (default: ./camunda8-output)
            --no-kb                    Ignore the knowledge base
            --user-tasks <mode>        camunda (default) | job-worker
            --interface-events <mode>  none (default) | message
            --platform-version <ver>   Camunda 8 version written into the models (default 8.6.0)
            --fail-on-issues           Exit with code 2 if any model has validation issues
        -h, --help                     Show this help
        -V, --version                  Show version
      """;

  private Oracle2C8() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Parsed command line. */
  static final class Args {
    String command;
    List<Path> inputs = new ArrayList<>();
    Path output;
    Path kb;
    Path in;
    boolean noKb;
    boolean failOnIssues;
    ConverterOptions options = ConverterOptions.defaults();
  }

  static int run(String[] argv, PrintStream out, PrintStream err) {
    if (argv.length == 0 || argv[0].equals("-h") || argv[0].equals("--help")) {
      out.printf(USAGE, OracleToCamundaConverter.EXPORTER_VERSION);
      return argv.length == 0 ? 1 : 0;
    }
    if (argv[0].equals("-V") || argv[0].equals("--version")) {
      out.println(OracleToCamundaConverter.EXPORTER_VERSION);
      return 0;
    }
    Args a;
    try {
      a = parse(argv);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return 1;
    }
    try {
      return switch (a.command) {
        case "migrate" -> migrate(a, out, err);
        case "convert" -> convert(a, out, err);
        case "learn" -> learn(a, out);
        case "evaluate" -> evaluate(a, out);
        default -> {
          err.println("Unknown command '" + a.command + "'. Try --help.");
          yield 1;
        }
      };
    } catch (IOException | IllegalArgumentException e) {
      err.println("Error: " + e.getMessage());
      return 1;
    }
  }

  static Args parse(String[] argv) {
    Args a = new Args();
    a.command = argv[0];
    for (int i = 1; i < argv.length; i++) {
      String s = argv[i];
      switch (s) {
        case "-o", "--output" -> a.output = Path.of(value(argv, ++i, s));
        case "--kb" -> a.kb = Path.of(value(argv, ++i, s));
        case "--in" -> a.in = Path.of(value(argv, ++i, s));
        case "--no-kb" -> a.noKb = true;
        case "--user-tasks" -> a.options = a.options.withUserTaskImplementation(switch (value(argv, ++i, s)) {
          case "camunda" -> UserTaskImplementation.CAMUNDA_USER_TASK;
          case "job-worker" -> UserTaskImplementation.JOB_WORKER;
          default -> throw new IllegalArgumentException("--user-tasks must be camunda or job-worker");
        });
        case "--interface-events" -> a.options = a.options.withInterfaceEvents(switch (value(argv, ++i, s)) {
          case "none" -> InterfaceEvents.NONE;
          case "message" -> InterfaceEvents.MESSAGE;
          default -> throw new IllegalArgumentException("--interface-events must be none or message");
        });
        case "--platform-version" -> a.options = a.options.withExecutionPlatformVersion(value(argv, ++i, s));
        case "--fail-on-issues" -> a.failOnIssues = true;
        default -> {
          if (s.startsWith("-")) {
            throw new IllegalArgumentException("Unknown option " + s);
          }
          a.inputs.add(Path.of(s));
        }
      }
    }
    return a;
  }

  // ------------------------------------------------------------------ commands

  private static int migrate(Args a, PrintStream out, PrintStream err) throws IOException {
    Path kbPath = a.noKb ? null : a.kb != null ? a.kb : Path.of(DEFAULT_KB);
    Path in = a.in != null ? a.in : a.inputs.isEmpty() ? Path.of(DEFAULT_IN) : a.inputs.get(0);
    Path output = a.output != null ? a.output : Path.of(DEFAULT_OUT);
    if (!Files.exists(in)) {
      err.println("Nothing to migrate: folder '" + in + "' not found. Put Oracle .bpmn files, Oracle projects "
          + "or zip/SAR archives in it.");
      return 1;
    }
    OracleToCamundaConverter converter = new OracleToCamundaConverter(a.options);
    KnowledgeBase kb = loadKnowledge(kbPath, converter, out, a.kb != null);
    Files.createDirectories(output);
    if (!kb.pairs().isEmpty() || !kb.notes().isEmpty()) {
      Files.writeString(output.resolve("knowledge-base.json"), kb.toJson());
    }
    return convertFolder(List.of(in), output, converter, kb, a.failOnIssues, out, err);
  }

  private static int convert(Args a, PrintStream out, PrintStream err) throws IOException {
    if (a.inputs.isEmpty()) {
      err.println("No input files given");
      return 1;
    }
    OracleToCamundaConverter converter = new OracleToCamundaConverter(a.options);
    KnowledgeBase kb = a.kb == null || a.noKb ? KnowledgeBase.empty() : loadKnowledge(a.kb, converter, out, true);
    Path output = a.output != null ? a.output : Path.of(DEFAULT_OUT);
    Files.createDirectories(output);
    return convertFolder(a.inputs, output, converter, kb, a.failOnIssues, out, err);
  }

  private static int learn(Args a, PrintStream out) throws IOException {
    Path folder = a.inputs.isEmpty() ? Path.of(DEFAULT_KB) : a.inputs.get(0);
    Path target = a.output != null ? a.output : Path.of("knowledge-base.json");
    OracleToCamundaConverter converter = new OracleToCamundaConverter(a.options);
    KnowledgeBase kb = loadKnowledge(folder, converter, out, true);
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }
    Files.writeString(target, kb.toJson());
    out.println("Wrote " + target.toAbsolutePath());
    return kb.pairs().isEmpty() ? 1 : 0;
  }

  private static int evaluate(Args a, PrintStream out) throws IOException {
    Path folder = a.inputs.isEmpty() ? Path.of(DEFAULT_KB) : a.inputs.get(0);
    DropFolder drop = DropFolder.load(folder);
    try {
      var results = Evaluator.leaveOneOut(drop, new OracleToCamundaConverter(a.options));
      if (results.size() < 2) {
        out.println("Need at least 2 pairs in " + folder + " to evaluate (found " + results.size() + ").");
        return 1;
      }
      out.println("Leave-one-out: each pair converted with knowledge learned from the other pairs.");
      out.printf("  %-40s %14s %14s%n", "Pair", "edits without", "edits with KB");
      int without = 0;
      int with = 0;
      for (var r : results) {
        out.printf("  %-40s %14d %14d%n", r.pair(), r.editsWithout(), r.editsWith());
        without += r.editsWithout();
        with += r.editsWith();
      }
      int pct = without == 0 ? 0 : Math.round(100f * (without - with) / without);
      out.printf("  %-40s %14d %14d   (%d%% fewer edits)%n", "Total", without, with, pct);
      return 0;
    } finally {
      drop.close();
    }
  }

  // ------------------------------------------------------------------ shared

  /** Knowledge from a folder of pairs or a knowledge-base.json; empty when the path does not exist. */
  static KnowledgeBase loadKnowledge(Path path, OracleToCamundaConverter converter, PrintStream out, boolean explicit)
      throws IOException {
    if (path == null) {
      out.println("Knowledge base disabled: built-in conversion only.");
      return KnowledgeBase.empty();
    }
    if (!Files.exists(path)) {
      if (explicit) {
        throw new IOException("Knowledge base not found: " + path);
      }
      out.println("No knowledge base (folder '" + path + "' not found): built-in conversion only.");
      return KnowledgeBase.empty();
    }
    if (Files.isRegularFile(path) && path.toString().endsWith(".json")) {
      KnowledgeBase kb = KnowledgeBase.fromJson(Files.readString(path));
      out.println("Knowledge base " + path + ": " + kb.pairs().size() + " pair(s), " + kb.size() + " rule(s).");
      return kb;
    }
    DropFolder drop = DropFolder.load(path);
    try {
      KnowledgeBase kb = new KnowledgeBaseBuilder(converter).build(drop);
      out.println("Knowledge base " + path + ": " + kb.pairs().size() + " pair(s), " + kb.size() + " rule(s).");
      for (KnowledgeBase.Pair p : kb.pairs()) {
        out.println("  learned from " + p.name() + " (" + p.oracle() + " + " + p.camunda() + ", matched by "
            + p.matchedBy() + ")");
      }
      kb.notes().forEach(n -> out.println("  note: " + n));
      return kb;
    } finally {
      drop.close();
    }
  }

  private static int convertFolder(List<Path> inputs, Path output, OracleToCamundaConverter converter,
      KnowledgeBase kb, boolean failOnIssues, PrintStream out, PrintStream err) throws IOException {
    DropFolder drop = DropFolder.load(inputs);
    try {
      List<ProcessFile> files = drop.processes().stream().filter(ProcessFile::convertible).toList();
      if (files.isEmpty()) {
        err.println("No Oracle .bpmn files found in " + inputs);
        return 1;
      }
      drop.processes(ProcessFile.Kind.CAMUNDA8).forEach(f ->
          out.println("  skipped " + f.displayPath() + " (already a Camunda 8 model)"));
      drop.warnings().forEach(w -> out.println("  warning: " + w));
      if (!drop.composites().isEmpty()) {
        out.println("  " + drop.composites().size() + " composite.xml file(s) found: service calls are configured from them.");
      }
      List<ConversionReport> reports = new ArrayList<>();
      boolean issues = false;
      for (ProcessFile f : files) {
        try {
          ConversionResult r = converter.convert(f, drop.compositeFor(f), kb);
          Path target = output.resolve(f.displayPath()).normalize();
          if (!target.startsWith(output.normalize())) {
            target = output.resolve(f.path().getFileName().toString());
          }
          Files.createDirectories(target.getParent());
          Files.writeString(target, r.bpmnXml());
          reports.add(r.report());
          int n = r.report().validationIssues().size();
          issues |= n > 0;
          long fromKb = r.report().elementsFrom(ConversionReport.Source.KNOWLEDGE_BASE);
          out.printf("  %-50s %3d%% automated%s%s%n", f.displayPath(), r.report().automationPercent(),
              fromKb == 0 ? "" : ", " + fromKb + " from knowledge base",
              n == 0 ? "" : ", " + n + " validation issue(s)");
        } catch (IOException | IllegalArgumentException e) {
          err.println("  " + f.displayPath() + ": FAILED - " + e.getMessage());
          issues = true;
        }
      }
      Files.writeString(output.resolve("conversion-report.md"), ReportWriter.markdown(reports, kb.pairs().isEmpty() ? null : kb));
      Files.writeString(output.resolve("conversion-report.json"), ReportWriter.json(reports));
      out.println("Wrote " + reports.size() + " model(s) and conversion-report.md to " + output.toAbsolutePath());
      return failOnIssues && issues ? 2 : 0;
    } finally {
      drop.close();
    }
  }

  private static String value(String[] args, int i, String option) {
    if (i >= args.length) {
      throw new IllegalArgumentException(option + " needs a value");
    }
    return args[i];
  }
}
