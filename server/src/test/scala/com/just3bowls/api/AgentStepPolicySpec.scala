package com.just3bowls.api
import com.just3bowls.api.chat.*

import zio.test._

object AgentStepPolicySpec extends ZIOSpecDefault {

  def spec = suite("AgentStepPolicy")(
    test("react budget keeps the existing two-superstep-per-round rule") {
      assertTrue(
        AgentStepPolicy.reactRecursionLimit(0) == 4,
        AgentStepPolicy.reactRecursionLimit(1) == 4,
        AgentStepPolicy.reactRecursionLimit(4) == 10
      )
    },
    test("structured terminal middleware leaves room for the post-tool beforeModel jump") {
      assertTrue(
        AgentStepPolicy.structuredRecursionLimit(1) == 8,
        AgentStepPolicy.structuredRecursionLimit(2) == 10,
        AgentStepPolicy.structuredRecursionLimit(4) == 16,
        AgentStepPolicy.structuredRecursionLimit(0) == 8
      )
    }
  )
}
