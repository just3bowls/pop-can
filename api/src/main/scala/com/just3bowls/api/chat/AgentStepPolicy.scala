package com.just3bowls.api.chat

/** LangGraph superstep 预算的统一换算；调用方只表达业务上的工具轮数。 */
object AgentStepPolicy {

  /** 无 terminal middleware 的 createAgent ReAct 循环。 */
  def reactRecursionLimit(toolRounds: Int): Int =
    math.max(4, math.max(0, toolRounds) * 2 + 2)

  /**
   * 带 terminal-tool middleware 的结构化 Agent。
   *
   * terminal 成功后的下一次 beforeModel 还需要一个 superstep 才能结束，所以不能沿用裸 ReAct 的 2 倍公式。
   */
  def structuredRecursionLimit(toolRounds: Int): Int =
    math.max(8, math.max(1, toolRounds) * 3 + 4)
}
