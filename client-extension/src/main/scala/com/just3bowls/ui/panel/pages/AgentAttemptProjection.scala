package com.just3bowls.ui.panel.pages

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.chat.{ChatAttempt, ChatMessage}

import scala.scalajs.js

/** Projects retry/recovery details into the attempt shown on a chat bubble. */
private[pages] object AgentAttemptProjection {
  def processSteps(raw: String): List[js.Any] =
    if (raw.trim.isEmpty) Nil
    else
      try JsBridge.asArray(js.JSON.parse(raw)).toList
      catch { case _: Throwable => Nil }

  def reindexSteps(previous: String, next: String, reason: String, fallback: ChatMessage): String = {
    val before    = processSteps(previous)
    val synthetic =
      if (before.nonEmpty) Nil
      else
        List(
          JsBridge.obj(
            "status"      -> ("error"),
            "error"       -> (reason),
            "finalAnswer" -> (false),
            "thinking"    -> (fallback.thinking),
            "content"     -> (fallback.content),
            "toolCalls"   -> (js.Array[js.Any]())
          )
        )
    val all       = (before ++ synthetic ++ processSteps(next)).zipWithIndex.map { case (step, index) =>
      val copy = JsBridge.objectConstructor.assign(JsBridge.obj(), step)
      JsBridge.update(copy, "index", JsBridge.asAny(index + 1))
      copy
    }
    js.JSON.stringify(js.Array(all.map(JsBridge.asObject(_))*))
  }

  def completedAttempt(
      base: ChatMessage,
      attemptId: String,
      index: Int,
      trigger: String,
      actual: ChatMessage,
      auto: Boolean,
      reason: String
  ): ChatAttempt = {
    val previous = base.attempts.find(_.id == attemptId).orElse(Some(ChatAttempt.fromMessage(base)))
    ChatAttempt(
      id = attemptId,
      index = index,
      trigger = if (auto) previous.map(_.trigger).getOrElse(trigger) else trigger,
      status = actual.status,
      provider = actual.provider,
      content = actual.content,
      thinking = actual.thinking,
      steps =
        if (auto) reindexSteps(previous.map(_.steps).getOrElse(""), actual.steps, reason, base)
        else actual.steps,
      error = if (actual.status == "error") actual.content else "",
      internalRecoveries = previous.map(_.internalRecoveries).getOrElse(0) + (if (auto) 1 else 0),
      createdAt = previous.map(_.createdAt).getOrElse(actual.createdAt),
      updatedAt = actual.updatedAt,
      requestedMode = actual.requestedMode,
      effectiveMode = actual.effectiveMode,
      modePath = actual.modePath,
      agentRunId = actual.agentRunId,
      browserTools = actual.browserTools
    )
  }
}
