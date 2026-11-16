package com.just3bowls.model.localapi

import com.just3bowls.facade.{InputPromptMessage, NormalizedChunk, PromptResponse, TabAiNoticeMessage}
import scala.scalajs.js

/** 插件内 runtime message 的构造器；DTO 形状统一定义在 `com.just3bowls.facade`。 */
object Msg {

  /**
   * native messaging 的 host 名（不是 URL）——**固定值**，不暴露给用户改。
   *
   * 安装脚本写 manifest 用的就是这个名字，两边写死同一个才不会出现"名字打错就静默连不上"。 想并存 dev/prod 两套 host 时改这里（配合安装脚本的 `--name`），而不是让用户在面板里手打。
   */
  val DefaultNativeHost = "com.just3bowls.pop_can"
  val DevNativeHost     = "com.just3bowls.pop_can.dev"

  /** 清掉占着 `127.0.0.1:8080` 的 host（面板在"端口被占"时把它作为一行提示给用户）。 */
  val FreePortCommand = "pkill -f com.just3bowls.server.bootstrap.ServerMain"
  def inputPrompt(
      provider: String,
      prompt: String,
      inputXPath: String,
      generationId: String = "",
      stream: Boolean = false,
      keyId: String = "",
      localTaskKind: String = "",
      bodyJson: String = "",
      messageIds: List[String] = Nil,
      sessionId: String = "",
      contextMode: String = "",
      protocolRepair: String = "",
      conversation: String = "",
      inject: Boolean = false,
      hostTabId: Int = 0,
      attachmentName: String = "",
      attachmentBody: String = "",
      promptMode: String = "",
      bindConversation: Boolean = false
  ): InputPromptMessage =
    RuntimeMessageFactory.inputPrompt(
      provider = provider,
      prompt = prompt,
      inputXPath = inputXPath,
      generationId = generationId,
      stream = stream,
      keyId = keyId,
      localTaskKind = localTaskKind,
      bodyJson = bodyJson,
      messageIds = js.Array(messageIds*),
      sessionId = sessionId,
      contextMode = contextMode,
      protocolRepair = protocolRepair,
      conversation = conversation,
      inject = inject,
      hostTabId = hostTabId,
      attachmentName = attachmentName,
      attachmentBody = attachmentBody,
      promptMode = promptMode,
      bindConversation = bindConversation
    )
  def tabAiNotice(generationId: String, text: String): TabAiNoticeMessage =
    RuntimeMessageFactory.tabAiNotice(generationId, text)
  def promptResponse(
      ok: Boolean,
      content: String,
      thinking: String = "",
      totalTokens: Int = 0,
      finishReason: String = "stop"
  ): PromptResponse =
    RuntimeMessageFactory.promptResponse(ok, content, thinking, totalTokens, finishReason)
  def normalizedChunk(
      generationId: String,
      seq: Int,
      contentDelta: String,
      done: Boolean,
      finishReason: String,
      error: String,
      rawChars: Int = 0,
      resetContent: Boolean = false
  ): NormalizedChunk =
    RuntimeMessageFactory.normalizedChunk(
      generationId,
      seq,
      contentDelta,
      done,
      finishReason,
      error,
      rawChars,
      resetContent
    )
}
