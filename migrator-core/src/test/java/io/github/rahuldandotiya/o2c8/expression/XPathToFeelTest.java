package io.github.rahuldandotiya.o2c8.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class XPathToFeelTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "bpmn:getDataObject('claim')                                       | claim",
      "bpmn:getDataInput('claim')                                        | claim",
      "bpmn:getDataOutput('outcome')                                     | outcome",
      "(bpmn:getDataObject('outcomeDO') != 'REJECT')                     | (outcomeDO != \"REJECT\")",
      "bpmn:getDataObject('req')/ns:FNOL/ns:sensitivity != 'Expert'      | req.FNOL.sensitivity != \"Expert\"",
      "bpmn:getDataObject('in')                                          | `in`",
      "bpmn:getDataObject('order')/ns:lines[2]/ns:qty                    | order.lines[2].qty",
      "bpmn:getDataObject('order')/ns:lines[ns:status = 'open']          | order.lines[status = \"open\"]",
      "bpmn:getDataObject('c')/@id                                       | c.id",
      "bpmn:getDataObject('c')/ns:name/text()                            | c.name",
      "bpmn:getDataObject('a') > 10 and bpmn:getDataObject('b') <= 5     | a > 10 and b <= 5",
      "bpmn:getDataObject('a') = 1 or not(bpmn:getDataObject('b'))       | a = 1 or not(b)",
      "bpmn:getDataObject('amount') * 2 div 4                            | amount * 2 / 4",
      "bpmn:getDataObject('n') mod 2 = 0                                 | modulo(n, 2) = 0",
      "string-length(bpmn:getDataObject('s')) > 0                        | string length(s) > 0",
      "starts-with(bpmn:getDataObject('s'), 'AB')                        | starts with(s, \"AB\")",
      "concat('Claim ', bpmn:getDataObject('id'))                        | (string(\"Claim \") + string(id))",
      "count(bpmn:getDataObject('o')/ns:line)                            | count(o.line)",
      "xp20:current-dateTime()                                           | now()",
      "true()                                                            | true",
      "'FNOLUserTask'                                                    | \"FNOLUserTask\"",
      "2                                                                 | 2",
      "$inputVariable.payload/ns:amount                                  | inputVariable.payload.amount",
      "bpmn:getDataObject('claim')/ns:policy-number                      | claim.`policy-number`",
  })
  void translates(String xpath, String feel) {
    XPathToFeel.Result r = XPathToFeel.translate(xpath);
    assertTrue(r.ok(), () -> "failed: " + r.problem());
    assertEquals(feel, r.feel());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "ora:getInstanceId()                          | vendor function",
      "bpmn:getDataObject('a')//ns:b                | descendant",
      "/ns:root/ns:child                            | absolute",
      "bpmn:getDataObject('a')/ancestor::ns:b       | axes",
      "'unterminated                                | unterminated",
  })
  void rejectsWithReason(String xpath, String reasonFragment) {
    XPathToFeel.Result r = XPathToFeel.translate(xpath);
    assertFalse(r.ok());
    assertTrue(r.problem().contains(reasonFragment), r.problem());
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
      "bpmn:getDataObject('claim')                  | claim",
      "bpmn:getDataObject('claim')/ns:status        | claim.status",
  })
  void translatesTargets(String xpath, String target) {
    assertEquals(target, XPathToFeel.translateTarget(xpath).feel());
  }

  @org.junit.jupiter.api.Test
  void targetMustBeAVariablePath() {
    assertFalse(XPathToFeel.translateTarget("concat('a', 'b')").ok());
  }
}
