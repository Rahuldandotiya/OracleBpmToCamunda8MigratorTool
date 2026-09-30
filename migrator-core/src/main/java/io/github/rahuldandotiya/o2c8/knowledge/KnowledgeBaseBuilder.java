package io.github.rahuldandotiya.o2c8.knowledge;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter;
import io.github.rahuldandotiya.o2c8.OracleToCamundaConverter.ConversionResult;
import io.github.rahuldandotiya.o2c8.composite.Composite;
import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase.Kind;
import io.github.rahuldandotiya.o2c8.project.DropFolder;
import io.github.rahuldandotiya.o2c8.project.ProcessFile;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Builds a {@link KnowledgeBase} from a drop folder that holds Oracle processes (or whole Oracle
 * projects) together with the Camunda 8 models someone finished for them.
 *
 * <p>For every pair: convert the Oracle side (the "draft"), match its elements with the finished
 * model, and record every difference as a rule under a key that will recur in other processes.
 */
public final class KnowledgeBaseBuilder {

  /** An Oracle process and the finished Camunda model for it. */
  public record ProcessPair(String name, ProcessFile oracle, ProcessFile camunda, String processId,
      String camundaProcessId, String matchedBy) {}

  private final OracleToCamundaConverter converter;

  public KnowledgeBaseBuilder(OracleToCamundaConverter converter) {
    this.converter = converter;
  }

  /** Pairs Oracle and Camunda processes and learns from every pair. */
  public KnowledgeBase build(DropFolder folder) {
    List<ProcessPair> pairs = pairs(folder);
    KnowledgeBase kb = learn(folder, pairs);
    notesForUnpaired(folder, pairs, kb);
    return kb;
  }

  public KnowledgeBase learn(DropFolder folder, List<ProcessPair> pairs) {
    KnowledgeBase kb = KnowledgeBase.empty();
    Map<String, Set<String>> jobPrefixes = new LinkedHashMap<>();
    Map<String, Set<String>> groupPrefixes = new LinkedHashMap<>();
    Map<String, Map<String, Set<String>>> typeNames = new LinkedHashMap<>(); // type -> newName -> pairs
    for (ProcessPair p : pairs) {
      try {
        learnPair(folder, p, kb, jobPrefixes, groupPrefixes, typeNames);
        kb.addPair(new KnowledgeBase.Pair(p.name(), p.oracle().displayPath(), p.camunda().displayPath(), p.matchedBy()));
      } catch (IOException | RuntimeException e) {
        kb.note("Pair " + p.name() + " skipped: " + e.getMessage());
      }
    }
    // conventions need agreement from two or more pairs
    convention(kb, "jobTypePrefix", jobPrefixes);
    convention(kb, "candidateGroupPrefix", groupPrefixes);
    convention(kb, "formIdPattern", formPatterns);
    formPatterns.clear();
    for (var t : typeNames.entrySet()) {
      for (var n : t.getValue().entrySet()) {
        n.getValue().forEach(pair -> kb.add(Kind.VARIABLE_TYPE, t.getKey(), n.getKey(), pair));
      }
    }
    return kb;
  }

  private static void convention(KnowledgeBase kb, String key, Map<String, Set<String>> votes) {
    for (var e : votes.entrySet()) {
      if (e.getValue().size() >= 2) {
        e.getValue().forEach(pair -> kb.add(Kind.CONVENTION, key, e.getKey(), pair));
      } else {
        kb.note("Convention " + key + " = '" + e.getKey() + "' seen only in " + e.getValue()
            + "; needs a second pair before it is applied.");
      }
    }
  }

  // ------------------------------------------------------------------ pairing

