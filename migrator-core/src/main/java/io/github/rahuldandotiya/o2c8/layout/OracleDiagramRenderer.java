package io.github.rahuldandotiya.o2c8.layout;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Draws an Oracle BPM process as SVG straight from its {@code bpmnext:GraphicsAttributes} (Oracle
 * exports have no BPMNDiagram), styled after Oracle BPM Studio: lanes as horizontal bands, green
 * start and red end events, typed activities. It shows the model as authored, independent of the
 * conversion, so people can compare it with the Camunda 8 result.
 */
public final class OracleDiagramRenderer {

  private static final double LANE_HEADER = 34;
  private static final String[] LANE_FILL = {"#eaf2fb", "#fdf6e3", "#eef7ee", "#f6edf7"};

  private record Box(double x, double y, double w, double h) {
    double cx() {
      return x + w / 2;
    }

    double cy() {
      return y + h / 2;
    }
  }

  private record Node(Element el, String kind, Box box) {}

  private record Flow(Element el, double ox, double oy) {}

  private final Map<String, Box> boxes = new HashMap<>();
  private final Map<String, String> kinds = new HashMap<>();
  private final List<Node> nodes = new ArrayList<>();
  private final List<Flow> flows = new ArrayList<>();

  private OracleDiagramRenderer() {}

  /** SVG for the first process in an Oracle BPMN document. */
  public static String render(Document oracle) {
    Element proc = children(oracle.getDocumentElement(), Ns.BPMN, "process").stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no bpmn:process in the file"));
    return new OracleDiagramRenderer().draw(proc);
  }

  private String draw(Element proc) {
    place(proc, LANE_HEADER + 10, 0);
    List<double[]> lanes = new ArrayList<>(); // offset, thickness
    List<String> laneNames = new ArrayList<>();
    for (Element ls : children(proc, Ns.BPMN, "laneSet")) {
      for (Element l : children(ls, Ns.BPMN, "lane")) {
        Optional<double[]> pos = position(l);
        Optional<double[]> size = size(l);
        lanes.add(new double[] {pos.map(p -> p[0]).orElse(0d), size.map(s -> s[0]).orElse(300d)});
        laneNames.add(l.getAttribute("name"));
      }
    }
    double maxX = 600;
    double maxY = 200;
    for (Box b : boxes.values()) {
      maxX = Math.max(maxX, b.x() + b.w());
      maxY = Math.max(maxY, b.y() + b.h());
    }
    for (double[] l : lanes) {
      maxY = Math.max(maxY, l[0] + l[1]);
    }
    maxX += 60;
    maxY += 30;
    if (!lanes.isEmpty()) {
      double[] last = lanes.get(lanes.size() - 1);
      last[1] = Math.max(last[1], maxY - 30 - last[0]);
    }

    StringBuilder svg = new StringBuilder();
    svg.append(String.format(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\" "
        + "viewBox=\"0 0 %.0f %.0f\" font-family=\"Helvetica, Arial, sans-serif\">", maxX, maxY, maxX, maxY));
    svg.append("<defs><linearGradient id=\"act\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#ffffff\"/>"
        + "<stop offset=\"1\" stop-color=\"#d7e6f5\"/></linearGradient>"
        + "<marker id=\"arr\" viewBox=\"0 0 10 10\" refX=\"10\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto\">"
        + "<path d=\"M0 0L10 5L0 10z\" fill=\"#33475b\"/></marker>"
        + "<marker id=\"dia\" viewBox=\"0 0 12 12\" refX=\"0\" refY=\"6\" markerWidth=\"10\" markerHeight=\"10\" orient=\"auto\">"
        + "<path d=\"M0 6L6 0L12 6L6 12z\" fill=\"white\" stroke=\"#33475b\"/></marker></defs>"
        + "<rect width=\"100%\" height=\"100%\" fill=\"white\"/>");
    for (int i = 0; i < lanes.size(); i++) {
      double off = lanes.get(i)[0];
      double th = lanes.get(i)[1];
      double tx = LANE_HEADER / 2 + 4;
      double ty = off + th / 2;
      svg.append(String.format(Locale.ROOT, "<rect x=\"0\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"#9fb3c8\"/>"
          + "<rect x=\"0\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"#c9d9ea\" stroke=\"#9fb3c8\"/>"
          + "<text x=\"%.1f\" y=\"%.1f\" font-size=\"11\" font-weight=\"bold\" fill=\"#24476b\" text-anchor=\"middle\" "
          + "transform=\"rotate(-90 %.1f %.1f)\">%s</text>",
          off, maxX, th, LANE_FILL[i % LANE_FILL.length], off, LANE_HEADER, th, tx, ty, tx, ty, esc(laneNames.get(i))));
    }
    StringBuilder containers = new StringBuilder();
    StringBuilder shapes = new StringBuilder();
    for (Node n : nodes) {
      if (n.kind().equals("subProcess")) {
        drawSubProcess(n, containers);
      } else if (n.kind().endsWith("Event")) {
        drawEvent(n, shapes);
      } else if (n.kind().endsWith("Gateway")) {
        drawGateway(n, shapes);
      } else {
        drawActivity(n, shapes);
      }
    }
    StringBuilder edges = new StringBuilder();
    for (Flow f : flows) {
      drawFlow(f, edges);
    }
    return svg.append(containers).append(edges).append(shapes).append("</svg>").toString();
  }

