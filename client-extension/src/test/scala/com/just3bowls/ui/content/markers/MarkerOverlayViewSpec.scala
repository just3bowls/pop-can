package com.just3bowls.ui.content.markers

import munit.FunSuite

class MarkerOverlayViewSpec extends FunSuite {
  test("places a side label on the side with enough viewport space") {
    assertEquals(MarkerOverlayView.labelSide("new-chat", 46, 88, 1283), "right")
    assertEquals(MarkerOverlayView.labelSide("new-chat", 300, 342, 1283), "left")
  }

  test("chooses the roomier side when neither side has full label space") {
    assertEquals(MarkerOverlayView.labelSide("new-chat", 20, 160, 200), "right")
    assertEquals(MarkerOverlayView.labelSide("new-chat", 160, 180, 200), "left")
  }
}
