package com.just3bowls.model.chat

import com.just3bowls.api.chat.AgentModePolicy
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future

/** Compatibility facade over the unified per-session settings store. */
object AgentModeSettings {
  def load(sessionId: String): Future[Option[String]]     =
    if (sessionId.trim.isEmpty) Future.successful(None)
    else SessionSettingsStore.get(sessionId).map(_.map(_.agentMode))
  def save(sessionId: String, mode: String): Future[Unit] = SessionSettingsStore.saveAgentMode(sessionId, mode)
  def clear(sessionId: String): Future[Unit]              =
    SessionSettingsStore.saveAgentMode(sessionId, AgentModePolicy.React)
}
