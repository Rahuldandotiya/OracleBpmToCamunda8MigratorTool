package io.github.rahuldandotiya.o2c8.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Oracle2C8Test {

  static final String SAMPLE = "../samples/oracle-bpm-12c/loan-origination";
  static final Path PROCESSES = Path.of(SAMPLE, "LoanOrigination", "SOA", "processes");

  /** A knowledge base with one finished migration: the team renamed the LoanRequest input variable. */
  private static Path knowledgeBase(Path dir) throws Exception {
    Path oracle = Files.createDirectories(dir.resolve("loan-as-service/oracle")).resolve("LOProcessAsService.bpmn");
    Files.copy(PROCESSES.resolve("LOProcessAsService.bpmn"), oracle);
    String finished = new io.github.rahuldandotiya.o2c8.OracleToCamundaConverter().convert(oracle).bpmnXml()
        .replace("lOProcessAsServiceINPDO", "loanRequest");
    Files.writeString(Files.createDirectories(dir.resolve("loan-as-service/camunda")).resolve("loan-as-service.bpmn"),
        finished);
    return dir;
  }

  @Test
  void convertsSampleFolder(@TempDir Path out) throws Exception {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int code = Oracle2C8.run(
        new String[] {"convert", PROCESSES.toString(), "-o", out.toString(), "--fail-on-issues"},
        new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));
    assertEquals(0, code, stdout.toString());
    assertTrue(Files.exists(out.resolve("LOProcessSchedule.bpmn")));
    assertTrue(Files.readString(out.resolve("conversion-report.md")).contains("| LOProcessSchedule.bpmn |"));
    assertTrue(Files.readString(out.resolve("conversion-report.json")).contains("\"automationPercent\""));
  }

  @Test
  void migrateUsesKnowledgeBaseAndProcessToMigrateFolders(@TempDir Path out) throws Exception {
    Path kb = knowledgeBase(out.resolve("kb"));
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int code = Oracle2C8.run(new String[] {"migrate",
        "--kb", kb.toString(), "--in", SAMPLE, "-o", out.resolve("result").toString(), "--fail-on-issues"},
        new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));
    assertEquals(0, code, stdout.toString());
    assertTrue(stdout.toString().contains("1 pair(s)"), stdout.toString());
    assertTrue(stdout.toString().contains("composite.xml"), stdout.toString());
    assertTrue(Files.exists(out.resolve("result/knowledge-base.json")));
    assertTrue(Files.exists(out.resolve("result/LoanOrigination/SOA/processes/LOProcessActivationFromQueue.bpmn")));
    assertTrue(Files.readString(out.resolve("result/conversion-report.md"))
        .contains("## Knowledge base"));
  }

  @Test
  void learnWritesJsonAndConvertAcceptsIt(@TempDir Path out) throws Exception {
    Path kb = out.resolve("kb.json");
    PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
    assertEquals(0, Oracle2C8.run(new String[] {"learn", knowledgeBase(out.resolve("kb")).toString(), "-o", kb.toString()},
        quiet, quiet));
    assertEquals(0, Oracle2C8.run(new String[] {"convert", PROCESSES.resolve("LOProcessActivationFromQueue.bpmn").toString(),
        "--kb", kb.toString(), "-o", out.resolve("models").toString()}, quiet, quiet));
    String xml = Files.readString(out.resolve("models/LOProcessActivationFromQueue.bpmn"));
    assertTrue(xml.contains("loanRequest") && !xml.contains("lOProcessActivationQueueINPDO"));
  }

  @Test
  void migrateWithoutInputFolderExplains() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Oracle2C8.run(new String[] {"migrate", "--in", "does-not-exist"},
        new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
    assertEquals(1, code);
    assertTrue(err.toString().contains("Nothing to migrate"));
  }

  @Test
  void unknownOptionFails() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Oracle2C8.run(new String[] {"convert", "x.bpmn", "--nope"},
        new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
    assertEquals(1, code);
    assertTrue(err.toString().contains("Unknown option"));
  }
}