  /** Matches each Oracle process with a finished Camunda 8 process. */
  public static List<ProcessPair> pairs(DropFolder folder) {
    List<ProcessFile> oracle = folder.processes().stream().filter(ProcessFile::convertible).toList();
    List<ProcessFile> camunda = folder.processes(ProcessFile.Kind.CAMUNDA8);
    List<ProcessPair> out = new ArrayList<>();
    Set<String> usedCamunda = new HashSet<>();
    Set<String> pairedOracle = new HashSet<>();
    // round 1: only Camunda files in the same sub-folder; round 2: anywhere in the drop folder
    for (boolean sameGroupOnly : new boolean[] {true, false}) {
      for (ProcessFile o : oracle) {
        for (int i = 0; i < o.processIds().size(); i++) {
          String pid = o.processIds().get(i);
          if (pairedOracle.contains(o.path() + "#" + pid)) {
            continue;
          }
          List<ProcessFile> cands = sameGroupOnly
              ? camunda.stream().filter(c -> c.group().equals(o.group())).toList() : camunda;
          Match m = find(o, pid, o.processNames().get(i), cands, usedCamunda);
          if (m != null) {
            usedCamunda.add(m.file.path() + "#" + m.processId);
            pairedOracle.add(o.path() + "#" + pid);
            String name = (o.group().isEmpty() ? "" : o.group() + "/") + pid;
            out.add(new ProcessPair(name, o, m.file, pid, m.processId, m.how));
          }
        }
      }
    }
    return out;
  }

  private record Match(ProcessFile file, String processId, String how) {}

  private static Match find(ProcessFile o, String pid, String pname, List<ProcessFile> camunda, Set<String> used) {
    List<ProcessFile> sameGroupFirst = new ArrayList<>(camunda.stream().filter(c -> c.group().equals(o.group())).toList());
    camunda.stream().filter(c -> !c.group().equals(o.group())).forEach(sameGroupFirst::add);
    for (ProcessFile c : sameGroupFirst) {
      if (c.processIds().contains(pid) && !used.contains(c.path() + "#" + pid)) {
        return new Match(c, pid, "process id");
      }
    }
    for (ProcessFile c : sameGroupFirst) {
      if (c.baseName().equalsIgnoreCase(o.baseName()) && c.processIds().size() == 1
          && !used.contains(c.path() + "#" + c.processIds().get(0))) {
        return new Match(c, c.processIds().get(0), "file name");
      }
    }
    Set<String> oracleIds = elementIds(o.path());
    Match best = null;
    double bestShare = 0.5;
    for (ProcessFile c : sameGroupFirst) {
      Set<String> ids = elementIds(c.path());
      if (oracleIds.isEmpty() || c.processIds().isEmpty()) {
        continue;
      }
      long common = oracleIds.stream().filter(ids::contains).count();
      double share = (double) common / oracleIds.size();
      if (share >= bestShare && !used.contains(c.path() + "#" + c.processIds().get(0))) {
        bestShare = share;
        best = new Match(c, c.processIds().get(0), "element ids (" + Math.round(share * 100) + "% shared)");
      }
    }
    if (best != null) {
      return best;
    }
    if (pname != null) {
      for (ProcessFile c : sameGroupFirst) {
        for (int i = 0; i < c.processNames().size(); i++) {
          if (pname.equalsIgnoreCase(String.valueOf(c.processNames().get(i)))
              && !used.contains(c.path() + "#" + c.processIds().get(i))) {
            return new Match(c, c.processIds().get(i), "process name");
          }
        }
      }
    }
    return null;
  }

  private static Set<String> elementIds(java.nio.file.Path bpmn) {
    Set<String> ids = new HashSet<>();
    try {
      for (Element p : children(XmlUtils.parse(bpmn).getDocumentElement(), Ns.BPMN, "process")) {
        ElementMatcher.flowElements(p).forEach(e -> ids.add(e.getAttribute("id")));
      }
    } catch (IOException ignored) {
      // unreadable files simply do not match
    }
    return ids;
  }

  private static void notesForUnpaired(DropFolder folder, List<ProcessPair> pairs, KnowledgeBase kb) {
    Set<String> pairedOracle = new HashSet<>();
    Set<String> pairedCamunda = new HashSet<>();
    pairs.forEach(p -> {
      pairedOracle.add(p.oracle().path() + "#" + p.processId());
      pairedCamunda.add(p.camunda().path() + "#" + p.camundaProcessId());
    });
    for (ProcessFile f : folder.processes()) {
      for (String pid : f.processIds()) {
        String k = f.path() + "#" + pid;
        if (f.convertible() && !pairedOracle.contains(k)) {
          kb.note("No finished Camunda model found for Oracle process " + pid + " (" + f.displayPath() + ").");
        } else if (!f.convertible() && !pairedCamunda.contains(k)) {
          kb.note("No Oracle process found for Camunda model " + pid + " (" + f.displayPath() + ").");
        }
      }
    }
    folder.warnings().forEach(kb::note);
  }

