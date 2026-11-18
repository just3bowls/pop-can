package com.just3bowls.model.localapi

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.{
  InputPromptMessage,
  NormalizedChunk,
  PromptResponse,
  TabAiNoticeMessage,
  TabAiProgressMessage
}
import com.just3bowls.model.chat.RunProgress

import scala.scalajs.js

object RuntimeMessageFactory {
  def inputPrompt(
      provider: String,
      prompt: String,
      inputXPath: String,
      generationId: String,
      stream: Boolean,
      keyId: String,
      localTaskKind: String,
      bodyJson: String = "",
      messageIds: js.Array[String] = js.Array[String](),
      sessionId: String = "",
      contextMode: String = "",
      protocolRepair: String = "",
      conversation: String = "",
      inject: Boolean = false,
      hostTabId: Int = 0,
      attachmentName: String = "",
      attachmentBody: String = "",
      promptMode: String = "",
      bindConversation: Boolean = false,
      toolProfileId: String = ""
  ): InputPromptMessage =
    JsBridge.obj(
      "type"             -> ("INPUT_PROMPT"),
      "provider"         -> (provider),
      "prompt"           -> (prompt),
      "inputXPath"       -> (inputXPath),
      "generationId"     -> (generationId),
      "stream"           -> (stream),
      "keyId"            -> (keyId),
      "localTaskKind"    -> (localTaskKind),
      "bodyJson"         -> (bodyJson),
      "messageIds"       -> (messageIds),
      "sessionId"        -> (sessionId),
      "contextMode"      -> (contextMode),
      "protocolRepair"   -> (protocolRepair),
      "conversation"     -> (conversation),
      "inject"           -> (inject),
      "hostTabId"        -> (hostTabId),
      "attachmentName"   -> (attachmentName),
      "attachmentBody"   -> (attachmentBody),
      "promptMode"       -> (promptMode),
      "bindConversation" -> (bindConversation),
      "toolProfileId"    -> (toolProfileId)
    ).asInstanceOf[InputPromptMessage]

  def tabAiNotice(generationId: String, text: String): TabAiNoticeMessage =
    JsBridge.obj("type" -> ("TAB_AI_NOTICE"), "generationId" -> (generationId), "text" -> (text)).asInstanceOf[
      TabAiNoticeMessage
    ]

  def tabAiProgress(generationId: String, progress: RunProgress): TabAiProgressMessage =
    JsBridge
      .obj(
        "type"           -> ("TAB_AI_PROGRESS"),
        "generationId"   -> (generationId),
        "phase"          -> (progress.phase.wire),
        "provider"       -> (progress.provider),
        "detail"         -> (progress.detail),
        "attempt"        -> (progress.attempt),
        "recoveryIndex"  -> (progress.recoveryIndex),
        "recoveryBudget" -> (progress.recoveryBudget)
      )
      .asInstanceOf[TabAiProgressMessage]

  def promptResponse(
      ok: Boolean,
      content: String,
      thinking: String,
      totalTokens: Int,
      finishReason: String
  ): PromptResponse =
    JsBridge.obj(
      "ok"           -> (ok),
      "content"      -> (content),
      "thinking"     -> (thinking),
      "totalTokens"  -> (totalTokens),
      "finishReason" -> (finishReason)
    ).asInstanceOf[PromptResponse]

  def normalizedChunk(
      generationId: String,
      seq: Int,
      contentDelta: String,
      done: Boolean,
      finishReason: String,
      error: String,
      rawChars: Int,
      resetContent: Boolean = false
  ): NormalizedChunk =
    JsBridge.obj(
      "type"         -> ("NORMALIZED_CHUNK"),
      "generationId" -> (generationId),
      "seq"          -> (seq),
      "contentDelta" -> (contentDelta),
      "done"         -> (done),
      "finishReason" -> (finishReason),
      "error"        -> (error),
      "rawChars"     -> (rawChars),
      "resetContent" -> (resetContent)
    ).asInstanceOf[NormalizedChunk]
}
