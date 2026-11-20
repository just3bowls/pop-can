package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import munit.FunSuite

class ParserRuleExplainSpec extends FunSuite {
  test("explains the effective phase and transport normalization") {
    val rule =
      """{
        |  "schemaVersion": 1,
        |  "phases": {"inResponse": "true"},
        |  "handlers": [
        |    {
        |      "id": "start",
        |      "phase": "inResponse",
        |      "startsPhase": true,
        |      "match": {"transport": "sse-json-line", "path": "$.type", "equals": "start"},
        |      "content": "$.text"
        |    }
        |  ]
        |}""".stripMargin

    val explained = ParserRuleExplain.explain(rule).fold(error => fail(error), identity)
    val handler   = JsBridge.arrayField(explained, "handlers").head
    val matchJson = JsBridge.field(handler, "match")
    val actions   = JsBridge.arrayField(handler, "actions")

    assertEquals(JsBridge.intField(explained, "schemaVersion"), Some(1))
    assertEquals(JsBridge.stringField(matchJson, "rawRegex"), Some("""(?m)^data:\s*(\{.*\})\s*$"""))
    assertEquals(JsBridge.intField(matchJson, "jsonGroup"), Some(1))
    assertEquals(JsBridge.stringField(JsBridge.field(matchJson, "textMatch"), "op"), Some("equals"))
    assertEquals(JsBridge.stringField(actions.head, "op"), Some("setMemory"))
    assertEquals(JsBridge.stringField(actions(1), "op"), Some("appendContent"))
  }
}
