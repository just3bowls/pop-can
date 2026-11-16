package com.just3bowls.ui.components

import com.raquo.laminar.api.L._
import org.scalajs.dom

/** Shared compact text switch used by provider and Local API controls. */
private[ui] object OnOffLabel {
  def render(
      activeSignal: Signal[Boolean],
      colorSignal: Signal[String],
      tooltipSignal: Signal[String],
      ariaLabelSignal: Signal[String]
  )(onClickAction: dom.MouseEvent => Unit): HtmlElement           =
    button(
      cls           := "th-config-focus",
      `type`        := "button",
      role          := "switch",
      aria.checked <-- activeSignal.map(_.toString),
      aria.label <-- ariaLabelSignal,
      display       := "inline-flex",
      alignItems.center,
      flexShrink    := "0",
      padding       := "0",
      cursor.pointer,
      border        := "none",
      background    := "transparent",
      fontFamily    := "ui-monospace, SFMono-Regular, Menlo, monospace",
      fontSize      := "10px",
      fontWeight    := "600",
      letterSpacing := "0.2px",
      color <-- colorSignal,
      title <-- tooltipSignal,
      onClick --> { event =>
        event.stopPropagation()
        onClickAction(event)
      },
      child.text <-- activeSignal.map(value => if (value) "[ON]" else "[OFF]")
    )
}
