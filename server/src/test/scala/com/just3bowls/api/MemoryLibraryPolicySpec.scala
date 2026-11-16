package com.just3bowls.api
import com.just3bowls.api.memory.*

import zio.test._

object MemoryLibraryPolicySpec extends ZIOSpecDefault {

  private def item(
      id: String,
      scope: String,
      status: String,
      text: String,
      sessionId: String = "",
      observedAt: Long = 0L
  ): MemoryItem =
    MemoryItem(
      id = id,
      scope = scope,
      kind = "fact",
      text = text,
      normalizedKey = text.toLowerCase,
      status = status,
      confidence = 0.9,
      importance = 7,
      pinned = false,
      origin = "user",
      sourceSessionId = sessionId,
      observedAt = observedAt
    )

  def spec = suite("MemoryLibraryPolicy")(
    test("filters global and current-session memories") {
      val items = List(
        item("g", "global", "active", "global fact"),
        item("s1", "session", "active", "current fact", sessionId = "session-1"),
        item("s2", "session", "active", "other fact", sessionId = "session-2")
      )
      assertTrue(
        MemoryLibraryPolicy(items, MemoryLibraryPolicy.Filters()).map(_.id).toSet == Set("g", "s1", "s2"),
        MemoryLibraryPolicy(
          items,
          MemoryLibraryPolicy.Filters(scope = MemoryLibraryPolicy.ScopeGlobal)
        ).map(_.id) == List("g"),
        MemoryLibraryPolicy(
          items,
          MemoryLibraryPolicy.Filters(
            scope = MemoryLibraryPolicy.ScopeCurrentSession,
            currentSessionId = "session-1"
          )
        ).map(_.id) == List("s1")
      )
    },
    test("filters by status and searches text, kind, origin, or normalized key") {
      val items = List(
        item("pending", "global", "pending", "Flink checkpoint"),
        item("active", "global", "active", "Kafka retention")
      )
      assertTrue(
        MemoryLibraryPolicy(
          items,
          MemoryLibraryPolicy.Filters(status = MemoryLibraryPolicy.StatusPending)
        ).map(_.id) == List("pending"),
        MemoryLibraryPolicy(items, MemoryLibraryPolicy.Filters(query = "CHECKPOINT")).map(_.id) == List("pending"),
        MemoryLibraryPolicy(items, MemoryLibraryPolicy.Filters(query = "fact")).map(_.id).toSet == Set(
          "pending",
          "active"
        ),
        MemoryLibraryPolicy(items, MemoryLibraryPolicy.Filters(query = "user")).map(_.id).toSet == Set(
          "pending",
          "active"
        )
      )
    },
    test("sorts pending first and then newest first with stable id fallback") {
      val items = List(
        item("active-old", "global", "active", "old", observedAt = 10),
        item("active-new-b", "global", "active", "new b", observedAt = 20),
        item("active-new-a", "global", "active", "new a", observedAt = 20),
        item("pending", "global", "pending", "pending", observedAt = 1)
      )
      assertTrue(
        MemoryLibraryPolicy(items, MemoryLibraryPolicy.Filters()).map(_.id) ==
          List("pending", "active-new-a", "active-new-b", "active-old")
      )
    }
  )
}
