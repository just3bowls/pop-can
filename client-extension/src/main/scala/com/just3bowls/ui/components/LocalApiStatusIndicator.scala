package com.just3bowls.ui.components

import com.just3bowls.model.localapi.LocalApiRuntimeStatus
import com.raquo.laminar.api.L._

private[ui] object LocalApiStatus {
  val Endpoint = LocalApiRuntimeStatus.Endpoint

  def isActive(status: String, running: Boolean): Boolean =
    LocalApiRuntimeStatus.isActive(status, running)

  def isError(status: String): Boolean =
    LocalApiRuntimeStatus.isError(status)

  def color(status: String): String =
    if (status == "Connected") "#16a34a"
    else if (status == "Connecting..." || status == "Reconnecting...") "#f59e0b"
    else if (isError(status)) "#dc2626"
    else "#cbd5e1"

  def controlColor(status: String, active: Boolean): String =
    if (active) color(status)
    else if (isError(status)) color(status)
    else "#6b7280"

  def indicatorColor(status: String, active: Boolean): String =
    if (active || isError(status)) color(status)
    else "#94a3b8"

  def indicatorHollow(status: String, active: Boolean): Boolean =
    !active && !isError(status)

  def isProgress(status: String): Boolean =
    LocalApiRuntimeStatus.isProgress(status)
}

private[ui] object LocalApiStatusIndicator {
  def render(
      statusSignal: Signal[String],
      activeSignal: Signal[Boolean],
      onOpen: () => Unit
  ): HtmlElement = {
    val hover           = Var(false)
    val visual          = statusSignal.combineWith(activeSignal).map { case (status, active) =>
      (
        status,
        LocalApiStatus.indicatorColor(status, active),
        LocalApiStatus.indicatorHollow(status, active),
        LocalApiStatus.isProgress(status)
      )
    }
    button(
      cls          := "th-config-focus",
      `type`       := "button",
      aria.label <-- visual.map { case (status, _, _, _) =>
        s"Local API $status. Open API settings"
      },
      display      := "inline-flex",
      alignItems.center,
      justifyContent.center,
      position     := "relative",
      width        := "24px",
      height       := "22px",
      padding      := "0",
      cursor.pointer,
      border       := "none",
      borderRadius := "4px",
      backgroundColor <-- hover.signal.map(value => if (value) "#f3f4f6" else "transparent"),
      color <-- hover.signal.map(value => if (value) "#111827" else "#6b7280"),
      transition   := "color 120ms ease, background-color 120ms ease",
      title <-- visual.map { case (status, _, _, _) =>
        s"Local API · $status\n${LocalApiStatus.Endpoint}\nClick to open API settings"
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
      span(
        position           := "absolute",
        right              := "1px",
        bottom             := "1px",
        width              := "5px",
        height             := "5px",
        boxSizing          := "border-box",
        flexShrink         := "0",
        borderRadius       := "999px",
        boxShadow          := "0 0 0 1px #ffffff",
        backgroundColor <-- visual.map { case (_, color, hollow, _) =>
          if (hollow) "transparent" else color
        },
        border <-- visual.map { case (_, color, hollow, _) =>
          if (hollow) s"1px solid $color" else "none"
        },
        animation <-- visual.map { case (_, _, _, progress) =>
          if (progress) "popcan-api-pulse 1.2s ease-in-out infinite" else "none"
        }
      ),
      onMouseEnter --> { _ => hover.set(true) },
      onMouseLeave --> { _ => hover.set(false) },
      onClick --> { _ => onOpen() }
    )
  }
}
