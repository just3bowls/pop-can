package com.just3bowls.model.provider

import munit.FunSuite

class ParserRuleDefinitionSpec extends FunSuite {
  private def parse(rule: String): ParserRuleDefinition =
    ParserRuleDefinition.parse(rule).fold(error => fail(error), identity)

  test("text match shorthands use literal semantics") {
    assert(TextMatch.Equals("answer").matches("answer"))
    assert(!TextMatch.Equals("answer").matches("answer-extra"))
    assert(TextMatch.Contains("swer").matches("answer"))
    assert(TextMatch.StartsWith("ans").matches("answer"))
    assert(TextMatch.NotStartsWith("[reference:").matches("answer"))
    assert(!TextMatch.NotStartsWith("[reference:").matches("[reference:1]"))
  }

  test("parses text shorthands and content into the runtime representation") {
    val rule = parse(
      """{
        |  "schemaVersion": 1,
        |  "handlers": [
        |    {
        |      "id": "answer",
        |      "match": {"path": "$.type", "equals": "answer"},
        |      "content": "$.text"
        |    },
        |    {
        |      "id": "prefix",
        |      "match": {"path": "$.type", "startsWith": "ans"},
        |      "actions": [{"op": "setFinishReason", "value": "stop"}]
        |    }
        |  ]
        |}""".stripMargin
    )

    assertEquals(rule.handlers.head.matchSpec.textMatch, Some(TextMatch.Equals("answer")))
    assertEquals(
      rule.handlers.head.actions,
      List(RuleAction(RuleActionOp.AppendContent, path = Some("$.text")))
    )
    assertEquals(rule.handlers(1).matchSpec.textMatch, Some(TextMatch.StartsWith("ans")))
  }

  test("rejects conflicting and invalid shorthand fields") {
    val invalid = List(
      """{"schemaVersion":1,"handlers":[{"id":"x","match":{"equals":"x","contains":"x"}}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","match":{"regex":"^x$","equals":"x"}}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","match":{"equals":1}}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","actions":[],"content":"$.text"}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","content":"text"}]}"""
    )

    invalid.foreach { rule =>
      assert(ParserRuleDefinition.parse(rule).isLeft, rule)
    }
  }

  test("normalizes sse-json-line into the existing raw matcher") {
    val rule = parse(
      """{
        |  "schemaVersion": 1,
        |  "handlers": [
        |    {
        |      "id": "answer",
        |      "match": {"transport": "sse-json-line", "path": "$.type", "equals": "answer"},
        |      "content": "$.text"
        |    }
        |  ]
        |}""".stripMargin
    )

    assertEquals(rule.handlers.head.matchSpec.rawRegex, Some("""(?m)^data:\s*(\{.*\})\s*$"""))
    assertEquals(rule.handlers.head.matchSpec.jsonGroup, 1)
  }

  test("rejects invalid transport combinations") {
    val invalid = List(
      """{"schemaVersion":1,"handlers":[{"id":"x","match":{"transport":"unknown"}}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","match":{"transport":"sse-json-line","rawRegex":"x"}}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","match":{"transport":"sse-json-line","jsonGroup":1}}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","match":{"allOf":[{"transport":"sse-json-line"}]}}]}"""
    )

    invalid.foreach { rule =>
      assert(ParserRuleDefinition.parse(rule).isLeft, rule)
    }
  }

  test("normalizes phase starts and continuations into memory gates") {
    val rule = parse(
      """{
        |  "schemaVersion": 1,
        |  "phases": {"inResponse": "true"},
        |  "handlers": [
        |    {
        |      "id": "start",
        |      "phase": "inResponse",
        |      "startsPhase": true,
        |      "match": {"path": "$.type", "equals": "start"},
        |      "content": "$.text"
        |    },
        |    {
        |      "id": "append",
        |      "phase": "inResponse",
        |      "match": {"path": "$.type", "equals": "append"},
        |      "content": "$.text"
        |    }
        |  ]
        |}""".stripMargin
    )

    assertEquals(
      rule.handlers.head.actions,
      List(
        RuleAction(RuleActionOp.SetMemory, value = Some("true"), key = Some("inResponse")),
        RuleAction(RuleActionOp.AppendContent, path = Some("$.text"))
      )
    )
    assertEquals(rule.handlers(1).matchSpec.memoryKey, Some("inResponse"))
    assertEquals(rule.handlers(1).matchSpec.memoryEquals, Some("true"))
  }

  test("rejects invalid phase declarations and handler conflicts") {
    val invalid = List(
      """{"schemaVersion":1,"phases":{"answer":"true"},"handlers":[{"id":"x","phase":"missing"}]}""",
      """{"schemaVersion":1,"handlers":[{"id":"x","startsPhase":true}]}""",
      """{"schemaVersion":1,"phases":{"answer":"true"},"handlers":[{"id":"x","phase":"answer","startsPhase":"yes"}]}""",
      """{"schemaVersion":1,"phases":{"answer":"true"},"handlers":[{"id":"x","phase":"answer","match":{"memoryKey":"answer"}}]}""",
      """{"schemaVersion":1,"phases":{"answer":"true"},"handlers":[{"id":"x","phase":"answer","match":{"allOf":[{"memoryKey":"answer"}]}}]}""",
      """{"schemaVersion":1,"phases":{"answer":"true"},"handlers":[{"id":"x","phase":"answer","startsPhase":true,"actions":[{"op":"setMemory","key":"answer","value":"true"}]}]}""",
      """{"schemaVersion":1,"memory":{"answer":"true"},"phases":{"answer":"true"},"handlers":[{"id":"x"}]}""",
      """{"schemaVersion":1,"phases":{"answer":true},"handlers":[{"id":"x"}]}"""
    )

    invalid.foreach { rule =>
      assert(ParserRuleDefinition.parse(rule).isLeft, rule)
    }
  }

  test("rejects the retired schema v2") {
    assert(ParserRuleDefinition.parse("""{"schemaVersion":2,"handlers":[]}""").isLeft)
    assert(ParserRuleDefinition.parse("""{"schemaVersion":1,"handlers":[]}""").isRight)
  }
}
