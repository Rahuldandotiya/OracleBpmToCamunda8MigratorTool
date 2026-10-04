package io.github.rahuldandotiya.o2c8.knowledge;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants;

import io.github.rahuldandotiya.o2c8.composite.ServiceCallResolver;
import io.github.rahuldandotiya.o2c8.connectors.ServiceCallMapper;
import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase.Kind;
import io.github.rahuldandotiya.o2c8.knowledge.KnowledgeBase.Rule;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Source;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

/**
 * Applies knowledge-base rules to a freshly converted process, most specific first:
 * exact element → service call / human task key → convention; then variable renames, roles,
 * conditions and (at definitions level) messages. Rules only fire on exact key matches.
 */
public final class KnowledgeApplier {

  private final KnowledgeBase kb;
  private final ConversionContext ctx;

  public KnowledgeApplier(KnowledgeBase kb, ConversionContext ctx) {
    this.kb = kb;
    this.ctx = ctx;
  }

  /** Applies element, variable, role and condition rules to one converted process. */
  public void applyToProcess(Element sourceProcess, Element targetProcess) {
    if (kb.isEmpty()) {
      return;
    }
    String pid = targetProcess.getAttribute("id");
    ServiceCallResolver resolver = ctx.serviceCalls();
    java.util.Set<String> groupsSetByRule = new java.util.HashSet<>();

    for (Element target : new ArrayList<>(ElementMatcher.flowElements(targetProcess))) {
      if (!ElementMatcher.isActivity(target)) {
        continue;
      }
      String id = target.getAttribute("id");
      Element src = ElementMatcher.byId(sourceProcess, id);
      if (src == null) {
        continue;
      }
      Optional<Rule> exact = kb.rule(Kind.EXACT, pid + "/" + id);
      if (exact.isPresent()) {
        Element e = Fragment.fromXml(exact.get().best().value()).applyTo(target, Fragment.Scope.FULL);
        done(src, id, exact.get(), "same element in pair " + exact.get().origin());
        if (e.getLocalName().equals("userTask")) {
          groupsSetByRule.add(id);
        }
        continue;
      }
      boolean isUser = target.getLocalName().equals("userTask");
      if (isUser) {
        Optional<Rule> ht = kb.rule(Kind.HUMAN_TASK, Keys.humanTask(src));
        if (ht.isPresent()) {
          Fragment f = Fragment.fromXml(ht.get().best().value());
          f.applyTo(target, Fragment.Scope.HUMAN_TASK);
          if (f.candidateGroups() != null) {
            groupsSetByRule.add(id);
          }
          if (f.zeebe("formDefinition") == null) {
            applyFormConvention(src, target);
          }
          if (f.zeebe("formDefinition") != null) {
            done(src, id, ht.get(), "human task " + Keys.humanTask(src).substring(5) + " (pair " + ht.get().origin() + ")");
          } else {
            note(src, ht.get(), "User task settings from pair " + ht.get().origin() + "; form still to be built.");
          }
        }
        if (ht.isEmpty()) {
          applyFormConvention(src, target);
        }
        continue;
      }
      boolean applied = false;
      for (String key : Keys.call(src, resolver)) {
        Optional<Rule> r = kb.rule(Kind.CALL, key);
        if (r.isPresent()) {
          Fragment.fromXml(r.get().best().value()).applyTo(target, Fragment.Scope.IMPLEMENTATION);
          done(src, id, r.get(), describe(key) + " (pair " + r.get().origin() + ")");
          applied = true;
          break;
        }
      }
      if (!applied) {
        applyJobTypeConvention(src, target);
      }
    }
    applyRoles(sourceProcess, targetProcess, groupsSetByRule);
    applyVariableRenames(sourceProcess, targetProcess);
    applyConditions(sourceProcess, targetProcess);
  }

  // ------------------------------------------------------------------ conventions & roles

