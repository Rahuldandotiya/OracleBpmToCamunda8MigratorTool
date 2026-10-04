package io.github.rahuldandotiya.o2c8.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DropFolderTest {

  static final Path SAMPLES = Path.of("..", "samples", "oracle-bpm-12c");

  @Test
  void classifiesOracleModelsAndFindsComposites() throws Exception {
    DropFolder d = DropFolder.load(SAMPLES);
    try {
      assertEquals(9, d.processes(ProcessFile.Kind.ORACLE).size());
      assertEquals(0, d.processes(ProcessFile.Kind.CAMUNDA8).size());
      assertEquals(1, d.composites().size());
      ProcessFile schedule = d.processes(ProcessFile.Kind.ORACLE).stream()
          .filter(p -> p.processIds().contains("LOProcessSchedule")).findFirst().orElseThrow();
      assertEquals("loan-origination", schedule.group());
      assertTrue(d.compositeFor(schedule).isPresent());
    } finally {
      d.close();
    }
  }

  @Test
  void readsProjectsInsideZipArchives(@TempDir Path tmp) throws Exception {
    Path project = SAMPLES.resolve("loan-origination/LoanOrigination");
    Path zip = tmp.resolve("LoanOrigination.zip");
    try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os);
        var files = Files.walk(project)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        z.putNextEntry(new ZipEntry(project.relativize(f).toString().replace('\\', '/')));
        z.write(Files.readAllBytes(f));
        z.closeEntry();
      }
    }
    DropFolder d = DropFolder.load(tmp);
    try {
      assertEquals(9, d.processes(ProcessFile.Kind.ORACLE).size());
      assertEquals(1, d.composites().size());
      assertEquals("LoanOrigination", d.processes().get(0).group());
      assertTrue(d.compositeFor(d.processes().get(0)).isPresent());
    } finally {
      d.close();
    }
  }

  @Test
  void rejectsArchiveEntriesThatEscapeTheFolder(@TempDir Path tmp) throws Exception {
    Path zip = tmp.resolve("evil.zip");
    try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
      z.putNextEntry(new ZipEntry("../../escaped.bpmn"));
      z.write("<x/>".getBytes());
      z.closeEntry();
    }
    DropFolder d = DropFolder.load(tmp);
    try {
      assertEquals(0, d.processes().size());
      assertTrue(d.warnings().stream().anyMatch(w -> w.contains("escapes")), d.warnings().toString());
      assertTrue(Files.notExists(tmp.getParent().getParent().resolve("escaped.bpmn")));
    } finally {
      d.close();
    }
  }
}
