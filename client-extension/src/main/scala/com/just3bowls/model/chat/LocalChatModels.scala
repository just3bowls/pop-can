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

/** 一次用户可见的完整 Chat Run；自动 repair / fallback 是 Attempt 内部的 Turn，不新增 Attempt。 */
final case class ChatAttempt(
    id: String,
    index: Int,
    trigger: String,
    status: String,
    provider: String,
    content: String,
    thinking: String,
    steps: String,
    error: String,
    internalRecoveries: Int,
    createdAt: Double,
    updatedAt: Double,
    requestedMode: String = "",
    effectiveMode: String = "",
    modePath: String = "",
    agentRunId: String = "",
    browserTools: Boolean = true
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
      error = if (message.status == "error") message.content else "",
      internalRecoveries = message.retries,
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
    /**
     * 这条回答一共重试了几次（0 = 一次就成）。
     *
     * 只有"同一轮失败后自动/手动重跑"才会累加：每次重试 = 换一段 provider 对话 + 投完整模版；UI 在回答上显示 `retried ×N`。
     */
    retries: Int = 0,
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
    browserTools: Boolean = true,
    /** User-selected tabs referenced by this question turn. */
    tabRefs: List[BrowserTabRef] = Nil
) {
  def activeAttemptIdOrLegacy: String =
    if (activeAttemptId.nonEmpty) activeAttemptId else if (attempts.nonEmpty) attempts.last.id else s"$id:1"
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
