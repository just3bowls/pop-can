package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.diagnostics.*

import com.just3bowls.api.marker.MarkerSettlement
import com.just3bowls.api.protocol.OpenAiSse
import scala.collection.mutable
import scala.scalajs.js

/** 插件端 marker 解析与 OpenAI 最终协议产物。独立实现，server 只转发。 */
final class FinalSseProducer(taskId: String, model: String) {
  private val log = PopCanLog.withContext("markers", "gen" -> taskId)
  private type ToolCall = MarkerSettlement.ToolCall
  private val raw            = new StringBuilder
  private var content        = ""
  private var reasoning      = ""
  private var failed         = false
  private var failure        = ""
  private var protocolErrors = List.empty[String]
  private var done           = false

  /** tool_calls 是否已经发过（feed 与 terminalSse 都可能负责发，避免发两遍）。 */
  private var toolCallsSent = false

  /** 这次失败是不是 marker 协议校验失败（区分传输错误，供上层决定要不要自动重试）。 */
  private var markerFailed            = false
  def isMarkerFailure: Boolean        = markerFailed
  def normalizedText: String          = raw.result()
  def allProtocolErrors: List[String] = protocolErrors

  /**
   * 正文过滤回退（解析器判定"已下发的那段被过滤改动了"）：丢掉这一轮累积的原文与已发正文，从头收。
   *
   * 只清"内容"相关的状态：`failed` / `done` / tool 记账保持不动 —— 这是一次内容回退，不是新的调用。
   */
  def reset(): Unit   = {
    raw.clear()
    content = ""
    reasoning = ""
  }
  private val chunkId = s"chatcmpl-$taskId"
  private val created = js.Date.now() / 1000.0

  /** 解析结果的结构化形态，供"网页原生会话"复用同一套 marker 解析。 */
  final case class MarkerResult(content: String, thinking: String, toolCalls: List[(String, String)])

  /** 只做 marker 解析、不产出 SSE：把一整段归一化正文喂进来后取结构化结果。 */
  def parseMarkersText(text: String): Either[String, MarkerResult] = {
    if (text.nonEmpty) raw.append(text)
    strictResult().left.map(_.mkString(" | ")).map { case (finalContent, finalReasoning, calls) =>
      MarkerResult(
        finalContent,
        finalReasoning.getOrElse(""),
        calls.map(call => (call.name, call.arguments))
      )
    }
  }

