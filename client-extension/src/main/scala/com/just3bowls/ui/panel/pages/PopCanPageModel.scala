package com.just3bowls.ui.panel.pages

import com.just3bowls.api.chat.ChatSendPolicy

final case class SessionUrlInfo(
    tabId: Int,
    url: String,
    provider: String,
    toolProfileId: String
)

final private[pages] case class PendingAgentPlan(planId: String, plan: String)

/**
 * Only affects the next send. It is intentionally memory-only and cleared when the session changes.
 */
final case class PendingSend(provider: String, settings: ChatSendPolicy.Settings)
