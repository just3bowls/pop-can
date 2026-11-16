package com.just3bowls.model.memory
import com.just3bowls.model.localapi.*

import com.just3bowls.api.memory.MemoryAck
import com.just3bowls.api.memory.MemoryContext
import com.just3bowls.api.memory.MemoryExtractionRequest
import com.just3bowls.api.memory.MemoryExtractionResult
import com.just3bowls.api.memory.MemoryForgetSession
import com.just3bowls.api.memory.MemoryItem
import com.just3bowls.api.memory.MemoryList
import com.just3bowls.api.memory.MemoryListRequest
import com.just3bowls.api.memory.MemoryMutation
import com.just3bowls.api.memory.MemoryQuery
import com.just3bowls.api.memory.MemorySettings
import com.just3bowls.api.memory.MemoryStatus
import com.just3bowls.api.memory.MemoryTurn
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import zio.json._
import scala.concurrent.Future
import scala.scalajs.js

/**
 * Scala.js facade over the JVM Memory runtime. Recall and post-turn ingestion are deliberately fail-open: a
 * disconnected host must never prevent a normal chat response.
 */
final class MemoryClient(server: ServerWorkerClient) {
  @volatile private var extractionHandler: MemoryExtractionRequest => Future[String] = _ =>
    Future.failed(new RuntimeException("Memory extraction handler is not installed"))
  private var extractionLoopStarted                                                  = false
  private var migrationInFlight                                = Option.empty[Future[MemorySettings]]
  private var syncInFlight                                     = Option.empty[Future[Unit]]
  private var lastSyncedSettingsJson                           = ""
  private val SettingsSyncTimeoutMs                            = 5000
  def status(): Future[MemoryStatus]                           =
    requestRaw[MemoryStatus]("status")
  def settings(): Future[MemorySettings]                       =
    MemorySettingsStore.read().flatMap {
      case Some(value) => Future.successful(value)
      case None        =>
        migrateRuntimeSettings().recover { case error =>
          PopCanLog.warn(
            "memory",
            s"settings migration deferred: ${Option(error.getMessage).getOrElse(error.toString)}"
          )
          MemorySettings.Default
        }
    }
  def configure(value: MemorySettings): Future[MemorySettings] =
    MemorySettingsStore.write(value).map { saved =>
      lastSyncedSettingsJson = ""
      syncSettingsToRuntime().recover { case error =>
        PopCanLog.debug("memory", s"settings sync deferred: ${Option(error.getMessage).getOrElse(error.toString)}")
        ()
      }
      saved
    }
  def recall(sessionId: String, query: String, recentContext: String, budgetTokens: Int = 1200): Future[MemoryContext] =
    requestWithSettings[MemoryContext](
      "recall",
      MemoryQuery(sessionId, query, recentContext.take(6000), budgetTokens).toJson,
      timeoutMs = 900
    ).recover { case error =>
      PopCanLog.warn("memory", s"recall degraded: ${Option(error.getMessage).getOrElse(error.toString)}")
      MemoryContext.Empty.copy(degraded = true, reason = Option(error.getMessage).getOrElse("recall failed"))
    }
  def ingest(turn: MemoryTurn): Future[MemoryAck]              =
    requestWithSettings[MemoryAck]("turn/ingest", turn.toJson, timeoutMs = 3000).recover { case error =>
      PopCanLog.warn("memory", s"turn ingest failed: ${Option(error.getMessage).getOrElse(error.toString)}")
      MemoryAck(ok = false, message = Option(error.getMessage).getOrElse("ingest failed"))
    }
  def remember(
      scope: String,
      kind: String,
      text: String,
      importance: Int = 7,
      pinned: Boolean = false,
      sourceSessionId: String = ""
  ): Future[MemoryItem] =
    requestRaw[MemoryItem](
      "remember",
      MemoryMutation(
        scope = scope,
        sourceSessionId = sourceSessionId,
        kind = kind,
        text = text,
        importance = importance,
        pinned = pinned
      ).toJson,
      timeoutMs = 3000
    )
  def list(scope: String = "", status: String = "", limit: Int = 200): Future[MemoryList] =
    requestRaw[MemoryList]("list", MemoryListRequest(scope, status, limit).toJson)
  def mutate(value: MemoryMutation): Future[Unit]              =
    server.requestMemory("mutate", value.toJson, timeoutMs = 3000).map { response =>
      if (!response.ok) throw new RuntimeException(response.error)
      ()
    }
  def forgetSession(sessionId: String, forgetSources: Boolean = false): Future[Int]      =
    requestRaw[MemoryAck](
      "forget-session",
      MemoryForgetSession(sessionId, forgetSources).toJson,
      timeoutMs = 3000
    ).map(_.count)
  def rebuildIndex(): Future[Int]                                                        =
    requestRaw[MemoryAck]("rebuild-index", "{}", timeoutMs = 30000).map(_.count)
  def installExtractionHandler(handler: MemoryExtractionRequest => Future[String]): Unit =
    extractionHandler = handler

