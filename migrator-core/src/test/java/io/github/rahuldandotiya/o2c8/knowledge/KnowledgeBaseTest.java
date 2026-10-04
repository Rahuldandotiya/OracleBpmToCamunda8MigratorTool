package io.github.rahuldandotiya.o2c8.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase.Kind;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.project.ProcessFile;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Source;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Learning from finished migrations (see {@link KnowledgeBaseFixture}) and applying them. */
class KnowledgeBaseTest {

  static final Path TO_MIGRATE = Path.of("..", "samples", "oracle-bpm-12c");

  private final OracleToCamundaConverter converter = new OracleToCamundaConverter();

  private KnowledgeBase learn(Path tmp) throws Exception {
    DropFolder drop = DropFolder.load(KnowledgeBaseFixture.create(tmp));
    try {
      return new KnowledgeBaseBuilder(converter).build(drop);
    } finally {
      drop.close();
    }
  }

  private ConversionResult migrate(String fileName, KnowledgeBase kb) throws Exception {
    DropFolder drop = DropFolder.load(TO_MIGRATE);
    try {
      ProcessFile f = drop.processes().stream()
          .filter(p -> p.path().getFileName().toString().equals(fileName)).findFirst().orElseThrow();
      return converter.convert(f, drop.compositeFor(f), kb);
    } finally {
      drop.close();
    }
  }

  @Test
  void pairsOracleProcessesWithFinishedModelsByProcessId(@TempDir Path tmp) throws Exception {
    DropFolder drop = DropFolder.load(KnowledgeBaseFixture.create(tmp));
    try {
      var pairs = KnowledgeBaseBuilder.pairs(drop);
      assertEquals(2, pairs.size());
      var human = pairs.stream().filter(p -> p.processId().equals("LOProcessHumanInitiation")).findFirst().orElseThrow();
      assertEquals("LOProcessHumanInitiation", human.camundaProcessId());
      assertEquals("process id", human.matchedBy());
    } finally {
      drop.close();
    }
  }

  @Test
  void learnsRulesKeyedByWhatRecursInOtherProcesses(@TempDir Path tmp) throws Exception {
    KnowledgeBase kb = learn(tmp);
    assertEquals("loanRequest", kb.rule(Kind.VARIABLE_TYPE, "LoanRequest|INPDO").orElseThrow().best().value());
    assertEquals("loan-officers", kb.rule(Kind.ROLE, "LoanOfficer").orElseThrow().best().value());
    assertTrue(kb.rules(Kind.HUMAN_TASK).values().stream()
        .anyMatch(r -> r.best().value().contains("loan-request-form")), kb.toJson());
  }

  @Test
  void jsonRoundTripKeepsEveryRule(@TempDir Path tmp) throws Exception {
    KnowledgeBase kb = learn(tmp);
    KnowledgeBase back = KnowledgeBase.fromJson(kb.toJson());
    assertEquals(kb.size(), back.size());
    assertEquals(kb.toJson(), back.toJson());
  }

  @Test
  void learnedVariableNameIsAppliedToAnotherProcess(@TempDir Path tmp) throws Exception {
    ConversionResult r = migrate("LOProcessActivationFromQueue.bpmn", learn(tmp));
    String xml = r.bpmnXml();
    assertFalse(xml.contains("lOProcessActivationQueueINPDO"), "LoanRequest|INPDO follows the learned rule");
    assertTrue(xml.contains("loanRequest"));
    assertEquals(List.of(), r.report().validationIssues());
    assertTrue(r.report().elementsFrom(Source.KNOWLEDGE_BASE) >= 1);
  }

  @Test
  void learnedVariableNameAlsoReachesTheMessageStartMapping(@TempDir Path tmp) throws Exception {
    ConversionResult r = migrate("LOProcessSendReceive.bpmn", learn(tmp));
    assertFalse(r.bpmnXml().contains("receiveLoanOriginationINPDO"));
    assertEquals(List.of(), r.report().validationIssues());
  }

  @Test
  void emptyKnowledgeBaseChangesNothing() throws Exception {
    String plain = migrate("LOProcessActivationFromQueue.bpmn", KnowledgeBase.empty()).bpmnXml();
    String again = migrate("LOProcessActivationFromQueue.bpmn",
        KnowledgeBase.fromJson(KnowledgeBase.empty().toJson())).bpmnXml();
    assertEquals(plain, again);
  }

  @Test
  void leaveOneOutNeverAddsEdits(@TempDir Path tmp) throws Exception {
    DropFolder drop = DropFolder.load(KnowledgeBaseFixture.create(tmp));
    try {
      var results = Evaluator.leaveOneOut(drop, converter);
      assertEquals(2, results.size());
      assertTrue(results.stream().allMatch(r -> r.editsWith() <= r.editsWithout()));
      int without = results.stream().mapToInt(Evaluator.Result::editsWithout).sum();
      int with = results.stream().mapToInt(Evaluator.Result::editsWith).sum();
      assertTrue(with < without, "the shared LoanRequest rule should save edits: " + with + " of " + without);
    } finally {
      drop.close();
    }
  }
}
