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
    labelSide: String,
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
  def labelSide(role: String, left: Double, right: Double, viewportWidth: Double): String = {
    val estimatedWidth = role match {
      case "new-chat" => 70.0
      case "input"    => 42.0
      case _          => 64.0
    }
    val leftSpace      = math.max(0.0, left)
    val rightSpace     = math.max(0.0, viewportWidth - right)
    if (leftSpace >= estimatedWidth + 8) "left"
    else if (rightSpace >= estimatedWidth + 8) "right"
    else if (leftSpace >= rightSpace) "left"
    else "right"
  }

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
          div(
            position                                       := "absolute",
            top                                            := "50%",
            width                                          := "7px",
            height                                         := "1px",
            backgroundColor                                := box.color,
            (if (box.labelSide == "left") right else left) := "-7px"
          ),
          span(
            position                                       := "absolute",
            (if (box.labelSide == "left") right else left) := "calc(100% + 7px)",
            top                                            := "50%",
            transform                                      := "translateY(-50%)",
            display                                        := "inline-block",
            padding                                        := "1px 5px",
            borderRadius    := (if (box.labelSide == "left") "3px 0 0 3px" else "0 3px 3px 0"),
            fontSize        := "10px",
            lineHeight      := "1.35",
            whiteSpace.nowrap,
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