  /**
   * Polling keeps the native protocol one-way for jobs: the runtime owns the queue, the extension claims one item at a
   * time. This avoids concurrent provider tabs and lost push messages after an extension/service-worker restart.
   */
  def startExtractionLoop(): Unit                              =
    if (!extractionLoopStarted) {
      extractionLoopStarted = true
      pollExtraction()
    }
  private def pollExtraction(): Unit                           =
    js.timers.setTimeout(1500) {
      syncSettingsToRuntime()
        .recover { case _ => () }
        .flatMap(_ => server.requestMemory("extraction/next", "{}", timeoutMs = 5000))
        .flatMap { response =>
          if (!response.ok) Future.successful(())
          else
            response.payload.fromJson[MemoryExtractionRequest] match {
              case Right(request) if request.jobId.nonEmpty =>
                extractionHandler(request)
                  .map(body => MemoryExtractionResult(request.requestId, request.jobId, ok = true, body = body))
                  .recover { case error =>
                    MemoryExtractionResult(
                      request.requestId,
                      request.jobId,
                      ok = false,
                      error = Option(error.getMessage).getOrElse(error.toString)
                    )
                  }
                  .map { result =>
                    server.submitMemoryExtractionResult(result)
                    ()
                  }
              case _                                        => Future.successful(())
            }
        }
        .recover { case e =>
          PopCanLog.debug("memory", s"extraction poll skipped: ${Option(e.getMessage).getOrElse("")}")
          ()
        }
        .foreach(_ => pollExtraction())
    }
  private def migrateRuntimeSettings(): Future[MemorySettings] =
    migrationInFlight match {
      case Some(pending) => pending
      case None          =>
        val pending = requestRaw[MemorySettings](
          "settings/get",
          timeoutMs = SettingsSyncTimeoutMs
        ).flatMap { value =>
          MemorySettingsStore.write(value).map { saved =>
            lastSyncedSettingsJson = saved.toJson
            saved
          }
        }
        migrationInFlight = Some(pending)
        pending.andThen { case _ =>
          if (migrationInFlight.contains(pending)) migrationInFlight = None
        }
    }
  private def syncSettingsToRuntime(): Future[Unit]            =
    syncInFlight match {
      case Some(pending) => pending
      case None          =>
        val pending = MemorySettingsStore.read().flatMap {
          case Some(value) =>
            val payload = value.toJson
            if (lastSyncedSettingsJson == payload) Future.successful(())
            else
              requestRaw[MemorySettings](
                "settings/set",
                payload,
                timeoutMs = SettingsSyncTimeoutMs
              ).map { normalized =>
                lastSyncedSettingsJson = MemorySettings.normalize(normalized).toJson
              }
          case None        =>
            migrateRuntimeSettings().map(_ => ())
        }
        syncInFlight = Some(pending)
        pending.andThen { case _ =>
          if (syncInFlight.contains(pending)) syncInFlight = None
        }
    }
  private def requestWithSettings[A: JsonDecoder](
      action: String,
      payload: String,
      timeoutMs: Int
  ): Future[A] =
    syncSettingsToRuntime()
      .recover { case _ => () }
      .flatMap(_ => requestRaw[A](action, payload, timeoutMs))
  private def requestRaw[A: JsonDecoder](
      action: String,
      payload: String = "{}",
      timeoutMs: Int = 3000
  ): Future[A] =
    server.requestMemory(action, payload, timeoutMs).flatMap { response =>
      if (!response.ok) Future.failed(new RuntimeException(response.error))
      else
        response.payload.fromJson[A] match {
          case Right(value) => Future.successful(value)
          case Left(error)  => Future.failed(new RuntimeException(s"Invalid Memory response: $error"))
        }
    }
}
