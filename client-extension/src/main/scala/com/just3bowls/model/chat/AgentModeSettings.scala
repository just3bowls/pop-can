package com.just3bowls.model.chat

import com.just3bowls.api.chat.AgentModePolicy
import com.just3bowls.facade.{ChromeAPI, JsBridge}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Session 级 Agent Mode；旧 `auto` 记录读取时统一归一为 ReAct。 */
object AgentModeSettings {
  val Key                                                     = "thSessionAgentModes"
  def load(sessionId: String): Future[Option[String]]         =
    if (sessionId.trim.isEmpty) Future.successful(None)
    else
      ChromeAPI.storage.local
        .get(js.Array(Key))
        .toFuture
        .map { values =>
          values
            .get(Key)
            .filter(value => value != null && !js.isUndefined(value))
            .flatMap(JsBridge.asDictionary(_).get(sessionId))
            .map(_.toString)
            .map(AgentModePolicy.normalize)
        }
        .recover { case _ => None }
  def save(sessionId: String, mode: String): Future[Unit]     =
    if (sessionId.trim.isEmpty) Future.successful(())
    else
      loadAll().flatMap { all =>
        write(all.updated(sessionId, AgentModePolicy.normalize(mode)))
      }
  def clear(sessionId: String): Future[Unit]                  =
    if (sessionId.trim.isEmpty) Future.successful(())
    else loadAll().flatMap(all => write(all - sessionId))
  private def loadAll(): Future[Map[String, String]]          =
    ChromeAPI.storage.local
      .get(js.Array(Key))
      .toFuture
      .map { values =>
        values
          .get(Key)
          .filter(value => value != null && !js.isUndefined(value))
          .map(JsBridge.asDictionary(_).toMap.map { case (sessionId, mode) =>
            sessionId -> AgentModePolicy.normalize(mode.toString)
          })
          .getOrElse(Map.empty)
      }
      .recover { case _ => Map.empty }
  private def write(modes: Map[String, String]): Future[Unit] =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](Key -> js.Dictionary[js.Any](
        modes.toSeq.map { case (key, value) => key -> (value: js.Any) }*
      )))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }
}
