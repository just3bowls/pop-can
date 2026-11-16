package com.just3bowls.model.chat

class AgentToolSurfaceSpec extends munit.FunSuite {
  private def tool(
      name: String,
      description: String = "description",
      kind: ToolKind = ToolKind.Local,
      terminal: Boolean = false,
      paramDescription: String = "value"
  ): AgentToolSpec =
    AgentToolSpec(
      name = name,
      description = description,
      kind = kind,
      params = List(ToolParam("value", ToolParamKind.Text, paramDescription)),
      terminal = terminal
    )

  test("revision is stable across tool order") {
    val first  = tool("first")
    val second = tool("second")

    assertEquals(
      AgentToolSurface(List(first, second)).revision,
      AgentToolSurface(List(second, first)).revision
    )
  }

  test("revision changes with tool definition content") {
    val base = AgentToolSurface(List(tool("search")))

    assertNotEquals(base.revision, AgentToolSurface(List(tool("search", description = "changed"))).revision)
    assertNotEquals(base.revision, AgentToolSurface(List(tool("search", terminal = true))).revision)
    assertNotEquals(base.revision, AgentToolSurface(List(tool("search", kind = ToolKind.BrowserUse))).revision)
    assertNotEquals(base.revision, AgentToolSurface(List(tool("search", paramDescription = "changed"))).revision)
  }

  test("empty surface is stable and has no browser tools") {
    val first  = AgentToolSurface(Nil)
    val second = AgentToolSurface(Nil)

    assertEquals(first.revision, second.revision)
    assertEquals(first.toolNames, Set.empty[String])
    assert(!first.browserToolsEnabled)
  }

  test("browser surface exposes exact tool names") {
    val surface = AgentToolSurface(AgentToolRegistry.browserTools)

    assertEquals(surface.toolNames, AgentToolRegistry.browserTools.map(_.name).toSet)
    assert(surface.browserToolsEnabled)
  }

}
