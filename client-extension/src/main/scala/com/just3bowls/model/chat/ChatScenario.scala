package com.just3bowls.model.chat

/**
 * 插件自己定义的 chat 场景提示词。
 *
 * 固定模版（`ThinOpenAi`）负责 marker 协议与"自己的能力用、自家身份别带进来"的分寸，这一层负责「这一回合怎么答」：外部/时效事实必须来自工具输出、 搜索只是索引、引用要给 URL。只作为 `system`
 * 消息进当次 body，不写历史、不落库。
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

  /** browser 工具打开时注入；关掉工具时不注入，让网页 AI 用它自己的能力回答。 */
  val browserToolsPolicy: String                                                    =
    """You are answering this chat request in a real browser. The browser tools declared in this request are the only tools you may call.
      |
      |Evidence
      |- Your own knowledge may be outdated. For anything that changes over time (news, prices, releases, schedules, versions, availability, people/company status), or whenever you are not confident a fact is still true, you are not allowed to answer from memory: gather evidence with the declared tools first.
      |- The chain is browser_search (find candidates) -> browser_navigate (open the result you intend to use) -> browser_extract without urls (read the current page). Search snippets are a preview/index, not proof.
      |- After browser_navigate, never pass the URL you just opened to browser_extract(urls). Use urls only for extra pages or batch comparison; the tool reuses existing same-URL tool tabs.
      |- Browser tool output begins with [browser workspace], the current truth for open tool tabs and the current operation page. Do not assume a page is unopened when the workspace already lists it.
      |- Open and read a page with browser_extract whenever the answer needs detail, a quote, or the sources disagree. A snippet is enough only for a short factual answer that the snippet itself fully supports.
      |- Tool results come back to you as messages: keep calling tools until the evidence covers the question, then write the final answer. Do not stop after the first search when it only gave you titles and previews.
      |- Every factual claim must come from tool output in this conversation, never from memory. If evidence is missing or a page could not be read, say what you could not verify instead of filling the gap.
      |- Never invent facts, quotes, URLs or tool names -- only the declared tools exist. If a tool fails, read the error and adjust (another query, another result, or browser_snapshot); never silently fall back to answering from memory.
      |
      |Answer
      |- Answer in the user's language, in markdown.
      |- End the answer with a short "Sources" list of the URLs you actually relied on. Omit it only when you relied on no external source at all.""".stripMargin
  val planRequestPolicy: String                                                     =
    """You are creating an execution plan for the user's request.
      |
      |Return a concise markdown plan with:
      |- Goal
      |- Ordered steps, one action per step
      |- Completion criteria
      |- Relevant risks or assumptions
      |
      |Do not execute the task yet and do not call tools. The plan will be shown to the user for approval.""".stripMargin
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
