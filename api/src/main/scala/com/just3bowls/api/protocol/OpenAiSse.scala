package com.just3bowls.api.protocol

/** Canonical Server-Sent Events framing for OpenAI-compatible streaming responses. */
object OpenAiSse {

  /** One SSE event. `payload` is already serialized JSON. */
  def data(payload: String): String = s"data: $payload\n\n"

  /** Terminal marker used by OpenAI-compatible streaming clients. */
  val done: String = data("[DONE]")
}
