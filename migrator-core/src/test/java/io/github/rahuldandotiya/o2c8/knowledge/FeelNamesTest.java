package io.github.rahuldandotiya.o2c8.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.rahuldandotiya.o2c8.util.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeelNamesTest {

  private static final Map<String, String> R = Map.of("orderDO", "order", "x", "y");

  @Test
  void renamesRootVariablesOnly() {
    assertEquals("=order.lines[1].qty > 2", FeelNames.rename("=orderDO.lines[1].qty > 2", R));
    assertEquals("=a.orderDO", FeelNames.rename("=a.orderDO", R), "after a dot it is a field");
    assertEquals("=\"orderDO\" + string(order)", FeelNames.rename("=\"orderDO\" + string(orderDO)", R));
    assertEquals("={orderDO: order}", FeelNames.rename("={orderDO: orderDO}", R), "context keys stay");
    assertEquals("=x(y)", FeelNames.rename("=x(x)", R), "function names stay");
  }

  @Test
  void renamesMappingTargets() {
    assertEquals("order.status", FeelNames.renameTarget("orderDO.status", R));
    assertEquals("other", FeelNames.renameTarget("other", R));
  }

  @Test
  void findsRoots() {
    assertEquals("claim", FeelNames.rootOf("=claim.FNOL.sensitivity"));
    assertNull(FeelNames.rootOf("=a + b"));
    assertEquals(List.of("a", "b"), List.copyOf(FeelNames.roots("=a.x > 1 and not(b)")));
  }

  @Test
  void oracleSuffixes() {
    assertEquals("INPDO", Keys.oracleSuffix("fNOLProcessINPDO"));
    assertEquals("OUTPD", Keys.oracleSuffix("customerAcceptanceProcessOUTPD"));
    assertNull(Keys.oracleSuffix("claim"));
  }

  @Test
  void xpathKeysIgnoreFormatting() {
    assertEquals(Keys.xpath("bpmn:getDataObject('a')/ns:b != \"x\""), Keys.xpath("getDataObject( 'a' )/ns2:b!='x'"));
  }

  @Test
  void jsonRoundTrip() {
    String text = "{\"a\": [1, 2.5, \"x\\n\\\"y\\\"\", null, true], \"b\": {}}";
    Object parsed = Json.parse(text);
    assertEquals(parsed, Json.parse(Json.write(parsed)));
  }
}
