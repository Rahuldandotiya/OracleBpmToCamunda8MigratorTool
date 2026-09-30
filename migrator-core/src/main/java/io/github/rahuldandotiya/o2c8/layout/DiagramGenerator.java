package io.github.rahuldandotiya.o2c8.layout;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.convert.ProcessConverter;
import io.github.rahuldandotiya.o2c8.convert.ProcessConverter.LaneInfo;
import io.github.rahuldandotiya.o2c8.oracle.OracleExtensions;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Builds the BPMN diagram interchange (DI) section.
 *
 * <p>Oracle BPM exports carry no {@code bpmndi:BPMNDiagram}; node positions live in
 * {@code bpmnext:GraphicsAttributes/Position} (the node centre, in pool coordinates; for sized
 * containers the top-left corner, with children relative to it). Lanes are stacked by
 * {@code Position/@x} with their thickness in {@code Size/@width}. This class turns that into a pool,
 * lanes, shapes and orthogonally routed edges that Camunda Modeler renders directly.
 */
public final class DiagramGenerator {

  private static final double POOL_X = 120;
  private static final double POOL_Y = 80;
  private static final double POOL_LABEL = 30;
  private static final double LANE_PADDING = 20;

  public record Box(double x, double y, double w, double h) {
    double cx() {
      return x + w / 2;
    }

    double cy() {
      return y + h / 2;
    }

    double right() {
      return x + w;
    }

    double bottom() {
      return y + h;
    }

    Box shift(double dx, double dy) {
      return new Box(x + dx, y + dy, w, h);
    }
  }

  private final Document doc;
  private final Map<String, Box> boxes = new LinkedHashMap<>();
  private final Map<String, String> kinds = new HashMap<>();

  private DiagramGenerator(Document doc) {
    this.doc = doc;
  }

  /**
   * Adds a collaboration (when the process has lanes) and the BPMNDiagram to {@code definitions}.
   *
   * @return the collaboration element, or null when no pool was needed
   */
  public static Element generate(Element sourceProcess, Element targetProcess, Element definitions) {
    return new DiagramGenerator(definitions.getOwnerDocument()).run(sourceProcess, targetProcess, definitions);
  }

