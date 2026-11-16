package com.just3bowls.ui.components

import com.raquo.laminar.api.L._

/** Agent Plan 的展示外壳；业务内容由调用方注入。 */
private[ui] object PlanCard {
  final case class Action(
      label: String,
      onClick: () => Unit,
      primary: Boolean = false,
      danger: Boolean = false,
      enabled: Boolean = true
  )
  def render(
      title: String,
      status: String,
      summary: String,
      body: List[Modifier[HtmlElement]],
      actions: List[Action]
  ): HtmlElement         =
    div(
      padding         := "10px",
      backgroundColor := "#f8fafc",
      border          := "1px solid #cbd5e1",
      borderRadius    := "8px",
      fontSize        := "11px",
      color           := "#334155",
      div(
        display.flex,
        alignItems.center,
        flexWrap.wrap,
        gap := "6px",
        span(width := "7px", height := "7px", borderRadius := "50%", backgroundColor := statusColor(status)),
        span(title, fontWeight.bold, color := "#0f172a"),
        span(statusText(status), fontSize  := "10px", color := statusColor(status)),
        if (summary.nonEmpty) span(summary, fontSize := "10px", color := "#64748b") else span(),
        span(flexGrow                      := "1"),
        if (actions.nonEmpty)
          div(
            display.flex,
            alignItems.center,
            flexWrap.wrap,
            gap        := "6px",
            flexShrink := "0",
            actions.map(actionButton)
          )
        else span()
      ),
      div(((marginTop := "7px") :: body)*)
    )
  private def actionButton(action: Action): HtmlElement = {
    val actionColor              =
      if (action.primary) "#ffffff"
      else if (action.danger) "#b91c1c"
      else "#334155"
    val actionBackground         =
      if (action.primary) "#2563eb"
      else if (action.danger) "#fef2f2"
      else "#ffffff"
    val actionBorder             =
      if (action.primary) "1px solid #2563eb"
      else if (action.danger) "1px solid #fecaca"
      else "1px solid #cbd5e1"
    button(
      action.label,
      padding         := "3px 10px",
      cursor          := (if (action.enabled) "pointer" else "not-allowed"),
      fontSize        := "10px",
      color           := actionColor,
      backgroundColor := actionBackground,
      border          := actionBorder,
      borderRadius    := "4px",
      opacity         := (if (action.enabled) "1" else "0.5"),
      disabled        := !action.enabled,
      onClick --> { _ => if (action.enabled) action.onClick() }
    )
  }
  private def statusColor(status: String): String       =
    status match {
      case "done" | "approved" => "#16a34a"
      case "running"           => "#f59e0b"
      case "failed" | "error"  => "#dc2626"
      case "awaiting-plan"     => "#2563eb"
      case _                   => "#94a3b8"
    }
  private def statusText(status: String): String        =
    status match {
      case "awaiting-plan" => "awaiting approval"
      case ""              => "pending"
      case value           => value
    }
}
