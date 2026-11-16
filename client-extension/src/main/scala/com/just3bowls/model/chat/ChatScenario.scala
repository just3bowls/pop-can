package com.just3bowls.model.chat

/**
 * Non-tool scenario context for a Chat run.
 *
 * Tool availability belongs exclusively to `AgentToolSurface` and the serialized request tools. This layer only carries
 * transient context such as attached tabs, plan handoff and approvals.
 */
object ChatScenario {

  /** Auto 发起的运行中升级策略；显式模式和 pin 不注入。 */
  def autoHandoffPolicy(allowedModes: List[String]): String =
    if (allowedModes.isEmpty) ""
    else
      s"""If the current mode cannot complete this request appropriately, call request_mode_handoff with one of:
         |${allowedModes.map(mode => s"- $mode").mkString("\n")}
         |
         |Use the transition only when it is necessary. Answer normally when the current mode is already sufficient.
         |For plan, the user will approve or revise the generated plan before execution continues.""".stripMargin

  val planRequestPolicy: String                                                     =
    """You are creating an execution plan for the user's request.
      |
      |Return a concise markdown plan with:
      |- Goal
      |- Ordered steps, one action per step
      |- Completion criteria
      |- Relevant risks or assumptions
      |
      |Do not execute the task yet. The only tool allowed for this planning turn is submit_plan.
      |The plan will be shown to the user for approval.""".stripMargin
  def approvedPlanPolicy(plan: String, note: String): String                        = {
    val revision = Option(note).getOrElse("").trim
    val extra    = if (revision.isEmpty) "" else s"\nUser revision request: $revision"
    s"""The user approved this execution plan:
       |
       |$plan$extra
       |
       |Execute the plan now and produce the final answer. Use the declared tools when evidence or browser interaction is needed.""".stripMargin
  }
  def attachedTabsPolicy(tabs: List[com.just3bowls.api.chat.BrowserTabRef]): String =
    if (tabs.isEmpty) ""
    else
      val lines = tabs.map(tab => s"- ${tab.ref}: ${tab.title} | ${tab.url}").mkString("\n")
      s"""
         |
         |Attached tabs for this run:
         |$lines
         |
         |These are explicit user references, not automatically the current page. Use browser_use_tab(ref) when the question
         |is about interacting with one of them; later browser tools then target it. browser_extract(urls) can read them
         |without switching. References expire after this run.""".stripMargin
}
