package com.just3bowls.api
import com.just3bowls.api.protocol.*

import zio.test._

object OpenAiSseSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("OpenAiSse")(
    test("wraps every payload in a valid data event") {
      assertTrue(OpenAiSse.data("""{"ok":true}""") == "data: {\"ok\":true}\n\n")
    },
    test("the terminal marker is a valid data event") {
      assertTrue(OpenAiSse.done == "data: [DONE]\n\n")
    }
  )
}
