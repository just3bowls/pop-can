package com.just3bowls.memory

import com.just3bowls.api.memory.MemoryCandidate
import com.just3bowls.api.memory.MemoryHit
import com.just3bowls.api.memory.MemoryTurn
import zio.test._

object MemoryPolicySpec extends ZIOSpecDefault {
  private val turn = MemoryTurn(
    turnId = "turn-1",
    sessionId = "session-1",
    userMessageId = "user-1",
    assistantMessageId = "assistant-1",
    userText = "I prefer dark mode and use SQLite for local data.",
    assistantText = "I will remember that preference."
  )

  def spec = suite("MemoryPolicySpec")(
    test("high-confidence owner evidence may activate global memory") {
      val admission = MemoryPolicy.admit(
        turn,
        MemoryCandidate(
          text = "The user prefers dark mode.",
          scope = "global",
          kind = "preference",
          confidence = 0.91,
          evidenceQuote = "I prefer dark mode"
        ),
        hasConflict = false
      )
      assertTrue(
        admission.accepted,
        admission.status == MemoryPolicy.ActiveStatus,
        admission.scope == MemoryPolicy.GlobalScope,
        admission.origin == MemoryPolicy.OwnerOrigin
      )
    },
    test("assistant-only evidence cannot auto-promote to global") {
      val admission = MemoryPolicy.admit(
        turn,
        MemoryCandidate(
          text = "The user uses SQLite.",
          scope = "global",
          kind = "fact",
          confidence = 0.99,
          evidenceQuote = "I will remember that preference."
        ),
        hasConflict = false
      )
      assertTrue(
        admission.accepted,
        admission.status == MemoryPolicy.PendingStatus,
        admission.scope == MemoryPolicy.SessionScope,
        admission.origin == MemoryPolicy.AgentOrigin
      )
    },
    test("missing evidence is rejected") {
      val admission = MemoryPolicy.admit(
        turn,
        MemoryCandidate(text = "Invented fact", confidence = 0.99, evidenceQuote = "not in transcript"),
        hasConflict = false
      )
      assertTrue(!admission.accepted, admission.reason == "evidence quote not found")
    },
    test("credentials are refused") {
      assertTrue(MemoryPolicy.containsSecret("api_key=sk-1234567890abcdefghijkl"))
    },
    test("context is fenced and can be stripped") {
      val block = MemoryPolicy.renderContext(
        List(MemoryHit(
          id = "1",
          scope = "global",
          kind = "preference",
          text = "Dark mode",
          origin = "explicit",
          importance = 8,
          pinned = true,
          score = 1.0
        ))
      )
      assertTrue(
        block.startsWith("<memory-context>"),
        block.contains("Dark mode"),
        MemoryPolicy.stripInternalContext(block).isEmpty
      )
    }
  )
}
