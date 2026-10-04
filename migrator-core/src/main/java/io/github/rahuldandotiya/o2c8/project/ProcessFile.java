package io.github.rahuldandotiya.o2c8.project;

import java.nio.file.Path;
import java.util.List;

/**
 * A {@code .bpmn} file found in a drop folder.
 *
 * @param path absolute path (inside an extracted archive, if it came from a zip/SAR)
 * @param displayPath path relative to the drop folder, for reports
 * @param group first-level sub-folder of the drop folder ("" for files directly in it)
 * @param kind Oracle (has bpmnext extensions / Oracle version), Camunda 8 (zeebe/modeler namespace) or plain BPMN
 * @param processIds ids of the {@code bpmn:process} elements in the file
 * @param processNames names of those processes (same order, may contain nulls)
 */
public record ProcessFile(
    Path path, String displayPath, String group, Kind kind, List<String> processIds, List<String> processNames) {

  public enum Kind {
    ORACLE,
    CAMUNDA8,
    OTHER_BPMN
  }

  public String baseName() {
    String n = path.getFileName().toString();
    int dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  /** Oracle and plain BPMN files are both converted; only Camunda 8 files are "finished". */
  public boolean convertible() {
    return kind != Kind.CAMUNDA8;
  }
}