  /** marker 解析结果同时写一条持久化追踪，便于事后定位是哪一类解析失败。 */
  private def strictResult(): Either[List[String], (String, Option[String], List[ToolCall])] = {
    val parseStartedAt = js.Date.now()
    val result         = MarkerSettlement.strict(raw.result()).flatMap { parsed =>
      val leaked =
        parsed.content.contains("<memory-context>") ||
          parsed.content.contains("</memory-context>") ||
          parsed.reasoning.exists(value =>
            value.contains("<memory-context>") || value.contains("</memory-context>")
          ) ||
          parsed.toolCalls.exists(call =>
            call.arguments.contains("<memory-context>") || call.arguments.contains("</memory-context>")
          )
      if (leaked) Left(List("Internal <memory-context> must not appear in the model response"))
      else Right(parsed)
    }
    TraceStore.noteTimings(taskId, Map("parse" -> (js.Date.now() - parseStartedAt)))
    result match {
      case Left(errors)  =>
        markerFailed = true
        protocolErrors = errors
        failure = errors.mkString(" | ")
        log.warn(s"marker parse failed rawChars=${raw.result().length} errors=${failure.take(500)}")
        TraceStore.noteMarkers(taskId, content.length, reasoning.length, 0, valid = false, failure)
      case Right(parsed) =>
        log.info(
          s"marker parse ok contentChars=${parsed.content.length} " +
            s"thinkingChars=${parsed.reasoning.getOrElse("").length} toolCalls=${parsed.toolCalls.size}"
        )
        TraceStore.noteMarkers(
          taskId,
          parsed.content.length,
          parsed.reasoning.getOrElse("").length,
          parsed.toolCalls.size,
          valid = true,
          error = ""
        )
    }
    result.map(parsed => (parsed.content, parsed.reasoning, parsed.toolCalls))
  }
  private def deltaFrom(previous: String, latest: String): String                            =
    if (latest.length >= previous.length && latest.startsWith(previous)) {
      latest.substring(previous.length)
    } else if (previous.isEmpty && latest.nonEmpty) {
      latest
    } else {
      ""
    }
  private def chunkJson(
      content: Option[String] = None,
      reasoning: Option[String] = None,
      finishReason: Option[String] = None,
      role: Option[String] = None,
      toolCalls: Option[js.Array[js.Any]] = None,
      usage: Option[js.Any] = None
  ): String = {
    val delta  = JsBridge.obj()
    role.foreach(r => JsBridge.update(delta, "role", JsBridge.asAny(r)))
    content.foreach(c => if (c.nonEmpty) JsBridge.update(delta, "content", JsBridge.asAny(c)))
    reasoning.foreach(r => if (r.nonEmpty) JsBridge.update(delta, "reasoning_content", JsBridge.asAny(r)))
    toolCalls.foreach(calls => JsBridge.update(delta, "tool_calls", JsBridge.asAny(calls)))
    val choice = JsBridge.obj("index" -> (0), "delta" -> (delta))
    finishReason.foreach(fr => JsBridge.update(choice, "finish_reason", JsBridge.asAny(fr)))
    val obj    = JsBridge.obj(
      "id"      -> (chunkId),
      "object"  -> ("chat.completion.chunk"),
      "created" -> (created),
      "model"   -> (model),
      "choices" -> (js.Array(choice))
    )
    usage.foreach(u => obj.usage = u)
    js.JSON.stringify(obj)
  }
  private def sse(chunkJson: String): String                                                 = OpenAiSse.data(chunkJson)
  private def finishChunk(finishReason: String, usageTotal: Int = 0): String                 =
    sse(chunkJson(
      finishReason = Some(finishReason),
      usage = Some(JsBridge.obj(
        "prompt_tokens"     -> (js.undefined),
        "completion_tokens" -> (js.undefined),
        "total_tokens"      -> (usageTotal)
      ))
    ))
  private def toolCallChunks(calls: List[ToolCall]): String                                  =
    calls.zipWithIndex.map { case (call, index) =>
      val callId    = s"call_${taskId.replace("-", "").take(12)}_$index"
      val nameChunk = sse(chunkJson(
        role = Some("assistant"),
        toolCalls = Some(js.Array(
          JsBridge.obj(
            "index"    -> (index),
            "id"       -> (callId),
            "type"     -> ("function"),
            "function" -> (JsBridge.obj("name" -> (call.name), "arguments" -> ("")))
          )
        ))
      ))
      val argsChunk = sse(chunkJson(
        toolCalls = Some(js.Array(
          JsBridge.obj("index" -> (index), "function" -> (JsBridge.obj("arguments" -> (call.arguments))))
        ))
      ))
      nameChunk + argsChunk
    }.mkString

  /** 每次收到归一化增量后调用；返回完整 SSE data 行文本（可能含多条）。 */
  def feed(normalizedDelta: String): List[String] =
    if (done || normalizedDelta.isEmpty || failed) Nil
    else {
      raw.append(normalizedDelta)
      val text       = raw.result()
      val settled    = MarkerSettlement.settledValues(text)
      val contentVal = settled.content
      val reasonVal  = settled.reasoning
      if (!text.contains("@@END:response@@")) {
        val contentDelta   = deltaFrom(content, contentVal)
        val reasoningDelta = deltaFrom(reasoning, reasonVal)
        content = contentVal
        reasoning = reasonVal
        val out            = mutable.ArrayBuffer.empty[String]
        if (reasoningDelta.nonEmpty)
          out.append(sse(chunkJson(reasoning = Some(reasoningDelta))))
        if (contentDelta.nonEmpty)
          out.append(sse(chunkJson(content = Some(contentDelta))))
        out.toList
      } else {
        strictResult() match {
          case Left(errors)                                 =>
            failed = true
            protocolErrors = errors
            failure = errors.mkString(" | ")
            List.empty
          case Right((finalContent, finalReasoning, calls)) =>
            val contentDelta   = deltaFrom(content, finalContent)
            val reasoningDelta = deltaFrom(reasoning, finalReasoning.getOrElse(""))
            content = finalContent
            reasoning = finalReasoning.getOrElse("")
            val out            = mutable.ArrayBuffer.empty[String]
            if (reasoningDelta.nonEmpty)
              out.append(sse(chunkJson(reasoning = Some(reasoningDelta))))
            if (contentDelta.nonEmpty && calls.isEmpty)
              out.append(sse(chunkJson(content = Some(contentDelta))))
            if (calls.nonEmpty) {
              out.append(toolCallChunks(calls))
              toolCallsSent = true
            }
            out.toList
        }
      }
    }
  def failureText: String                         =
    if (failed) failure else ""

