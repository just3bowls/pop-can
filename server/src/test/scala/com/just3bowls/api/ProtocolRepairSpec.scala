package com.just3bowls.api
import com.just3bowls.api.marker.*

import zio.test._

object ProtocolRepairSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("ProtocolRepair")(
    test("contains only the latest validation errors and repair instructions") {
      val directive = ProtocolRepair.directive(
        List("Browser response is missing required marker(s): reasoning_content")
      )
      assertTrue(
        directive.contains("missing required marker(s): reasoning_content"),
        directive.contains("Return only a corrected replacement response"),
        !directive.contains("@@BEG:response@@"),
        directive.length < 1000
      )
    }
  )
}
