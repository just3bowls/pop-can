package com.just3bowls.model.chat

class AgentCapabilityPolicySpec extends munit.FunSuite {
  private def localTool(name: String): AgentToolSpec =
    AgentToolSpec(
      name = name,
      description = name,
      kind = ToolKind.Local,
      params = Nil
    )

  test("browser off policy keeps non-browser tools available") {
    val memory = localTool("memory_search")
    val policy = ChatScenario.capabilityPolicy(AgentCapabilitySnapshot(List(memory)))

    assert(policy.contains("Browser tools are disabled"))
    assert(policy.contains("Historical browser tool calls"))
    assert(policy.contains("memory_search"))
    assert(!policy.contains("No tools are declared"))
  }

  test("browser on policy uses the exact declared capability set") {
    val tool   = AgentToolSpec(
      name = "browser_search",
      description = "search",
      kind = ToolKind.BrowserUse,
      params = Nil
    )
    val policy = ChatScenario.capabilityPolicy(AgentCapabilitySnapshot(List(tool)))

    assert(policy.contains("Browser tools are enabled"))
    assert(policy.contains("browser_search"))
    assert(!policy.contains("are the only tools you may call"))
  }

  test("no tools policy still explains the browser capability state") {
    val policy = ChatScenario.capabilityPolicy(AgentCapabilitySnapshot(Nil))

    assert(policy.contains("Browser tools are disabled"))
    assert(policy.contains("No tools are declared for this turn"))
  }
}
