package com.just3bowls.model.chat

/**
 * 模型这一轮的回复没按 marker 协议（`FinalSseProducer` 的 marker 校验失败）。
 *
 * 只有这类失败会被自动重试（"换新对话 + 完整模版"）：传输 / 超时 / 工具 / 取消失败都不算。
 */
final class MarkerParseFailed(val detail: String) extends RuntimeException(detail)
object MarkerParseFailed {

  /** `FinalSseProducer.strictResult` 产出的 marker 错误文案（异常被 LangGraph 包一层、类型丢了时的兜底）。 */
  private val markerMessages = List(
    "did not use the required @@BEG:response@@ marker",
    "did not use the required marker protocol",
    "is missing @@END:response@@",
    "has an unpaired marker",
    "has unpaired marker",
    "is missing required marker",
    "missing required marker",
    "contains @@BEG:tool_call@@ outside @@BEG:tool_calls@@",
    "does not contain a tool_call",
    "contains a malformed tool_call",
    "cannot include content when tool_calls are present",
    "must include content or tool_calls",
    "arguments:",
    "closing marker",
    "unclosed marker",
    "duplicate field"
  )

  /** 从错误（含 `cause` 链）里认出 marker 失败：LangGraph 可能把原始异常包一层。 */
  def fromError(error: Throwable): Option[MarkerParseFailed] = {
    @annotation.tailrec
    def loop(current: Throwable, depth: Int): Option[MarkerParseFailed] =
      if (current == null || depth > 8) None
      else
        current match {
          case marker: MarkerParseFailed => Some(marker)
          case other                     => loop(other.getCause, depth + 1)
        }
    loop(error, 0)
  }
  def isMarkerFailure(error: Throwable): Boolean             =
    fromError(error).isDefined || messageChain(error).exists(text => markerMessages.exists(text.contains))
  private def messageChain(error: Throwable): List[String]   = {
    @annotation.tailrec
    def loop(current: Throwable, depth: Int, acc: List[String]): List[String] =
      if (current == null || depth > 8) acc
      else loop(current.getCause, depth + 1, Option(current.getMessage).filter(_.nonEmpty).getOrElse("") :: acc)
    loop(error, 0, Nil)
  }
}
