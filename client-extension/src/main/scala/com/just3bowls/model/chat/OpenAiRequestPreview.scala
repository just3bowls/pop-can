package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import scala.scalajs.js

/**
 * 从原始 OpenAI Chat Completions body 生成“输入框里显示的短文本”。
 *
 * 它只负责展示：完整语义载荷始终是 `bodyJson` 本身，绝不能用预览反推或重建。最后一条消息代表当前待处理 turn；tool 轮因此显示工具摘要，而不是回退到上一条 user。
 */
object OpenAiRequestPreview {
  val Default                                                = "Request payload"
  def of(bodyJson: String, limit: Int = 2000): String        =
    try {
      val parsed   = JsBridge.asObject(js.JSON.parse(bodyJson))
      val messages = JsBridge.undefinedArrayAny(parsed.messages).toOption
      messages.flatMap(_.toList.lastOption).map(message => previewOf(message, limit)).getOrElse(Default)
    } catch {
      case _: Throwable => Default
    }
  private def previewOf(message: js.Any, limit: Int): String = {
    val role       = str(JsBridge.field(message, "role"))
    val content    = contentText(JsBridge.field(message, "content"))
    val toolName   = str(JsBridge.field(message, "name"))
    val toolCallId = str(JsBridge.field(message, "tool_call_id"))
    val base       =
      role match {
        case "user"      => content
        case "tool"      =>
          val name = if (toolName.nonEmpty) toolName else toolCallId
          s"[tool]${if (name.nonEmpty) s" $name" else ""} $content"
        case "assistant" =>
          val names = toolCallNames(JsBridge.field(message, "tool_calls"))
          if (names.nonEmpty) s"[assistant tool call] ${names.mkString(", ")}"
          else s"[assistant] $content"
        case other       =>
          s"[${if (other.nonEmpty) other else "message"}] $content"
      }
    truncate(base.trim, limit)
  }

  /** string 原样；多模态数组只取文本片段，其余类型保留短占位。 */
  def contentText(raw: js.Any): String                   =
    if (raw == null || js.isUndefined(raw)) ""
    else if (js.typeOf(raw) == "string") JsBridge.asString(raw)
    else if (js.Array.isArray(raw)) {
      JsBridge
        .asArray(raw)
        .toList
        .flatMap { part =>
          val text = JsBridge.stringField(part, "text").getOrElse("").trim
          if (text.nonEmpty) Some(text)
          else {
            val kind = str(JsBridge.field(part, "type")).toLowerCase
            kind match {
              case "image" | "image_url" | "input_image" => Some("[image]")
              case "audio" | "input_audio"               => Some("[audio]")
              case "file" | "input_file"                 => Some("[file]")
              case ""                                    => None
              case other                                 => Some(s"[$other]")
            }
          }
        }
        .mkString(" ")
    } else {
      try JsBridge.asString(js.JSON.stringify(raw))
      catch { case _: Throwable => "" }
    }
  private def toolCallNames(raw: js.Any): List[String]   =
    if (raw == null || js.isUndefined(raw)) Nil
    else
      JsBridge
        .asArray(raw)
        .toList
        .flatMap { call =>
          val name = JsBridge
            .undefinedAny(JsBridge.field(call, "function"))
            .toOption
            .flatMap(value => JsBridge.stringField(value, "name"))
            .getOrElse("")
          Option(name).map(_.trim).filter(_.nonEmpty)
        }
  private def truncate(text: String, limit: Int): String =
    if (limit <= 0 || text.length <= limit) text else text.take(limit) + " …"
  private def str(value: js.Any): String                 =
    if (value == null || js.isUndefined(value)) "" else value.toString
}
