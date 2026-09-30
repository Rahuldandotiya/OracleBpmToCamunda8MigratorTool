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

/** Learning from the demo knowledge-base folder and applying it to processToMigrate. */
class KnowledgeBaseTest {

  static final Path KB = Path.of("..", "samples", "demo", "knowledge-base");
  static final Path TO_MIGRATE = Path.of("..", "samples", "demo", "processToMigrate");

  private final OracleToCamundaConverter converter = new OracleToCamundaConverter();

  private KnowledgeBase learn() throws Exception {
    DropFolder drop = DropFolder.load(KB);
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
  void pairsOracleProcessesWithFinishedModels() throws Exception {
    DropFolder drop = DropFolder.load(KB);
    try {
      var pairs = KnowledgeBaseBuilder.pairs(drop);
      assertEquals(5, pairs.size());
      var fnol = pairs.stream().filter(p -> p.processId().equals("FNOLProcess")).findFirst().orElseThrow();
      assertEquals("fnol-intake", fnol.camundaProcessId());
      assertEquals("file name", fnol.matchedBy());
    } finally {
      drop.close();
    }
  }

  @Test
  void learnsRulesKeyedByWhatRecursInOtherProcesses() throws Exception {
    KnowledgeBase kb = learn();
    assertTrue(kb.rule(Kind.CALL, "ref:PolicyService.getPolicy").isPresent());
    assertTrue(kb.rule(Kind.CALL, "ref:FraudService.checkFraud").isPresent());
    assertTrue(kb.rule(Kind.CALL, "endpoint:http://fraud.example.com/fraudservice#checkFraud").isPresent());
    assertEquals("claims-csr", kb.rule(Kind.ROLE, "CSR").orElseThrow().best().value());
    assertEquals("claims.", kb.rule(Kind.CONVENTION, "jobTypePrefix").orElseThrow().best().value());
    assertEquals("claims-", kb.rule(Kind.CONVENTION, "candidateGroupPrefix").orElseThrow().best().value());
    assertEquals("{name}-form", kb.rule(Kind.CONVENTION, "formIdPattern").orElseThrow().best().value());
    assertEquals("claim", kb.rule(Kind.VARIABLE_TYPE, "Claim|INPDO").orElseThrow().best().value());
    assertTrue(kb.rule(Kind.HUMAN_TASK, "task:ValidationUserTask").isPresent());
    assertTrue(kb.rules(Kind.CALL).values().stream().noneMatch(KnowledgeBase.Rule::conflict));
  }

  @Test
  void jsonRoundTripKeepsEveryRule() throws Exception {
    KnowledgeBase kb = learn();
    KnowledgeBase back = KnowledgeBase.fromJson(kb.toJson());
    assertEquals(kb.size(), back.size());
    assertEquals(kb.toJson(), back.toJson());
  }

  @Test
  void appliesServiceCallRulesConventionsRolesAndVariables() throws Exception {
    ConversionResult r = migrate("ClaimSettlementProcess.bpmn", learn());
    String xml = r.bpmnXml();
    // GetPolicy: auth and headers the team added in the Claim Intake pair
    assertTrue(xml.contains("<zeebe:input source=\"bearer\" target=\"authentication.type\"/>"));
    assertTrue(xml.contains("{{secrets.POLICY_API_TOKEN}}"));
    // CheckFraud: worker settings from the pair (same reference + operation)
    assertTrue(xml.contains("<zeebe:taskDefinition retries=\"5\" type=\"claims.soap-fraud-service-check-fraud\"/>"));
    // RecordSettlement: new reference, only the naming convention applies
    assertTrue(xml.contains("type=\"claims.db-settlement-db-merge\""));
    // new role through the candidate group convention, form id through the form convention
    assertTrue(xml.contains("candidateGroups=\"claims-adjuster\""));
    assertTrue(xml.contains("formId=\"approve-settlement-form\""));
    // Claim data object renamed everywhere
    assertFalse(xml.contains("claimSettlementINPDO"));
    assertTrue(xml.contains("string(claim.policyNumber)"));
    assertEquals(List.of(), r.report().validationIssues());
    assertTrue(r.report().elementsFrom(Source.KNOWLEDGE_BASE) >= 4);
  }

  @Test
  void sameOracleHumanTaskGetsTheSameUserTaskSettings() throws Exception {
    String xml = migrate("RejectionHandlerProcess.bpmn", learn()).bpmnXml();
    assertTrue(xml.contains("formId=\"validation-user-task-form\""), "RejectedCase reuses ValidationUserTask.task");
    assertTrue(xml.contains("candidateGroups=\"claims-case-manager\""));
  }

  @Test
  void doesNotMergeTwoDataObjectsOfTheSameType() throws Exception {
    ConversionResult r = migrate("CustomerAcceptanceProcess.bpmn", learn());
    assertFalse(r.bpmnXml().contains("customerAcceptanceProcessINPDO"), "INPDO follows the learned Claim|INPDO rule");
    assertTrue(r.report().entries().stream().anyMatch(e -> e.message().contains("customerAcceptanceProcessOUTPD kept")),
        "OUTPD must keep its name: renaming it too would merge two Claim variables");
  }

  @Test
  void emptyKnowledgeBaseChangesNothing() throws Exception {
    String plain = migrate("ClaimSettlementProcess.bpmn", KnowledgeBase.empty()).bpmnXml();
    String again = migrate("ClaimSettlementProcess.bpmn", KnowledgeBase.fromJson(KnowledgeBase.empty().toJson())).bpmnXml();
    assertEquals(plain, again);
  }

  @Test
  void leaveOneOutShowsFewerEdits() throws Exception {
    DropFolder drop = DropFolder.load(KB);
    try {
      var results = Evaluator.leaveOneOut(drop, converter);
      int without = results.stream().mapToInt(Evaluator.Result::editsWithout).sum();
      int with = results.stream().mapToInt(Evaluator.Result::editsWith).sum();
      assertEquals(5, results.size());
      assertTrue(with * 2 <= without, "expected at least 50% fewer edits, got " + with + " of " + without);
      assertTrue(results.stream().allMatch(r -> r.editsWith() <= r.editsWithout()));
    } finally {
      drop.close();
    }
  }
}
