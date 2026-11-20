package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import munit.FunSuite
import scala.scalajs.js

class ParserProviderMetadataSpec extends FunSuite {
  test("restores omitted provider and fingerprint defaults") {
    val doc = JsBridge.obj(
      "schemaVersion" -> (1),
      "page"          -> (JsBridge.obj(
        "url"          -> ("https://example.test/chat"),
        "selectors"    -> (JsBridge.obj("input" -> ("textarea"))),
        "fingerprints" -> (JsBridge.obj(
          "input" -> (JsBridge.obj("tag" -> ("textarea"), "editable" -> (true)))
        ))
      )),
      "network"       -> (JsBridge.obj("apiPatterns" -> (js.Array("https://example.test/api")))),
      "attachment"    -> (JsBridge.obj("strategy" -> ("paste"))),
      "parserRule"    -> (JsBridge.obj("schemaVersion" -> (1), "handlers" -> (js.Array())))
    )

    val provider = ParserProviderLocal.parse("demo", doc)
    val input    = provider.fingerprints("input")

    assertEquals(provider.name, "demo")
    assertEquals(provider.newChatSelector, "")
    assertEquals(provider.attachmentSelector, "")
    assertEquals(input.tag, "textarea")
    assertEquals(input.editable, true)
    assertEquals(input.id, "")
    assertEquals(input.classes, Nil)
    assertEquals(input.nearInput, false)
    assertEquals(input.x, 0.0)
  }
}
