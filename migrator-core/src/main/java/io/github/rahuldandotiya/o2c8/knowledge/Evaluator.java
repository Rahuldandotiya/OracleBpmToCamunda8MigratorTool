package io.github.rahuldandotiya.o2c8.knowledge;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBaseBuilder.ProcessPair;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Measures how much the knowledge base helps. For every pair: learn from all the other pairs,
 * convert this pair's Oracle process with and without that knowledge, and count the edits still
 * needed to reach the finished model (settings that differ, conditions, messages, missing elements).
 */
public final class Evaluator {

  public record Result(String pair, int editsWithout, int editsWith) {
    public int saved() {
      return editsWithout - editsWith;
    }
  }

  private Evaluator() {}

  public static List<Result> leaveOneOut(DropFolder folder, OracleToCamundaConverter converter) throws IOException {
    List<ProcessPair> pairs = KnowledgeBaseBuilder.pairs(folder);
    KnowledgeBaseBuilder builder = new KnowledgeBaseBuilder(converter);
    List<Result> out = new ArrayList<>();
    for (ProcessPair p : pairs) {
      List<ProcessPair> others = pairs.stream().filter(x -> x != p).toList();
      KnowledgeBase kb = builder.learn(folder, others);
      var composite = folder.compositeFor(p.oracle());
      ConversionResult without = converter.convert(p.oracle(), composite, KnowledgeBase.empty());
      ConversionResult with = converter.convert(p.oracle(), composite, kb);
      Document human = XmlUtils.parse(p.camunda().path());
      out.add(new Result(p.name(),
          edits(without.document(), human, p.processId(), p.camundaProcessId()),
          edits(with.document(), human, p.processId(), p.camundaProcessId())));
    }
    return out;
  }

  /** Number of settings a person would still have to change in {@code draft} to reach {@code human}. */
  public static int edits(Document draft, Document human, String draftProcessId, String humanProcessId) {
    Element dp = process(draft, draftProcessId);
    Element hp = process(human, humanProcessId);
    if (dp == null || hp == null) {
      return 0;
    }
    Map<String, Element> matches = ElementMatcher.match(dp, hp);
    Map<String, String> dMsg = KnowledgeBaseBuilder.messageNames(draft);
    Map<String, String> hMsg = KnowledgeBaseBuilder.messageNames(human);
    Map<String, String> dKey = KnowledgeBaseBuilder.correlationKeys(draft);
    Map<String, String> hKey = KnowledgeBaseBuilder.correlationKeys(human);
    int edits = 0;
    Set<Element> matchedHuman = new HashSet<>(matches.values());
    for (Element d : ElementMatcher.flowElements(dp)) {
      Element h = matches.get(d.getAttribute("id"));
      if (h == null) {
        continue;
      }
      if (ElementMatcher.isActivity(d)) {
        List<String> a = Fragment.of(d).parts();
        List<String> b = Fragment.of(h).parts();
        edits += (int) a.stream().filter(x -> !b.contains(x)).count();
        edits += (int) b.stream().filter(x -> !a.contains(x)).count();
      }
      if (d.getLocalName().equals("sequenceFlow")) {
        String dc = XmlUtils.child(d, Ns.BPMN, "conditionExpression").map(e -> e.getTextContent().trim()).orElse("");
        String hc = XmlUtils.child(h, Ns.BPMN, "conditionExpression").map(e -> e.getTextContent().trim()).orElse("");
        edits += dc.equals(hc) ? 0 : 1;
      }
      String dr = KnowledgeBaseBuilder.messageRef(d);
      String hr = KnowledgeBaseBuilder.messageRef(h);
      if (dr != null && hr != null) {
        edits += String.valueOf(dMsg.get(dr)).equals(String.valueOf(hMsg.get(hr))) ? 0 : 1;
        edits += String.valueOf(dKey.get(dr)).equals(String.valueOf(hKey.get(hr))) ? 0 : 1;
      }
    }
    for (Element h : ElementMatcher.flowElements(hp)) {
      if (!matchedHuman.contains(h) && !h.getLocalName().equals("sequenceFlow")) {
        edits++;
      }
    }
    return edits;
  }

  private static Element process(Document doc, String id) {
    List<Element> ps = children(doc.getDocumentElement(), Ns.BPMN, "process");
    return ps.stream().filter(p -> id.equals(p.getAttribute("id"))).findFirst()
        .orElse(ps.size() == 1 ? ps.get(0) : null);
  }
}
