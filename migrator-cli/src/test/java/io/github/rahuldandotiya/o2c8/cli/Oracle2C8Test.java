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

  @Test
  void convertsSampleFolder(@TempDir Path out) throws Exception {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int code = Oracle2C8.run(
        new String[] {"convert", "../samples/oracle-bpm-12c/insurance-claim", "-o", out.toString(), "--fail-on-issues"},
        new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));
    assertEquals(0, code, stdout.toString());
    assertTrue(Files.exists(out.resolve("FNOLProcess.bpmn")));
    assertTrue(Files.readString(out.resolve("conversion-report.md")).contains("| FNOLProcess.bpmn |"));
    assertTrue(Files.readString(out.resolve("conversion-report.json")).contains("\"automationPercent\""));
  }

  @Test
  void migrateUsesKnowledgeBaseAndProcessToMigrateFolders(@TempDir Path out) throws Exception {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int code = Oracle2C8.run(new String[] {"migrate",
        "--kb", "../samples/demo/knowledge-base", "--in", "../samples/demo/processToMigrate",
        "-o", out.toString(), "--fail-on-issues"},
        new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));
    assertEquals(0, code, stdout.toString());
    assertTrue(stdout.toString().contains("5 pair(s)"), stdout.toString());
    assertTrue(Files.exists(out.resolve("knowledge-base.json")));
    assertTrue(Files.exists(out.resolve("claim-settlement/ClaimSettlement/SOA/processes/ClaimSettlementProcess.bpmn")));
    String report = Files.readString(out.resolve("conversion-report.md"));
    assertTrue(report.contains("## Knowledge base"));
    assertTrue(report.contains("POLICY_SERVICE_URL"));
  }

  @Test
  void learnWritesJsonAndConvertAcceptsIt(@TempDir Path out) throws Exception {
    Path kb = out.resolve("kb.json");
    PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
    assertEquals(0, Oracle2C8.run(new String[] {"learn", "../samples/demo/knowledge-base", "-o", kb.toString()},
        quiet, quiet));
    assertEquals(0, Oracle2C8.run(new String[] {"convert", "../samples/demo/processToMigrate", "--kb", kb.toString(),
        "-o", out.resolve("models").toString()}, quiet, quiet));
    assertTrue(Files.readString(out.resolve("models/RejectionHandlerProcess.bpmn")).contains("claims-case-manager"));
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