  private Element run(Element src, Element target, Element definitions) {
    Map<String, Element> sourceById = new HashMap<>();
    index(src, sourceById);

    // 1. raw geometry in Oracle coordinates
    place(target, sourceById, 0, 0);
    placeUnpositioned(target);

    List<LaneInfo> lanes = ProcessConverter.lanes(src);
    double minLeft = boxes.values().stream().mapToDouble(Box::x).min().orElse(0);
    double dx = POOL_X + POOL_LABEL + Math.max(0, LANE_PADDING - minLeft)
        + (lanes.isEmpty() ? 0 : 0);
    double dy = POOL_Y;

    // 2. lanes: grow bands that are too small and push later lanes (and their nodes) down
    List<Box> laneBoxes = new ArrayList<>();
    Map<String, Double> shiftByNode = new HashMap<>();
    if (!lanes.isEmpty()) {
      Map<String, String> laneOf = laneMembership(target, lanes);
      double accum = 0;
      double top = lanes.get(0).offset();
      for (LaneInfo l : lanes) {
        double maxBottom = l.offset();
        double minTop = l.offset() + l.thickness();
        for (var en : laneOf.entrySet()) {
          if (en.getValue().equals(l.id())) {
            Box b = boxes.get(en.getKey());
            maxBottom = Math.max(maxBottom, b.bottom());
            minTop = Math.min(minTop, b.y());
          }
        }
        double pushDown = Math.max(0, l.offset() + LANE_PADDING - minTop);
        double height = Math.max(l.thickness(), maxBottom + pushDown - l.offset() + LANE_PADDING);
        for (var en : laneOf.entrySet()) {
          if (en.getValue().equals(l.id())) {
            shiftByNode.put(en.getKey(), accum + pushDown);
          }
        }
        laneBoxes.add(new Box(0, top + accum, 0, height));
        accum += height - l.thickness();
        top += l.thickness();
      }
      // apply lane shifts (children of containers move with their container)
      Map<String, Box> moved = new LinkedHashMap<>();
      for (var en : boxes.entrySet()) {
        String topLevel = topLevelOwner(en.getKey(), target);
        double s = shiftByNode.getOrDefault(topLevel, 0d);
        moved.put(en.getKey(), en.getValue().shift(0, s));
      }
      boxes.clear();
      boxes.putAll(moved);
    }

    // 3. translate into canvas coordinates
    Map<String, Box> canvas = new LinkedHashMap<>();
    boxes.forEach((k, b) -> canvas.put(k, b.shift(dx, dy)));
    boxes.clear();
    boxes.putAll(canvas);

    double maxRight = boxes.values().stream().mapToDouble(Box::right).max().orElse(POOL_X + 400);
    double poolW = Math.max(600, maxRight - POOL_X + 60);

    // 4. write DI
    Element collaboration = null;
    String planeElement = target.getAttribute("id");
    Element diagram = di("bpmndi:BPMNDiagram", Ns.BPMNDI);
    diagram.setAttribute("id", "BPMNDiagram_" + target.getAttribute("id"));
    Element plane = di("bpmndi:BPMNPlane", Ns.BPMNDI);
    plane.setAttribute("id", "BPMNPlane_" + target.getAttribute("id"));
    diagram.appendChild(plane);

    if (!lanes.isEmpty()) {
      collaboration = doc.createElementNS(Ns.BPMN, "bpmn:collaboration");
      collaboration.setAttribute("id", "Collaboration_" + target.getAttribute("id"));
      Element participant = doc.createElementNS(Ns.BPMN, "bpmn:participant");
      String pid = "Participant_" + target.getAttribute("id");
      participant.setAttribute("id", pid);
      if (target.hasAttribute("name")) {
        participant.setAttribute("name", target.getAttribute("name"));
      }
      participant.setAttribute("processRef", target.getAttribute("id"));
      collaboration.appendChild(participant);
      definitions.insertBefore(collaboration, target);
      planeElement = collaboration.getAttribute("id");

      double poolTop = laneBoxes.get(0).y() + dy;
      double poolBottom = laneBoxes.get(laneBoxes.size() - 1).bottom() + dy;
      plane.appendChild(shape(pid, new Box(POOL_X, poolTop, poolW, poolBottom - poolTop), true, null));
      for (int i = 0; i < lanes.size(); i++) {
        Box lb = laneBoxes.get(i);
        plane.appendChild(shape(lanes.get(i).id(),
            new Box(POOL_X + POOL_LABEL, lb.y() + dy, poolW - POOL_LABEL, lb.h()), true, null));
      }
    }
    plane.setAttribute("bpmnElement", planeElement);

    writeShapes(target, plane);
    writeEdges(target, plane);
    definitions.appendChild(diagram);
    return collaboration;
  }

  // ------------------------------------------------------------------ geometry

  private void index(Element container, Map<String, Element> out) {
    for (Element e : children(container, Ns.BPMN, null)) {
      if (e.hasAttribute("id")) {
        out.put(e.getAttribute("id"), e);
      }
      if (e.getLocalName().equals("subProcess") || e.getLocalName().equals("transaction")
          || e.getLocalName().equals("adHocSubProcess")) {
        index(e, out);
      }
    }
  }

  private void place(Element targetContainer, Map<String, Element> sourceById, double ox, double oy) {
    for (Element n : children(targetContainer, Ns.BPMN, null)) {
      String kind = n.getLocalName();
      if (!isNode(kind)) {
        continue;
      }
      String id = n.getAttribute("id");
      kinds.put(id, kind);
      Element src = sourceById.get(id);
      OracleExtensions ox2 = src == null ? null : OracleExtensions.of(src);
      Optional<double[]> pos = ox2 == null ? Optional.empty() : ox2.position();
      if (pos.isEmpty()) {
        continue; // placed later
      }
      double[] p = pos.get();
      if (isContainer(kind)) {
        double[] size = ox2.size().orElse(new double[] {350, 200});
        Box b = new Box(ox + p[0], oy + p[1], Math.max(size[0], 120), Math.max(size[1], 80));
        boxes.put(id, b);
        place(n, sourceById, b.x(), b.y());
      } else {
        double[] s = size(kind);
        boxes.put(id, new Box(ox + p[0] - s[0] / 2, oy + p[1] - s[1] / 2, s[0], s[1]));
      }
    }
  }

