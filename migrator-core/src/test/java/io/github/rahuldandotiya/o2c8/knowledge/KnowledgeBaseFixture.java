package io.github.rahuldandotiya.o2c8.knowledge;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a knowledge-base folder at test time from the real loan-origination sample: two Oracle
 * processes, each paired with a "finished" Camunda model that carries decisions a team would make
 * after the automatic conversion.
 *
 * <ul>
 *   <li>LoanRequest input data objects ({@code ...INPDO}) are renamed to {@code loanRequest}.
 *   <li>The LoanOfficer role becomes candidate group {@code loan-officers}.
 *   <li>The initiator task gets the Camunda form {@code loan-request-form}.
 * </ul>
 *
 * The finished models use different file names, so pairing has to match on the process id.
 */
public final class KnowledgeBaseFixture {

  public static final Path SAMPLES = Path.of("..", "samples", "oracle-bpm-12c", "loan-origination",
      "LoanOrigination", "SOA", "processes");

  private KnowledgeBaseFixture() {}

  public static Path create(Path dir) throws IOException {
    OracleToCamundaConverter converter = new OracleToCamundaConverter();
    pair(dir, converter, "loan-as-service", "LOProcessAsService.bpmn", xml -> xml
        .replace("lOProcessAsServiceINPDO", "loanRequest"));
    pair(dir, converter, "loan-human-initiation", "LOProcessHumanInitiation.bpmn", xml -> xml
        .replace("lOProcessHumanInitiationINPDO", "loanRequest")
        .replace("candidateGroups=\"LoanOfficer\"", "candidateGroups=\"loan-officers\"")
        .replace("<zeebe:userTask/>", "<zeebe:userTask/><zeebe:formDefinition formId=\"loan-request-form\"/>"));
    return dir;
  }

  private static void pair(Path dir, OracleToCamundaConverter converter, String name, String oracleFile,
      java.util.function.UnaryOperator<String> teamEdits) throws IOException {
    Path oracle = Files.createDirectories(dir.resolve(name).resolve("oracle")).resolve(oracleFile);
    Files.copy(SAMPLES.resolve(oracleFile), oracle);
    String converted = converter.convert(oracle).bpmnXml();
    String finished = teamEdits.apply(converted);
    if (finished.equals(converted)) {
      throw new IllegalStateException("fixture edits did not apply to " + oracleFile);
    }
    Files.writeString(Files.createDirectories(dir.resolve(name).resolve("camunda")).resolve(name + ".bpmn"), finished);
  }
}