  private void applyJobTypeConvention(Element src, Element target) {
    Optional<Rule> prefix = kb.rule(Kind.CONVENTION, "jobTypePrefix");
    if (prefix.isEmpty()) {
      return;
    }
    Element td = zeebe(target, "taskDefinition");
    if (td == null) {
      return;
    }
    String type = td.getAttribute("type");
    String p = prefix.get().best().value();
    if (type.startsWith("io.camunda:") || type.startsWith(p) || type.equals(ServiceCallMapper.REST_CONNECTOR_TYPE)) {
      return;
    }
    td.setAttribute("type", p + type);
    ctx.report(src, Level.INFO, "Job type '" + p + type + "' follows the naming convention learned from pairs "
        + prefix.get().origin() + ".", Source.KNOWLEDGE_BASE);
  }

  /** Adds a form reference named by the learned pattern (the form itself still has to be built). */
  private void applyFormConvention(Element src, Element userTask) {
    Optional<Rule> pattern = kb.rule(Kind.CONVENTION, "formIdPattern");
    if (pattern.isEmpty() || zeebe(userTask, "userTask") == null || zeebe(userTask, "formDefinition") != null) {
      return;
    }
    String formId = pattern.get().best().value().replace("{name}", Keys.kebab(userTask.getAttribute("name")));
    Element ext = XmlUtils.child(userTask, Ns.BPMN, "extensionElements").orElseThrow();
    Element fd = userTask.getOwnerDocument().createElementNS(Ns.ZEEBE, "zeebe:formDefinition");
    fd.setAttribute("formId", formId);
    ext.insertBefore(fd, zeebe(userTask, "userTask").getNextSibling());
    ctx.report(src, Level.PARTIAL, "Form id '" + formId + "' follows the naming convention from pairs "
        + pattern.get().origin() + ". Create and deploy a Camunda Form with this id.", Source.KNOWLEDGE_BASE);
  }

  private void applyRoles(Element sourceProcess, Element targetProcess, java.util.Set<String> skip) {
    Optional<Rule> pattern = kb.rule(Kind.CONVENTION, "candidateGroupPrefix");
    for (Element ut : descendants(targetProcess, Ns.BPMN, "userTask")) {
      if (skip.contains(ut.getAttribute("id"))) {
        continue;
      }
      Element a = zeebe(ut, "assignmentDefinition");
      if (a == null || XmlUtils.attr(a, "candidateGroups") == null) {
        continue;
      }
      List<String> out = new ArrayList<>();
      String how = null;
      for (String g : a.getAttribute("candidateGroups").split("\\s*,\\s*")) {
        Optional<Rule> r = kb.rule(Kind.ROLE, g);
        if (r.isPresent()) {
          out.add(r.get().best().value());
          how = "role '" + g + "' mapped as in pair " + r.get().origin();
        } else if (pattern.isPresent()) {
          out.add(pattern.get().best().value() + Keys.kebab(g));
          how = "candidate group naming convention from pairs " + pattern.get().origin();
        } else {
          out.add(g);
        }
      }
      String joined = String.join(",", out);
      if (!joined.equals(a.getAttribute("candidateGroups"))) {
        a.setAttribute("candidateGroups", joined);
        Element src = ElementMatcher.byId(sourceProcess, ut.getAttribute("id"));
        if (src != null) {
          ctx.report(src, Level.INFO, "Candidate groups '" + joined + "': " + how + ".", Source.KNOWLEDGE_BASE);
        }
      }
    }
  }

  // ------------------------------------------------------------------ variables