  /** Boundary events sit on their host's bottom edge; other nodes without a position go in a row. */
  private void placeUnpositioned(Element container) {
    double nextX = boxes.values().stream().mapToDouble(Box::right).max().orElse(0) + 60;
    Map<String, Integer> perHost = new HashMap<>();
    for (Element n : allNodes(container)) {
      String id = n.getAttribute("id");
      if (boxes.containsKey(id)) {
        continue;
      }
      double[] s = size(n.getLocalName());
      Box host = boxes.get(n.getAttribute("attachedToRef"));
      if (host != null) {
        int k = perHost.merge(n.getAttribute("attachedToRef"), 1, Integer::sum) - 1;
        boxes.put(id, new Box(host.right() - 20 - s[0] - k * 44, host.bottom() - s[1] / 2, s[0], s[1]));
      } else {
        boxes.put(id, new Box(nextX, 100 - s[1] / 2, s[0], s[1]));
        nextX += s[0] + 60;
      }
    }
  }

  private List<Element> allNodes(Element container) {
    List<Element> out = new ArrayList<>();
    for (Element n : children(container, Ns.BPMN, null)) {
      if (isNode(n.getLocalName())) {
        out.add(n);
        if (isContainer(n.getLocalName())) {
          out.addAll(allNodes(n));
        }
      }
    }
    return out;
  }

  private Map<String, String> laneMembership(Element target, List<LaneInfo> lanes) {
    Map<String, String> laneOf = new LinkedHashMap<>();
    for (Element n : children(target, Ns.BPMN, null)) {
      Box b = boxes.get(n.getAttribute("id"));
      if (b != null && isNode(n.getLocalName())) {
        double y = isContainer(n.getLocalName()) ? b.y() + 1 : b.cy();
        laneOf.put(n.getAttribute("id"), ProcessConverter.laneAt(lanes, y).id());
      }
    }
    return laneOf;
  }

  private String topLevelOwner(String id, Element target) {
    for (Element n : children(target, Ns.BPMN, null)) {
      if (id.equals(n.getAttribute("id"))) {
        return id;
      }
      if (isContainer(n.getLocalName())) {
        for (Element c : allNodes(n)) {
          if (id.equals(c.getAttribute("id"))) {
            return n.getAttribute("id");
          }
        }
      }
    }
    return id;
  }

  static double[] size(String kind) {
    if (kind.endsWith("Event")) {
      return new double[] {36, 36};
    }
    if (kind.endsWith("Gateway")) {
      return new double[] {50, 50};
    }
    return new double[] {100, 80};
  }

  private static boolean isNode(String kind) {
    return kind.endsWith("Event") || kind.endsWith("Gateway") || kind.endsWith("Task")
        || kind.equals("task") || kind.equals("callActivity") || isContainer(kind);
  }

  private static boolean isContainer(String kind) {
    return kind.equals("subProcess") || kind.equals("transaction") || kind.equals("adHocSubProcess");
  }

  // ------------------------------------------------------------------ DI output

  private void writeShapes(Element container, Element plane) {
    for (Element n : children(container, Ns.BPMN, null)) {
      Box b = boxes.get(n.getAttribute("id"));
      if (b == null || !isNode(n.getLocalName())) {
        continue;
      }
      Boolean expanded = isContainer(n.getLocalName()) ? Boolean.TRUE : null;
      plane.appendChild(shape(n.getAttribute("id"), b, false, expanded));
      if (isContainer(n.getLocalName())) {
        writeShapes(n, plane);
      }
    }
  }

  private void writeEdges(Element container, Element plane) {
    for (Element n : children(container, Ns.BPMN, null)) {
      if (n.getLocalName().equals("sequenceFlow")) {
        Box s = boxes.get(n.getAttribute("sourceRef"));
        Box t = boxes.get(n.getAttribute("targetRef"));
        if (s == null || t == null) {
          continue;
        }
        Element edge = di("bpmndi:BPMNEdge", Ns.BPMNDI);
        edge.setAttribute("id", n.getAttribute("id") + "_di");
        edge.setAttribute("bpmnElement", n.getAttribute("id"));
        for (double[] p : route(s, t, kinds.get(n.getAttribute("sourceRef")), kinds.get(n.getAttribute("targetRef")))) {
          Element wp = di("di:waypoint", Ns.DI);
          wp.setAttribute("x", fmt(p[0]));
          wp.setAttribute("y", fmt(p[1]));
          edge.appendChild(wp);
        }
        plane.appendChild(edge);
      } else if (isContainer(n.getLocalName())) {
        writeEdges(n, plane);
      }
    }
  }

