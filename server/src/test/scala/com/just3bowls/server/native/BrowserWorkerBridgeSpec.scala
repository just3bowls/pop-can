package com.just3bowls.server.native

import zio.*
import zio.test.*

object BrowserWorkerBridgeSpec extends ZIOSpecDefault {

  private def hooks(seen: Ref[String]): BrowserRuntimeHooks =
    BrowserRuntimeHooks(
      memoryRequest = request => seen.set(request.action),
      memoryExtractionResult = _ => ZIO.unit,
      serverStart = ZIO.unit,
      serverStop = ZIO.unit
    )

  def spec: Spec[Any, Any] = suite("BrowserWorkerBridge")(
    test("dispatches a formatted memory_request by top-level type") {
      val raw =
        """
          {
            "action": "status",
            "requestId": "request-1",
            "payload": "{}",
            "type": "memory_request"
          }
        """
      for {
        seen   <- Ref.make("")
        bridge <- BrowserWorkerBridge.create()
        _      <- bridge.runtimeHooks.set(Some(hooks(seen)))
        _      <- bridge.onMessage(raw)
        action <- seen.get
      } yield assertTrue(action == "status")
    },
    test("does not dispatch a nested type from an unknown top-level message") {
      val raw =
        """
          {
            "type": "unknown",
            "nested": {
              "type": "memory_request",
              "action": "must-not-run",
              "requestId": "request-2",
              "payload": "{}"
            }
          }
        """
      for {
        seen   <- Ref.make("")
        bridge <- BrowserWorkerBridge.create()
        _      <- bridge.runtimeHooks.set(Some(hooks(seen)))
        _      <- bridge.onMessage(raw)
        action <- seen.get
      } yield assertTrue(action.isEmpty)
    },
    test("ignores malformed JSON") {
      for {
        seen   <- Ref.make("")
        bridge <- BrowserWorkerBridge.create()
        _      <- bridge.runtimeHooks.set(Some(hooks(seen)))
        _      <- bridge.onMessage("not-json")
        action <- seen.get
      } yield assertTrue(action.isEmpty)
    }
  )
}
