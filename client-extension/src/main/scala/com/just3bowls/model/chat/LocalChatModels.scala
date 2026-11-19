package com.just3bowls.model.chat

import com.just3bowls.api.chat.BrowserTabRef
final case class ChatSession(
    id: String,
    title: String,
    theme: String,
    provider: String,
    createdAt: Double,
    updatedAt: Double,
    /** `local` = 插件页发起的会话；`web` = 在 provider 网页上发起的会话（只作来源标记，两侧都可继续）。 */
    source: String = "local",
    /** Empty means the session is unfiled at the root. */
    folderId: String = ""
)
final case class SessionFolder(
    id: String,
    name: String,
    parentId: String,
    createdAt: Double,
    updatedAt: Double
)

/**
 * 一次可查看的 Chat Run。
 *
 * `parentAttemptId` 为空时是用户可见的 Generation（G1/G2）；非空时是该 Generation 内的自动 Recovery（R1/R2）。内部 protocol repair 仍属于当前 Run
 * 的执行细节，不新增记录。
 */
final case class ChatAttempt(
    id: String,
    index: Int,
    trigger: String,
    status: String,
    provider: String,
    content: String,
    thinking: String,
    steps: String,
    toolCalls: String,
    toolCallId: String,
    toolResults: String,
    error: String,
    createdAt: Double,
    updatedAt: Double,
    parentAttemptId: String = "",
    requestedMode: String = "",
    effectiveMode: String = "",
    modePath: String = "",
    agentRunId: String = "",
    browserTools: Boolean = false
)
object ChatAttempt     {
  val Initial: String                                = "initial"
  val Regenerate: String                             = "regenerate"
  val AutoRecovery: String                           = "auto"
  def fromMessage(message: ChatMessage): ChatAttempt =
    ChatAttempt(
      id = message.activeAttemptIdOrLegacy,
      index = 1,
      trigger = Initial,
      status = message.status,
      provider = message.provider,
      content = message.content,
      thinking = message.thinking,
      steps = message.steps,
      toolCalls = message.toolCalls,
      toolCallId = message.toolCallId,
      toolResults = message.toolResults,
      error = if (message.status == "error") message.content else "",
      createdAt = message.createdAt,
      updatedAt = message.updatedAt,
      requestedMode = message.requestedMode,
      effectiveMode = message.effectiveMode,
      modePath = message.modePath,
      agentRunId = message.agentRunId,
      browserTools = message.browserTools
    )
}
final case class ChatMessage(
    id: String,
    sessionId: String,
    parentId: Option[String],
    role: String,
    content: String,
    thinking: String,
    status: String,
    toolCalls: String,
    toolCallId: String,
    toolResults: String,
    provider: String,
    mergedFrom: List[String],
    createdAt: Double,
    updatedAt: Double,
    mode: String = "chat",
    steps: String = "",
    /** 消息的来源：空 = 老数据/本地；`plugin` = 插件页跑出来的；`web:<tabId>` = 某个 provider tab 上的原生轮次。 */
    origin: String = "",
    /** assistant 的历史 Run；旧消息为空，读取时自动迁移为单个 initial Attempt。 */
    attempts: List[ChatAttempt] = Nil,
    activeAttemptId: String = "",
    /** 用户请求的模式：auto 或显式 react/plan。旧消息按 mode 推断。 */
    requestedMode: String = "",
    /** 本轮最终执行的模式。 */
    effectiveMode: String = "",
    /** LangGraph 实际走过的模式路径，JSON 数组，元素包含 mode/reason/at。 */
    modePath: String = "",
    /** 跨页面重载定位 AgentRun / checkpoint 的 id。 */
    agentRunId: String = "",
    /** Actual browser-tools decision used by this run, including a one-turn @tab override. */
    browserTools: Boolean = false,
    /** User-selected tabs referenced by this question turn. */
    tabRefs: List[BrowserTabRef] = Nil
) {
  def activeAttemptIdOrLegacy: String =
    if (activeAttemptId.nonEmpty && attempts.exists(_.id == activeAttemptId)) activeAttemptId
    else if (attempts.nonEmpty) attempts.last.id
    else s"$id:1"
}
object ChatMessage     {
  def withAttempts(message: ChatMessage): ChatMessage =
    if (message.role != "assistant" || message.attempts.nonEmpty) message
    else {
      val attempt = ChatAttempt.fromMessage(message)
      message.copy(attempts = List(attempt), activeAttemptId = attempt.id)
    }
}
sealed trait ChatStreamEvent
object ChatStreamEvent {
  final case class Thinking(delta: String)                   extends ChatStreamEvent
  final case class Content(delta: String)                    extends ChatStreamEvent
  final case class ToolCall(name: String, arguments: String) extends ChatStreamEvent
  final case class Done(assistant: ChatMessage)              extends ChatStreamEvent
  final case class Error(message: String)                    extends ChatStreamEvent
}
