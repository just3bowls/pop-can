package com.just3bowls.ui.panel.pages
import com.just3bowls.model.memory.*

import com.just3bowls.ui.components.*

import com.just3bowls.api.memory.MemoryItem
import com.just3bowls.api.memory.MemoryLibraryPolicy
import com.just3bowls.api.memory.MemoryMutation
import com.just3bowls.api.memory.MemoryStatus
import com.raquo.laminar.api.L._
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
final private[ui] class MemoryLibraryPanel(
    client: MemoryClient,
    currentSessionId: Signal[String],
    onOpen: () => Unit
) {
  private val open           = Var(false)
  private val items          = Var(List.empty[MemoryItem])
  private val total          = Var(0)
  private val triggerState   = Var[MemoryTriggerState](MemoryTriggerState.Checking)
  private var statusInFlight = Option.empty[Promise[MemoryStatus]]
  private var statusRetryTimer: js.UndefOr[js.timers.SetTimeoutHandle]     = js.undefined
  private var statusPoller: js.UndefOr[js.timers.SetIntervalHandle]        = js.undefined
  private var refreshInFlight                                              = false
  private val loading                                                      = Var(false)
  private val loadError                                                    = Var("")
  private val query                                                        = Var("")
  private val scopeFilter                                                  = Var(MemoryLibraryPolicy.ScopeAll)
  private val statusFilter                                                 = Var(MemoryLibraryPolicy.StatusAll)
  private val TriggerId                                                    = "popcan-memory-trigger"
  private val PanelId                                                      = "popcan-memory-panel"
  private val SearchId                                                     = "popcan-memory-search"
  private val visibleItems: Signal[List[MemoryItem]]                       =
    Signal
      .combine(items.signal, query.signal, scopeFilter.signal, statusFilter.signal, currentSessionId)
      .map { case (all, text, scope, itemStatus, sessionId) =>
        MemoryLibraryPolicy(
          all,
          MemoryLibraryPolicy.Filters(
            query = text,
            scope = scope,
            status = itemStatus,
            currentSessionId = sessionId
          )
        )
      }
  private val triggerView: Signal[MemoryTriggerView]                       =
    triggerState.signal.map(MemoryTriggerPresentation.view)
  private val statusSummary: Signal[String]                                =
    triggerState.signal.map {
      case MemoryTriggerState.Checking                       => "Checking memory runtime…"
      case MemoryTriggerState.Unavailable(_)                 => "Memory unavailable"
      case MemoryTriggerState.Ready(value) if !value.enabled => "Memory disabled"
      case MemoryTriggerState.Ready(value)                   =>
        s"${value.activeCount} active · ${value.pendingCount} pending · ${value.queuedJobs} queued"
    }
  def close(): Unit                                                        = open.set(false)
  def externalChange(): Unit                                               =
    if (open.now()) refresh()
    else probeStatus(showChecking = triggerState.now() == MemoryTriggerState.Checking, retryOnce = false)
  def renderTrigger: HtmlElement                                           =
    button(
      idAttr          := TriggerId,
      `type`          := "button",
      aria.expanded <-- open.signal,
      aria.controls   := PanelId,
      aria.label <-- triggerView.map(_.ariaLabel),
      display         := "inline-flex",
      alignItems.center,
      gap             := "5px",
      padding         := "2px 0",
      border          := "none",
      backgroundColor := "transparent",
      cursor.pointer,
      fontSize        := "11px",
      fontWeight      := "500",
      lineHeight      := "1.4",
      title <-- triggerView.map(_.tooltip),
      children <-- triggerView.map(_.segments.map(segment =>
        span(segment.text, color := segment.color)
      )),
      onMountCallback { _ =>
        probeStatus(showChecking = true, retryOnce = true)
        statusPoller.foreach(js.timers.clearInterval)
        statusPoller = js.timers.setInterval(5000)(probeStatus(showChecking = false, retryOnce = false))
      },
      onUnmountCallback { _ =>
        statusPoller.foreach(js.timers.clearInterval)
        statusPoller = js.undefined
        statusRetryTimer.foreach(js.timers.clearTimeout)
        statusRetryTimer = js.undefined
      },
      onClick --> { _ => toggle() }
    )
  def renderPanel: HtmlElement                                             =
    div(
      idAttr          := PanelId,
      role            := "dialog",
      aria.label      := "Stored memories",
      aria.hidden <-- open.signal.map(!_),
      position        := "absolute",
      right           := "0",
      bottom          := "calc(100% + 6px)",
      width           := "min(720px, calc(100vw - 20px))",
      maxHeight       := "min(520px, calc(100vh - 120px))",
      zIndex          := "210",
      flexDirection.column,
      backgroundColor := "#ffffff",
      border          := "1px solid #d1d5db",
      borderRadius    := "10px",
      boxShadow       := "0 18px 40px rgba(15,23,42,0.2)",
      overflow.hidden,
      display <-- open.signal.map(value => if (value) "flex" else "none"),
      documentEvents(_.onKeyDown).filter(event => open.now() && event.key == "Escape") --> { event =>
        event.preventDefault()
        close()
      },
      documentEvents(_.onMouseDown).filter { event =>
        open.now() && {
          val target  = event.target.asInstanceOf[dom.Node]
          val panel   = dom.document.getElementById(PanelId)
          val trigger = dom.document.getElementById(TriggerId)
          val inside  =
            (panel != null && panel.contains(target)) || (trigger != null && trigger.contains(target))
          !inside
        }
      } --> { _ => close() },
      div(
        display.flex,
        alignItems.center,
        gap                              := "8px",
        padding                          := "10px 12px",
        borderBottom                     := "1px solid #e5e7eb",
        div(
          flexGrow        := "1",
          minWidth        := "0",
          span("Stored memories", fontSize := "13px", fontWeight.bold, color := "#0f172a"),
          div(
            child.text <-- statusSummary,
            marginTop                      := "1px",
            fontSize                       := "10px",
            color                          := "#64748b"
          )
        ),
        smallButton("Refresh", () => refresh()),
        button(
          "×",
          width           := "24px",
          height          := "24px",
          padding         := "0",
          cursor.pointer,
          backgroundColor := "#f3f4f6",
          color           := "#374151",
          border          := "1px solid #d1d5db",
          borderRadius    := "5px",
          title           := "Close stored memories",
          onClick --> { _ => close() }
        )
      ),
      div(
        padding                          := "8px 12px",
        borderBottom                     := "1px solid #e5e7eb",
        backgroundColor                  := "#f8fafc",
        input(
          idAttr          := SearchId,
          `type`          := "search",
          placeholder     := "Search stored memories",
          width           := "100%",
          boxSizing       := "border-box",
          padding         := "6px 8px",
          fontSize        := "12px",
          color           := "#111827",
          backgroundColor := "#ffffff",
          border          := "1px solid #cbd5e1",
          borderRadius    := "5px",
          outline         := "none",
          controlled(
            value <-- query,
            onInput.mapToValue --> query.set
          )
        ),
        div(
          display.flex,
          alignItems.center,
          flexWrap.wrap,
          gap             := "6px",
          marginTop       := "7px",
          filterButton(
            "All",
            scopeFilter.signal.map(_ == MemoryLibraryPolicy.ScopeAll),
            () =>
              scopeFilter.set(MemoryLibraryPolicy.ScopeAll)
          ),
          filterButton(
            "Global",
            scopeFilter.signal.map(_ == MemoryLibraryPolicy.ScopeGlobal),
            () =>
              scopeFilter.set(MemoryLibraryPolicy.ScopeGlobal)
          ),
          filterButton(
            "This session",
            scopeFilter.signal.map(_ == MemoryLibraryPolicy.ScopeCurrentSession),
            () => scopeFilter.set(MemoryLibraryPolicy.ScopeCurrentSession)
          ),
          span("|", color := "#cbd5e1", fontSize := "10px"),
          filterButton(
            "All",
            statusFilter.signal.map(_ == MemoryLibraryPolicy.StatusAll),
            () =>
              statusFilter.set(MemoryLibraryPolicy.StatusAll)
          ),
          filterButton(
            "Active",
            statusFilter.signal.map(_ == MemoryLibraryPolicy.StatusActive),
            () =>
              statusFilter.set(MemoryLibraryPolicy.StatusActive)
          ),
          filterButton(
            "Pending",
            statusFilter.signal.map(_ == MemoryLibraryPolicy.StatusPending),
            () =>
              statusFilter.set(MemoryLibraryPolicy.StatusPending)
          )
        )
      ),
      div(
        flexGrow                         := "1",
        minHeight                        := "0",
        overflowY.auto,
        styleProp("overscroll-behavior") := "contain",
        children <-- Signal
          .combine(visibleItems, loading.signal, loadError.signal, total.signal, triggerState.signal)
          .map { case (all, isLoading, error, knownTotal, state) =>
            state match {
              case MemoryTriggerState.Checking                       =>
                List(div("Checking memory runtime…", padding := "16px", fontSize := "11px", color := "#64748b"))
              case MemoryTriggerState.Unavailable(message)           =>
                List(
                  div(
                    s"Memory unavailable: $message",
                    padding   := "16px 16px 8px",
                    fontSize  := "11px",
                    color     := "#b91c1c"
                  ),
                  div(padding := "0 16px 16px", smallButton("Retry", () => refresh()))
                )
              case MemoryTriggerState.Ready(value) if !value.enabled =>
                List(div("Memory is disabled in Config.", padding := "16px", fontSize := "11px", color := "#64748b"))
              case _                                                 =>
                if (isLoading && all.isEmpty)
                  List(
                    div("Loading stored memories…", padding := "16px", fontSize := "11px", color := "#64748b")
                  )
                else if (error.nonEmpty)
                  List(div(
                    s"Could not load stored memories: $error",
                    padding  := "16px",
                    fontSize := "11px",
                    color    := "#b91c1c"
                  ))
                else if (all.isEmpty)
                  List(
                    div("No memories match these filters.", padding := "16px", fontSize := "11px", color := "#64748b")
                  )
                else
                  all.map(itemRow) :+ div(
                    padding  := "8px 12px",
                    fontSize := "10px",
                    color    := "#94a3b8",
                    if (knownTotal > items.now().size)
                      s"Showing ${items.now().size} of $knownTotal stored memories"
                    else s"${items.now().size} stored memories"
                  )
            }
          }
      )
    )
  private def toggle(): Unit                                               =
    if (open.now()) close()
    else {
      onOpen()
      query.set("")
      scopeFilter.set(MemoryLibraryPolicy.ScopeAll)
      statusFilter.set(MemoryLibraryPolicy.StatusAll)
      open.set(true)
      refresh()
      js.timers.setTimeout(0) {
        Option(dom.document.getElementById(SearchId).asInstanceOf[dom.HTMLInputElement]).foreach(_.focus())
      }
    }
  private def fetchStatus(): Future[MemoryStatus]                          =
    statusInFlight match {
      case Some(pending) => pending.future
      case None          =>
        val pending = Promise[MemoryStatus]()
        statusInFlight = Some(pending)
        client.status().onComplete { result =>
          statusInFlight = None
          pending.tryComplete(result)
        }
        pending.future
    }
  private def probeStatus(showChecking: Boolean, retryOnce: Boolean): Unit = {
    if (showChecking && !triggerState.now().isInstanceOf[MemoryTriggerState.Ready])
      triggerState.set(MemoryTriggerState.Checking)
    fetchStatus().onComplete {
      case scala.util.Success(value)          =>
        statusRetryTimer.foreach(js.timers.clearTimeout)
        statusRetryTimer = js.undefined
        triggerState.set(MemoryTriggerState.Ready(value))
      case scala.util.Failure(_) if retryOnce =>
        statusRetryTimer.foreach(js.timers.clearTimeout)
        statusRetryTimer = js.timers.setTimeout(1000) {
          statusRetryTimer = js.undefined
          probeStatus(showChecking = false, retryOnce = false)
        }
      case scala.util.Failure(error)          =>
        triggerState.set(
          MemoryTriggerState.Unavailable(
            Option(error.getMessage).getOrElse("Memory runtime unavailable")
          )
        )
    }
  }
  private def refresh(): Unit                                              = {
    if (refreshInFlight) return
    refreshInFlight = true
    loading.set(true)
    loadError.set("")
    statusRetryTimer.foreach(js.timers.clearTimeout)
    statusRetryTimer = js.undefined
    if (!triggerState.now().isInstanceOf[MemoryTriggerState.Ready])
      triggerState.set(MemoryTriggerState.Checking)
    fetchStatus().onComplete {
      case scala.util.Success(value) =>
        triggerState.set(MemoryTriggerState.Ready(value))
        if (!value.enabled) {
          items.set(Nil)
          total.set(0)
          loading.set(false)
          refreshInFlight = false
        } else
          client
            .list(limit = 300)
            .map { list =>
              items.set(list.items)
              total.set(list.total)
              loading.set(false)
              refreshInFlight = false
            }
            .recover { case error =>
              loadError.set(Option(error.getMessage).getOrElse("unknown error"))
              loading.set(false)
              refreshInFlight = false
            }
            .foreach(_ => ())
      case scala.util.Failure(error) =>
        triggerState.set(
          MemoryTriggerState.Unavailable(
            Option(error.getMessage).getOrElse("Memory runtime unavailable")
          )
        )
        loading.set(false)
        refreshInFlight = false
    }
  }
  private def action(item: MemoryItem, decision: String): Unit             =
    client
      .mutate(MemoryMutation(id = item.id, decision = decision))
      .map(_ => refresh())
      .recover { case error =>
        Toast.error(s"Memory action failed: ${Option(error.getMessage).getOrElse("unknown error")}")
      }
  private def filterButton(label: String, selected: Signal[Boolean], run: () => Unit): HtmlElement =
    button(
      `type`       := "button",
      padding      := "2px 7px",
      cursor.pointer,
      fontSize     := "10px",
      lineHeight   := "1.4",
      color <-- selected.map(value => if (value) "#1d4ed8" else "#64748b"),
      fontWeight <-- selected.map(value => if (value) "600" else "400"),
      backgroundColor <-- selected.map(value => if (value) "#eff6ff" else "#ffffff"),
      border <-- selected.map(value => if (value) "1px solid #bfdbfe" else "1px solid #e2e8f0"),
      borderRadius := "999px",
      onClick --> { _ => run() },
      label
    )
  private def smallButton(label: String, run: () => Unit): HtmlElement     =
    button(
      label,
      flexShrink      := "0",
      whiteSpace      := "nowrap",
      padding         := "2px 7px",
      cursor.pointer,
      fontSize        := "10px",
      backgroundColor := "#ffffff",
      color           := "#374151",
      border          := "1px solid #d1d5db",
      borderRadius    := "4px",
      onClick --> { _ => run() }
    )
  private def itemRow(item: MemoryItem): HtmlElement                       =
    div(
      display.flex,
      alignItems.flexStart,
      gap          := "8px",
      padding      := "8px 12px",
      borderBottom := "1px solid #f1f5f9",
      div(
        flex           := "1",
        minWidth       := "0",
        div(
          display.flex,
          alignItems.center,
          flexWrap.wrap,
          gap                        := "6px",
          span(item.scope, fontSize := "10px", color := "#475569"),
          span(item.kind, fontSize  := "10px", color := "#64748b"),
          span(
            item.status,
            fontSize                := "10px",
            color                   := (if (item.status == "pending") "#b45309" else "#15803d")
          ),
          if (item.pinned) span("pinned", fontSize := "10px", color := "#1d4ed8") else span("")
        ),
        div(
          item.text,
          marginTop                  := "3px",
          fontSize                   := "12px",
          color                      := "#111827",
          lineHeight                 := "1.45",
          styleProp("overflow-wrap") := "anywhere"
        ),
        div(
          s"confidence ${"%.2f".format(item.confidence)} · importance ${item.importance}",
          marginTop                  := "2px",
          fontSize                   := "10px",
          color                      := "#94a3b8"
        )
      ),
      div(
        display.flex,
        gap            := "4px",
        flexWrap       := "nowrap",
        flexShrink     := "0",
        justifyContent := "flex-end",
        if (item.status == "pending") smallButton("Approve", () => action(item, "approve")) else span(""),
        if (item.status == "pending") smallButton("Reject", () => action(item, "reject")) else span(""),
        smallButton(if (item.pinned) "Unpin" else "Pin", () => action(item, if (item.pinned) "unpin" else "pin")),
        smallButton("Delete", () => action(item, "delete"))
      )
    )
}
