package com.just3bowls.background.automation.provider

import munit.FunSuite

class LocalRawParserShorthandSpec extends FunSuite {
  private val rule =
    """{
      |  "schemaVersion": 1,
      |  "handlers": [
      |    {
      |      "id": "answer",
      |      "match": {
      |        "rawRegex": "data: (\\{.*\\})\n",
      |        "jsonGroup": 1,
      |        "path": "$.type",
      |        "equals": "answer"
      |      },
      |      "content": "$.text"
      |    }
      |  ]
      |}""".stripMargin

  test("executes equals and content without changing append semantics") {
    val parser = new LocalRawParser(rule)
    parser.feed("data: {\"type\":\"answer\",\"text\":\"hello\"}\n")
    parser.finish()

    assertEquals(parser.filteredContent, "hello")
  }

  test("equals does not retain the regex search semantics") {
    val parser = new LocalRawParser(rule)
    parser.feed("data: {\"type\":\"answer-extra\",\"text\":\"wrong\"}\n")
    parser.finish()

    assertEquals(parser.filteredContent, "")
  }

  test("sse-json-line handles split chunks and ignores non-json data") {
    val transportRule =
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
    val parser        = new LocalRawParser(transportRule)

    parser.feed("event: message\ndata: status\n\n")
    parser.feed("data: {\"type\":\"ans")
    parser.feed("wer\",\"text\":\"hello\"}\n\n")
    parser.finish()

    assertEquals(parser.filteredContent, "hello")
  }

  test("phase gates continuation handlers until a start handler opens the phase") {
    val phaseRule =
      """{
        |  "schemaVersion": 1,
        |  "phases": {"inResponse": "true"},
        |  "handlers": [
        |    {
        |      "id": "start",
        |      "phase": "inResponse",
        |      "startsPhase": true,
        |      "match": {"rawRegex": "START (\\{.*\\})\n", "jsonGroup": 1},
        |      "content": "$.text"
        |    },
        |    {
        |      "id": "append",
        |      "phase": "inResponse",
        |      "match": {"rawRegex": "DATA (\\{.*\\})\n", "jsonGroup": 1},
        |      "content": "$.text"
        |    }
        |  ]
        |}""".stripMargin
    val parser    = new LocalRawParser(phaseRule)

    parser.feed("DATA {\"text\":\"ignored\"}\n")
    parser.feed("START {\"text\":\"start\"}\n")
    parser.feed("DATA {\"text\":\"-after\"}\n")
    parser.finish()

    assertEquals(parser.filteredContent, "start-after")
  }

  test("phase start and continuation use the same pre-frame memory snapshot") {
    val phaseRule =
      """{
        |  "schemaVersion": 1,
        |  "phases": {"inResponse": "true"},
        |  "handlers": [
        |    {
        |      "id": "start",
        |      "phase": "inResponse",
        |      "startsPhase": true,
        |      "match": {"rawRegex": "DATA (\\{.*\\})\n", "jsonGroup": 1},
        |      "content": "$.text"
        |    },
        |    {
        |      "id": "append",
        |      "phase": "inResponse",
        |      "match": {"rawRegex": "DATA (\\{.*\\})\n", "jsonGroup": 1},
        |      "content": "$.duplicate"
        |    }
        |  ]
        |}""".stripMargin
    val parser    = new LocalRawParser(phaseRule)

    parser.feed("DATA {\"text\":\"first\",\"duplicate\":\"wrong\"}\n")
    parser.finish()

    assertEquals(parser.filteredContent, "first")
  }

  test("shares raw extraction across handlers with the same matcher") {
    val sharedRule =
      """{
        |  "schemaVersion": 1,
        |  "handlers": [
        |    {
        |      "id": "first",
        |      "match": {"rawRegex": "DATA (\\{.*\\})\n", "jsonGroup": 1},
        |      "content": "$.first"
        |    },
        |    {
        |      "id": "second",
        |      "match": {"rawRegex": "DATA (\\{.*\\})\n", "jsonGroup": 1},
        |      "content": "$.second"
        |    }
        |  ]
        |}""".stripMargin
    val parser     = new LocalRawParser(sharedRule)

    parser.feed("DATA {\"first\":\"a\",\"second\":\"b\"}\n")

    assertEquals(parser.stats.rawScans, 1)
    assertEquals(parser.stats.framesParsed, 1)
    assertEquals(parser.stats.jsonParses, 1)
    assertEquals(parser.stats.handlerFanouts, 2)
  }
}