  // ------------------------------------------------------------------ geometry

  private void place(Element container, double ox, double oy) {
    for (Element e : children(container, Ns.BPMN, null)) {
      String k = e.getLocalName();
      if (k.equals("sequenceFlow")) {
        flows.add(new Flow(e, ox, oy));
        continue;
      }
      if (!isNode(k)) {
        continue;
      }
      Optional<double[]> pos = position(e);
      if (pos.isEmpty()) {
        continue;
      }
      double[] p = pos.get();
      Box b;
      if (k.equals("subProcess") || k.equals("transaction") || k.equals("adHocSubProcess")) {
        double[] s = size(e).orElse(new double[] {300, 200});
        b = new Box(ox + p[0], oy + p[1], Math.max(s[0], 120), Math.max(s[1], 80));
        k = "subProcess";
      } else {
        double[] s = k.endsWith("Event") ? new double[] {32, 32} : k.endsWith("Gateway") ? new double[] {44, 44}
            : new double[] {100, 64};
        b = new Box(ox + p[0] - s[0] / 2, oy + p[1] - s[1] / 2, s[0], s[1]);
      }
      String id = e.getAttribute("id");
      boxes.put(id, b);
      kinds.put(id, k);
      nodes.add(new Node(e, k, b));
      if (k.equals("subProcess")) {
        place(e, b.x(), b.y());
      }
    }
  }

  private static boolean isNode(String k) {
    return k.endsWith("Event") || k.endsWith("Gateway") || k.endsWith("Task") || k.equals("task")
        || k.equals("callActivity") || k.equals("subProcess") || k.equals("transaction") || k.equals("adHocSubProcess");
  }

  private static Optional<Element> graphics(Element e) {
    return child(e, Ns.BPMN, "extensionElements").flatMap(x -> child(x, Ns.ORACLE, "OracleExtensions"))
        .flatMap(o -> child(o, Ns.ORACLE, "GraphicsAttributes"));
  }

  private static Optional<double[]> position(Element e) {
    return graphics(e).flatMap(g -> child(g, Ns.ORACLE, "Position"))
        .map(p -> new double[] {num(p.getAttribute("x")), num(p.getAttribute("y"))});
  }

  private static Optional<double[]> size(Element e) {
    return graphics(e).flatMap(g -> child(g, Ns.ORACLE, "Size"))
        .map(p -> new double[] {num(p.getAttribute("width")), num(p.getAttribute("height"))});
  }

