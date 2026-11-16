package com.just3bowls.ui.content.webchat

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.BrowserEvents
import com.just3bowls.ui.components.{InlineDeleteConfirmation, NeutralActionButton, NeutralIconButton}

import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.CommentNode

import scala.scalajs.js

final case class WebChatUrlView(
    url: String,
    provider: String,
    current: Boolean,
    last: Boolean
)

final case class WebChatSessionView(
    id: String,
    title: String,
    urls: List[WebChatUrlView]
)

final case class WebChatToastView(
    id: String,
    summary: String,
    detail: String
)

final case class WebChatPanelPlacement(openLeft: Boolean, openAbove: Boolean)

final case class WebChatViewState(
    supported: Boolean,
    capsuleEnabled: Boolean,
    collapsed: Boolean,
    associated: Boolean,
    behind: Boolean,
    error: String,
    title: String,
    provider: String,
    sessionId: String,
    canTakeover: Boolean,
    agentRunning: Boolean,
    sessionsLoaded: Boolean,
    sessions: List[WebChatSessionView],
    listError: String,
    actionError: String,
    confirmingDelete: String,
    renamingId: String,
    renameDraft: String,
    expandedSessionIds: Set[String],
    panelPlacement: WebChatPanelPlacement
)

final case class WebChatViewCallbacks(
    toggleCapsule: () => Unit,
    startDrag: js.Any => Unit,
    bindSession: String => Unit,
    clickSessionTitle: String => Unit,
    renameSession: String => Unit,
    changeRenameDraft: String => Unit,
    saveRename: () => Unit,
    cancelRename: () => Unit,
    requestDelete: String => Unit,
    cancelDelete: () => Unit,
    deleteSession: String => Unit,
    newSession: () => Unit,
    toggleUrls: String => Unit,
    openUrl: String => Unit,
    stopAgent: () => Unit,
    copyToast: WebChatToastView => Unit,
    dismissToast: String => Unit
)

object WebChatView {
  private val font = "system-ui,-apple-system,'Segoe UI',sans-serif"

  def render(
      state: WebChatViewState,
      toasts: List[WebChatToastView],
      callbacks: WebChatViewCallbacks
  ): HtmlElement =
    if (!state.supported || !state.capsuleEnabled) div(display := "none")
    else
      div(
        display                                                := "block",
        fontFamily                                             := font,
        renderToasts(toasts, callbacks),
        div(
          // 面板是绝对定位的 popover；这个节点始终只按胶囊尺寸参与布局，展开时胶囊不会移动。
          display := "inline-block",
          position.relative,
          renderCapsule(state, callbacks),
          if (!state.collapsed) renderPanel(state, state.panelPlacement, callbacks) else new CommentNode("")
        )
      )

  private def renderCapsule(state: WebChatViewState, cb: WebChatViewCallbacks): HtmlElement = {
    val label              =
      if (!state.associated) "Unassigned"
      else if (state.title.nonEmpty) state.title
      else s"${state.provider} session"
    val dotColor           =
      if (state.error.nonEmpty) "#dc2626"
      else if (!state.associated) "#9ca3af"
      else if (state.agentRunning) "#2563eb"
      else if (state.behind) "#d97706"
      else "#16a34a"
    val titleLines         = List(
      if (!state.associated) "Unassigned - this conversation isn't linked yet; pick a session below or create one"
      else s"Current: $label${if (state.provider.nonEmpty) s" · ${state.provider}" else ""}",
      if (state.agentRunning) "A native agent run is in progress. Open the panel and press Stop to cancel."
      else "",
      if (!state.canTakeover) "This provider has no request.userText rule: observe-only, no context injection" else "",
      state.error
    ).filter(_.nonEmpty)
    div(
      display.flex,
      alignItems.center,
      cursor.pointer,
      padding         := "5px 9px",
      borderRadius    := "14px",
      backgroundColor := "#ffffff",
      border          := "1px solid #e5e7eb",
      boxShadow       := "0 2px 8px rgba(0,0,0,.12)",
      fontSize        := "11px",
      color           := "#111827",
      title           := titleLines.mkString("\n"),
      onPointerDown --> { event => cb.startDrag(JsBridge.asObject(event)) },
      onClick --> { _ => cb.toggleCapsule() },
      span(
        display         := "inline-block",
        width           := "7px",
        height          := "7px",
        borderRadius    := "50%",
        marginRight     := "6px",
        flexShrink      := 0,
        backgroundColor := dotColor
      ),
      span(
        overflow.hidden,
        textOverflow    := "ellipsis",
        whiteSpace.nowrap,
        if (state.agentRunning) s"$label · agent" else label
      )
    )
  }

