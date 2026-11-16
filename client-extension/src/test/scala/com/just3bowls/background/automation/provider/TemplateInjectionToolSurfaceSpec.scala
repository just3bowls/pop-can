package com.just3bowls.background.automation.provider

import com.just3bowls.model.chat.{AgentToolRegistry, AgentToolSurface}

class TemplateInjectionToolSurfaceSpec extends munit.FunSuite {
  test("native payload only changes request tools, not capability system text") {
    val off = TemplateInjection.buildNativePayload(
      TemplateInjection.ModeDelta,
      provider = "test",
      delta = Nil,
      userText = "hello",
      surface = AgentToolSurface(Nil)
    )
    val on  = TemplateInjection.buildNativePayload(
      TemplateInjection.ModeDelta,
      provider = "test",
      delta = Nil,
      userText = "hello",
      surface = AgentToolSurface(AgentToolRegistry.browserTools)
    )

    assert(!off.contains("Enabled PopCan tools"))
    assert(!off.contains("Disabled PopCan tools"))
    assert(!off.contains("\"tools\""))
    assert(on.contains("\"tools\""))
    assert(on.contains("browser_search"))
  }
}
