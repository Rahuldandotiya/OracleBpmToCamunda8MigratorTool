package io.github.rahuldandotiya.o2c8.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.project.ProcessFile;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Source;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * composite.xml → configured service calls. Outbound references use the hand-written
 * ClaimSettlement test fixture; inbound adapters use the real loan-origination sample.
 */
class CompositeConversionTest {

  static final Path TO_MIGRATE = Path.of("src", "test", "resources", "fixtures", "claim-settlement");
  static final Path SAMPLE = Path.of("..", "samples", "oracle-bpm-12c", "loan-origination");

  private static ConversionResult convert(String processFileName) throws Exception {
    return convert(TO_MIGRATE, processFileName);
  }

  private static ConversionResult convert(Path folder, String processFileName) throws Exception {
    DropFolder drop = DropFolder.load(folder);
    try {
      ProcessFile f = drop.processes().stream()
          .filter(p -> p.path().getFileName().toString().equals(processFileName)).findFirst().orElseThrow();
      return new OracleToCamundaConverter().convert(f, drop.compositeFor(f), KnowledgeBase.empty());
    } finally {
      drop.close();
    }
  }

  @Test
  void readsCompositeReferencesWiresAndConfigPlan() throws Exception {
    DropFolder drop = DropFolder.load(TO_MIGRATE);
    try {
      assertEquals(1, drop.composites().size());
      Composite c = drop.composites().get(0);
      assertEquals("ClaimSettlement", c.name());
      assertEquals(List.of("PolicyService", "FraudService", "SettlementDB", "PaymentGateway"),
          c.references().stream().map(Composite.Reference::name).toList());
      assertTrue(c.references().get(0).binding() instanceof Composite.RestBinding);
      assertTrue(c.references().get(1).binding() instanceof Composite.WsBinding);
      assertTrue(c.references().get(2).binding() instanceof Composite.JcaBinding);
      assertEquals("PaymentProcess/PaymentProcess.service",
          c.wireTarget("ClaimSettlementProcess", "PaymentService").orElseThrow());
      assertTrue(c.configPlanOverrides().get("PolicyService").containsValue("https://policy.prod.example.com/api"));
    } finally {
      drop.close();
    }
  }

  @Test
  void restReferenceBecomesRestConnectorWithSecretUrlPathParameterAndResult() throws Exception {
    String xml = convert("ClaimSettlementProcess.bpmn").bpmnXml();
    assertTrue(xml.contains("type=\"io.camunda:http-json:1\""));
    assertTrue(xml.contains("<zeebe:input source=\"GET\" target=\"method\"/>"));
    assertTrue(xml.contains("{{secrets.POLICY_SERVICE_URL}}/policies/&quot; + string(claimSettlementINPDO.policyNumber)"));
    assertTrue(xml.contains("value=\"={policy: response.body}\""));
  }

  @Test
  void restPostBuildsBodyFromOracleInputAssignments() throws Exception {
    String xml = convert("PaymentProcess.bpmn").bpmnXml();
    assertTrue(xml.contains("<zeebe:input source=\"POST\" target=\"method\"/>"));
    assertTrue(xml.contains("<zeebe:input source=\"{{secrets.PAYMENT_GATEWAY_URL}}/payments\" target=\"url\"/>"));
    assertTrue(xml.contains(
        "source=\"={claimId: paymentProcessINPDO.claimId, amount: paymentProcessINPDO.settlement.amount}\" target=\"body\""));
    assertTrue(xml.contains("={paymentResult: {transactionId: response.body.transactionId}}"));
  }

  @Test
  void soapAndAdapterReferencesBecomeJobWorkersWithMetadata() throws Exception {
    String xml = convert("ClaimSettlementProcess.bpmn").bpmnXml();
    assertTrue(xml.contains("type=\"soap-fraud-service-check-fraud\""));
    assertTrue(xml.contains("key=\"soapAction\" value=\"http://example.com/fraud/checkFraud\""));
    assertTrue(xml.contains("key=\"endpoint\" value=\"{{secrets.FRAUD_SERVICE_URL}}\""));
    assertTrue(xml.contains("type=\"db-settlement-db-merge\""));
    assertTrue(xml.contains("key=\"connectionFactory\" value=\"eis/DB/ClaimsDS\""));
  }

  @Test
  void callToAnotherBpmnComponentBecomesCallActivity() throws Exception {
    ConversionResult r = convert("ClaimSettlementProcess.bpmn");
    String xml = r.bpmnXml();
    assertTrue(xml.contains("<bpmn:callActivity id=\"ACT_PaySettlement\""));
    assertTrue(xml.contains("<zeebe:calledElement processId=\"PaymentProcess\" propagateAllChildVariables=\"false\"/>"));
    assertEquals(List.of(), r.report().validationIssues());
    assertEquals(4L, r.report().elementsFrom(Source.COMPOSITE));
    assertEquals(List.of("POLICY_SERVICE_URL", "FRAUD_SERVICE_URL"), List.copyOf(r.report().secrets().keySet()));
  }

  @Test
  void withoutCompositeServiceTasksFallBackToBuiltInWorkers() throws Exception {
    DropFolder drop = DropFolder.load(TO_MIGRATE);
    try {
      ProcessFile f = drop.processes().stream()
          .filter(p -> p.path().getFileName().toString().equals("ClaimSettlementProcess.bpmn")).findFirst().orElseThrow();
      String xml = new OracleToCamundaConverter().convert(f, java.util.Optional.empty(), KnowledgeBase.empty()).bpmnXml();
      assertFalse(xml.contains("io.camunda:http-json:1"));
      assertTrue(xml.contains("type=\"get-policy\""));
    } finally {
      drop.close();
    }
  }

  @Test
  void readsInboundAdaptersFromTheRealSample() throws Exception {
    DropFolder drop = DropFolder.load(SAMPLE);
    try {
      Composite c = drop.composites().get(0);
      assertEquals("LoanOrigination", c.name());
      assertTrue(c.services().stream().anyMatch(s -> s.name().equals("ConsumeLoanRequest")
          && s.binding() instanceof Composite.JcaBinding));
      assertTrue(c.services().stream().anyMatch(s -> s.name().equals("ReceiveLOEmail")));
    } finally {
      drop.close();
    }
  }

  @Test
  void jmsAdapterStartBecomesNamedMessageStart() throws Exception {
    ConversionResult r = convert(SAMPLE, "LOProcessActivationFromQueue.bpmn");
    assertTrue(r.bpmnXml().contains("name=\"ConsumeLoanRequest\""));
    assertEquals(List.of(), r.report().validationIssues());
    assertEquals(1L, r.report().elementsFrom(Source.COMPOSITE));
  }

  @Test
  void emailAdapterStartPointsToTheEmailConnector() throws Exception {
    ConversionResult r = convert(SAMPLE, "LOProcessActivationFromEmail.bpmn");
    assertTrue(r.bpmnXml().contains("name=\"ReceiveLOEmail\""));
    assertTrue(r.report().entries().stream().anyMatch(e -> e.message().contains("Email inbound connector")));
  }
}
