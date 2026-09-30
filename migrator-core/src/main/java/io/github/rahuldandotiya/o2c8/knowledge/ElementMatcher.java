package io.github.rahuldandotiya.o2c8.knowledge;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * Pairs the elements of the converter's draft with the elements of the finished (human) model:
 * same id first (the converter keeps Oracle ids), then same kind + same name, then — for sequence
 * flows — same matched source and target.
 */
public final class ElementMatcher {

  private static final Set<String> ACTIVITIES = Set.of("task", "userTask", "serviceTask", "sendTask", "receiveTask",
      "scriptTask", "businessRuleTask", "manualTask", "callActivity");

  private ElementMatcher() {}

  /** Result: draft element id → human element. */
  public static Map<String, Element> match(Element draftProcess, Element humanProcess) {
    List<Element> draft = flowElements(draftProcess);
    List<Element> human = flowElements(humanProcess);
    Map<String, Element> humanById = new HashMap<>();
    human.forEach(h -> humanById.put(h.getAttribute("id"), h));

    Map<String, Element> result = new LinkedHashMap<>();
    java.util.Set<Element> used = new java.util.HashSet<>();
    // 1. id
    for (Element d : draft) {
      Element h = humanById.get(d.getAttribute("id"));
      if (h != null && category(d).equals(category(h))) {
        result.put(d.getAttribute("id"), h);
        used.add(h);
      }
    }
    // 2. category + unique name
    Map<String, List<Element>> humanByName = new HashMap<>();
    for (Element h : human) {
      if (!used.contains(h) && !isFlow(h) && XmlUtils.attr(h, "name") != null) {
        humanByName.computeIfAbsent(category(h) + "|" + norm(h.getAttribute("name")), k -> new ArrayList<>()).add(h);
      }
    }
    Map<String, Integer> draftNameCount = new HashMap<>();
    for (Element d : draft) {
      if (!isFlow(d) && XmlUtils.attr(d, "name") != null) {
        draftNameCount.merge(category(d) + "|" + norm(d.getAttribute("name")), 1, Integer::sum);
      }
    }
    for (Element d : draft) {
      if (result.containsKey(d.getAttribute("id")) || isFlow(d) || XmlUtils.attr(d, "name") == null) {
        continue;
      }
      String key = category(d) + "|" + norm(d.getAttribute("name"));
      List<Element> cands = humanByName.getOrDefault(key, List.of());
      if (cands.size() == 1 && draftNameCount.get(key) == 1 && !used.contains(cands.get(0))) {
        result.put(d.getAttribute("id"), cands.get(0));
        used.add(cands.get(0));
      }
    }
    // 3. flows by matched endpoints
    for (Element d : draft) {
      if (!isFlow(d) || result.containsKey(d.getAttribute("id"))) {
        continue;
      }
      Element hs = result.get(d.getAttribute("sourceRef"));
      Element ht = result.get(d.getAttribute("targetRef"));
      if (hs == null || ht == null) {
        continue;
      }
      for (Element h : human) {
        if (isFlow(h) && !used.contains(h) && hs.getAttribute("id").equals(h.getAttribute("sourceRef"))
            && ht.getAttribute("id").equals(h.getAttribute("targetRef"))) {
          result.put(d.getAttribute("id"), h);
          used.add(h);
          break;
        }
      }
    }
    return result;
  }

  /** All flow nodes and sequence flows, including those inside sub-processes. */
  public static List<Element> flowElements(Element container) {
    List<Element> out = new ArrayList<>();
    for (Element e : children(container, Ns.BPMN, null)) {
      String ln = e.getLocalName();
      if (ln.equals("laneSet") || ln.equals("extensionElements") || ln.equals("documentation")
          || ln.equals("dataObject") || ln.equals("dataObjectReference") || ln.equals("textAnnotation")
          || ln.equals("association") || ln.equals("ioSpecification") || ln.equals("property")) {
        continue;
      }
      out.add(e);
      if (ln.equals("subProcess") || ln.equals("transaction") || ln.equals("adHocSubProcess")) {
        out.addAll(flowElements(e));
      }
    }
    return out;
  }

  public static boolean isActivity(Element e) {
    return ACTIVITIES.contains(e.getLocalName());
  }

  static boolean isFlow(Element e) {
    return "sequenceFlow".equals(e.getLocalName());
  }

  /** Activities of any task type count as one category, so a task → serviceTask change still matches. */
  static String category(Element e) {
    return isActivity(e) ? "activity" : e.getLocalName();
  }

  public static String norm(String name) {
    return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  /** Finds a direct or nested element by id. */
  public static Element byId(Element container, String id) {
    for (Element e : flowElements(container)) {
      if (id.equals(e.getAttribute("id"))) {
        return e;
      }
    }
    return null;
  }
}
