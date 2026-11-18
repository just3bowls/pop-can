package com.just3bowls.ui.panel.pages

import com.just3bowls.model.chat.ChatMessage

class AgentAttemptProjectionSpec extends munit.FunSuite {
  private def result(status: String, content: String): ChatMessage =
    ChatMessage(
      id = "assistant",
      sessionId = "session",
      parentId = Some("user"),
      role = "assistant",
      content = content,
      thinking = "reason",
      status = status,
      toolCalls = "[]",
      toolCallId = "",
      toolResults = "",
      provider = "deepseek",
      mergedFrom = Nil,
      createdAt = 1.0,
      updatedAt = 2.0
    )

  test("projects a recovery without merging the parent attempt's process") {
    val attempt =
      AgentAttemptProjection.completedAttempt(
        attemptId = "a1:r1",
        index = 1,
        trigger = "auto",
        parentAttemptId = "a1",
        actual = result("success", "recovered")
      )

    assertEquals(attempt.parentAttemptId, "a1")
    assertEquals(attempt.content, "recovered")
    assertEquals(attempt.steps, "")
    assertEquals(attempt.toolCalls, "[]")
  }

  test("projects a top-level manual attempt") {
    val attempt =
      AgentAttemptProjection.completedAttempt(
        attemptId = "a2",
        index = 2,
        trigger = "regenerate",
        parentAttemptId = "",
        actual = result("error", "failed")
      )

    assertEquals(attempt.parentAttemptId, "")
    assertEquals(attempt.index, 2)
    assertEquals(attempt.error, "failed")
  }
}
