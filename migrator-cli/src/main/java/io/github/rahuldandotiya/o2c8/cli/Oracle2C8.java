package io.github.rahuldandotiya.o2c8.cli;

import io.github.rahuldandotiya.o2c8.ConverterOptions;
import io.github.rahuldandotiya.o2c8.ConverterOptions.InterfaceEvents;
import io.github.rahuldandotiya.o2c8.ConverterOptions.UserTaskImplementation;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.report.ConversionReport;
import io.github.rahuldandotiya.o2c8.report.ReportWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Command line: {@code oracle2c8 convert <file-or-folder>... [-o out] [options]}.
 *
 * <p>Deliberately dependency-free (no picocli) so the shaded jar has zero third-party code.
 */
public final class Oracle2C8 {

  static final String USAGE = """
      Oracle BPM to Camunda 8 Migrator %s

      Usage:
        oracle2c8 convert <file-or-folder>... [options]

      Converts Oracle BPM .bpmn files (folders are searched recursively) into Camunda 8 BPMN
      and writes conversion-report.md / conversion-report.json next to them.

      Options:
        -o, --output <dir>             Output folder (default: ./camunda8-output)
            --user-tasks <mode>        camunda (default, Camunda user tasks) | job-worker
            --interface-events <mode>  none (default: none start event) | message
            --platform-version <ver>   Camunda 8 version written into the models (default 8.6.0)
            --fail-on-issues           Exit with code 2 if any model has validation issues
        -h, --help                     Show this help
        -V, --version                  Show version
      """;

  private Oracle2C8() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
      out.printf(USAGE, OracleToCamundaConverter.EXPORTER_VERSION);
      return args.length == 0 ? 1 : 0;
    }
    if (args[0].equals("-V") || args[0].equals("--version")) {
      out.println(OracleToCamundaConverter.EXPORTER_VERSION);
      return 0;
    }
    if (!args[0].equals("convert")) {
      err.println("Unknown command '" + args[0] + "'. Try --help.");
      return 1;
    }

    List<Path> inputs = new ArrayList<>();
    Path output = Path.of("camunda8-output");
    ConverterOptions options = ConverterOptions.defaults();
    boolean failOnIssues = false;
    try {
      for (int i = 1; i < args.length; i++) {
        String a = args[i];
        switch (a) {
          case "-o", "--output" -> output = Path.of(value(args, ++i, a));
          case "--user-tasks" -> options = options.withUserTaskImplementation(
              switch (value(args, ++i, a)) {
                case "camunda" -> UserTaskImplementation.CAMUNDA_USER_TASK;
                case "job-worker" -> UserTaskImplementation.JOB_WORKER;
                default -> throw new IllegalArgumentException("--user-tasks must be camunda or job-worker");
              });
          case "--interface-events" -> options = options.withInterfaceEvents(
              switch (value(args, ++i, a)) {
                case "none" -> InterfaceEvents.NONE;
                case "message" -> InterfaceEvents.MESSAGE;
                default -> throw new IllegalArgumentException("--interface-events must be none or message");
              });
          case "--platform-version" -> options = options.withExecutionPlatformVersion(value(args, ++i, a));
          case "--fail-on-issues" -> failOnIssues = true;
          default -> {
            if (a.startsWith("-")) {
              throw new IllegalArgumentException("Unknown option " + a);
            }
            inputs.add(Path.of(a));
          }
        }
      }
      if (inputs.isEmpty()) {
        throw new IllegalArgumentException("No input files given");
      }
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return 1;
    }

    try {
      List<Path> files = collect(inputs);
      if (files.isEmpty()) {
        err.println("No .bpmn files found in " + inputs);
        return 1;
      }
      Files.createDirectories(output);
      OracleToCamundaConverter converter = new OracleToCamundaConverter(options);
      List<ConversionReport> reports = new ArrayList<>();
      boolean issues = false;
      for (Path f : files) {
        try {
          ConversionResult r = converter.convert(f);
          Path target = output.resolve(f.getFileName().toString());
          Files.writeString(target, r.bpmnXml());
          reports.add(r.report());
          int n = r.report().validationIssues().size();
          issues |= n > 0;
          out.printf("  %-40s %3d%% automated%s%n", f.getFileName(), r.report().automationPercent(),
              n == 0 ? "" : ", " + n + " validation issue(s)");
        } catch (IOException | IllegalArgumentException e) {
          err.println("  " + f.getFileName() + ": FAILED - " + e.getMessage());
          issues = true;
        }
      }
      Files.writeString(output.resolve("conversion-report.md"), ReportWriter.markdown(reports));
      Files.writeString(output.resolve("conversion-report.json"), ReportWriter.json(reports));
      out.println("Wrote " + reports.size() + " model(s) and conversion-report.md to " + output.toAbsolutePath());
      return failOnIssues && issues ? 2 : 0;
    } catch (IOException e) {
      err.println("Error: " + e.getMessage());
      return 1;
    }
  }

  private static String value(String[] args, int i, String option) {
    if (i >= args.length) {
      throw new IllegalArgumentException(option + " needs a value");
    }
    return args[i];
  }

  static List<Path> collect(List<Path> inputs) throws IOException {
    List<Path> out = new ArrayList<>();
    for (Path p : inputs) {
      if (Files.isDirectory(p)) {
        try (Stream<Path> s = Files.walk(p)) {
          s.filter(Files::isRegularFile)
              .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".bpmn"))
              .sorted()
              .forEach(out::add);
        }
      } else if (Files.isRegularFile(p)) {
        out.add(p);
      } else {
        throw new IOException("Not found: " + p);
      }
    }
    return out;
  }
}
