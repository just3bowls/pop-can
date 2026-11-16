package com.just3bowls.api
import com.just3bowls.api.webchat.*

import zio.test._

object WebChatErrorPresentationSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("WebChatErrorPresentation")(
    test("separates page-control errors from marker-protocol errors") {
      assertTrue(
        WebChatErrorPresentation.summary("doubao", "Input element not found: /html/div") ==
          "Doubao could not continue this turn: the page input controls were not ready.",
        WebChatErrorPresentation.summary(
          "kimi",
          "Browser response did not use the required @@BEG:response@@ marker"
        ) == "Kimi could not continue this turn: the response did not follow the marker protocol."
      )
    },
    test("falls back to a generic summary and recognizes cancellation") {
      assertTrue(
        WebChatErrorPresentation.summary("", "unexpected failure") ==
          "The provider could not continue this turn.",
        WebChatErrorPresentation.isCancellation("Task canceled"),
        !WebChatErrorPresentation.isCancellation("Input element not found")
      )
    }
  )
}
