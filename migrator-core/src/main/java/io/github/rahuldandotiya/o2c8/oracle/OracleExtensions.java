package io.github.rahuldandotiya.o2c8.oracle;

import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.attr;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.child;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.children;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.descendants;
import static io.github.rahuldandotiya.o2c8.xml.XmlUtils.ownText;

import io.github.rahuldandotiya.o2c8.xml.Ns;
import java.util.Optional;
import org.w3c.dom.Element;

/**
 * Read-only view over the {@code bpmnext:OracleExtensions} block that Oracle BPM Studio (JDeveloper)
 * writes into every element's {@code bpmn:extensionElements}.
 */
public final class OracleExtensions {

  private final Element root; // may be null

  private OracleExtensions(Element root) {
    this.root = root;
  }

  public static OracleExtensions of(Element bpmnElement) {
    Element ext =
        child(bpmnElement, Ns.BPMN, "extensionElements")
            .flatMap(ee -> child(ee, Ns.ORACLE, "OracleExtensions"))
            .orElse(null);
    return new OracleExtensions(ext);
  }

  public boolean present() {
    return root != null;
  }

  /** {@code GraphicsAttributes/Position}; Oracle stores the centre of a node (top-left for sized containers). */
  public Optional<double[]> position() {
    return graphics("Position").map(p -> new double[] {num(p, "x"), num(p, "y")});
  }

  /** {@code GraphicsAttributes/Size}; present on lanes and expanded sub-processes. */
  public Optional<double[]> size() {
    return graphics("Size").map(p -> new double[] {num(p, "width"), num(p, "height")});
  }

  private Optional<Element> graphics(String what) {
    return child(root, Ns.ORACLE, "GraphicsAttributes").flatMap(g -> child(g, Ns.ORACLE, what));
  }

  /** Value of a {@code FeatureSet/StringFeature|BooleanFeature} by name. */
  public Optional<String> feature(String name) {
    return child(root, Ns.ORACLE, "FeatureSet")
        .flatMap(
            fs ->
                children(fs, Ns.ORACLE, null).stream()
                    .filter(f -> name.equals(f.getAttribute("name")))
                    .findFirst())
        .map(f -> f.getAttribute("value"));
  }

  /** Text of a {@code FeatureSet/AttributeExpression[@name]} expression (e.g. priorityExpressionFeature). */
  public Optional<String> attributeExpression(String name) {
    return child(root, Ns.ORACLE, "FeatureSet")
        .flatMap(
            fs ->
                children(fs, Ns.ORACLE, "AttributeExpression").stream()
                    .filter(f -> name.equals(f.getAttribute("name")))
                    .findFirst())
        .flatMap(ae -> child(ae, Ns.ORACLE, "expression"))
        .map(e -> ownText(e));
  }

  /** {@code HumanTask/humanTaskRef} (name + namespace of the Oracle .task file). */
  public Optional<TypeRef> humanTaskRef() {
    return child(root, Ns.ORACLE, "HumanTask")
        .flatMap(h -> child(h, Ns.ORACLE, "humanTaskRef"))
        .map(TypeRef::of);
  }

  /** {@code LaneAttributes/@roleId}. */
  public Optional<String> laneRole() {
    return child(root, Ns.ORACLE, "LaneAttributes").map(l -> attr(l, "roleId"));
  }

  /** Name referenced by a Signal/Error/Message event definition's Oracle block, e.g. SignalEvent/eventRef. */
  public Optional<TypeRef> eventRef() {
    if (root == null) {
      return Optional.empty();
    }
    for (String ref : new String[] {"eventRef", "errorRef", "messageRef"}) {
      var found = descendants(root, Ns.ORACLE, ref);
      if (!found.isEmpty()) {
        return Optional.of(TypeRef.of(found.get(0)));
      }
    }
    return Optional.empty();
  }

  /** {@code Conversational/DefineInterfaceConversationalDefinition/@definedOperationName}, e.g. start / end. */
  public Optional<String> definedInterfaceOperation() {
    return child(root, Ns.ORACLE, "Conversational")
        .flatMap(c -> child(c, Ns.ORACLE, "DefineInterfaceConversationalDefinition"))
        .map(d -> attr(d, "definedOperationName"));
  }

  /** True when the node participates in any Oracle conversation (SOAP/service interface). */
  public boolean conversational() {
    return child(root, Ns.ORACLE, "Conversational").isPresent();
  }

  /** {@code Expression/@mode} inside an expression's own extension (simple | xpath | text | number). */
  public Optional<String> expressionMode() {
    return child(root, Ns.ORACLE, "Expression").map(e -> attr(e, "mode"));
  }

  /** English (or first) description text from {@code Localization/Description}. */
  public Optional<String> description() {
    return child(root, Ns.ORACLE, "Localization")
        .flatMap(l -> child(l, Ns.ORACLE, "Description"))
        .flatMap(d -> child(d, Ns.ORACLE, "LocalizedContent"))
        .map(c -> ownText(c))
        .filter(s -> !s.isBlank());
  }

  /** {@code DataObjectType/TypeRef} or {@code TypeRef} directly under the block. */
  public Optional<TypeRef> typeRef() {
    if (root == null) {
      return Optional.empty();
    }
    var refs = descendants(root, Ns.ORACLE, "TypeRef");
    return refs.isEmpty() ? Optional.empty() : Optional.of(TypeRef.of(refs.get(0)));
  }

  private static double num(Element e, String a) {
    String v = e.getAttribute(a);
    try {
      return v.isBlank() ? 0 : Double.parseDouble(v);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  /** Oracle's typed reference: {@code refType, name, namespace, implementationType}. */
  public record TypeRef(String name, String namespace, String implementationType) {
    static TypeRef of(Element e) {
      return new TypeRef(attr(e, "name"), attr(e, "namespace"), attr(e, "implementationType"));
    }
  }
}
