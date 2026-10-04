package io.github.rahuldandotiya.o2c8.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationTest {

  static final Path SAMPLE = Path.of("..", "samples", "oracle-bpm-12c", "loan-origination");
  static final Path PROCESSES = SAMPLE.resolve(Path.of("LoanOrigination", "SOA", "processes"));

  private Migration.Run run(Path folder) throws Exception {
    DropFolder drop = DropFolder.load(folder);
    try {
      return Migration.run(drop, new OracleToCamundaConverter(), KnowledgeBase.empty());
    } finally {
      drop.close();
    }
  }

  @Test
  void aBrokenFileFailsAloneAndIsReportedWithTheReason(@TempDir Path tmp) throws Exception {
    Path in = Files.createDirectories(tmp.resolve("in"));
    Files.copy(PROCESSES.resolve("LOProcessSchedule.bpmn"), in.resolve("LOProcessSchedule.bpmn"));
    Files.writeString(in.resolve("Broken.bpmn"), "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">");
    Files.writeString(in.resolve("Evil.bpmn"), "<?xml version=\"1.0\"?>\n<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>\n<x>&e;</x>");

    Migration.Run run = run(in);
    assertEquals(3, run.outcomes().size());
    assertEquals(1L, run.count(Status.CONVERTED));
    assertEquals(2L, run.count(Status.FAILED));
    assertTrue(run.anyFailed());
    var broken = run.outcomes().stream().filter(o -> o.sourcePath().equals("Broken.bpmn")).findFirst().orElseThrow();
    assertTrue(broken.report().failure().startsWith("the file could not be read as BPMN XML"), broken.report().failure());
    var evil = run.outcomes().stream().filter(o -> o.sourcePath().equals("Evil.bpmn")).findFirst().orElseThrow();
    assertTrue(evil.report().failure().contains("DOCTYPE"), evil.report().failure());
    assertEquals("3 file(s): 1 converted, 0 with validation issues, 2 failed", run.summary());

    Path out = tmp.resolve("out");
    Migration.write(run, out);
    assertTrue(Files.exists(out.resolve("LOProcessSchedule.bpmn")));
    assertFalse(Files.exists(out.resolve("Broken.bpmn")), "no model for a failed file");
    String md = Files.readString(out.resolve("conversion-report.md"));
    assertTrue(md.contains("| Broken.bpmn | FAILED |"), md);
    assertTrue(md.contains("**Not converted.**"), md);
    String json = Files.readString(out.resolve("conversion-report.json"));
    assertTrue(json.contains("\"failed\": 2") && json.contains("\"status\": \"FAILED\""), json);
  }

  @Test
  void camundaModelsAreSkippedNotConverted(@TempDir Path tmp) throws Exception {
    Path in = Files.createDirectories(tmp.resolve("in"));
    String converted = new OracleToCamundaConverter().convert(PROCESSES.resolve("LOProcessSchedule.bpmn")).bpmnXml();
    Files.writeString(in.resolve("Already.bpmn"), converted);
    Files.copy(PROCESSES.resolve("LOProcessSendReceive.bpmn"), in.resolve("LOProcessSendReceive.bpmn"));
    Migration.Run run = run(in);
    assertEquals(1, run.outcomes().size());
    assertEquals(1, run.skipped().size());
    assertTrue(run.skipped().get(0).startsWith("Already.bpmn"));
  }

  @Test
  void wholeSampleConvertsCleanly() throws Exception {
    Migration.Run run = run(SAMPLE);
    assertEquals(9L, run.count(Status.CONVERTED), run.summary());
    assertEquals(1, run.composites());
  }
}
