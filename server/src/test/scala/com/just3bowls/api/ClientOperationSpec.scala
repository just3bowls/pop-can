package com.just3bowls.api

import com.just3bowls.api.protocol.ClientOperation
import zio.test.*

object ClientOperationSpec extends ZIOSpecDefault {
  def spec = suite("ClientOperationSpec")(
    test("uses stable wire values") {
      assertTrue(ClientOperation.Models.wire == "models") &&
      assertTrue(ClientOperation.ChatCompletion.wire == "chat_completion")
    },
    test("rejects unknown operations") {
      assertTrue(ClientOperation.fromWire("unknown").isEmpty)
    }
  )
}