  private def renderPanel(
      state: WebChatViewState,
      placement: WebChatPanelPlacement,
      cb: WebChatViewCallbacks
  ): HtmlElement         =
    div(
      position.absolute,
      if (placement.openLeft) right   := "0" else left               := "0",
      if (placement.openAbove) bottom := "calc(100% + 6px)" else top := "calc(100% + 6px)",
      padding         := "8px",
      width           := "min(300px, calc(100vw - 24px))",
      maxHeight       := "min(70vh, 520px)",
      overflowY.auto,
      borderRadius    := "8px",
      backgroundColor := "#ffffff",
      border          := "1px solid #e5e7eb",
      boxShadow       := "0 6px 20px rgba(0,0,0,.16)",
      fontSize        := "11px",
      color           := "#111827",
      fontFamily      := font,
      if (state.actionError.nonEmpty)
        div(margin := "2px 0 4px 0", color := "#dc2626", fontSize := "10px", lineHeight := "1.5", state.actionError)
      else new CommentNode(""),
      if (state.listError.nonEmpty)
        div(margin := "2px 0 4px 0", color := "#dc2626", fontSize := "10px", lineHeight := "1.5", state.listError)
      else new CommentNode(""),
      state.sessions.map(session => renderSession(state, session, cb)),
      if (state.sessionsLoaded && state.sessions.isEmpty)
        div(color := "#6b7280", fontSize := "10px", "No sessions yet - create one first")
      else new CommentNode(""),
      renderFooter(state, cb)
    )