  // ------------------------------------------------------------------ learning one pair

  private void learnPair(DropFolder folder, ProcessPair p, KnowledgeBase kb, Map<String, Set<String>> jobPrefixes,
      Map<String, Set<String>> groupPrefixes, Map<String, Map<String, Set<String>>> typeNames) throws IOException {
    Optional<Composite> composite = folder.compositeFor(p.oracle());
    ConversionResult draft = converter.convert(p.oracle(), composite, KnowledgeBase.empty());
    Element draftProcess = process(draft.document(), p.processId());
    Element sourceProcess = process(draft.source(), p.processId());
    Document humanDoc = XmlUtils.parse(p.camunda().path());
    Element humanProcess = process(humanDoc, p.camundaProcessId());
    if (draftProcess == null || sourceProcess == null || humanProcess == null) {
      throw new IOException("process " + p.processId() + " not found in both files");
    }
    ServiceCallResolver resolver = composite.map(c -> new ServiceCallResolver(c,
        c.componentForBpmn(p.oracle().path(), p.processId()).map(Composite.Component::name).orElse(p.processId())))
        .orElse(null);

    Map<String, Element> matches = ElementMatcher.match(draftProcess, humanProcess);
    Map<String, String> draftMessages = messageNames(draft.document());
    Map<String, String> humanMessages = messageNames(humanDoc);
    Map<String, String> humanKeys = correlationKeys(humanDoc);
    Map<String, String> dataObjects = dataObjectTypes(sourceProcess);

    for (Element d : ElementMatcher.flowElements(draftProcess)) {
      Element h = matches.get(d.getAttribute("id"));
      if (h == null) {
        continue;
      }
      String id = d.getAttribute("id");
      String source = p.name() + "#" + id;
      Element src = ElementMatcher.byId(sourceProcess, id);
      if (ElementMatcher.isActivity(d) && src != null) {
        learnActivity(kb, p, d, h, src, source, resolver, jobPrefixes, groupPrefixes);
      }
      if (d.getLocalName().equals("sequenceFlow") && src != null) {
        learnCondition(kb, d, h, src, source);
      }
      learnVariables(kb, d, h, dataObjects, source, typeNames, p.name());
      learnMessage(kb, d, h, draftMessages, humanMessages, humanKeys, source);
    }
  }

  private final Map<String, Set<String>> formPatterns = new LinkedHashMap<>();

  private void learnActivity(KnowledgeBase kb, ProcessPair p, Element d, Element h, Element src, String source,
      ServiceCallResolver resolver, Map<String, Set<String>> jobPrefixes, Map<String, Set<String>> groupPrefixes) {
    Fragment fd = Fragment.of(d);
    Fragment fh = Fragment.of(h);
    if (fh.canonical().equals(fd.canonical())) {
      return; // nobody changed it
    }
    kb.add(Kind.EXACT, p.processId() + "/" + d.getAttribute("id"), fh.toXml(), source);
    boolean user = h.getLocalName().equals("userTask") || d.getLocalName().equals("userTask");
    if (user) {
      Fragment hu = fh.without(z -> !Fragment.HUMAN_KEYS.contains(z.getLocalName()));
      Fragment du = fd.without(z -> !Fragment.HUMAN_KEYS.contains(z.getLocalName()));
      if (!hu.canonical().equals(du.canonical())) {
        kb.add(Kind.HUMAN_TASK, Keys.humanTask(src), hu.toXml(), source);
      }
      Element form = fh.zeebe("formDefinition");
      String formId = form == null ? null : XmlUtils.attr(form, "formId");
      String kebabName = Keys.kebab(d.getAttribute("name"));
      if (formId != null && !kebabName.isEmpty() && formId.contains(kebabName)) {
        int at = formId.indexOf(kebabName);
        formPatterns.computeIfAbsent(formId.substring(0, at) + "{name}" + formId.substring(at + kebabName.length()),
            k -> new HashSet<>()).add(p.name());
      }
      String dg = fd.candidateGroups();
      String hg = fh.candidateGroups();
      if (dg != null && hg != null && !dg.equals(hg) && !dg.contains(",") && !hg.contains(",")) {
        kb.add(Kind.ROLE, dg, hg, source);
        String kebab = Keys.kebab(dg);
        if (hg.endsWith(kebab) && hg.length() > kebab.length()) {
          groupPrefixes.computeIfAbsent(hg.substring(0, hg.length() - kebab.length()), k -> new HashSet<>())
              .add(p.name());
        }
      }
      return;
    }
    if (!implementation(fh).equals(implementation(fd))) {
      for (String key : Keys.call(src, resolver)) {
        kb.add(Kind.CALL, key, fh.toXml(), source);
      }
    }
    // an abstract Oracle task has no job type yet; its name is what a person would have used
    String dt = fd.jobType() != null ? fd.jobType()
        : d.getLocalName().equals("task") ? Keys.kebab(d.getAttribute("name")) : null;
    String ht = fh.jobType();
    if (dt != null && ht != null && !dt.equals(ht) && ht.endsWith(dt) && !ht.startsWith("io.camunda")) {
      jobPrefixes.computeIfAbsent(ht.substring(0, ht.length() - dt.length()), k -> new HashSet<>()).add(p.name());
    }
  }

