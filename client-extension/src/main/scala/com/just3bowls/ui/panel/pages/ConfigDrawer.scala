package com.just3bowls.ui.panel.pages

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js

/**
 * Page-level Config surface.
 *
 * The native plugin page is narrow and chat is long-lived, so Config must not replace or unmount the chat tree. The
 * panel opens up from the bottom-right trigger, owns its scrolling, and keeps the chat mounted while configuration
 * changes.
 */
object ConfigDrawer {
  private val DrawerId                                                                          = "popcan-config-drawer"
  private def close(open: Var[Boolean]): Unit                                                   = {
    open.set(false)
    js.timers.setTimeout(0) {
      dom.document.querySelector(s"button[aria-controls='$DrawerId']") match {
        case button: dom.html.Button => button.focus()
        case _                       => ()
      }
    }
  }
  private def show(open: Var[Boolean], mounted: Var[Boolean], onBeforeOpen: () => Unit): Unit   = {
    onBeforeOpen()
    mounted.set(true)
    open.set(true)
  }
  private def toggle(open: Var[Boolean], mounted: Var[Boolean], onBeforeOpen: () => Unit): Unit =
    if (open.now()) close(open)
    else show(open, mounted, onBeforeOpen)
  private def configSlidersIcon                                                                 =
    svg.svg(
      svg.width          := "16",
      svg.height         := "16",
      svg.viewBox        := "0 0 24 24",
      svg.fill           := "none",
      svg.stroke         := "currentColor",
      svg.strokeWidth    := "1.8",
      svg.strokeLineCap  := "round",
      svg.strokeLineJoin := "round",
      svg.line(svg.x1   := "4", svg.y1  := "6", svg.x2  := "20", svg.y2 := "6"),
      svg.circle(svg.cx := "9", svg.cy  := "6", svg.r   := "2"),
      svg.line(svg.x1   := "4", svg.y1  := "12", svg.x2 := "20", svg.y2 := "12"),
      svg.circle(svg.cx := "15", svg.cy := "12", svg.r  := "2"),
      svg.line(svg.x1   := "4", svg.y1  := "18", svg.x2 := "20", svg.y2 := "18"),
      svg.circle(svg.cx := "8", svg.cy  := "18", svg.r  := "2")
    )
  def renderTrigger(
      open: Var[Boolean],
      mounted: Var[Boolean],
      onBeforeOpen: () => Unit = () => ()
  ): HtmlElement = {
    val triggerHover           = Var(false)
    button(
      cls           := "th-config-focus",
      aria.expanded <-- open.signal,
      aria.controls := DrawerId,
      aria.label <-- open.signal.map(value =>
        if (value) "Close configuration" else "Open configuration"
      ),
      display       := "inline-flex",
      alignItems.center,
      justifyContent.center,
      width         := "24px",
      height        := "22px",
      padding       := "0",
      cursor.pointer,
      border        := "none",
      borderRadius  := "4px",
      backgroundColor <-- Signal.combine(open.signal, triggerHover.signal).map {
        case (true, _)      => "#eff6ff"
        case (false, true)  => "#f3f4f6"
        case (false, false) => "transparent"
      },
      color <-- Signal.combine(open.signal, triggerHover.signal).map {
        case (true, _)      => "#1d4ed8"
        case (false, true)  => "#111827"
        case (false, false) => "#6b7280"
      },
      transition    := "color 120ms ease, background-color 120ms ease",
      title <-- open.signal.map(value =>
        if (value) "Close Config" else "Open Config"
      ),
      configSlidersIcon,
      onMouseEnter --> { _ => triggerHover.set(true) },
      onMouseLeave --> { _ => triggerHover.set(false) },
      onClick --> { _ => toggle(open, mounted, onBeforeOpen) }
    )
  }
  def renderOverlay(
      ctx: ConfigPanelContext,
      open: Var[Boolean],
      mounted: Var[Boolean]
  ): HtmlElement = {
    def drawer(): HtmlElement =
      div(
        idAttr          := DrawerId,
        role            := "dialog",
        aria.label      := "Configuration",
        aria.hidden <-- open.signal.map(!_),
        position        := "fixed",
        right           := "8px",
        bottom          := "52px",
        width           := "min(720px, calc(100vw - 16px))",
        height          := "min(720px, calc(100vh - 60px))",
        zIndex          := "300",
        display.flex,
        flexDirection.column,
        backgroundColor := "#ffffff",
        border          := "1px solid #d1d5db",
        borderRadius    := "10px",
        boxShadow       := "0 18px 40px rgba(15,23,42,0.2)",
        overflow.hidden,
        transform <-- open.signal.map(value =>
          if (value) "translateY(0) scale(1)" else "translateY(12px) scale(0.985)"
        ),
        opacity <-- open.signal.map(value => if (value) "1" else "0"),
        visibility <-- open.signal.map(value => if (value) "visible" else "hidden"),
        pointerEvents <-- open.signal.map(value => if (value) "auto" else "none"),
        transition <-- open.signal.map(value =>
          if (value)
            "transform 180ms ease, opacity 180ms ease, visibility 0s linear 0s"
          else
            "transform 180ms ease, opacity 180ms ease, visibility 0s linear 180ms"
        ),
        documentEvents(_.onKeyDown)
          .filter { event =>
            val target = event.target match {
              case element: dom.Element => element
              case _                    => null
            }
            event.key == "Escape" &&
            open.now() &&
            target != null &&
            target.closest(s"#$DrawerId, [aria-controls='$DrawerId']") != null
          }
          .mapToUnit --> { _ => close(open) },
        div(
          display.flex,
          alignItems.center,
          flexShrink                       := "0",
          gap                              := "8px",
          padding                          := "12px 14px",
          borderBottom                     := "1px solid #e5e7eb",
          span(
            flexGrow        := "1",
            fontWeight.bold,
            fontSize        := "14px",
            color           := "#0f172a",
            "Configuration"
          ),
          button(
            "×",
            cls             := "th-config-focus",
            aria.label      := "Close configuration",
            fontSize        := "16px",
            lineHeight      := "1",
            width           := "28px",
            height          := "28px",
            padding         := "0",
            cursor.pointer,
            backgroundColor := "#f3f4f6",
            color           := "#374151",
            border          := "1px solid #d1d5db",
            borderRadius    := "6px",
            onClick --> { _ => close(open) }
          )
        ),
        div(
          flexShrink                       := "0",
          padding                          := "8px 12px",
          borderBottom                     := "1px solid #e5e7eb",
          backgroundColor                  := "#ffffff",
          ConfigPanel.renderNavigation(ctx)
        ),
        div(
          flexGrow                         := "1",
          minHeight                        := "0",
          overflowY.auto,
          styleProp("overscroll-behavior") := "contain",
          paddingBottom                    := "12px",
          backgroundColor                  := "#f8fafc",
          ConfigPanel.renderContent(ctx)
        )
      )
    div(
      display := "contents",
      child <-- mounted.signal.map { value =>
        if (value) drawer()
        else new CommentNode("")
      }
    )
  }
}
