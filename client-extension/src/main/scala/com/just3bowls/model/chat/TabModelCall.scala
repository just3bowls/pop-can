package com.just3bowls.model.chat
import com.just3bowls.model.provider.*
import com.just3bowls.model.localapi.*
import com.just3bowls.model.diagnostics.*

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 「一次网页模型调用」的统一入口：把 OpenAI 格式 body 与结构化上下文一起交给后台，由后台按水位线决定这一轮投什么 （增量消息 / 整份模版）、以及全量那一投的正文走注入还是附件（`promptMode`），再投递给网页
 * AI 并返回解析结果。
 */
object TabModelCall {
  def run(
      provider: String,
      bodyJson: String,
      onEvent: TabAiEvent => Unit,
      cancellation: AgentCancellation,
      /** 页面侧等待一次模型调用的绝对上限；与 `LocalAgentModel` 同一个口径（后台的流等待是 10 分钟）。 */
      timeoutMs: Int = 720000,
      keyId: String = ApiKeyStore.SystemApiKeyId,
      sessionId: String = "",
      messageIds: List[String] = Nil,
      contextMode: String = ProviderChatSettings.Delta,
      protocolRepair: Option[String] = None,
      conversation: String = "",
      inject: Boolean = true,
      hostTabId: Int = 0,
      /** 正文投递方式（`inline` / `file`）：由调用方按 `/provider` 的本次设置给出，缺省 `inline`。 */
      promptMode: String = ProviderChatSettings.Inline,
      /** 这一轮用过的对话要不要绑给会话（插件页 Chat / Retry 为 true；网页原生轮为 false）。 */
      bindConversation: Boolean = false,
      /** Exact LangChain tool-surface revision used by this provider conversation. */
      toolProfileId: String = "",
      onTaskStarted: String => Unit = _ => ()
  ): Future[TabAiResult] = {
    val pageStartedAt = js.Date.now()
    cancellation.check()
    // build = 组装最终提示词的耗时；pageTotal 覆盖整个网页侧调用。
    val buildMs       = js.Date.now() - pageStartedAt
    TabAiExecutor
      .run(
        provider,
        ThinOpenAi.buildPrompt(bodyJson, protocolRepair),
        onEvent,
        keyId = keyId,
        timeoutMs = timeoutMs,
        bodyJson = bodyJson,
        messageIds = messageIds,
        sessionId = sessionId,
        contextMode = ProviderChatSettings.normalizeConversation(contextMode),
        protocolRepair = protocolRepair.getOrElse(""),
        conversation = conversation,
        inject = inject,
        hostTabId = hostTabId,
        toolProfileId = toolProfileId,
        promptMode = ProviderChatSettings.normalizePrompt(promptMode),
        bindConversation = bindConversation,
        onTaskStarted = onTaskStarted
      )
      .map { result =>
        TraceStore.noteTimings(
          result.gen,
          Map("build" -> buildMs, "pageTotal" -> (js.Date.now() - pageStartedAt))
        )
        result
      }
  }
}
