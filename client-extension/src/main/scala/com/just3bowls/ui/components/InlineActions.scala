package com.just3bowls.ui.components

import com.raquo.laminar.api.L._
import org.scalajs.dom

/** Neutral action button shared by Config, Providers, and session management surfaces. */
private[ui] object NeutralActionButton {
  def render(
      label: Modifier[HtmlElement],
      onClickAction: () => Unit,
      disabledSignal: Signal[Boolean] = Val(false),
      textSize: String = "11px",
      spacing: String = "4px 9px",
      displaySignal: Signal[String] = Val("inline-flex"),
      fixedSize: Option[String] = None,
      stopPropagation: Boolean = false,
      focusOnMount: Boolean = false,
      titleText: Option[String] = None,
      ariaLabel: Option[String] = None
  ): HtmlElement = {
    val buttonNode = button(
      label,
      `type`          := "button",
      display <-- displaySignal,
      alignItems.center,
      justifyContent.center,
      width           := fixedSize.getOrElse("auto"),
      height          := fixedSize.getOrElse("auto"),
      padding         := fixedSize.fold(spacing)(_ => "0"),
      fontSize        := textSize,
      lineHeight      := "1",
      whiteSpace.nowrap,
      cursor <-- disabledSignal.map(if (_) "not-allowed" else "pointer"),
      backgroundColor := "#ffffff",
      color           := "#374151",
      border          := "1px solid #d1d5db",
      borderRadius    := "4px",
      disabled <-- disabledSignal,
      opacity <-- disabledSignal.map(if (_) "0.5" else "1"),
      transition      := "background-color 120ms ease",
      titleText.fold(emptyNode)(value => title := value),
      ariaLabel.fold(emptyNode)(value => aria.label := value),
      onMouseEnter --> { event =>
        val target = event.currentTarget.asInstanceOf[dom.HTMLButtonElement]
        if (!target.disabled) target.style.backgroundColor = "#f8fafc"
      },
      onMouseLeave --> { event =>
        val target = event.currentTarget.asInstanceOf[dom.HTMLButtonElement]
        if (!target.disabled) target.style.backgroundColor = "#ffffff"
      },
      onClick --> { event =>
        if (stopPropagation) {
          event.stopPropagation()
          event.preventDefault()
        }
        onClickAction()
      }
    )
    if (focusOnMount) buttonNode.amend(onMountFocus) else buttonNode
  }
}

/** Compact square action used for rename/delete glyphs. */
private[ui] object NeutralIconButton {
  def render(
      label: String,
      tooltip: String,
      onClickAction: () => Unit,
      disabledSignal: Signal[Boolean] = Val(false),
      stopPropagation: Boolean = true
  ): HtmlElement =
    NeutralActionButton.render(
      span(label),
      onClickAction,
      disabledSignal = disabledSignal,
      textSize = "11px",
      fixedSize = Some("22px"),
      stopPropagation = stopPropagation,
      titleText = Some(tooltip),
      ariaLabel = Some(tooltip)
    )
}

/** Shared two-step destructive confirmation matching the Provider deletion pattern. */
private[ui] object InlineDeleteConfirmation {
  def render(
      onConfirm: () => Unit,
      onCancel: () => Unit,
      busySignal: Signal[Boolean] = Val(false),
      confirmLabel: Signal[String] = Val("Yes"),
      cancelLabel: String = "No"
  ): HtmlElement        =
    div(
      display.flex,
      alignItems.center,
      gap        := "6px",
      flexShrink := "0",
      span("Delete?", fontSize := "11px", color := "#b91c1c", whiteSpace.nowrap),
      NeutralActionButton.render(
        child.text <-- confirmLabel,
        onConfirm,
        disabledSignal = busySignal,
        stopPropagation = true,
        focusOnMount = true
      ),
      NeutralActionButton.render(
        span(cancelLabel),
        onCancel,
        disabledSignal = busySignal,
        stopPropagation = true
      )
    )
}
