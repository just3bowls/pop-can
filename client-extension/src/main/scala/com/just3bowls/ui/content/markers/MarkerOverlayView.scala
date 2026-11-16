package com.just3bowls.ui.content.markers

import com.raquo.laminar.api.L._

final case class MarkerBoxView(
    role: String,
    label: String,
    color: String,
    labelOnly: Boolean,
    left: Double,
    top: Double,
    width: Double,
    height: Double,
    flash: Boolean
)

final case class MarkerRingView(
    id: String,
    color: String,
    left: Double,
    top: Double,
    width: Double,
    height: Double
)

final case class MarkerOverlayState(
    boxes: List[MarkerBoxView],
    rings: List[MarkerRingView]
)

object MarkerOverlayView {
  def render(state: MarkerOverlayState): HtmlElement =
    div(
      position := "fixed",
      top      := "0",
      right    := "0",
      bottom   := "0",
      left     := "0",
      pointerEvents.none,
      zIndex   := "2147483646",
      state.boxes.map { box =>
        div(
          position := "fixed",
          left     := s"${box.left}px",
          top      := s"${box.top}px",
          width    := s"${box.width}px",
          height   := s"${box.height}px",
          border   := (if (box.labelOnly) "0" else s"1px dashed ${box.color}"),
          pointerEvents.none,
          span(
            position        := "absolute",
            left            := "-1px",
            top             := "-18px",
            padding         := "1px 5px",
            borderRadius    := "3px",
            fontSize        := "10px",
            lineHeight      := "1.35",
            color           := "#fff",
            backgroundColor := box.color,
            opacity         := (if (box.flash) "1" else ".86"),
            transition      := "opacity .2s ease",
            box.label
          )
        )
      },
      state.rings.map { ring =>
        div(
          position     := "fixed",
          left         := s"${ring.left}px",
          top          := s"${ring.top}px",
          width        := s"${ring.width}px",
          height       := s"${ring.height}px",
          border       := s"3px solid ${ring.color}",
          borderRadius := "6px",
          pointerEvents.none,
          boxShadow    := s"0 0 0 3px ${ring.color}33"
        )
      }
    )
}
