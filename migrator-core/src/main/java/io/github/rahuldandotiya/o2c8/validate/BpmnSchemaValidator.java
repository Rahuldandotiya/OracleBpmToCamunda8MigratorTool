package io.github.rahuldandotiya.o2c8.validate;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Validates a model against the official OMG BPMN 2.0 XML schema (bundled, no network access).
 * Zeebe and modeler extensions are allowed by the schema's extension points.
 */
public final class BpmnSchemaValidator {

  private static final String XSD_DIR = "/io/github/rahuldandotiya/o2c8/validate/xsd/";
  private static final int MAX_ISSUES = 20;
  private static volatile Schema schema;

  private BpmnSchemaValidator() {}

  /** Schema violations of a serialized model, each prefixed with "BPMN schema: "; empty when valid. */
  public static List<String> validate(String xml) {
    return run(new javax.xml.transform.stream.StreamSource(new java.io.StringReader(xml)));
  }

  /** Same check on a DOM (serialized first, so namespace prefixes are resolved as in the file). */
  public static List<String> validate(Document doc) {
    return validate(io.github.rahuldandotiya.o2c8.xml.XmlUtils.toString(doc));
  }

  private static List<String> run(javax.xml.transform.Source source) {
    List<String> issues = new ArrayList<>();
    try {
      Validator v = schema().newValidator();
      v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      v.setErrorHandler(new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {}

        @Override
        public void error(SAXParseException e) {
          if (issues.size() < MAX_ISSUES) {
            issues.add("BPMN schema: " + e.getMessage());
          }
        }

        @Override
        public void fatalError(SAXParseException e) {
          error(e);
        }
      });
      v.validate(source);
    } catch (Exception e) {
      if (issues.isEmpty()) {
        issues.add("BPMN schema: validation could not run (" + e.getMessage() + ")");
      }
    }
    return issues;
  }

  private static Schema schema() throws Exception {
    Schema s = schema;
    if (s == null) {
      synchronized (BpmnSchemaValidator.class) {
        if (schema == null) {
          SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
          f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
          f.setResourceResolver(new ClasspathResolver());
          try (InputStream in = BpmnSchemaValidator.class.getResourceAsStream(XSD_DIR + "BPMN20.xsd")) {
            javax.xml.transform.stream.StreamSource src = new javax.xml.transform.stream.StreamSource(in);
            src.setSystemId("BPMN20.xsd");
            schema = f.newSchema(src);
          }
        }
        s = schema;
      }
    }
    return s;
  }

  /** Resolves the schema's imports and includes (BPMNDI.xsd, Semantic.xsd ...) from the classpath only. */
  private static final class ClasspathResolver implements LSResourceResolver {
    @Override
    public LSInput resolveResource(String type, String ns, String publicId, String systemId, String baseUri) {
      if (systemId == null) {
        return null;
      }
      String name = systemId.substring(systemId.lastIndexOf('/') + 1);
      InputStream in = BpmnSchemaValidator.class.getResourceAsStream(XSD_DIR + name);
      if (in == null) {
        throw new IllegalStateException("schema file not bundled: " + name);
      }
      return new Input(in, publicId, name);
    }
  }

  private record Input(InputStream byteStream, String publicId, String systemId) implements LSInput {
    @Override public InputStream getByteStream() { return byteStream; }
    @Override public String getPublicId() { return publicId; }
    @Override public String getSystemId() { return systemId; }
    @Override public Reader getCharacterStream() { return null; }
    @Override public void setCharacterStream(Reader r) {}
    @Override public void setByteStream(InputStream i) {}
    @Override public String getStringData() { return null; }
    @Override public void setStringData(String s) {}
    @Override public void setSystemId(String s) {}
    @Override public void setPublicId(String s) {}
    @Override public String getBaseURI() { return null; }
    @Override public void setBaseURI(String s) {}
    @Override public String getEncoding() { return null; }
    @Override public void setEncoding(String s) {}
    @Override public boolean getCertifiedText() { return false; }
    @Override public void setCertifiedText(boolean b) {}
  }
}
