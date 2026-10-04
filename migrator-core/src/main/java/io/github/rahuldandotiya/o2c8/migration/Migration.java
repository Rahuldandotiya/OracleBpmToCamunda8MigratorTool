package io.github.rahuldandotiya.o2c8.migration;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.project.ProcessFile;
import io.github.rahuldandotiya.o2c8.report.ConversionReport;
import io.github.rahuldandotiya.o2c8.report.ReportWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts every Oracle process in a drop folder, one file at a time. A file that cannot be
 * converted (broken XML, unexpected model, a bug in a converter) is recorded as FAILED with the
 * reason; it never stops the other files. Used by the CLI and the web UI.
 */
public final class Migration {

  /** Result for one Oracle file: the converted model, or the reason it failed. */
  public record Outcome(String sourcePath, Optional<ProcessFile> file, ConversionReport report,
      Optional<ConversionResult> result) {
    public ConversionReport.Status status() {
      return report.status();
    }

    /** Path of the converted model relative to the output folder (mirrors the input structure). */
    public String outputPath() {
      return sourcePath;
    }
  }

  /** Everything one run produced. */
  public record Run(List<Outcome> outcomes, List<String> skipped, List<String> warnings, int composites,
      KnowledgeBase knowledgeBase) {

    public List<ConversionReport> reports() {
      return outcomes.stream().map(Outcome::report).toList();
    }

    public long count(ConversionReport.Status status) {
      return outcomes.stream().filter(o -> o.status() == status).count();
    }

    public boolean anyFailed() {
      return count(ConversionReport.Status.FAILED) > 0;
    }

    public boolean anyIssues() {
      return count(ConversionReport.Status.CONVERTED_WITH_ISSUES) > 0;
    }

    public String summary() {
      return ReportWriter.summary(reports());
    }
  }

  private Migration() {}

  /** Converts all Oracle processes of {@code drop}; never throws for a single bad file. */
  public static Run run(DropFolder drop, OracleToCamundaConverter converter, KnowledgeBase kb) {
    List<Outcome> outcomes = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (ProcessFile f : drop.processes()) {
      if (!f.convertible()) {
        skipped.add(f.displayPath() + " (already a Camunda 8 model)");
        continue;
      }
      outcomes.add(convertOne(drop, f, converter, kb));
    }
    for (DropFolder.Unreadable u : drop.unreadable()) {
      outcomes.add(new Outcome(u.displayPath(), Optional.empty(), ConversionReport.failed(u.displayPath(), u.reason()),
          Optional.empty()));
    }
    return new Run(List.copyOf(outcomes), List.copyOf(skipped), List.copyOf(drop.warnings()),
        drop.composites().size(), kb);
  }

  static Outcome convertOne(DropFolder drop, ProcessFile f, OracleToCamundaConverter converter, KnowledgeBase kb) {
    try {
      ConversionResult r = converter.convert(f, drop.compositeFor(f), kb);
      return new Outcome(f.displayPath(), Optional.of(f), r.report(), Optional.of(r));
    } catch (IOException e) {
      return failed(f, "the file could not be read as BPMN XML: " + message(e));
    } catch (IllegalArgumentException e) {
      return failed(f, message(e));
    } catch (RuntimeException | StackOverflowError e) {
      // a converter bug must not stop the run; keep the class name to make the report actionable
      return failed(f, "unexpected " + e.getClass().getSimpleName() + " while converting: " + message(e)
          + " (please report this with the file, if you can share it)");
    }
  }

  private static Outcome failed(ProcessFile f, String reason) {
    return new Outcome(f.displayPath(), Optional.of(f), ConversionReport.failed(f.displayPath(), reason),
        Optional.empty());
  }

  private static String message(Throwable e) {
    String m = e.getMessage();
    return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.replace('\n', ' ');
  }

  /**
   * Writes the converted models (same folder structure as the input) plus
   * conversion-report.md and conversion-report.json into {@code output}.
   */
  public static void write(Run run, Path output) throws IOException {
    Path root = output.toAbsolutePath().normalize();
    Files.createDirectories(root);
    for (Outcome o : run.outcomes()) {
      if (o.result().isEmpty()) {
        continue;
      }
      Path target = root.resolve(o.outputPath()).normalize();
      if (!target.startsWith(root)) {
        target = root.resolve(Path.of(o.outputPath()).getFileName().toString());
      }
      Files.createDirectories(target.getParent());
      Files.writeString(target, o.result().get().bpmnXml());
    }
    KnowledgeBase kb = run.knowledgeBase();
    Files.writeString(root.resolve("conversion-report.md"),
        ReportWriter.markdown(run.reports(), kb == null || kb.pairs().isEmpty() ? null : kb));
    Files.writeString(root.resolve("conversion-report.json"), ReportWriter.json(run.reports()));
  }
}
