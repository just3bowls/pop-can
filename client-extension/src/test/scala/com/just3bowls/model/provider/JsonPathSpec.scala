package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import munit.FunSuite

import scala.scalajs.js

class JsonPathSpec extends FunSuite {
  private def fixture: js.Any =
    JsBridge.obj(
      "items" -> js.Array(
        JsBridge.obj(
          "kind" -> "other",
          "meta" -> JsBridge.obj(
            "state"  -> "pending",
            "detail" -> JsBridge.obj("text" -> "ignore")
          )
        ),
        JsBridge.obj(
          "kind" -> "target",
          "meta" -> JsBridge.obj(
            "state"  -> "ready",
            "detail" -> JsBridge.obj("text" -> "matched")
          )
        )
      )
    )

  test("filters by a nested field") {
    assertEquals(
      JsonPath.getString(fixture, "$.items[?(@.meta.state=='ready')][0].meta.detail.text"),
      Some("matched")
    )
  }

  test("supports nested not-equal filters") {
    assertEquals(
      JsonPath.getString(fixture, "$.items[?(@.meta.state!='ready')][0].kind"),
      Some("other")
    )
  }

  test("returns none when no nested field matches") {
    assertEquals(JsonPath.get(fixture, "$.items[?(@.meta.state=='missing')][0].kind"), None)
  }

  test("writes through a nested filter followed by an index") {
    val updated = JsonPath.setString(
      fixture,
      "$.items[?(@.meta.state=='ready')][0].meta.detail.text",
      "rewritten"
    )
    assert(updated.nonEmpty)
    assertEquals(
      JsonPath.getString(updated.get, "$.items[?(@.meta.state=='ready')][0].meta.detail.text"),
      Some("rewritten")
    )
    assertEquals(
      JsonPath.getString(updated.get, "$.items[?(@.meta.state=='pending')][0].meta.detail.text"),
      Some("ignore")
    )
  }
}