  private void applyVariableRenames(Element sourceProcess, Element targetProcess) {
    Map<String, String> typeOf = new LinkedHashMap<>();
    for (Element d : ElementMatcher.flowElements(sourceProcess)) {
      if (d.getLocalName().equals("dataObject")) {
        typeOf.put(d.getAttribute("name"), Keys.dataObjectType(d));
      }
    }
    for (Element d : children(sourceProcess, Ns.BPMN, "dataObject")) {
      typeOf.put(d.getAttribute("name"), Keys.dataObjectType(d));
    }
    Map<String, Long> perType = new LinkedHashMap<>();
    typeOf.values().forEach(t -> perType.merge(String.valueOf(t), 1L, Long::sum));

    Map<String, String> renames = new LinkedHashMap<>();
    List<String> notes = new ArrayList<>();
    for (var e : typeOf.entrySet()) {
      Optional<Rule> byName = kb.rule(Kind.VARIABLE, e.getKey());
      if (byName.isPresent()) {
        renames.put(e.getKey(), byName.get().best().value());
        notes.add(e.getKey() + " → " + byName.get().best().value() + " (pair " + byName.get().origin() + ")");
        continue;
      }
      String suffix = Keys.oracleSuffix(e.getKey());
      Optional<Rule> byTypeSuffix = e.getValue() == null || suffix == null ? Optional.empty()
          : kb.rule(Kind.VARIABLE_TYPE, e.getValue() + "|" + suffix);
      if (byTypeSuffix.isPresent()) {
        long same = typeOf.entrySet().stream().filter(x -> e.getValue().equals(x.getValue())
            && suffix.equals(Keys.oracleSuffix(x.getKey()))).count();
        if (same == 1) {
          renames.put(e.getKey(), byTypeSuffix.get().best().value());
          notes.add(e.getKey() + " → " + byTypeSuffix.get().best().value() + " (" + e.getValue() + " data object ending in "
              + suffix + ", pairs " + byTypeSuffix.get().origin() + ")");
          continue;
        }
      }
      Optional<Rule> byType = e.getValue() == null ? Optional.empty() : kb.rule(Kind.VARIABLE_TYPE, e.getValue());
      if (byType.isPresent()) {
        if (perType.get(String.valueOf(e.getValue())) == 1) {
          renames.put(e.getKey(), byType.get().best().value());
          notes.add(e.getKey() + " → " + byType.get().best().value() + " (type " + e.getValue() + ", pairs "
              + byType.get().origin() + ")");
        } else {
          notes.add(e.getKey() + " kept: several data objects of type " + e.getValue() + " (the learned name "
              + byType.get().best().value() + " would merge them)");
        }
      }
    }
    if (renames.isEmpty() && notes.isEmpty()) {
      return;
    }
    for (Element z : descendants(targetProcess, Ns.ZEEBE, null)) {
      switch (z.getLocalName()) {
        case "input", "output" -> {
          z.setAttribute("source", FeelNames.rename(z.getAttribute("source"), renames));
          if (z.getLocalName().equals("output")) {
            z.setAttribute("target", FeelNames.renameTarget(z.getAttribute("target"), renames));
          }
        }
        case "loopCharacteristics" -> {
          for (String a : List.of("inputCollection", "outputCollection")) {
            if (z.hasAttribute(a)) {
              z.setAttribute(a, a.equals("inputCollection") ? FeelNames.rename(z.getAttribute(a), renames)
                  : FeelNames.renameTarget(z.getAttribute(a), renames));
            }
          }
        }
        case "header" -> {
          if (z.getAttribute("value").startsWith("=")) {
            z.setAttribute("value", FeelNames.rename(z.getAttribute("value"), renames));
          }
        }
        default -> { }
      }
    }
    for (Element c : descendants(targetProcess, Ns.BPMN, "conditionExpression")) {
      c.setTextContent(FeelNames.rename(c.getTextContent(), renames));
    }
    ctx.reportObject().add(new io.github.rahuldandotiya.o2c8.report.ConversionReport.Entry(ctx.processId(),
        targetProcess.getAttribute("id"), XmlUtils.attr(targetProcess, "name"), "process", Level.INFO,
        "Variables from the knowledge base: " + String.join("; ", notes) + ".", Source.KNOWLEDGE_BASE));
  }

  // ------------------------------------------------------------------ conditions

