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
  void unknownOptionFails() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Oracle2C8.run(new String[] {"convert", "x.bpmn", "--nope"},
        new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
    assertEquals(1, code);
    assertTrue(err.toString().contains("Unknown option"));
  }
}
