package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import scala.scalajs.js

/** 把已经通过严格 marker 校验的模型结果编码成标准 OpenAI JSON / SSE。 */
object OpenAiTurnEncoder {
  def json(result: TabAiResult, model: String): String               =
    js.JSON.stringify(completion(result, model))
  private def completion(result: TabAiResult, model: String): js.Any = {
    val id           = responseId()
    val message      = JsBridge.obj("role" -> ("assistant"), "content" -> (result.content))
    if (result.thinking.nonEmpty) message.reasoning_content = result.thinking
    if (result.toolCalls.nonEmpty) {
      message.tool_calls = js.Array(result.toolCalls.zipWithIndex.map { case (call, index) =>
        JsBridge.obj(
          "id"       -> (s"${id}_$index"),
          "type"     -> ("function"),
          "function" -> (JsBridge.obj("name" -> (call.name), "arguments" -> (call.arguments)))
        )
      }*)
    }
    JsBridge.obj(
      "id"      -> (id),
      "object"  -> ("chat.completion"),
      "created" -> (js.Date.now() / 1000.0),
      "model"   -> (model),
      "choices" -> (js.Array(JsBridge.obj(
        "index"         -> (0),
        "message"       -> (message),
        "finish_reason" -> (if (result.toolCalls.nonEmpty) "tool_calls" else result.finishReason)
      )))
    )
  }
  private def responseId(): String                                   =
    s"chatcmpl-${Math.round(js.Date.now() * 1000 + Math.random() * 1e6).toLong}"
}
