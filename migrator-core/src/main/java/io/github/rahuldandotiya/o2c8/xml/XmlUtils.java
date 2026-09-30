package io.github.rahuldandotiya.o2c8.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Small, dependency-free DOM helpers. Parsing is hardened against XXE and entity expansion. */
public final class XmlUtils {

  private XmlUtils() {}

  public static DocumentBuilder secureBuilder() {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      DocumentBuilder b = f.newDocumentBuilder();
      // errors surface as exceptions, not stderr noise
      b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
          throw e;
        }

        @Override
        public void error(org.xml.sax.SAXParseException e) throws SAXException {
          throw e;
        }
      });
      return b;
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("Cannot create secure XML parser", e);
    }
  }

  public static Document parse(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return parse(in);
    }
  }

  public static Document parse(byte[] bytes) throws IOException {
    return parse(new ByteArrayInputStream(bytes));
  }

  public static Document parse(InputStream in) throws IOException {
    try {
      return secureBuilder().parse(in);
    } catch (SAXException e) {
      throw new IOException("Not a well-formed XML document: " + e.getMessage(), e);
    }
  }

  public static Document newDocument() {
    return secureBuilder().newDocument();
  }

  public static String toString(Document doc) {
    try {
      TransformerFactory tf = TransformerFactory.newInstance();
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer t = tf.newTransformer();
      t.setOutputProperty(OutputKeys.INDENT, "yes");
      t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
      doc.setXmlStandalone(true);
      StringWriter w = new StringWriter();
      t.transform(new DOMSource(doc), new StreamResult(w));
      // JDK transformer puts the first element on the declaration line
      return w.toString().replaceFirst("\\?><", "?>\n<");
    } catch (TransformerException e) {
      throw new IllegalStateException("Cannot serialise XML", e);
    }
  }

  /** Direct child elements, optionally filtered by namespace and local name (null = any). */
  public static List<Element> children(Node parent, String ns, String localName) {
    List<Element> out = new ArrayList<>();
    if (parent == null) {
      return out;
    }
    NodeList nl = parent.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
      if (nl.item(i) instanceof Element e && matches(e, ns, localName)) {
        out.add(e);
      }
    }
    return out;
  }

  public static List<Element> children(Node parent) {
    return children(parent, null, null);
  }

  public static Optional<Element> child(Node parent, String ns, String localName) {
    List<Element> c = children(parent, ns, localName);
    return c.isEmpty() ? Optional.empty() : Optional.of(c.get(0));
  }

  /** Follows a path of local names in one namespace, e.g. path(e, BPMN, "extensionElements"). */
  public static Optional<Element> path(Node start, String ns, String... localNames) {
    Node cur = start;
    for (String n : localNames) {
      Optional<Element> next = child(cur, ns, n);
      if (next.isEmpty()) {
        return Optional.empty();
      }
      cur = next.get();
    }
    return Optional.of((Element) cur);
  }

  /** All descendant elements (depth-first) matching ns/localName. */
  public static List<Element> descendants(Node parent, String ns, String localName) {
    List<Element> out = new ArrayList<>();
    collect(parent, ns, localName, out);
    return out;
  }

  private static void collect(Node n, String ns, String ln, List<Element> out) {
    NodeList nl = n.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
      if (nl.item(i) instanceof Element e) {
        if (matches(e, ns, ln)) {
          out.add(e);
        }
        collect(e, ns, ln, out);
      }
    }
  }

  public static boolean matches(Element e, String ns, String localName) {
    return (ns == null || ns.equals(e.getNamespaceURI()))
        && (localName == null || localName.equals(e.getLocalName()));
  }

  public static boolean is(Element e, String localName) {
    return matches(e, Ns.BPMN, localName);
  }

  /** Text of direct text/CDATA children only (Oracle nests extensionElements inside expressions). */
  public static String ownText(Element e) {
    StringBuilder sb = new StringBuilder();
    NodeList nl = e.getChildNodes();
    for (int i = 0; i < nl.getLength(); i++) {
      Node n = nl.item(i);
      if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
        sb.append(n.getNodeValue());
      }
    }
    return sb.toString().trim();
  }

  /** Attribute value or null when absent/empty. */
  public static String attr(Element e, String name) {
    if (e == null || !e.hasAttribute(name)) {
      return null;
    }
    String v = e.getAttribute(name);
    return v.isBlank() ? null : v;
  }
}
