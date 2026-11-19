package com.just3bowls.server.native

import zio.*
import zio.test.*
import zio.json.*
import zio.json.ast.Json

import com.just3bowls.api.protocol.ClientOperation

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
    },
    test("emits typed operation and always sends chat body through chunks") {
      val body = """{"model":"pop-can"}"""
      for {
        bridge   <- BrowserWorkerBridge.create()
        outFiber <- bridge.outbound.take(2).runCollect.fork
        _        <- bridge.submitClientRequest(
          ClientOperation.ChatCompletion,
          "Bearer test-secret",
          body
        )
        messages <- outFiber.join
        headJson <- ZIO.fromEither(messages.head.fromJson[Json])
        chunk    <- ZIO.fromEither(messages(1).fromJson[WorkerClientRequestChunk])
      } yield {
        val fields = headJson match {
          case Json.Obj(values) => values.toMap
          case _                => Map.empty[String, Json]
        }
        val keys   = fields.keySet
        assertTrue(
          fields("operation") == Json.Str(ClientOperation.ChatCompletion.wire),
          fields("authorization") == Json.Str("Bearer test-secret"),
          fields("bodyFrames") == Json.Num(1),
          !keys.contains("method"),
          !keys.contains("path"),
          !keys.contains("headers"),
          !keys.contains("body"),
          chunk.seq == 0,
          chunk.data == body
        )
      }
    }
  )
}
