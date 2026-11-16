package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge
import com.just3bowls.api.protocol.OpenAiSse
import scala.collection.mutable
import scala.scalajs.js

/**
 * Stateful OpenAI-compatible SSE encoder for one Server request.
 *
 * Model runtime events are encoded as they arrive. The final result only supplies tool calls and finish reasons;
 * content, reasoning and the assistant role are not sent twice.
 */
final class OpenAiStreamEncoder(taskId: String, model: String) {
  private val id                                                   = s"chatcmpl-${taskId.replace("-", "").take(24)}"
  private val created                                              = js.Date.now() / 1000.0
  private var roleSent                                             = false
  private var reasoningSent                                        = false
  private var contentSent                                          = false
  private var finished                                             = false
  def onEvent(event: TabAiEvent): List[String]                     =
    event match {
      case TabAiEvent.Thinking(delta) if delta.nonEmpty =>
        role() ::: List(reasoning(delta))
      case TabAiEvent.Content(delta) if delta.nonEmpty  =>
        role() ::: List(content(delta))
      case _                                            =>
        Nil
    }
  def finish(result: TabAiResult): List[String]                    =
    if (finished) Nil
    else {
      finished = true
      val out    = mutable.ListBuffer.empty[String]
      out ++= role()
      if (!reasoningSent && result.thinking.nonEmpty) out += reasoning(result.thinking)
      if (!contentSent && result.toolCalls.isEmpty && result.content.nonEmpty) out += content(result.content)
      result.toolCalls.zipWithIndex.foreach { case (call, index) =>
        out += chunk(toolCallDelta(call, index), None)
      }
      val reason = if (result.toolCalls.nonEmpty) "tool_calls"
      else Option(result.finishReason).filter(_.nonEmpty).getOrElse("stop")
      out += chunk(JsBridge.obj(), Some(reason))
      out += OpenAiSse.done
      out.toList
    }
  private def role(): List[String]                                 =
    if (roleSent) Nil
    else {
      roleSent = true
      List(chunk(JsBridge.obj("role" -> ("assistant")), None))
    }
  private def reasoning(delta: String): String                     = {
    reasoningSent = true
    chunk(JsBridge.obj("reasoning_content" -> (delta)), None)
  }
  private def content(delta: String): String                       = {
    contentSent = true
    chunk(JsBridge.obj("content" -> (delta)), None)
  }
  private def toolCallDelta(call: TabToolCall, index: Int): js.Any =
    JsBridge.obj("tool_calls" -> (js.Array(
      JsBridge.obj(
        "index"    -> (index),
        "id"       -> (s"${id}_$index"),
        "type"     -> ("function"),
        "function" -> (JsBridge.obj("name" -> (call.name), "arguments" -> (call.arguments)))
      )
    )))
  private def chunk(delta: js.Any, finishReason: Option[String]): String = {
    val choice  = JsBridge.obj("index" -> (0), "delta" -> (delta))
    finishReason.foreach(reason => JsBridge.update(choice, "finish_reason", JsBridge.asAny(reason)))
    val payload = js.JSON.stringify(JsBridge.obj(
      "id"      -> (id),
      "object"  -> ("chat.completion.chunk"),
      "created" -> (created),
      "model"   -> (model),
      "choices" -> (js.Array(choice))
    ))
    OpenAiSse.data(payload)
  }
}
