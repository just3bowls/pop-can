package com.just3bowls.ui.components

import com.raquo.laminar.api.L._

/** Compact session-scoped switch for the browser tools exposed to the page AI. */
private[ui] object BrowserToolsIndicator {
  def render(enabledSignal: Signal[Boolean], onToggle: () => Unit): HtmlElement = {
    val hover       = Var(false)
    button(
      `type`       := "button",
      display      := "inline-flex",
      alignItems.center,
      justifyContent.center,
      position     := "relative",
      width        := "24px",
      height       := "22px",
      flexShrink   := "0",
      padding      := "0",
      cursor.pointer,
      border       := "none",
      borderRadius := "4px",
      backgroundColor <-- hover.signal.map(value => if (value) "#f3f4f6" else "transparent"),
      color <-- Signal
        .combine(enabledSignal, hover.signal)
        .map { case (enabled, isHovered) =>
          if (isHovered) "#111827"
          else if (enabled) "#6b7280"
          else "#9ca3af"
        },
      opacity <-- enabledSignal.map(enabled => if (enabled) 1.0 else 0.72),
      transition   := "color 120ms ease, background-color 120ms ease, opacity 120ms ease",
      aria.label <-- enabledSignal.map(enabled =>
        if (enabled) "Browser tools on. Click to turn off."
        else "Browser tools off. Click to turn on."
      ),
      aria.pressed <-- enabledSignal.map(_.toString),
      title <-- enabledSignal.map(enabled =>
        if (enabled)
          "Browser tools: On. Click to turn off for this session (/browser-tools)."
        else
          "Browser tools: Off for this session. Click to turn on (/browser-tools). " +
            "Referencing @tab enables them for that run only."
      ),
      svg.svg(
        svg.width          := "16",
        svg.height         := "16",
        svg.viewBox        := "0 0 16 16",
        svg.fill           := "none",
        svg.stroke         := "currentColor",
        svg.strokeWidth    := "1.35",
        svg.strokeLineCap  := "round",
        svg.strokeLineJoin := "round",
        aria.hidden        := true,
        svg.rect(
          svg.x        := "2",
          svg.y        := "2.5",
          svg.width    := "12",
          svg.height   := "11",
          svg.rx       := "2"
        ),
        svg.path(svg.d := "M2.5 5.5h11"),
        svg.path(
          svg.d        := "M7.8 7.2l4.3 2.2-2 .7-.7 2z",
          svg.fill     := "currentColor",
          svg.stroke   := "none"
        ),
        child <-- enabledSignal.map(enabled =>
          if (enabled) emptyNode
          else
            svg.path(
              svg.d           := "M3.2 12.8L12.8 3.2",
              svg.strokeWidth := "1.5"
            )
        )
      ),
      onMouseEnter --> { _ => hover.set(true) },
      onMouseLeave --> { _ => hover.set(false) },
      onClick --> { _ => onToggle() }
    )
  }
}
