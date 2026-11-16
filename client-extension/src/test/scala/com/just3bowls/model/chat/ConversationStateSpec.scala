package com.just3bowls.model.chat

import com.just3bowls.model.provider.ProviderChatSettings

class ConversationStateSpec extends munit.FunSuite {
  test("new is consumed by the first provider call only") {
    val state  = new LocalAgentModel.ConversationState("profile-1")
    val first  = state.next(ProviderChatSettings.New, "")
    val second = state.next(ProviderChatSettings.New, "")
    val third  = state.next(ProviderChatSettings.New, "")

    assertEquals(first, LocalAgentModel.ProviderCall(1, ProviderChatSettings.New, "", "profile-1"))
    assertEquals(second, LocalAgentModel.ProviderCall(2, ProviderChatSettings.Delta, "reuse", "profile-1"))
    assertEquals(third, LocalAgentModel.ProviderCall(3, ProviderChatSettings.Delta, "reuse", "profile-1"))
  }

  test("continue stays on the same conversation and later tool rounds force delta reuse") {
    val state  = new LocalAgentModel.ConversationState("profile-2")
    val first  = state.next(ProviderChatSettings.Delta, "")
    val second = state.next(ProviderChatSettings.Delta, "")

    assertEquals(first, LocalAgentModel.ProviderCall(1, ProviderChatSettings.Delta, "", "profile-2"))
    assertEquals(second, LocalAgentModel.ProviderCall(2, ProviderChatSettings.Delta, "reuse", "profile-2"))
  }

  test("separate runs do not share conversation state") {
    val firstRun  = new LocalAgentModel.ConversationState("profile")
    val secondRun = new LocalAgentModel.ConversationState("profile")

    assertEquals(firstRun.next(ProviderChatSettings.New, "").round, 1)
    assertEquals(secondRun.next(ProviderChatSettings.New, "").round, 1)
  }
}
