package com.just3bowls.ui.components
import com.just3bowls.model.chat.*

import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.{DOMPurify, JsBridge, Marked, ProcessStepDto}
import com.raquo.laminar.codecs.StringAsIsCodec
import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ChildNode
import org.scalajs.dom
import scala.scalajs.js
import scala.collection.mutable
private[ui] object PopCanRenderers {
  final private[ui] case class ProcessToolView(
      name: String,
      arguments: String,
      result: String,
      ok: Boolean,
      done: Boolean,
      durationMs: Double
  )
  final private[ui] case class ProcessTurnView(
      index: Int,
      attempt: Int = 1,
      kind: String = "model",
      label: String = "",
      status: String,
      thinking: String,
      content: String,
      finalAnswer: Boolean = false,
      error: String = "",
      toolCalls: List[ProcessToolView] = Nil,
      retryAvailable: Boolean = false
  )
  final private[ui] case class AttemptTabView(
      id: String,
      index: Int,
      status: String,
      title: String
  )
  sealed private[ui] trait AttemptSwitcherLayout
  private[ui] object AttemptSwitcherLayout {
    case object Horizontal extends AttemptSwitcherLayout
    case object Vertical   extends AttemptSwitcherLayout
  }
  private[ui] val testConfigSteps: List[(String, String)] = List(
    "openPage" -> "Open Page",
    "newChat"  -> "New Chat",
    "input1"   -> "Input",
    "type1"    -> "Type",
    "send1"    -> "Enter",
    "wait1"    -> "Wait"
  )
  final private class RawHtmlNode(html: String) extends ChildNode[dom.HTMLElement] {
    final override val ref: dom.HTMLElement = {
      val el = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
      el.setAttribute("class", "popcan-markdown")
      el.innerHTML = html
      el
    }
  }
  private val sectionOpenVars = mutable.Map.empty[(String, String), Var[Boolean]]
  private val attemptSelectionVars = mutable.Map.empty[String, Var[Int]]
  private[ui] def sectionOpenVar(recordId: String, section: String, defaultOpen: Boolean): Var[Boolean] =
    sectionOpenVars.getOrElseUpdate(
      (recordId, section),
      Var(defaultOpen)
    )

  /** 无状态 Attempt 切换器；选中状态归调用方所有。 */
  private[ui] def renderAttemptSwitcher(
      tabs: List[AttemptTabView],
      selectedId: Signal[String],
      onSelect: String => Unit,
      layout: AttemptSwitcherLayout = AttemptSwitcherLayout.Horizontal
  ): HtmlElement =
    if (tabs.size <= 1) div()
    else {
      val vertical = layout == AttemptSwitcherLayout.Vertical
      div(
        display.flex,
        alignItems.center,
        flexDirection := (if (vertical) "column" else "row"),
        gap           := "4px",
        overflowX     := (if (vertical) "visible" else "auto"),
        overflowY     := (if (vertical) "auto" else "visible"),
        maxHeight     := (if (vertical) "120px" else "none"),
        paddingBottom := (if (vertical) "0" else "4px"),
        tabs.map { tab =>
          val selected   = selectedId.map(_ == tab.id)
          button(
            s"A${tab.index}",
            flexShrink   := "0",
            fontSize     := "10px",
            padding      := "1px 7px",
            cursor.pointer,
            borderRadius := "999px",
            border       := "1px solid #d1d5db",
            color <-- selected.map(if (_) "#ffffff" else "#475569"),
            backgroundColor <-- selected.map(if (_) "#475569" else "#ffffff"),
            title        := tab.title,
            onClick --> { _ => onSelect(tab.id) }
          )
        }
      )
    }
  private[ui] def renderMarkdown(markdown: String): String = {
    val raw = Marked.parse(markdown) match {
      case text: String => text
      case _            => markdown
    }
    DOMPurify.sanitize(raw)
  }

  private def renderPlainContent(content: String): HtmlElement = {
    val trimmed = content.trim
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        val parsed  = js.JSON.parse(trimmed)
        pre(
          whiteSpace.preWrap,
          overflowX  := "auto",
          fontSize   := "12px",
          lineHeight := "20px",
          js.JSON.stringify(parsed, null, 2)
        )
      } catch {
        case _: Throwable => div(content)
      }
    } else {
      div(content)
    }
  }
  private def formatToolArguments(argsText: String): String    =
    if (argsText.trim.isEmpty) "{}"
    else {
      try {
        val parsed = js.JSON.parse(argsText)
        if (js.typeOf(parsed) == "object" || js.typeOf(parsed) == "array") js.JSON.stringify(parsed, null, 2)
        else argsText
      } catch {
        case _: Throwable => argsText
      }
    }
  private def tabRefChips(
      refs: List[BrowserTabRef],
      onActivate: BrowserTabRef => Unit
  ): HtmlElement =
    if (refs.isEmpty) div()
    else
      div(
        display.flex,
        flexWrap   := "wrap",
        gap        := "4px",
        marginTop  := "6px",
        paddingTop := "6px",
        borderTop  := "1px solid #bfdbfe",
        refs.map { tab =>
          button(
            display         := "inline-flex",
            alignItems.center,
            gap             := "4px",
            maxWidth        := "100%",
            padding         := "2px 6px",
            cursor.pointer,
            backgroundColor := "#ffffff",
            color           := "#1d4ed8",
            border          := "1px solid #bfdbfe",
            borderRadius    := "999px",
            fontSize        := "10px",
            lineHeight      := "1.4",
            title           := s"${tab.ref} · ${tab.title}\n${tab.url}",
            onClick --> { _ => onActivate(tab) },
            span(tab.ref, fontWeight.bold),
            span("·", color := "#93c5fd"),
            span(
              tab.title,
              minWidth      := "0",
              overflow.hidden,
              textOverflow.ellipsis,
              whiteSpace.nowrap
            )
          )
        }
      )
  private[ui] def renderChatBubble(
      message: ChatMessage,
      constrained: Boolean,
      onActivateTab: BrowserTabRef => Unit
  ): HtmlElement =
    if (message.role == "user") {
      div(
        maxWidth        := (if (constrained) "86%" else "none"),
        padding         := "8px 12px",
        backgroundColor := "#eff6ff",
        border          := "1px solid #bfdbfe",
        borderRadius    := "8px",
        fontSize        := "13px",
        lineHeight      := "20px",
        color           := "#1f2937",
        whiteSpace.preWrap,
        minWidth        := "0",
        overflowWrap    := "anywhere",
        renderPlainContent(message.content),
        tabRefChips(message.tabRefs, onActivateTab)
      )
    } else {
      div(
        maxWidth        := (if (constrained) "86%" else "none"),
        width           := (if (constrained) "100%" else "auto"),
        padding         := "8px 12px",
        backgroundColor := "#ffffff",
        border          := "1px solid #e5e7eb",
        borderRadius    := "8px",
        fontSize        := "13px",
        lineHeight      := "20px",
        color           := "#1f2937",
        boxSizing.borderBox,
        minWidth        := "0",
        overflowWrap    := "anywhere",
        renderAgentProcess(message),
        if (message.content.nonEmpty) {
          new RawHtmlNode(renderMarkdown(message.content))
        } else if (message.status == "running") {
          div(fontSize := "12px", color := "#6b7280", "…")
        } else {
          new CommentNode("")
        }
      )
    }

  /** 过程区：有 steps 时按回合分组展示，否则回退到 Reason / Tools 折叠块。 */
  private def renderAgentProcess(message: ChatMessage): HtmlElement                        = {
    val normalized = ChatMessage.withAttempts(message)
    val turns      =
      if (normalized.attempts.nonEmpty)
        normalized.attempts.flatMap { attempt =>
          val steps = parseProcessSteps(attempt.steps)
          if (steps.nonEmpty) steps.map(step => processTurnFromChat(step, attempt.index))
          else if (attempt.status == "error")
            List(
              ProcessTurnView(
                index = 1,
                attempt = attempt.index,
                status = "error",
                thinking = attempt.thinking,
                content = attempt.content,
                error = attempt.error
              )
            )
          else Nil
        }
      else parseProcessSteps(message.steps).map(step => processTurnFromChat(step))
    if (turns.isEmpty) {
      if (message.thinking.nonEmpty)
        singleTurnThinking(
          ProcessTurnView(index = 1, attempt = 1, status = message.status, thinking = message.thinking, content = ""),
          running = message.status == "running"
        )
      else div()
    } else {
      val toolCount = turns.map(_.toolCalls.length).foldLeft(0)(_ + _)
      // 单轮且没有工具调用时不值得占一块过程区，只保留可折叠的思考。
      if (turns.size <= 1 && toolCount == 0) singleTurnThinking(turns.head, running = message.status == "running")
      else
        renderProcessPanel(
          processKey = message.id,
          turns = turns,
          running = message.status == "running",
          title = "Process"
        )
    }
  }
  private def processTurnFromChat(step: ProcessStepDto, attempt: Int = 1): ProcessTurnView =
    ProcessTurnView(
      index = step.index,
      attempt = attempt,
      kind = step.kind.toOption.getOrElse("model"),
      label = step.label.toOption.getOrElse(""),
      status = step.status,
      thinking = step.thinking,
      content = step.content,
      finalAnswer = step.finalAnswer.getOrElse(false),
      toolCalls = step.toolCalls.toList.map(tool =>
        ProcessToolView(
          name = tool.name,
          arguments = tool.arguments,
          result = tool.result,
          ok = tool.ok,
          done = tool.done,
          durationMs = tool.durationMs
        )
      )
    )
  private def singleTurnThinking(turn: ProcessTurnView, running: Boolean): HtmlElement     =
    if (turn.thinking.nonEmpty) div(marginBottom := "8px", reasonDetails(turn.thinking, running))
    else div()

  /**
   * 理由（marker 的 `reasoning_content`）折叠块。
   *
   * 展开体单独包一个"左色条 + 浅底 + 灰字"的容器：只靠间距的话，展开的长理由会和下面的正文粘成一片、读不出分界。
   */
  private[ui] def reasonDetails(text: String, running: Boolean): HtmlElement =
    detailsTag(
      htmlAttr("open", StringAsIsCodec) := (if (running) "open" else ""),
      summaryTag(cursor.pointer, fontSize := "11px", color := "#b45309", "Reason"),
      div(
        marginTop                         := "4px",
        borderLeft                        := "2px solid #fbbf24",
        backgroundColor                   := "#fffbeb",
        borderRadius                      := "0 4px 4px 0",
        padding                           := "4px 8px",
        fontSize                          := "11px",
        lineHeight                        := "16px",
        color                             := "#6b7280",
        whiteSpace.preWrap,
        overflowWrap                      := "anywhere",
        maxHeight                         := "320px",
        overflowY                         := "auto",
        text
      )
    )

  /** Turn 正文与 Chat Process 共用同一套排版，避免 Reason 与正文的边界和样式再次分叉。 */
  private[ui] def processContent(text: String): HtmlElement                                         =
    pre(
      marginTop := "6px",
      whiteSpace.preWrap,
      overflowX := "auto",
      maxHeight := "320px",
      overflowY := "auto",
      fontSize  := "11px",
      color     := "#374151",
      text
    )
  private def parseProcessSteps(stepsJson: String): List[ProcessStepDto]                            =
    if (stepsJson.trim.isEmpty) Nil
    else {
      try {
        JsBridge.asTypedArray[ProcessStepDto](js.JSON.parse(stepsJson)).toList
      } catch {
        case _: Throwable => Nil
      }
    }
  private[ui] def renderProcessPanel(
      processKey: String,
      turns: List[ProcessTurnView],
      running: Boolean,
      title: String = "Process",
      extraSummary: String = "",
      onRetryTurn: Option[Int => Unit] = None
  ): HtmlElement = {
    val attempts               = turns.map(_.attempt).distinct.sorted
    val lastAttempt            = attempts.lastOption.getOrElse(1)
    val hasError               = turns.exists(_.error.trim.nonEmpty)
    val open                   = sectionOpenVar(
      processKey,
      "process",
      defaultOpen = running || hasError
    )
    val toolCount              = turns.map(_.toolCalls.length).sum
    val modelTurnCount         = turns.count(_.kind == "model")
    val humanInputCount        = turns.count(_.kind == "human")
    val summary                =
      List(
        if (modelTurnCount > 0) s"$modelTurnCount turn(s)" else "",
        if (humanInputCount > 0) s"$humanInputCount human input(s)" else "",
        s"$toolCount tool call(s)",
        if (attempts.size > 1) s"${attempts.size} attempts" else "",
        extraSummary
      ).filter(_.nonEmpty).mkString(" · ")
    val groups                 = turns.groupBy(_.attempt).toList.sortBy(_._1)
    val selectedAttempt        = attemptSelection(processKey, attempts, running)
    div(
      maxWidth        := "100%",
      marginTop       := "4px",
      marginBottom    := "6px",
      backgroundColor := "#fafafa",
      border          := "1px solid #e5e7eb",
      borderRadius    := "8px",
      div(
        display.flex,
        alignItems.center,
        gap     := "6px",
        cursor.pointer,
        padding := "6px 10px",
        onClick --> { _ => open.update(!_) },
        span(fontWeight.bold, fontSize := "11px", color := "#374151", title),
        span(fontSize                  := "11px", color := "#6b7280", summary),
        span(
          fontSize                     := "11px",
          color                        := "#6b7280",
          child.text <-- open.signal.map(isOpen => if (isOpen) "[-]" else "[+]")
        )
      ),
      child <-- open.signal.map { isOpen =>
        if (!isOpen) new CommentNode("")
        else
          div(
            padding := "0 10px 10px",
            if (attempts.size > 1)
              renderAttemptSwitcher(
                tabs = groups.map { case (attempt, attemptTurns) =>
                  AttemptTabView(
                    id = attempt.toString,
                    index = attempt,
                    status = attemptStatus(attemptTurns),
                    title = attemptTitle(attempt, lastAttempt, attemptTurns)
                  )
                },
                selectedId = selectedAttempt.signal.map(_.toString),
                onSelect = id => selectedAttempt.set(id.toInt)
              )
            else new CommentNode(""),
            child <-- selectedAttempt.signal.map { selected =>
              groups.find(_._1 == selected).orElse(groups.lastOption) match {
                case None                    => new CommentNode("")
                case Some((_, attemptTurns)) =>
                  div(
                    attemptTurns.sortBy(_.index).map(turn =>
                      renderProcessTurn(
                        processKey,
                        turn,
                        onRetryTurn = onRetryTurn
                      )
                    )
                  )
              }
            }
          )
      }
    )
  }
  private def attemptSelection(processKey: String, attempts: List[Int], running: Boolean): Var[Int] = {
    val current  = attemptSelectionVars.getOrElseUpdate(processKey, Var(attempts.lastOption.getOrElse(1)))
    val fallback = attempts.lastOption.getOrElse(1)
    if (!attempts.contains(current.now()) || (running && current.now() != fallback)) current.set(fallback)
    current
  }
  private def attemptTitle(attempt: Int, latest: Int, turns: List[ProcessTurnView]): String         = {
    val status = attemptStatus(turns)
    s"Attempt $attempt · $status${if (attempt == latest) " · latest" else " · history"}"
  }
  private def attemptStatus(turns: List[ProcessTurnView]): String                                   =
    if (turns.exists(_.status == "running")) "running"
    else if (turns.exists(_.status == "error")) "failed"
    else "done"
  private def renderProcessTurn(
      processKey: String,
      turn: ProcessTurnView,
      onRetryTurn: Option[Int => Unit]
  ): HtmlElement =
    if (turn.kind == "human") renderHumanProcessTurn(turn)
    else {
      val turnRunning       = turn.status == "running"
      val showContent       = turn.content.nonEmpty
      div(
        marginTop       := "6px",
        padding         := "6px 8px",
        backgroundColor := "#ffffff",
        border          := "1px solid #f3f4f6",
        borderRadius    := "6px",
        div(
          display.flex,
          alignItems.center,
          flexWrap.wrap,
          gap := "6px",
          span(fontWeight.bold, fontSize := "11px", color := "#4b5563", s"Turn ${turn.index}"),
          span(
            fontSize                     := "10px",
            color                        := processStatusColor(turn.status),
            turn.status
          ),
          if (turn.finalAnswer) span(fontSize := "10px", color := "#15803d", "final answer")
          else new CommentNode(""),
          if (turn.toolCalls.nonEmpty)
            span(
              fontSize := "10px",
              color    := "#9ca3af",
              s"${turn.toolCalls.count(_.done)}/${turn.toolCalls.length} tool"
            )
          else new CommentNode("")
        ),
        if (turn.thinking.nonEmpty) reasonDetails(turn.thinking, turnRunning)
        else new CommentNode(""),
        turn.toolCalls.zipWithIndex.map { case (call, callIndex) =>
          renderProcessToolCall(processKey, turn, call, callIndex)
        },
        if (showContent) processContent(turn.content) else new CommentNode(""),
        if (turn.error.trim.nonEmpty) processErrorDetails(turn.error)
        else new CommentNode(""),
        if (turn.retryAvailable && onRetryTurn.nonEmpty)
          div(
            display.flex,
            justifyContent := "flex-start",
            marginTop      := "4px",
            button(
              "Retry turn",
              fontSize        := "10px",
              padding         := "1px 6px",
              cursor.pointer,
              backgroundColor := "#f3f4f6",
              color           := "#374151",
              border          := "1px solid #d1d5db",
              borderRadius    := "4px",
              onClick --> { _ => onRetryTurn.foreach(_(turn.index)) }
            )
          )
        else new CommentNode("")
      )
    }
  private def renderHumanProcessTurn(turn: ProcessTurnView): HtmlElement                            =
    div(
      marginTop       := "6px",
      padding         := "6px 8px",
      backgroundColor := "#f8fafc",
      border          := "1px solid #e2e8f0",
      borderRadius    := "6px",
      div(
        display.flex,
        alignItems.center,
        gap := "6px",
        span(fontWeight.bold, fontSize := "11px", color := "#475569", s"Turn ${turn.index}"),
        span(fontSize                  := "10px", color := "#64748b", "You"),
        if (turn.label.trim.nonEmpty)
          span(fontSize := "10px", color := "#64748b", s"· ${turn.label.trim}")
        else new CommentNode("")
      ),
      if (turn.content.nonEmpty) processContent(turn.content) else new CommentNode("")
    )
  private def renderProcessToolCall(
      processKey: String,
      turn: ProcessTurnView,
      call: ProcessToolView,
      callIndex: Int
  ): HtmlElement = {
    val open = sectionOpenVar(
      s"$processKey:tool:${turn.attempt}:${turn.index}:$callIndex",
      "result",
      defaultOpen = false
    )
    div(
      marginTop := "6px",
      div(
        display.flex,
        alignItems.center,
        gap       := "6px",
        span(fontWeight.bold, fontSize := "11px", color := "#4c1d95", call.name),
        span(
          fontSize                     := "10px",
          color                        := (if (!call.done) "#d97706" else if (call.ok) "#15803d" else "#b91c1c"),
          if (!call.done) "running" else if (call.ok) "ok" else "failed"
        ),
        if (call.result.nonEmpty) span(fontSize := "10px", color := "#9ca3af", formatByteSize(call.result.length))
        else new CommentNode("")
      ),
      pre(
        whiteSpace.preWrap,
        overflowX := "auto",
        fontSize  := "11px",
        color     := "#312e81",
        formatToolArguments(call.arguments)
      ),
      if (call.result.nonEmpty) {
        div(
          marginTop := "4px",
          div(
            display.flex,
            alignItems.center,
            gap := "6px",
            cursor.pointer,
            onClick --> { _ => open.update(!_) },
            span(fontSize := "10px", color := "#6b7280", "Result"),
            span(
              fontSize    := "10px",
              color       := "#6b7280",
              child.text <-- open.signal.map(isOpen => if (isOpen) "[-]" else "[+]")
            )
          ),
          child <-- open.signal.map { isOpen =>
            if (isOpen)
              pre(
                whiteSpace.preWrap,
                overflowX := "auto",
                maxHeight := "320px",
                overflowY := "auto",
                fontSize  := "11px",
                color     := "#1f2937",
                formatToolResult(call.result)
              )
            else new CommentNode("")
          }
        )
      } else new CommentNode("")
    )
  }
  private def processStatusColor(status: String): String                                            =
    status match {
      case "done"                    => "#15803d"
      case "running"                 => "#d97706"
      case "failed" | "error"        => "#b91c1c"
      case "interrupted" | "partial" => "#d97706"
      case _                         => "#9ca3af"
    }
  private def processErrorDetails(error: String): Modifier[HtmlElement]                             =
    Option(error).map(_.trim).filter(_.nonEmpty) match {
      case None         => new CommentNode("")
      case Some(detail) =>
        div(
          marginTop       := "4px",
          padding         := "5px 7px",
          backgroundColor := "#fef2f2",
          border          := "1px solid #fecaca",
          borderRadius    := "4px",
          div(fontSize := "10px", fontWeight := "600", color := "#b91c1c", processErrorSummary(detail)),
          div(
            marginTop  := "3px",
            maxHeight  := "160px",
            overflowY  := "auto",
            whiteSpace := "pre-wrap",
            wordBreak  := "break-word",
            fontFamily := "ui-monospace, SFMono-Regular, Menlo, monospace",
            fontSize   := "10px",
            lineHeight := "14px",
            color      := "#7f1d1d",
            detail
          )
        )
    }
  private def processErrorSummary(detail: String): String                                           = {
    val line = detail.linesIterator.nextOption().getOrElse(detail).trim
    if (line.length <= 96) line else line.take(95) + "…"
  }
  private def formatByteSize(chars: Int): String                                                    =
    if (chars < 1024) s"$chars B"
    else {
      val kb = chars / 1024.0
      if (kb < 1024) f"$kb%.1f KB" else f"${kb / 1024.0}%.1f MB"
    }
  private def formatToolResult(result: String): String                                              =
    if (result.length > 20000) result
    else {
      try {
        val parsed = js.JSON.parse(result)
        if (js.typeOf(parsed) == "object" || js.typeOf(parsed) == "array") js.JSON.stringify(parsed, null, 2)
        else result
      } catch {
        case _: Throwable => result
      }
    }

  /** 气泡 + 底部 footer（Branch / chips）+ 可选右侧轨道（Attempt 切换）。 */
  private[ui] def renderChatMessageWithFooter(
      message: ChatMessage,
      footer: HtmlElement,
      aside: Option[HtmlElement] = None,
      onActivateTab: BrowserTabRef => Unit = _ => ()
  ): HtmlElement =
    div(
      display.flex,
      justifyContent := (if (message.role == "user") "flex-end" else "flex-start"),
      alignItems     := "flex-start",
      width          := "100%",
      marginBottom   := "10px",
      div(
        display.flex,
        flexDirection.column,
        alignItems.stretch,
        maxWidth := (if (aside.nonEmpty) "calc(100% - 34px)" else "86%"),
        minWidth := "0",
        renderChatBubble(message, constrained = false, onActivateTab),
        footer
      ),
      aside.map(rail =>
        div(
          flexShrink := "0",
          marginLeft := "6px",
          rail
        )
      ).getOrElse(div())
    )
}
