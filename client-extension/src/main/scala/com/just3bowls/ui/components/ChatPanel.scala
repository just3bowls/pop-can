package com.just3bowls.ui.components

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.chat.*

import com.just3bowls.api.chat.BrowserTabRef
import com.raquo.laminar.api.L._
import scala.scalajs.js
import scala.collection.mutable
private[ui] object ChatPanel {
  private val attemptSelections                                           = mutable.Map.empty[String, Var[String]]
  private val lastActiveAttempt                                           = mutable.Map.empty[String, String]
  def render(
      messages: Var[List[ChatMessage]],
      regenerableMessageId: Var[Option[String]],
      onFork: String => Unit,
      onUndo: String => Unit,
      onRegenerate: ChatMessage => Unit,
      chatRunActive: Signal[Boolean],
      onStop: () => Unit,
      onActivateTab: BrowserTabRef => Unit
  ): HtmlElement    =
    div(
      width    := "100%",
      minWidth := "0",
      renderMessages(
        messages,
        regenerableMessageId,
        onFork,
        onUndo,
        onRegenerate,
        chatRunActive,
        onStop,
        onActivateTab
      )
    )
  private def renderMessages(
      messages: Var[List[ChatMessage]],
      regenerableMessageId: Var[Option[String]],
      onFork: String => Unit,
      onUndo: String => Unit,
      onRegenerate: ChatMessage => Unit,
      chatRunActive: Signal[Boolean],
      onStop: () => Unit,
      onActivateTab: BrowserTabRef => Unit
  ): HtmlElement =
    div(
      children <-- messages.signal
        .map(_.filterNot(_.role == "tool"))
        .split(_.id) { (_, initial, signal) =>
          val selection = attemptSelection(initial)
          div(
            child <-- Signal.combine(signal, selection.map(_.signal).getOrElse(Val(""))).map {
              case (message, selectedAttempt) =>
                val hovered    = Var(false)
                val normalized = ChatMessage.withAttempts(message)
                val selected   = selection
                followLatestAttempt(normalized, selected)
                val shown      = attemptDisplay(normalized, selectedAttempt)
                val aside      =
                  if (normalized.attempts.size > 1) selected.map(attemptTabs(normalized, _)) else None
                div(
                  onMouseEnter --> { _ => hovered.set(true) },
                  onMouseLeave --> { _ => hovered.set(false) },
                  if (selected.nonEmpty && shown.activeAttemptIdOrLegacy != normalized.activeAttemptIdOrLegacy)
                    PopCanRenderers.renderChatMessageWithFooter(
                      shown,
                      div(),
                      aside,
                      onActivateTab
                    )
                  else
                    messageWithFooter(
                      shown,
                      hovered,
                      regenerableMessageId,
                      onFork,
                      onUndo,
                      onRegenerate,
                      aside,
                      chatRunActive,
                      onStop,
                      onActivateTab
                    )
                )
            }
          )
        }
    )
  private def attemptSelection(message: ChatMessage): Option[Var[String]] = {
    val normalized = ChatMessage.withAttempts(message)
    if (normalized.role != "assistant") None
    else {
      val selected = attemptSelections.getOrElseUpdate(message.id, Var(normalized.activeAttemptIdOrLegacy))
      if (!normalized.attempts.exists(_.id == selected.now())) selected.set(normalized.activeAttemptIdOrLegacy)
      Some(selected)
    }
  }
  private def followLatestAttempt(message: ChatMessage, selection: Option[Var[String]]): Unit =
    selection.foreach { selected =>
      val active = message.activeAttemptIdOrLegacy
      val last   = lastActiveAttempt.getOrElse(message.id, "")
      if (
        !message.attempts.exists(_.id == selected.now()) ||
        (last.nonEmpty && selected.now() == last && selected.now() != active)
      )
        selected.set(active)
      lastActiveAttempt.update(message.id, active)
    }
  private def attemptDisplay(message: ChatMessage, selectedAttempt: String): ChatMessage = {
    val normalized = ChatMessage.withAttempts(message)
    normalized.attempts.find(_.id == selectedAttempt) match {
      case None         => normalized
      case Some(active) =>
        normalized.copy(
          provider = active.provider,
          content = active.content,
          thinking = active.thinking,
          steps = active.steps,
          status = active.status,
          retries = active.internalRecoveries,
          activeAttemptId = active.id,
          requestedMode = active.requestedMode,
          effectiveMode = active.effectiveMode,
          modePath = active.modePath,
          agentRunId = active.agentRunId,
          attempts = List(active)
        )
    }
  }
  private def attemptTabs(message: ChatMessage, selected: Var[String]): HtmlElement =
    PopCanRenderers.renderAttemptSwitcher(
      tabs = message.attempts.map(attempt =>
        PopCanRenderers.AttemptTabView(
          id = attempt.id,
          index = attempt.index,
          status = attempt.status,
          title = s"Attempt ${attempt.index} · ${attempt.trigger} · ${attempt.status}"
        )
      ),
      selectedId = selected.signal,
      onSelect = selected.set,
      layout = PopCanRenderers.AttemptSwitcherLayout.Vertical
    )

  /**
   * 助手消息底部操作：常驻 `retried ×N` 徽标（重试过才显示，不受 hover 影响）+ 操作行。
   *
   * 操作行平时 hover 才显示；**失败（`status == error`）时整行常驻**，重试按钮文案用 `Retry`（成功回答仍是 `Regenerate`）。
   */
  private def assistantActions(
      message: ChatMessage,
      hovered: Var[Boolean],
      regenerableMessageId: Var[Option[String]],
      onFork: String => Unit,
      onRegenerate: ChatMessage => Unit
  ): HtmlElement = {
    val regenerable        = regenerableMessageId.signal.map(_.contains(message.id))
    val isError            = message.status == "error"
    val normalized         = ChatMessage.withAttempts(message)
    val attemptCount       = normalized.attempts.size
    div(
      display.flex,
      alignItems.center,
      justifyContent.flexStart,
      gap       := "6px",
      width     := "100%",
      marginTop := "2px",
      // 重试次数徽标：只有这一轮真的重试过才显示，hover 说明次数与策略。
      span(
        display <-- Val(if (attemptCount > 1 || message.retries > 0) "inline-block" else "none"),
        fontSize        := "10px",
        lineHeight      := "1.6",
        padding         := "0 6px",
        borderRadius    := "8px",
        color           := "#b45309",
        backgroundColor := "#fef3c7",
        border          := "1px solid #fde68a",
        flexShrink      := "0",
        title           := (
          if (attemptCount > 1) s"This answer has $attemptCount user-visible runs. Switch attempts on the right."
          else s"This run used ${message.retries} internal recoveries; they remain Turns inside Attempt 1."
        ),
        if (attemptCount > 1) s"$attemptCount runs" else s"recovered ×${message.retries}"
      ),
      div(
        display.flex,
        alignItems.center,
        gap             := "4px",
        flexGrow        := "1",
        opacity <-- hovered.signal.map(value => if (value || isError) "1" else "0"),
        pointerEvents <-- hovered.signal.map(value => if (value || isError) "auto" else "none"),
        actionButton("Fork", () => onFork(message.id), titleText = "Fork into a new session"),
        copyButton(message),
        actionButton(
          if (isError) "Retry" else "Regenerate",
          () => onRegenerate(message),
          visibleSignal = regenerable,
          titleText = if (isError) "Retry this turn (fresh provider chat + full template)" else ""
        )
      )
    )
  }
  private def runtimeFooter(
      chatRunActive: Signal[Boolean],
      onStop: () => Unit
  ): HtmlElement =
    div(
      display <-- chatRunActive.map(if (_) "flex" else "none"),
      alignItems := "center",
      gap        := "6px",
      marginTop  := "4px",
      fontSize   := "11px",
      color      := "#6b7280",
      role       := "status",
      aria.live  := "polite",
      span("Generating…"),
      stopButton(onStop)
    )
  private def stopButton(onStop: () => Unit): HtmlElement   =
    button(
      "Stop",
      fontSize        := "10px",
      padding         := "1px 8px",
      cursor.pointer,
      backgroundColor := "#fee2e2",
      color           := "#991b1b",
      border          := "1px solid #fecaca",
      borderRadius    := "4px",
      title           := "Stop the current chat run",
      onClick --> { _ => onStop() }
    )
  private def actionButton(
      label: String,
      onClickAction: () => Unit,
      visibleSignal: Signal[Boolean] = Val(true),
      disabledSignal: Signal[Boolean] = Val(false),
      titleText: String
  ): HtmlElement           =
    button(
      label,
      title           := titleText,
      display <-- visibleSignal.map(value => if (value) "inline-block" else "none"),
      fontSize        := "10px",
      padding         := "1px 6px",
      cursor.pointer,
      backgroundColor := "#f3f4f6",
      color           := "#374151",
      border          := "1px solid #d1d5db",
      borderRadius    := "4px",
      disabled <-- disabledSignal,
      onClick --> { _ => onClickAction() }
    )
  private def copyButton(message: ChatMessage): HtmlElement = {
    val copied        = Var(false)
    button(
      child.text <-- copied.signal.map(value => if (value) "Copied" else "Copy"),
      fontSize        := "10px",
      padding         := "1px 6px",
      cursor.pointer,
      backgroundColor := "#f3f4f6",
      color           := "#374151",
      border          := "1px solid #d1d5db",
      borderRadius    := "4px",
      onClick --> { _ =>
        JsBridge.navigator.clipboard.writeText(message.content)
        copied.set(true)
        js.timers.setTimeout(1500)(copied.set(false))
      }
    )
  }
  private def messageWithFooter(
      message: ChatMessage,
      hovered: Var[Boolean],
      regenerableMessageId: Var[Option[String]],
      onFork: String => Unit,
      onUndo: String => Unit,
      onRegenerate: ChatMessage => Unit,
      aside: Option[HtmlElement],
      chatRunActive: Signal[Boolean],
      onStop: () => Unit,
      onActivateTab: BrowserTabRef => Unit
  ): HtmlElement =
    val footer =
      if (message.role == "assistant" && message.status == "running")
        runtimeFooter(chatRunActive, onStop)
      else if (message.role == "user") {
        div(
          display.flex,
          alignItems.center,
          justifyContent.flexEnd,
          gap       := "4px",
          width     := "100%",
          marginTop := "2px",
          opacity <-- hovered.signal.map(value => if (value) "1" else "0"),
          pointerEvents <-- hovered.signal.map(value => if (value) "auto" else "none"),
          copyButton(message),
          actionButton("Undo", () => onUndo(message.id), titleText = "Undo from this question")
        )
      } else {
        assistantActions(message, hovered, regenerableMessageId, onFork, onRegenerate)
      }
    PopCanRenderers.renderChatMessageWithFooter(message, footer, aside, onActivateTab)
}
