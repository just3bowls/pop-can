package com.just3bowls.model.chat

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

/** UI 与 LangGraph interrupt 之间的薄桥；暂停和恢复仍由 LangGraph 图负责。 */
object AgentPlanApproval {
  final case class Decision(action: String, note: String = "")
  private val pending = mutable.Map.empty[String, Promise[Decision]]
  def request(planId: String, plan: String, onEvent: TabAiEvent => Unit): Future[Decision] = {
    val promise = Promise[Decision]()
    pending.update(planId, promise)
    onEvent(TabAiEvent.PlanProposed(planId, plan))
    promise.future
  }
  def resolve(planId: String, action: String, note: String = ""): Boolean                  =
    pending.remove(planId) match {
      case Some(promise) =>
        promise.trySuccess(Decision(action, Option(note).getOrElse("").trim))
        true
      case None          => false
    }
  def cancelAll(reason: String): Unit                                                      = {
    val values = pending.values.toList
    pending.clear()
    values.foreach(_.tryFailure(new RuntimeException(reason)))
  }
}