  /** Comparable text of the implementation part: everything except data mappings (keeps connector config). */
  static String implementation(Fragment f) {
    return f.without(z -> z.getLocalName().equals("ioMapping")).canonical() + "|" + configInputs(f);
  }

  private static String configInputs(Fragment f) {
    Element io = f.zeebe("ioMapping");
    if (io == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (Element in : children(io, Ns.ZEEBE, "input")) {
      if (Fragment.isConfig(in)) {
        parts.add(in.getAttribute("target") + "=" + in.getAttribute("source"));
      }
    }
    parts.sort(null);
    return String.join(",", parts);
  }

  private static void learnCondition(KnowledgeBase kb, Element d, Element h, Element src, String source) {
    String xpath = XmlUtils.child(src, Ns.BPMN, "conditionExpression").map(XmlUtils::ownText).orElse(null);
    String hc = XmlUtils.child(h, Ns.BPMN, "conditionExpression").map(e -> e.getTextContent().trim()).orElse(null);
    String dc = XmlUtils.child(d, Ns.BPMN, "conditionExpression").map(e -> e.getTextContent().trim()).orElse(null);
    if (xpath != null && hc != null && !hc.equals(dc)) {
      kb.add(Kind.EXPRESSION, Keys.xpath(xpath), hc, source);
    }
  }

  /** Compares data mappings entry by entry and records renamed Oracle data objects. */
  private static void learnVariables(KnowledgeBase kb, Element d, Element h, Map<String, String> dataObjects,
      String source, Map<String, Map<String, Set<String>>> typeNames, String pairName) {
    Element dio = zeebe(d, "ioMapping");
    Element hio = zeebe(h, "ioMapping");
    List<String[]> renames = new ArrayList<>();
    if (dio != null && hio != null) {
      align(children(dio, Ns.ZEEBE, "input"), children(hio, Ns.ZEEBE, "input"), "target", "source", renames);
      align(children(dio, Ns.ZEEBE, "output"), children(hio, Ns.ZEEBE, "output"), "source", "target", renames);
    }
    String dc = XmlUtils.child(d, Ns.BPMN, "conditionExpression").map(Element::getTextContent).orElse(null);
    String hc = XmlUtils.child(h, Ns.BPMN, "conditionExpression").map(Element::getTextContent).orElse(null);
    if (dc != null && hc != null) {
      var dr = new ArrayList<>(FeelNames.roots(dc));
      var hr = new ArrayList<>(FeelNames.roots(hc));
      if (dr.size() == 1 && hr.size() == 1) {
        renames.add(new String[] {dr.get(0), hr.get(0)});
      }
    }
    for (String[] r : renames) {
      if (r[0] != null && r[1] != null && !r[0].equals(r[1]) && dataObjects.containsKey(r[0])) {
        kb.add(Kind.VARIABLE, r[0], r[1], source);
        String type = dataObjects.get(r[0]);
        if (type != null) {
          typeNames.computeIfAbsent(type, k -> new LinkedHashMap<>())
              .computeIfAbsent(r[1], k -> new HashSet<>()).add(pairName);
          String suffix = Keys.oracleSuffix(r[0]);
          if (suffix != null) {
            typeNames.computeIfAbsent(type + "|" + suffix, k -> new LinkedHashMap<>())
                .computeIfAbsent(r[1], k -> new HashSet<>()).add(pairName);
          }
        }
      }
    }
  }

  /**
   * Aligns draft and human mapping entries: by the side that is local to the task (input target /
   * output source), falling back to position; then compares the process-variable side.
   */
  private static void align(List<Element> draft, List<Element> human, String localAttr, String varAttr,
      List<String[]> out) {
    for (int i = 0; i < draft.size(); i++) {
      Element de = draft.get(i);
      Element he = human.stream().filter(x -> x.getAttribute(localAttr).equals(de.getAttribute(localAttr)))
          .findFirst().orElse(draft.size() == human.size() ? human.get(i) : null);
      if (he == null) {
        continue;
      }
      String dv = de.getAttribute(varAttr);
      String hv = he.getAttribute(varAttr);
      String droot = varAttr.equals("source") ? FeelNames.rootOf(dv) : root(dv);
      String hroot = varAttr.equals("source") ? FeelNames.rootOf(hv) : root(hv);
      out.add(new String[] {droot, hroot});
    }
  }

  private static String root(String target) {
    return target == null || target.isBlank() ? null : target.split("\\.")[0];
  }

  private static void learnMessage(KnowledgeBase kb, Element d, Element h, Map<String, String> draftMessages,
      Map<String, String> humanMessages, Map<String, String> humanKeys, String source) {
    String dRef = messageRef(d);
    String hRef = messageRef(h);
    if (dRef == null || hRef == null) {
      return;
    }
    String dName = draftMessages.get(dRef);
    String hName = humanMessages.get(hRef);
    String hKey = humanKeys.getOrDefault(hRef, "");
    if (dName != null && hName != null) {
      String value = hName + "|" + hKey;
      if (!value.equals(dName + "|")) {
        kb.add(Kind.MESSAGE, dName, value, source);
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Element process(Document doc, String id) {
    List<Element> ps = children(doc.getDocumentElement(), Ns.BPMN, "process");
    return ps.stream().filter(p -> id.equals(p.getAttribute("id"))).findFirst()
        .orElse(ps.size() == 1 ? ps.get(0) : null);
  }

  private static Map<String, String> dataObjectTypes(Element sourceProcess) {
    Map<String, String> m = new HashMap<>();
    for (Element e : XmlUtils.descendants(sourceProcess, Ns.BPMN, "dataObject")) {
      m.put(e.getAttribute("name"), Keys.dataObjectType(e));
    }
    return m;
  }

  static Map<String, String> messageNames(Document doc) {
    Map<String, String> m = new HashMap<>();
    for (Element msg : children(doc.getDocumentElement(), Ns.BPMN, "message")) {
      m.put(msg.getAttribute("id"), msg.getAttribute("name"));
    }
    return m;
  }

  static Map<String, String> correlationKeys(Document doc) {
    Map<String, String> m = new HashMap<>();
    for (Element msg : children(doc.getDocumentElement(), Ns.BPMN, "message")) {
      XmlUtils.child(msg, Ns.BPMN, "extensionElements").flatMap(x -> XmlUtils.child(x, Ns.ZEEBE, "subscription"))
          .ifPresent(s -> m.put(msg.getAttribute("id"), s.getAttribute("correlationKey")));
    }
    return m;
  }

  static String messageRef(Element e) {
    String r = XmlUtils.attr(e, "messageRef");
    if (r != null) {
      return r;
    }
    return children(e, Ns.BPMN, "messageEventDefinition").stream().map(md -> XmlUtils.attr(md, "messageRef"))
        .filter(java.util.Objects::nonNull).findFirst().orElse(null);
  }

  private static Element zeebe(Element e, String localName) {
    return XmlUtils.child(e, Ns.BPMN, "extensionElements").flatMap(x -> XmlUtils.child(x, Ns.ZEEBE, localName))
        .orElse(null);
  }
}