  private def renderSession(
      state: WebChatViewState,
      session: WebChatSessionView,
      cb: WebChatViewCallbacks
  ): HtmlElement =
    val current  = state.associated && state.sessionId == session.id
    val expanded = state.expandedSessionIds.contains(session.id)
    if (state.confirmingDelete == session.id)
      div(
        margin := "4px 0",
        InlineDeleteConfirmation.render(
          onConfirm = () => cb.deleteSession(session.id),
          onCancel = () => cb.cancelDelete()
        )
      )
    else if (state.renamingId == session.id)
      div(
        display.flex,
        alignItems.center,
        gap   := "4px",
        width := "100%",
        input(
          typ          := "text",
          value        := state.renameDraft,
          width        := "120px",
          maxWidth     := "120px",
          minWidth     := "0",
          fontSize     := "11px",
          padding      := "2px 5px",
          border       := "1px solid #93c5fd",
          borderRadius := "4px",
          outline      := "none",
          onMountFocus,
          onMountCallback { context =>
            val input = context.thisNode.ref
            js.timers.setTimeout(0) {
              input.focus()
              input.select()
            }
          },
          onInput.mapToValue --> cb.changeRenameDraft,
          onKeyDown --> { event =>
            if (
              !BrowserEvents.isComposing(event) &&
              event.key == "Enter"
            ) {
              event.preventDefault()
              cb.saveRename()
            } else if (
              !BrowserEvents.isComposing(event) &&
              event.key == "Escape"
            ) {
              event.preventDefault()
              cb.cancelRename()
            }
          },
          onBlur --> { event =>
            val editor = event.currentTarget.asInstanceOf[org.scalajs.dom.HTMLElement].parentElement
            val next   = event.relatedTarget.asInstanceOf[org.scalajs.dom.Node]
            if (editor == null || next == null || !editor.contains(next)) cb.cancelRename()
          }
        ),
        NeutralIconButton.render("✓", "Save session name", () => cb.saveRename()),
        NeutralIconButton.render("×", "Cancel session rename", () => cb.cancelRename())
      )
    else
      div(
        div(
          display.flex,
          alignItems.center,
          gap      := "4px",
          minWidth := "0",
          cursor.pointer,
          onClick --> { event =>
            if (event.detail >= 2) cb.renameSession(session.id)
            else cb.clickSessionTitle(session.id)
          },
          button(
            if (expanded) "▾" else "▸",
            title        := (if (expanded) "Collapse session details" else "Expand session details"),
            padding      := "0",
            width        := "18px",
            height       := "20px",
            flexShrink   := 0,
            cursor.pointer,
            background   := "transparent",
            color        := "#6b7280",
            border       := "0",
            onClick --> { event =>
              event.stopPropagation()
              cb.toggleUrls(session.id)
            }
          ),
          div(
            flex         := "1",
            minWidth     := "0",
            fontSize     := "12px",
            fontWeight.bold,
            whiteSpace.nowrap,
            overflow.hidden,
            textOverflow := "ellipsis",
            cursor.pointer,
            title        := (
              "Click to show or hide session details. Double-click to rename."
            ),
            session.title
          ),
          bindAction(current, session.title, () => cb.bindSession(session.id)),
          button(
            "×",
            title        := "Delete this session (native chats stay)",
            padding      := "0",
            width        := "18px",
            height       := "20px",
            cursor.pointer,
            background   := "transparent",
            color        := "#9ca3af",
            border       := "0",
            onClick --> { event =>
              event.stopPropagation()
              cb.requestDelete(session.id)
            }
          )
        ),
        if (expanded && session.urls.nonEmpty)
          div(
            margin        := "0 0 4px 10px",
            paddingLeft   := "6px",
            borderLeft    := "2px solid #e5e7eb",
            display.flex,
            flexDirection := "column",
            gap           := "1px",
            session.urls.map(renderUrl(_, cb))
          )
        else new CommentNode("")
      )

  private def bindAction(current: Boolean, sessionTitle: String, onBind: () => Unit): HtmlElement =
    button(
      `type`          := "button",
      width           := "22px",
      height          := "20px",
      padding         := "0",
      flexShrink      := 0,
      display         := "inline-flex",
      alignItems      := "center",
      justifyContent  := "center",
      cursor.pointer,
      color           := (if (current) "#2563eb" else "#94a3b8"),
      backgroundColor := (if (current) "#eff6ff" else "transparent"),
      border          := "none",
      borderRadius    := "4px",
      aria.pressed    := current.toString,
      aria.label      := (
        if (current) s"$sessionTitle is bound. Click to bind it again."
        else s"Bind $sessionTitle to the current native conversation."
      ),
      title           := (
        if (current) "Currently bound to this native conversation. Click to bind again."
        else "Bind this session to the current native conversation."
      ),
      onMouseEnter --> { event =>
        event.currentTarget.asInstanceOf[org.scalajs.dom.HTMLElement].style.backgroundColor = "#f3f4f6"
      },
      onMouseLeave --> { event =>
        event.currentTarget.asInstanceOf[org.scalajs.dom.HTMLElement].style.backgroundColor =
          if (current) "#eff6ff" else "transparent"
      },
      svg.svg(
        svg.width          := "15",
        svg.height         := "15",
        svg.viewBox        := "0 0 16 16",
        svg.fill           := "none",
        svg.stroke         := "currentColor",
        svg.strokeWidth    := "1.45",
        svg.strokeLineCap  := "round",
        svg.strokeLineJoin := "round",
        aria.hidden        := true,
        svg.path(svg.d := "M6.7 9.3l2.6-2.6"),
        svg.path(svg.d := "M7.3 4.5l1.1-1.1a2.75 2.75 0 0 1 3.9 3.9l-1.1 1.1"),
        svg.path(svg.d := "M8.7 11.5l-1.1 1.1a2.75 2.75 0 0 1-3.9-3.9l1.1-1.1")
      ),
      onClick --> { event =>
        event.stopPropagation()
        onBind()
      }
    )