  /** 流结束/错误时的最终 SSE 片段（finish chunk）。 */
  def terminalSse(finishReason: String): Either[String, String] =
    if (failed) Left(failure)
    else
      strictResult() match {
        case Left(errors)                    =>
          protocolErrors = errors
          failure = errors.mkString(" | ")
          failed = true
          Left(failure)
        case Right((finalContent, _, calls)) =>
          // 兜底收下的正文还没发过（feed 只在能读出 content 标签时发增量）：补一条 content chunk，
          // 否则 server 的 SSE 消费者会拿到空回答。正常 marker 路径这里差值为空，不会重复发。
          val contentDelta = if (calls.isEmpty) deltaFrom(content, finalContent) else ""
          if (calls.isEmpty) content = finalContent
          val prefix       = if (contentDelta.nonEmpty) sse(chunkJson(content = Some(contentDelta))) else ""
          // 工具轮的收尾必须把 tool_calls 发出去：gml 这类 provider 的闭标记常常只在最后一个（done）分片里出现，
          // 解析只在 terminalSse 才完成 —— 旧实现把 calls 丢掉，客户端只看到 reasoning + 一个 stop，
          // 表现成"模型好像什么都没做"（opencode 实测：tool 调用整批消失，agent 环直接卡住）。
          val toolText     = if (calls.nonEmpty && !toolCallsSent) toolCallChunks(calls) else ""
          val reason       = if (calls.nonEmpty) "tool_calls" else finishReason
          Right(prefix + toolText + finishChunk(reason))
      }

  /** 非流式：返回完整 chat.completion JSON 字符串。 */
  def finalJson(): Either[String, String] =
    strictResult().left.map(_.mkString(" | ")).map { case (finalContent, finalReasoning, calls) =>
      val message = JsBridge.obj("role" -> ("assistant"))
      finalReasoning.filter(_.nonEmpty).foreach(r => message.reasoning_content = r)
      if (calls.nonEmpty) {
        message.content = null
        message.tool_calls = js.Array(
          calls.zipWithIndex.map { case (call, index) =>
            JsBridge.obj(
              "id"       -> (s"call_${taskId.replace("-", "").take(12)}_$index"),
              "type"     -> ("function"),
              "function" -> (JsBridge.obj("name" -> (call.name), "arguments" -> (call.arguments)))
            )
          }*
        )
        val choice = JsBridge.obj("index" -> (0), "message" -> (message), "finish_reason" -> ("tool_calls"))
        js.JSON.stringify(JsBridge.obj(
          "id"      -> (chunkId),
          "object"  -> ("chat.completion"),
          "created" -> (created),
          "model"   -> (model),
          "choices" -> (js.Array(choice)),
          "usage"   -> (JsBridge.obj(
            "prompt_tokens"     -> (js.undefined),
            "completion_tokens" -> (js.undefined),
            "total_tokens"      -> (js.undefined)
          ))
        ))
      } else {
        message.content = finalContent
        val choice = JsBridge.obj("index" -> (0), "message" -> (message), "finish_reason" -> ("stop"))
        js.JSON.stringify(JsBridge.obj(
          "id"      -> (chunkId),
          "object"  -> ("chat.completion"),
          "created" -> (created),
          "model"   -> (model),
          "choices" -> (js.Array(choice)),
          "usage"   -> (JsBridge.obj(
            "prompt_tokens"     -> (js.undefined),
            "completion_tokens" -> (js.undefined),
            "total_tokens"      -> (js.undefined)
          ))
        ))
      }
    }
  def isDone: Boolean                     = done
  def markDone(): Unit                    = {
    done = true
    ()
  }
}
