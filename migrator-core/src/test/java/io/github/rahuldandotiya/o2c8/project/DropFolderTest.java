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

  static final Path DEMO_KB = Path.of("..", "samples", "demo", "knowledge-base");

  @Test
  void classifiesOracleAndCamundaModelsAndFindsComposites() throws Exception {
    DropFolder d = DropFolder.load(DEMO_KB);
    try {
      assertEquals(5, d.processes(ProcessFile.Kind.ORACLE).size());
      assertEquals(5, d.processes(ProcessFile.Kind.CAMUNDA8).size());
      assertEquals(1, d.composites().size());
      ProcessFile intake = d.processes(ProcessFile.Kind.ORACLE).stream()
          .filter(p -> p.processIds().contains("ClaimIntakeProcess")).findFirst().orElseThrow();
      assertEquals("claim-intake", intake.group());
      assertTrue(d.compositeFor(intake).isPresent());
    } finally {
      d.close();
    }
  }

  @Test
  void readsProjectsInsideZipArchives(@TempDir Path tmp) throws Exception {
    Path project = DEMO_KB.resolve("claim-intake/ClaimIntake");
    Path zip = tmp.resolve("ClaimIntake.zip");
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
      assertEquals(1, d.processes(ProcessFile.Kind.ORACLE).size());
      assertEquals(1, d.composites().size());
      assertEquals("ClaimIntake", d.processes().get(0).group());
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
