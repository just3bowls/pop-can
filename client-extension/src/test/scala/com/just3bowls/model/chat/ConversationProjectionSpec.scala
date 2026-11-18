package com.just3bowls.model.chat

class ConversationProjectionSpec extends munit.FunSuite {
  private def message(id: String, content: String, createdAt: Double): ChatMessage =
    ChatMessage(
      id = id,
      sessionId = "session",
      parentId = None,
      role = "assistant",
      content = content,
      thinking = "",
      status = "success",
      toolCalls = "",
      toolCallId = "",
      toolResults = "",
      provider = "deepseek",
      mergedFrom = Nil,
      createdAt = createdAt,
      updatedAt = createdAt
    )

  test("live message overrides the stored message with the same id") {
    val stored = message("assistant", "old", 1.0)
    val live   = message("assistant", "running", 2.0).copy(status = "running")
    val view   = ConversationProjection(List(stored), Map(live.id -> live)).visible

    assertEquals(view.map(_.content), List("running"))
    assertEquals(view.map(_.status), List("running"))
  }

  test("keeps stored order and appends live-only messages") {
    val first  = message("first", "first", 1.0)
    val second = message("second", "second", 2.0)
    val live   = message("live", "live", 3.0).copy(status = "running")
    val view   = ConversationProjection(List(first, second), Map(live.id -> live)).visible

    assertEquals(view.map(_.id), List("first", "second", "live"))
  }

  test("path lookup sees the live overlay") {
    val stored = message("assistant", "old", 1.0)
    val live   = message("assistant", "running", 2.0).copy(status = "running")
    val path   = ConversationProjection(List(stored), Map(live.id -> live)).pathTo(Some(live.id))

    assertEquals(path.map(_.content), List("running"))
  }
}
