package com.just3bowls.api.chat

/**
 * Agent 执行模式的纯逻辑入口。
 *
 * 这里只负责用户词汇归一和显式模式路由；真正的执行、循环、并行与暂停恢复全部由 LangGraph 图负责。
 */
object AgentModePolicy {

  val React = "react"
  val Plan  = "plan"

  final case class ModeOption(
      id: String,
      label: String,
      description: String
  )

  val Options: List[ModeOption] = List(
    ModeOption(React, "ReAct", "Answer directly and use browser tools when enabled"),
    ModeOption(Plan, "Plan", "Plan first, then execute after approval")
  )

  val EscalationLadder: List[String] = List(React, Plan)

  final case class Decision(
      mode: String,
      locked: Boolean,
      reason: String,
      confidence: Double
  )

  def normalize(raw: String): String = {
    val value = Option(raw).getOrElse("").trim.toLowerCase
    value match {
      case Plan  => Plan
      case React => React
      case _     => React
    }
  }

  /** `/agent` 列表的过滤规则：空查询显示全部，其余按 id / label 前缀匹配。 */
  def matchingOptions(rawQuery: String): List[ModeOption] = {
    val query = Option(rawQuery).getOrElse("").trim.toLowerCase
    Options.filter(option =>
      query.isEmpty ||
        option.id.toLowerCase.startsWith(query) ||
        option.label.toLowerCase.startsWith(query)
    )
  }

  /** Session 已明确选择模式；请求文本不再触发隐式模式切换。 */
  def route(requestedMode: String): Decision =
    Decision(normalize(requestedMode), locked = true, reason = "session agent mode", confidence = 1.0)

  /** 运行中升级只允许沿固定阶梯向上；显式 Session 模式永不 handoff。 */
  def canHandoff(from: String, to: String, locked: Boolean): Boolean =
    if (locked) false
    else {
      val source = EscalationLadder.indexOf(normalize(from))
      val target = EscalationLadder.indexOf(normalize(to))
      source >= 0 && target > source
    }
}
