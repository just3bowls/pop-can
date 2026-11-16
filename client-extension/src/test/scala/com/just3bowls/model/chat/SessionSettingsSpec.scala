package com.just3bowls.model.chat

import com.just3bowls.api.chat.AgentModePolicy
import com.just3bowls.facade.JsBridge

class SessionSettingsSpec extends munit.FunSuite {
  test("parses a versioned session settings document") {
    val raw = JsBridge.obj(
      "schemaVersion" -> 1,
      "agentMode"     -> "PLAN",
      "browserTools"  -> false
    )

    assertEquals(
      SessionSettingsStore.parseDocument(raw),
      Some(SessionSettings(agentMode = AgentModePolicy.Plan, browserTools = false))
    )
  }

  test("rejects unsupported or malformed session settings documents") {
    assertEquals(
      SessionSettingsStore.parseDocument(JsBridge.obj("schemaVersion" -> 2, "browserTools" -> false)),
      None
    )
    assertEquals(SessionSettingsStore.parseDocument("not-an-object"), None)
  }

  test("uses the legacy global off value and the session mode while migrating") {
    val legacyModes = JsBridge.obj(
      "session-plan"  -> "plan",
      "session-react" -> "react"
    )

    assertEquals(
      SessionSettingsStore.legacySettingsFor("session-plan", Some("off"), Some(legacyModes)),
      SessionSettings(agentMode = AgentModePolicy.Plan, browserTools = false)
    )
    assertEquals(
      SessionSettingsStore.legacySettingsFor("session-new", Some("on"), Some(legacyModes)),
      SessionSettings(agentMode = AgentModePolicy.React, browserTools = true)
    )
  }

  test("missing legacy values default browser tools off") {
    assertEquals(
      SessionSettingsStore.legacySettingsFor("session-1", None, None),
      SessionSettings.Default
    )
    assertEquals(SessionSettings.Default.browserTools, false)
  }

  test("legacy chat attempts inherit the message browser-tools snapshot") {
    val message = ChatMessage(
      id = "assistant-1",
      sessionId = "session-1",
      parentId = Some("user-1"),
      role = "assistant",
      content = "answer",
      thinking = "",
      status = "success",
      toolCalls = "",
      toolCallId = "",
      toolResults = "",
      provider = "gml",
      mergedFrom = Nil,
      createdAt = 1.0,
      updatedAt = 1.0,
      browserTools = false
    )

    assertEquals(ChatAttempt.fromMessage(message).browserTools, false)
  }

  test("@tab enables browser tools for one run without changing the session default") {
    assertEquals(BrowserToolsPolicy.effectiveForRun(sessionEnabled = false, referencedTabCount = 0), false)
    assertEquals(BrowserToolsPolicy.effectiveForRun(sessionEnabled = false, referencedTabCount = 1), true)
    assertEquals(BrowserToolsPolicy.isOneTurnOverride(sessionEnabled = false, referencedTabCount = 1), true)
    assertEquals(BrowserToolsPolicy.effectiveForRun(sessionEnabled = true, referencedTabCount = 0), true)
    assertEquals(BrowserToolsPolicy.isOneTurnOverride(sessionEnabled = true, referencedTabCount = 1), false)
  }
}
