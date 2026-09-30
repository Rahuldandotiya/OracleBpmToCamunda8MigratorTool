package io.github.rahuldandotiya.o2c8;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;

import io.github.rahuldandotiya.o2c8.convert.ConversionContext;
import io.github.rahuldandotiya.o2c8.convert.ConversionContextAccess;
import io.github.rahuldandotiya.o2c8.convert.ElementConverters;
import io.github.rahuldandotiya.o2c8.convert.ProcessConverter;
import io.github.rahuldandotiya.o2c8.layout.DiagramGenerator;
import io.github.rahuldandotiya.o2c8.report.ConversionReport;
import io.github.rahuldandotiya.o2c8.report.ConversionReport.Level;
import io.github.rahuldandotiya.o2c8.validate.Camunda8ModelValidator;
import io.github.rahuldandotiya.o2c8.xml.Ns;
import io.github.rahuldandotiya.o2c8.xml.XmlUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Entry point: converts one Oracle BPM {@code .bpmn} file into a Camunda 8 BPMN model plus a report.
 *
 * <pre>{@code
 * ConversionResult r = new OracleToCamundaConverter().convert(Path.of("FNOLProcess.bpmn"));
 * Files.writeString(Path.of("out/FNOLProcess.bpmn"), r.bpmnXml());
 * }</pre>
 */
public final class OracleToCamundaConverter {

  public static final String EXPORTER = "Oracle BPM to Camunda 8 Migrator";
  public static final String EXPORTER_VERSION = "0.1.0";

  private final ConverterOptions options;
  private final ElementConverters converters;

  public OracleToCamundaConverter() {
    this(ConverterOptions.defaults());
  }

  public OracleToCamundaConverter(ConverterOptions options) {
    this(options, ElementConverters.load());
  }

  public OracleToCamundaConverter(ConverterOptions options, ElementConverters converters) {
    this.options = options;
    this.converters = converters;
  }

  public ConversionResult convert(Path file) throws IOException {
    return convert(XmlUtils.parse(file), file.getFileName().toString());
  }

  public ConversionResult convert(byte[] content, String sourceName) throws IOException {
    return convert(XmlUtils.parse(content), sourceName);
  }

  public ConversionResult convert(Document source, String sourceName) {
    ConversionReport report = new ConversionReport(sourceName);
    Element srcDefs = source.getDocumentElement();
    if (!Ns.BPMN.equals(srcDefs.getNamespaceURI()) || !"definitions".equals(srcDefs.getLocalName())) {
      throw new IllegalArgumentException(sourceName + " is not a BPMN 2.0 definitions document");
    }
    if (!srcDefs.hasAttributeNS(Ns.XMLNS, "bpmnext") && children(srcDefs, Ns.BPMN, "process").stream()
        .noneMatch(p -> !io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants(p, Ns.ORACLE, null).isEmpty())) {
      report.add(null, sourceName, null, "definitions", Level.INFO,
          "No Oracle extensions found; converting as generic BPMN 2.0.");
    }

    Document target = XmlUtils.newDocument();
    Element defs = target.createElementNS(Ns.BPMN, "bpmn:definitions");
    defs.setAttributeNS(Ns.XMLNS, "xmlns:bpmn", Ns.BPMN);
    defs.setAttributeNS(Ns.XMLNS, "xmlns:bpmndi", Ns.BPMNDI);
    defs.setAttributeNS(Ns.XMLNS, "xmlns:dc", Ns.DC);
    defs.setAttributeNS(Ns.XMLNS, "xmlns:di", Ns.DI);
    defs.setAttributeNS(Ns.XMLNS, "xmlns:xsi", Ns.XSI);
    defs.setAttributeNS(Ns.XMLNS, "xmlns:zeebe", Ns.ZEEBE);
    defs.setAttributeNS(Ns.XMLNS, "xmlns:modeler", Ns.MODELER);
    defs.setAttribute("id", "Definitions_" + baseName(sourceName));
    defs.setAttribute("targetNamespace", "http://bpmn.io/schema/bpmn");
    defs.setAttribute("exporter", EXPORTER);
    defs.setAttribute("exporterVersion", EXPORTER_VERSION);
    defs.setAttributeNS(Ns.MODELER, "modeler:executionPlatform", "Camunda Cloud");
    defs.setAttributeNS(Ns.MODELER, "modeler:executionPlatformVersion", options.executionPlatformVersion());
    target.appendChild(defs);

    ConversionContext ctx = new ConversionContext(target, defs, options, report, converters);
    List<Element> processes = children(srcDefs, Ns.BPMN, "process");
    if (processes.isEmpty()) {
      report.add(null, sourceName, null, "definitions", Level.MANUAL, "No bpmn:process found.");
    }
    Element[] targets = new Element[processes.size()];
    for (int i = 0; i < processes.size(); i++) {
      targets[i] = ProcessConverter.convertProcess(processes.get(i), defs, ctx);
    }
    ConversionContextAccess.writeRootElements(ctx);
    for (int i = 0; i < processes.size(); i++) {
      DiagramGenerator.generate(processes.get(i), targets[i], defs);
    }

    List<String> issues = Camunda8ModelValidator.validate(target);
    issues.forEach(report::addValidationIssue);
    return new ConversionResult(sourceName, target, report);
  }

  static String baseName(String name) {
    String n = name.replaceAll(".*[/\\\\]", "");
    int dot = n.lastIndexOf('.');
    return (dot > 0 ? n.substring(0, dot) : n).replaceAll("[^A-Za-z0-9_]", "_");
  }

  /** Converted model and its report. */
  public record ConversionResult(String sourceName, Document document, ConversionReport report) {
    public String bpmnXml() {
      return XmlUtils.toString(document);
    }
  }
}