  private void applyConditions(Element sourceProcess, Element targetProcess) {
    for (Element flow : descendants(targetProcess, Ns.BPMN, "sequenceFlow")) {
      Element src = ElementMatcher.byId(sourceProcess, flow.getAttribute("id"));
      if (src == null) {
        continue;
      }
      String xpath = XmlUtils.child(src, Ns.BPMN, "conditionExpression").map(XmlUtils::ownText).orElse(null);
      if (xpath == null) {
        continue;
      }
      Optional<Rule> r = kb.rule(Kind.EXPRESSION, Keys.xpath(xpath));
      Element ce = XmlUtils.child(flow, Ns.BPMN, "conditionExpression").orElse(null);
      if (r.isEmpty() || ce == null || ce.getTextContent().equals(r.get().best().value())) {
        continue;
      }
      ce.setTextContent(r.get().best().value());
      done(src, flow.getAttribute("id"), r.get(), "XPath " + xpath + " as written in pair " + r.get().origin());
    }
  }

  // ------------------------------------------------------------------ messages (definitions level)

  /** Renames messages and sets correlation keys; call after messages were written to definitions. */
  public void applyToMessages(Element definitions) {
    for (Element m : children(definitions, Ns.BPMN, "message")) {
      Optional<Rule> r = kb.rule(Kind.MESSAGE, m.getAttribute("name"));
      if (r.isEmpty()) {
        continue;
      }
      String[] v = r.get().best().value().split("\\|", 2);
      String oldName = m.getAttribute("name");
      m.setAttribute("name", v[0]);
      if (v.length > 1 && !v[1].isBlank()) {
        Element ext = XmlUtils.child(m, Ns.BPMN, "extensionElements").orElseGet(() -> {
          Element x = definitions.getOwnerDocument().createElementNS(Ns.BPMN, "bpmn:extensionElements");
          m.appendChild(x);
          return x;
        });
        Element sub = XmlUtils.child(ext, Ns.ZEEBE, "subscription").orElseGet(() -> {
          Element s = definitions.getOwnerDocument().createElementNS(Ns.ZEEBE, "zeebe:subscription");
          ext.appendChild(s);
          return s;
        });
        sub.setAttribute("correlationKey", v[1]);
      }
      String msgId = m.getAttribute("id");
      for (Element user : descendants(definitions, Ns.BPMN, null)) {
        boolean refers = msgId.equals(user.getAttribute("messageRef"))
            && (user.getLocalName().equals("receiveTask") || user.getLocalName().equals("messageEventDefinition"));
        if (refers) {
          Element owner = user.getLocalName().equals("messageEventDefinition") ? (Element) user.getParentNode() : user;
          ctx.reportObject().resolve(owner.getAttribute("id"), "knowledge base (message " + oldName + ")");
          ctx.reportObject().add(new io.github.rahuldandotiya.o2c8.report.ConversionReport.Entry(null,
              owner.getAttribute("id"), XmlUtils.attr(owner, "name"), owner.getLocalName(), Level.AUTO,
              "Message '" + v[0] + "'" + (v.length > 1 && !v[1].isBlank() ? " correlated by " + v[1] : "")
                  + ", as in pair " + r.get().origin() + ".", Source.KNOWLEDGE_BASE));
        }
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  private void done(Element src, String id, Rule rule, String what) {
    ctx.reportObject().resolve(id, "knowledge base");
    ctx.report(src, rule.conflict() ? Level.PARTIAL : Level.AUTO,
        "From the knowledge base: " + what + "."
            + (rule.conflict() ? " Pairs disagree (" + rule.candidates().size() + " variants); the most common was used." : ""),
        Source.KNOWLEDGE_BASE);
  }

  private void note(Element src, Rule rule, String message) {
    ctx.report(src, Level.INFO, message, Source.KNOWLEDGE_BASE);
  }

  private static String describe(String key) {
    if (key.startsWith("ref:")) {
      return "call to " + key.substring(4);
    }
    if (key.startsWith("endpoint:")) {
      return "call to endpoint " + key.substring(9);
    }
    if (key.startsWith("component:")) {
      return "call to component " + key.substring(10);
    }
    return "activity named like '" + key.substring(key.indexOf(':') + 1) + "'";
  }

  private static Element zeebe(Element e, String localName) {
    return XmlUtils.child(e, Ns.BPMN, "extensionElements").flatMap(x -> XmlUtils.child(x, Ns.ZEEBE, localName))
        .orElse(null);
  }
}
