package com.just3bowls.ui.panel.pages

import com.just3bowls.model.chat.{ChatAttempt, ChatMessage}

/** Projects a completed model result into a top-level Attempt or nested Recovery run. */
private[pages] object AgentAttemptProjection {
  def completedAttempt(
      attemptId: String,
      index: Int,
      trigger: String,
      parentAttemptId: String,
      actual: ChatMessage
  ): ChatAttempt =
    ChatAttempt(
      id = attemptId,
      index = index,
      trigger = trigger,
      status = actual.status,
      provider = actual.provider,
      content = actual.content,
      thinking = actual.thinking,
      steps = actual.steps,
      toolCalls = actual.toolCalls,
      toolCallId = actual.toolCallId,
      toolResults = actual.toolResults,
      error = if (actual.status == "error") actual.content else "",
      createdAt = actual.createdAt,
      updatedAt = actual.updatedAt,
      parentAttemptId = parentAttemptId,
      requestedMode = actual.requestedMode,
      effectiveMode = actual.effectiveMode,
      modePath = actual.modePath,
      agentRunId = actual.agentRunId,
      browserTools = actual.browserTools
    )
}
