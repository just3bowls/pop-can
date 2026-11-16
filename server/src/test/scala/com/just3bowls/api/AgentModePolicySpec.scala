package com.just3bowls.api
import com.just3bowls.api.chat.*

import zio.test._

object AgentModePolicySpec extends ZIOSpecDefault {

  def spec = suite("AgentModePolicy")(
    test("normalizes current and unknown mode values") {
      assertTrue(
        AgentModePolicy.normalize(" REACT ") == AgentModePolicy.React,
        AgentModePolicy.normalize(" PLAN ") == AgentModePolicy.Plan,
        AgentModePolicy.normalize("unknown") == AgentModePolicy.React,
        AgentModePolicy.normalize("") == AgentModePolicy.React
      )
    },
    test("agent options expose only ReAct and Plan") {
      assertTrue(
        AgentModePolicy.Options.map(_.id) == List(AgentModePolicy.React, AgentModePolicy.Plan),
        AgentModePolicy.matchingOptions("").map(_.id) == List(AgentModePolicy.React, AgentModePolicy.Plan),
        AgentModePolicy.matchingOptions("pl").map(_.id) == List(AgentModePolicy.Plan),
        AgentModePolicy.matchingOptions("RE").map(_.id) == List(AgentModePolicy.React),
        AgentModePolicy.matchingOptions("plan pin").isEmpty,
        AgentModePolicy.matchingOptions("unpin").isEmpty
      )
    },
    test("session mode is explicit and text does not reroute it") {
      val react = AgentModePolicy.route(AgentModePolicy.React)
      val plan  = AgentModePolicy.route(AgentModePolicy.Plan)
      assertTrue(
        react.mode == AgentModePolicy.React,
        plan.mode == AgentModePolicy.Plan,
        react.locked,
        plan.locked,
        react.reason == "session agent mode",
        react.confidence == 1.0
      )
    },
    test("runtime handoff only moves upward unless explicitly locked") {
      assertTrue(
        AgentModePolicy.canHandoff(AgentModePolicy.React, AgentModePolicy.Plan, locked = false),
        !AgentModePolicy.canHandoff(AgentModePolicy.Plan, AgentModePolicy.React, locked = false),
        !AgentModePolicy.canHandoff(AgentModePolicy.React, AgentModePolicy.Plan, locked = true)
      )
    }
  )
}