  private def renderUrl(url: WebChatUrlView, cb: WebChatViewCallbacks): HtmlElement =
    div(
      display.flex,
      alignItems.flexStart,
      gap     := "4px",
      padding := "2px 0",
      cursor.pointer,
      title   := (
        if (url.current) "This tab is showing this conversation" else "Switch to this conversation"
      ),
      onClick --> { _ => cb.openUrl(url.url) },
      div(
        flex         := "1",
        minWidth     := "0",
        fontSize     := "10px",
        lineHeight   := "1.35",
        overflowWrap := "anywhere",
        color        := (if (url.current) "#1d4ed8" else "#6b7280"),
        fontWeight   := (if (url.current) "600" else "400"),
        if (url.provider.nonEmpty) s"${url.provider} · ${url.url}" else url.url
      ),
      if (url.current) badge("now", "#1d4ed8", "#dbeafe") else new CommentNode(""),
      if (url.last) badge("last", "#b45309", "#fef3c7") else new CommentNode("")
    )

  private def renderFooter(state: WebChatViewState, cb: WebChatViewCallbacks): HtmlElement =
    div(
      display.flex,
      justifyContent := "flex-end",
      alignItems.center,
      gap            := "10px",
      marginTop      := "6px",
      borderTop      := "1px solid #e5e7eb",
      paddingTop     := "6px",
      if (state.agentRunning)
        button(
          "■ Stop agent",
          marginRight.auto,
          padding         := "3px 8px",
          fontSize        := "10px",
          borderRadius    := "4px",
          cursor.pointer,
          backgroundColor := "#fef2f2",
          color           := "#b91c1c",
          border          := "1px solid #fecaca",
          onClick --> { _ => cb.stopAgent() }
        )
      else new CommentNode(""),
      NeutralActionButton.render(
        span("+ Session"),
        () => cb.newSession(),
        textSize = "11px",
        spacing = "4px 9px",
        titleText = Some("New session")
      )
    )

  private def renderToasts(toasts: List[WebChatToastView], cb: WebChatViewCallbacks): HtmlElement =
    div(
      position      := "fixed",
      top           := "12px",
      right         := "12px",
      zIndex        := "2147483647",
      width         := "320px",
      maxWidth      := "calc(100vw - 24px)",
      display.flex,
      flexDirection := "column",
      gap           := "8px",
      pointerEvents.none,
      toasts.map { toast =>
        div(
          display.flex,
          alignItems.stretch,
          pointerEvents.auto,
          backgroundColor := "#fff",
          border          := "1px solid #e5e7eb",
          borderRadius    := "6px",
          boxShadow       := "0 6px 18px rgba(15,23,42,.14)",
          overflow.hidden,
          div(width      := "3px", flexShrink := 0, backgroundColor := "#dc2626"),
          div(
            flex         := "1",
            minWidth     := "0",
            padding      := "8px 10px",
            fontSize     := "11px",
            lineHeight   := "15px",
            color        := "#111827",
            whiteSpace.preWrap,
            overflowWrap := "anywhere",
            cursor.copy,
            title        := "Click to copy the full error",
            onClick --> { _ => cb.copyToast(toast) },
            toast.summary
          ),
          button(
            "✕",
            title        := "Dismiss",
            margin       := "6px 6px 0 0",
            padding      := "2px 4px",
            border       := "none",
            background   := "transparent",
            color        := "#9ca3af",
            fontSize     := "10px",
            lineHeight   := "1",
            cursor.pointer,
            onClick --> { _ => cb.dismissToast(toast.id) }
          )
        )
      }
    )

  private def badge(label: String, fg: String, bg: String): HtmlElement =
    span(
      flexShrink      := 0,
      fontSize        := "9px",
      lineHeight      := "1.4",
      padding         := "0 4px",
      borderRadius    := "6px",
      color           := fg,
      backgroundColor := bg,
      border          := s"1px solid $bg",
      label
    )
}