  private static double num(String s) {
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static double[] clip(Box b, String kind, double tx, double ty) {
    double cx = b.cx();
    double cy = b.cy();
    double dx = tx - cx;
    double dy = ty - cy;
    if (dx == 0 && dy == 0) {
      return new double[] {cx, cy};
    }
    if (kind.endsWith("Event")) {
      double r = b.w() / 2;
      double d = Math.hypot(dx, dy);
      return new double[] {cx + dx / d * r, cy + dy / d * r};
    }
    if (kind.endsWith("Gateway")) {
      double t = (b.w() / 2) / (Math.abs(dx) + Math.abs(dy));
      return new double[] {cx + dx * t, cy + dy * t};
    }
    double sx = dx != 0 ? (b.w() / 2) / Math.abs(dx) : 1e9;
    double sy = dy != 0 ? (b.h() / 2) / Math.abs(dy) : 1e9;
    double t = Math.min(sx, sy);
    return new double[] {cx + dx * t, cy + dy * t};
  }

  // ------------------------------------------------------------------ shapes

  private void drawSubProcess(Node n, StringBuilder sb) {
    Box b = n.box();
    sb.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"10\" fill=\"#ffffff\" "
        + "fill-opacity=\"0.85\" stroke=\"#3a6ea5\" stroke-width=\"1.5\"/><text x=\"%.1f\" y=\"%.1f\" font-size=\"11\" "
        + "font-weight=\"bold\" fill=\"#24476b\">%s</text>", b.x(), b.y(), b.w(), b.h(), b.x() + 8, b.y() + 15,
        esc(n.el().getAttribute("name"))));
  }

  private void drawEvent(Node n, StringBuilder sb) {
    Box b = n.box();
    double cx = b.cx();
    double cy = b.cy();
    double r = b.w() / 2;
    String fill;
    String stroke;
    double sw;
    switch (n.kind()) {
      case "startEvent" -> {
        fill = "#dff3d8";
        stroke = "#3c8d2f";
        sw = 2;
      }
      case "endEvent" -> {
        fill = "#f9dcdc";
        stroke = "#b8312f";
        sw = 3.5;
      }
      default -> {
        fill = "#fff4d6";
        stroke = "#c88a12";
        sw = 1.5;
      }
    }
    sb.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"/>",
        cx, cy, r, fill, stroke, sw));
    if (n.kind().startsWith("intermediate") || n.kind().equals("boundaryEvent")) {
      sb.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\"/>",
          cx, cy, r - 3, stroke));
    }
    boolean filled = n.kind().equals("endEvent") || n.kind().equals("intermediateThrowEvent");
    for (Element d : children(n.el(), Ns.BPMN, null)) {
      switch (d.getLocalName()) {
        case "messageEventDefinition" -> sb.append(String.format(Locale.ROOT,
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"16\" height=\"11\" fill=\"%s\" stroke=\"#333\"/>"
                + "<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1f\" fill=\"none\" stroke=\"%s\"/>",
            cx - 8, cy - 5, filled ? "#333" : "white", cx - 8, cy - 5, cx, cy + 1, cx + 8, cy - 5,
            filled ? "white" : "#333"));
        case "timerEventDefinition" -> sb.append(String.format(Locale.ROOT,
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"9\" fill=\"white\" stroke=\"#333\"/>"
                + "<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1f\" fill=\"none\" stroke=\"#333\"/>",
            cx, cy, cx, cy - 6, cx, cy, cx + 5, cy + 2));
        case "signalEventDefinition" -> sb.append(String.format(Locale.ROOT,
            "<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fz\" fill=\"%s\" stroke=\"#333\"/>",
            cx, cy - 9, cx + 8, cy + 6, cx - 8, cy + 6, filled ? "#333" : "white"));
        case "terminateEventDefinition" -> sb.append(String.format(Locale.ROOT,
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"8\" fill=\"#333\"/>", cx, cy));
        case "errorEventDefinition" -> sb.append(String.format(Locale.ROOT,
            "<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fz\" fill=\"#333\"/>",
            cx - 7, cy + 7, cx - 3, cy - 7, cx + 2, cy + 1, cx + 7, cy - 7, cx + 3, cy + 7, cx - 2, cy - 1));
        default -> { }
      }
    }
    textBlock(sb, cx, b.y() + b.h() + 14, n.el().getAttribute("name"), 9.5, 18);
  }

  private void drawGateway(Node n, StringBuilder sb) {
    Box b = n.box();
    double cx = b.cx();
    double cy = b.cy();
    sb.append(String.format(Locale.ROOT, "<polygon points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f\" fill=\"#fff8d6\" "
        + "stroke=\"#b08d12\" stroke-width=\"1.5\"/>", cx, b.y(), b.x() + b.w(), cy, cx, b.y() + b.h(), b.x(), cy));
    switch (n.kind()) {
      case "exclusiveGateway" -> sb.append(String.format(Locale.ROOT,
          "<path d=\"M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f\" stroke=\"#333\" stroke-width=\"3\"/>",
          cx - 7, cy - 7, cx + 7, cy + 7, cx + 7, cy - 7, cx - 7, cy + 7));
      case "parallelGateway" -> sb.append(String.format(Locale.ROOT,
          "<path d=\"M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f\" stroke=\"#333\" stroke-width=\"3\"/>",
          cx, cy - 10, cx, cy + 10, cx - 10, cy, cx + 10, cy));
      case "inclusiveGateway" -> sb.append(String.format(Locale.ROOT,
          "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"9\" fill=\"none\" stroke=\"#333\" stroke-width=\"2.5\"/>", cx, cy));
      case "eventBasedGateway" -> sb.append(String.format(Locale.ROOT,
          "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"11\" fill=\"none\" stroke=\"#333\"/>"
              + "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"8\" fill=\"none\" stroke=\"#333\"/>", cx, cy, cx, cy));
      default -> { }
    }
    textBlock(sb, cx, b.y() - 10, n.el().getAttribute("name"), 9.5, 18);
  }

  private void drawActivity(Node n, StringBuilder sb) {
    Box b = n.box();
    String label = switch (n.kind()) {
      case "task" -> "Abstract";
      case "userTask" -> "User";
      case "serviceTask" -> "Service";
      case "scriptTask" -> "Script";
      case "sendTask" -> "Send";
      case "receiveTask" -> "Receive";
      case "businessRuleTask" -> "Rule";
      case "manualTask" -> "Manual";
      case "callActivity" -> "Call";
      default -> n.kind();
    };
    sb.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"9\" fill=\"url(#act)\" "
        + "stroke=\"#3a6ea5\" stroke-width=\"1.5\"/><text x=\"%.1f\" y=\"%.1f\" font-size=\"8\" fill=\"#5b7a99\">%s</text>",
        b.x(), b.y(), b.w(), b.h(), b.x() + 6, b.y() + 12, label));
    textBlock(sb, b.cx(), b.cy() + 4, n.el().getAttribute("name"), 10, 15);
  }

  private void drawFlow(Flow f, StringBuilder sb) {
    String s = f.el().getAttribute("sourceRef");
    String t = f.el().getAttribute("targetRef");
    Box a = boxes.get(s);
    Box b = boxes.get(t);
    if (a == null || b == null) {
      return;
    }
    List<double[]> bends = new ArrayList<>();
    child(f.el(), Ns.BPMN, "extensionElements").flatMap(x -> child(x, Ns.ORACLE, "OracleExtensions"))
        .ifPresent(o -> io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants(o, Ns.ORACLE, "Positions")
            .forEach(p -> bends.add(new double[] {f.ox() + num(p.getAttribute("x")), f.oy() + num(p.getAttribute("y"))})));
    double[] first = bends.isEmpty() ? new double[] {b.cx(), b.cy()} : bends.get(0);
    double[] last = bends.isEmpty() ? new double[] {a.cx(), a.cy()} : bends.get(bends.size() - 1);
    List<double[]> pts = new ArrayList<>();
    pts.add(clip(a, kinds.get(s), first[0], first[1]));
    pts.addAll(bends);
    pts.add(clip(b, kinds.get(t), last[0], last[1]));
    boolean conditional = child(f.el(), Ns.BPMN, "conditionExpression").isPresent();
    StringBuilder p = new StringBuilder();
    for (double[] pt : pts) {
      p.append(String.format(Locale.ROOT, "%.1f,%.1f ", pt[0], pt[1]));
    }
    sb.append("<polyline points=\"").append(p.toString().trim())
        .append("\" fill=\"none\" stroke=\"#33475b\" stroke-width=\"1.4\" marker-end=\"url(#arr)\"")
        .append(conditional ? " marker-start=\"url(#dia)\"" : "").append("/>");
    Element src = nodes.stream().filter(n -> n.el().getAttribute("id").equals(s)).map(Node::el).findFirst().orElse(null);
    if (src != null && f.el().getAttribute("id").equals(src.getAttribute("default")) && pts.size() > 1) {
      double[] p0 = pts.get(0);
      double[] p1 = pts.get(1);
      double d = Math.max(1, Math.hypot(p1[0] - p0[0], p1[1] - p0[1]));
      double mx = p0[0] + (p1[0] - p0[0]) * 10 / d;
      double my = p0[1] + (p1[1] - p0[1]) * 10 / d;
      sb.append(String.format(Locale.ROOT, "<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#33475b\" stroke-width=\"1.5\"/>",
          mx - 5, my + 5, mx + 5, my - 5));
    }
    String name = f.el().getAttribute("name");
    if (!name.isBlank() && !name.startsWith("sf") && !name.startsWith("SequenceFlow")) {
      double[] mid = pts.get(Math.max(0, pts.size() / 2 - (pts.size() % 2 == 0 ? 1 : 0)));
      sb.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"9\" fill=\"#555\">%s</text>",
          mid[0] + 4, mid[1] - 5, esc(name)));
    }
  }

  // ------------------------------------------------------------------ text

  private static void textBlock(StringBuilder sb, double x, double y, String text, double size, int width) {
    List<String> lines = wrap(text, width);
    double y0 = y - (lines.size() - 1) * (size + 1) / 2;
    for (int i = 0; i < lines.size(); i++) {
      sb.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\" fill=\"#1f2d3d\" "
          + "text-anchor=\"middle\">%s</text>", x, y0 + i * (size + 1) + size / 3, size, esc(lines.get(i))));
    }
  }

  private static final Pattern CAPITAL = Pattern.compile("[A-Z]");

  /** Wraps at spaces and underscores; splits long CamelCase words at a capital letter. Max 4 lines. */
  static List<String> wrap(String text, int width) {
    List<String> words = new ArrayList<>();
    for (String w : (text == null ? "" : text).replace("_", "_ ").trim().split("\\s+")) {
      if (w.isEmpty()) {
        continue;
      }
      while (w.length() > width) {
        int cut = width;
        Matcher m = CAPITAL.matcher(w);
        while (m.find()) {
          if (m.start() >= 3 && m.start() <= width) {
            cut = m.start();
          }
        }
        words.add(w.substring(0, cut));
        w = w.substring(cut);
      }
      words.add(w);
    }
    List<String> lines = new ArrayList<>();
    String cur = "";
    for (String w : words) {
      if (!cur.isEmpty() && cur.length() + w.length() + 1 > width) {
        lines.add(cur);
        cur = w;
      } else {
        cur = (cur + " " + w).trim();
      }
    }
    if (!cur.isEmpty()) {
      lines.add(cur);
    }
    List<String> out = new ArrayList<>();
    for (String l : lines.subList(0, Math.min(4, lines.size()))) {
      out.add(l.replace("_ ", "_"));
    }
    return out;
  }

  private static String esc(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
