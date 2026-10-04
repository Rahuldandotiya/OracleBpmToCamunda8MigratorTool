package io.github.rahuldandotiya.o2c8.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase;
import io.github.rahuldandotiya.o2c8.migration.Analysis.Category;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalysisTest {

  private static Analysis.Result analyze(Analysis.Weights w) throws Exception {
    DropFolder drop = DropFolder.load(MigrationTest.SAMPLE);
    try {
      return Analysis.analyze(Migration.run(drop, new OracleToCamundaConverter(), KnowledgeBase.empty()), w);
    } finally {
      drop.close();
    }
  }

  @Test
  void groupsFollowUpWorkByKind() throws Exception {
    Analysis.Result a = analyze(Analysis.Weights.defaults());
    Map<Category, Integer> t = a.totals();
    assertEquals(1, t.get(Category.FORM), "the initiator user task");
    assertEquals(2, t.get(Category.INBOUND_TRIGGER), "JMS queue and email adapters");
    assertEquals(1, t.get(Category.JOB_WORKER), "the asynchronous reply of LOProcessSendReceive");
    assertTrue(t.get(Category.UNDEFINED_TASK) >= 10, "the sample is full of abstract tasks: " + t);
    assertEquals(9, a.processes().size());
    var human = a.processes().stream().filter(p -> p.source().endsWith("LOProcessHumanInitiation.bpmn")).findFirst().orElseThrow();
    assertEquals(1, human.size().lanes());
    assertEquals("low", human.size().complexity());
  }

  @Test
  void hoursFollowTheConfiguredWeights() throws Exception {
    Analysis.Result defaults = analyze(Analysis.Weights.defaults());
    Analysis.Result cheap = analyze(Analysis.Weights.from(Map.of(
        "analyze.hours.undefined-task", "0", "analyze.hours.per-process", "0")));
    int undefined = defaults.totals().get(Category.UNDEFINED_TASK);
    double expected = defaults.totalHours() - undefined * Category.UNDEFINED_TASK.defaultHours() - 9 * 4;
    assertEquals(expected, cheap.totalHours(), 0.001);
    String md = Analysis.markdown(defaults);
    assertTrue(md.startsWith("# Migration analysis"));
    assertTrue(md.contains("Camunda forms to build for user tasks"));
    assertTrue(Analysis.json(defaults).contains("\"workByKind\""));
  }
}