  /** Orthogonal routing between two boxes. */
  static List<double[]> route(Box s, Box t, String sKind, String tKind) {
    List<double[]> pts = new ArrayList<>();
    boolean sGateway = sKind != null && sKind.endsWith("Gateway");
    boolean tGateway = tKind != null && tKind.endsWith("Gateway");
    if (t.x() >= s.right() - 1) {
      // forward flow
      if (s.cy() >= t.y() + 4 && s.cy() <= t.bottom() - 4) {
        pts.add(new double[] {s.right(), s.cy()});
        pts.add(new double[] {t.x(), s.cy()});
      } else if (t.cy() >= s.y() + 4 && t.cy() <= s.bottom() - 4) {
        pts.add(new double[] {s.right(), t.cy()});
        pts.add(new double[] {t.x(), t.cy()});
      } else if (sGateway || (!tGateway && Math.abs(t.cx() - s.cx()) < 1)) {
        // leave a gateway through top/bottom, then go right
        double y0 = t.cy() < s.cy() ? s.y() : s.bottom();
        pts.add(new double[] {s.cx(), y0});
        pts.add(new double[] {s.cx(), t.cy()});
        pts.add(new double[] {t.x(), t.cy()});
      } else if (tGateway) {
        // go right, then enter a merging gateway through top/bottom
        double y1 = s.cy() < t.cy() ? t.y() : t.bottom();
        pts.add(new double[] {s.right(), s.cy()});
        pts.add(new double[] {t.cx(), s.cy()});
        pts.add(new double[] {t.cx(), y1});
      } else {
        double midX = (s.right() + t.x()) / 2;
        pts.add(new double[] {s.right(), s.cy()});
        pts.add(new double[] {midX, s.cy()});
        pts.add(new double[] {midX, t.cy()});
        pts.add(new double[] {t.x(), t.cy()});
      }
    } else if (t.y() >= s.bottom()) {
      // target below (overlapping horizontally)
      pts.add(new double[] {s.cx(), s.bottom()});
      pts.add(new double[] {s.cx(), (s.bottom() + t.y()) / 2});
      pts.add(new double[] {t.cx(), (s.bottom() + t.y()) / 2});
      pts.add(new double[] {t.cx(), t.y()});
    } else if (t.bottom() <= s.y()) {
      pts.add(new double[] {s.cx(), s.y()});
      pts.add(new double[] {s.cx(), (t.bottom() + s.y()) / 2});
      pts.add(new double[] {t.cx(), (t.bottom() + s.y()) / 2});
      pts.add(new double[] {t.cx(), t.bottom()});
    } else {
      // backward loop: go under both shapes
      double low = Math.max(s.bottom(), t.bottom()) + 40;
      pts.add(new double[] {s.cx(), s.bottom()});
      pts.add(new double[] {s.cx(), low});
      pts.add(new double[] {t.cx(), low});
      pts.add(new double[] {t.cx(), t.bottom()});
    }
    return pts;
  }

  private Element shape(String id, Box b, boolean horizontal, Boolean expanded) {
    Element s = di("bpmndi:BPMNShape", Ns.BPMNDI);
    s.setAttribute("id", id + "_di");
    s.setAttribute("bpmnElement", id);
    if (horizontal) {
      s.setAttribute("isHorizontal", "true");
    }
    if (expanded != null) {
      s.setAttribute("isExpanded", expanded.toString());
    }
    Element bounds = di("dc:Bounds", Ns.DC);
    bounds.setAttribute("x", fmt(b.x()));
    bounds.setAttribute("y", fmt(b.y()));
    bounds.setAttribute("width", fmt(b.w()));
    bounds.setAttribute("height", fmt(b.h()));
    s.appendChild(bounds);
    return s;
  }

  private Element di(String qname, String ns) {
    return doc.createElementNS(ns, qname);
  }

  private static String fmt(double v) {
    return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
  }
}
