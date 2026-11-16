package com.just3bowls.facade
import scala.scalajs.js

/** Agent 过程快照 DTO：ChatMessage.steps 的 JSON 结构。 */
@js.native
trait ProcessStepDto extends js.Object {
  val index: Int                              = js.native
  val kind: js.UndefOr[String]                = js.native
  val label: js.UndefOr[String]               = js.native
  val status: String                          = js.native
  val finalAnswer: js.UndefOr[Boolean]        = js.native
  val thinking: String                        = js.native
  val content: String                         = js.native
  val toolCalls: js.Array[ProcessToolCallDto] = js.native
}

/** 过程中单次工具调用 DTO。 */
@js.native
trait ProcessToolCallDto extends js.Object {
  val name: String       = js.native
  val arguments: String  = js.native
  val result: String     = js.native
  val ok: Boolean        = js.native
  val done: Boolean      = js.native
  val durationMs: Double = js.native
}

@js.native
trait StructuredAgentOutput extends js.Object {
  def messages: js.UndefOr[js.Array[js.Any]] = js.native
}

@js.native
trait PlanApprovalState extends js.Object {
  def note: js.UndefOr[String]   = js.native
  def action: js.UndefOr[String] = js.native
}

@js.native
trait PlanGraphState extends js.Object {
  def approval: js.UndefOr[PlanApprovalState] = js.native
  def plan: js.UndefOr[String]                = js.native
}

@js.native
trait PlanToolArguments extends js.Object {
  def plan: js.UndefOr[String] = js.native
}

@js.native
trait LlmResponseMetadata extends js.Object {
  def reasoning_content: js.UndefOr[String] = js.native
}
