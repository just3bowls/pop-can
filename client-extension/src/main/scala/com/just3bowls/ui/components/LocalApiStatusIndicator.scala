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
      gap          := "4px",
      height       := "22px",
      padding      := "0 4px",
      cursor.pointer,
      border       := "none",
      borderRadius := "4px",
      backgroundColor <-- hover.signal.map(value => if (value) "#f3f4f6" else "transparent"),
      color        := "#6b7280",
      transition   := "background-color 120ms ease",
      title <-- visual.map { case (status, _, _, _) =>
        s"Local API · $status\n${LocalApiStatus.Endpoint}\nClick to open API settings"
      },
      span("API", fontSize := "10px", fontWeight := "500", lineHeight := "1"),
      span(
        width              := "6px",
        height             := "6px",
        boxSizing          := "border-box",
        flexShrink         := "0",
        borderRadius       := "999px",
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
