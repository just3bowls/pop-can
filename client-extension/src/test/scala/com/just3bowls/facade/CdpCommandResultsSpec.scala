package com.just3bowls.facade

import scala.scalajs.js

class CdpCommandResultsSpec extends munit.FunSuite {
  test("commandResult preserves the protocol result object") {
    val raw    = JsBridge.obj("identifier" -> "script-1")
    val result = CdpCommandResults.commandResult(raw)

    assertEquals(result.asInstanceOf[AddScriptResult].identifier.toOption, Some("script-1"))
  }

  test("evaluateValue unwraps Runtime.evaluate exactly once") {
    val result = JsBridge.obj(
      "result" -> JsBridge.obj("type" -> "string", "value" -> "usable")
    )

    assertEquals(CdpCommandResults.evaluateValue(result).asInstanceOf[String], "usable")
  }

  test("evaluateValue preserves numbers and booleans") {
    val number  = JsBridge.obj(
      "result" -> JsBridge.obj("type" -> "number", "value" -> 3)
    )
    val boolean = JsBridge.obj(
      "result" -> JsBridge.obj("type" -> "boolean", "value" -> true)
    )

    assertEquals(CdpCommandResults.evaluateValue(number).asInstanceOf[Double], 3.0)
    assertEquals(CdpCommandResults.evaluateValue(boolean).asInstanceOf[Boolean], true)
  }

  test("evaluateValue rejects a RemoteObject passed as the command result") {
    val remote = JsBridge.obj("type" -> "string", "value" -> "usable")
    val error  = intercept[IllegalArgumentException](CdpCommandResults.evaluateValue(remote))

    assert(error.getMessage.contains("no RemoteObject"))
  }

  test("evaluateValue preserves a legitimate undefined result") {
    val result = JsBridge.obj(
      "result" -> JsBridge.obj("type" -> "undefined")
    )

    assert(js.isUndefined(CdpCommandResults.evaluateValue(result)))
  }

  test("evaluateValue reports page exceptions with their location") {
    val result = JsBridge.obj(
      "result"           -> JsBridge.obj("type" -> "object"),
      "exceptionDetails" -> JsBridge.obj(
        "text"         -> "Uncaught",
        "lineNumber"   -> 4,
        "columnNumber" -> 7
      )
    )
    val error  = intercept[IllegalArgumentException](CdpCommandResults.evaluateValue(result))

    assert(error.getMessage.contains("Uncaught"))
    assert(error.getMessage.contains("4:7"))
  }

  test("identifier reads the command result directly") {
    val result = JsBridge.obj("identifier" -> "script-2")

    assertEquals(CdpCommandResults.identifier(result), Some("script-2"))
  }
}
