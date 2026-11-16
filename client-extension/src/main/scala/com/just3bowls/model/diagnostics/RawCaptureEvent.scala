package com.just3bowls.model.diagnostics

import com.just3bowls.facade.JsBridge

import scala.scalajs.js
final case class RawCaptureEvent(
    seq: Int,
    captureSource: String,
    data: String,
    base64: Boolean,
    done: Boolean,
    error: String,
    timestamp: Double
)
object RawCaptureEvent {
  def toJs(event: RawCaptureEvent): js.Any =
    JsBridge.obj(
      "seq"           -> (event.seq),
      "captureSource" -> (event.captureSource),
      "data"          -> (event.data),
      "base64"        -> (event.base64),
      "done"          -> (event.done),
      "error"         -> (event.error),
      "timestamp"     -> (event.timestamp)
    )
}
