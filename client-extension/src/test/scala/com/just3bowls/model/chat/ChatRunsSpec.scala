package com.just3bowls.model.chat

class ChatRunsSpec extends munit.FunSuite {
  private def run(
      id: String,
      index: Int,
      parentAttemptId: String,
      status: String,
      content: String,
      createdAt: Double
  ): ChatAttempt =
    ChatAttempt(
      id = id,
      index = index,
      trigger = if (parentAttemptId.isEmpty) ChatAttempt.Initial else ChatAttempt.AutoRecovery,
      status = status,
      provider = "deepseek",
      content = content,
      thinking = "",
      steps = "",
      toolCalls = "",
      toolCallId = "",
      toolResults = "",
      error = if (status == "error") content else "",
      createdAt = createdAt,
      updatedAt = createdAt,
      parentAttemptId = parentAttemptId
    )

  private def message(attempts: List[ChatAttempt], activeAttemptId: String): ChatMessage =
    ChatMessage(
      id = "assistant",
      sessionId = "session",
      parentId = Some("user"),
      role = "assistant",
      content = "",
      thinking = "",
      status = "running",
      toolCalls = "",
      toolCallId = "",
      toolResults = "",
      provider = "deepseek",
      mergedFrom = Nil,
      createdAt = 1.0,
      updatedAt = 1.0,
      attempts = attempts,
      activeAttemptId = activeAttemptId
    )

  test("groups recoveries under their owning attempt") {
    val a1 = run("a1", 1, "", "error", "failed", 1.0)
    val r1 = run("r1", 1, "a1", "error", "failed again", 2.0)
    val r2 = run("r2", 2, "a1", "success", "recovered", 3.0)
    val a2 = run("a2", 2, "", "success", "manual retry", 4.0)

    val grouped = ChatRuns.grouped(List(a2, r2, a1, r1))

    assertEquals(grouped.map(_._1.id), List("a1", "a2"))
    assertEquals(grouped.head._2.map(_.id), List("r1", "r2"))
    assertEquals(grouped(1)._2, Nil)
  }

  test("activating a recovery mirrors its content and status") {
    val a1     = run("a1", 1, "", "error", "failed", 1.0)
    val r1     = run("r1", 1, "a1", "success", "recovered", 2.0)
    val active = ChatRuns.activate(message(List(a1, r1), "a1"), "r1")

    assertEquals(active.activeAttemptIdOrLegacy, "r1")
    assertEquals(active.content, "recovered")
    assertEquals(active.status, "success")
  }

  test("updates only the selected recovery") {
    val a1      = run("a1", 1, "", "error", "failed", 1.0)
    val r1      = run("r1", 1, "a1", "running", "", 2.0)
    val updated = ChatRuns.updateActive(message(List(a1, r1), "r1"))(
      _.copy(status = "repairing", content = "partial")
    )

    assertEquals(updated.status, "repairing")
    assertEquals(updated.content, "partial")
    assertEquals(updated.attempts.find(_.id == "a1").map(_.status), Some("error"))
    assertEquals(updated.attempts.find(_.id == "r1").map(_.status), Some("repairing"))
  }

  test("finds the owning attempt for a recovery") {
    val a1 = run("a1", 1, "", "error", "failed", 1.0)
    val r1 = run("r1", 1, "a1", "success", "recovered", 2.0)

    assertEquals(ChatRuns.rootOf(message(List(a1, r1), "r1"), "r1").map(_.id), Some("a1"))
    assertEquals(ChatRuns.nextAttemptIndex(message(List(a1, r1), "r1")), 2)
    assertEquals(ChatRuns.nextRecoveryIndex(message(List(a1, r1), "r1"), "a1"), 2)
  }
}
