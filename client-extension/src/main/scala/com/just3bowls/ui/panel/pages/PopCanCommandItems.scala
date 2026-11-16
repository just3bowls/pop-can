package com.just3bowls.ui.panel.pages

import com.just3bowls.api.chat.{AgentModePolicy, BrowserTabRef}
import com.just3bowls.model.chat.{ChatCommandItem, ChatCommandSpec}

/** Pure command-palette row factories; event handlers are supplied by the page controller. */
private[pages] object PopCanCommandItems {
  def agents(
      query: String,
      currentMode: String,
      choose: String => Unit
  ): List[ChatCommandItem] =
    AgentModePolicy.matchingOptions(query).map { option =>
      ChatCommandItem(
        id = s"agent:${option.id}",
        label = option.label,
        description = option.description,
        selected = option.id == currentMode,
        title = s"Use ${option.label} for this session",
        run = () => choose(option.id)
      )
    }

  def tabs(
      query: String,
      candidates: List[BrowserTabRef],
      selectedIds: Map[Int, String],
      toggle: BrowserTabRef => Unit
  ): List[ChatCommandItem] = {
    val q = query.trim.toLowerCase
    candidates
      .filter(tab => q.isEmpty || tab.title.toLowerCase.contains(q) || tab.url.toLowerCase.contains(q))
      .map { tab =>
        val ref      = selectedIds.getOrElse(tab.tabId, "")
        val selected = ref.nonEmpty
        ChatCommandItem(
          id = s"tab:${tab.tabId}",
          label = (if (selected) s"[$ref] " else "") + tab.title,
          description = tab.url,
          selected = selected,
          title =
            if (selected) s"$ref selected. Press Enter or click to remove it."
            else "Press Enter or click to reference this tab",
          run = () => toggle(tab)
        )
      }
  }

  def rememberScopes(
      query: String,
      hasSession: Boolean,
      choose: String => Unit
  ): List[ChatCommandItem] = {
    val q = query.trim.toLowerCase
    List(
      ChatCommandItem(
        id = "remember:global",
        label = "Global memory",
        description = "Available across all sessions",
        title = "Save a pinned fact to global Memory",
        run = () => choose("global")
      ),
      ChatCommandItem(
        id = "remember:session",
        label = "Session memory",
        description = if (hasSession) "Available only in this chat" else "Open a chat first",
        title =
          if (hasSession) "Save a pinned fact to this session's Memory"
          else "This option requires an open chat",
        run = () => choose("session")
      )
    ).filter(item => q.isEmpty || item.label.toLowerCase.contains(q) || item.id.endsWith(q))
  }

  def commands(
      query: String,
      remember: () => Unit,
      execute: (String, String) => Unit
  ): List[ChatCommandItem] =
    ChatCommandSpec.filter(query).map { spec =>
      ChatCommandItem(
        id = s"command:${spec.name}",
        label = spec.usage,
        description = spec.description,
        run = () =>
          if (spec.name == "remember") remember()
          else execute(spec.name, "")
      )
    }
}
