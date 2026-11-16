package com.just3bowls.memory

import com.just3bowls.api.memory.MemoryAck
import com.just3bowls.api.memory.MemoryContext
import com.just3bowls.api.memory.MemoryExtractionRequest
import com.just3bowls.api.memory.MemoryExtractionResult
import com.just3bowls.api.memory.MemoryForgetSession
import com.just3bowls.api.memory.MemoryListRequest
import com.just3bowls.api.memory.MemoryMutation
import com.just3bowls.api.memory.MemoryQuery
import com.just3bowls.api.memory.MemoryRequestEnvelope
import com.just3bowls.api.memory.MemoryResponseEnvelope
import com.just3bowls.api.memory.MemorySettings
import com.just3bowls.api.memory.MemoryStatus
import com.just3bowls.api.memory.MemoryTurn
import zio.json._

import java.nio.file.Path
import java.util.UUID

/** Single-threaded semantic facade over the graph store. Native transports never touch SQL/Lucene. */
final class MemoryRuntime(dataDir: Path) extends AutoCloseable {
  private val store = new MemoryStore(dataDir)

  def handle(request: MemoryRequestEnvelope): MemoryResponseEnvelope =
    try {
      val payload: Either[String, String] = request.action.trim.toLowerCase match {
        case "status"           =>
          Right(store.status().toJson)
        case "settings/get"     =>
          Right(store.settings().toJson)
        case "settings/set"     =>
          decode[MemorySettings](request.payload).map(value => store.updateSettings(value).toJson)
        case "turn/ingest"      =>
          val turn = decode[MemoryTurn](request.payload)
          turn.map { value =>
            val (_, job) = store.ingestTurn(value)
            MemoryAck(ok = true, message = if (job.nonEmpty) "queued" else "stored", count = if (job.nonEmpty) 1 else 0).toJson
          }
        case "recall"           =>
          decode[MemoryQuery](request.payload).map(value => store.recall(value).toJson)
        case "remember"         =>
          decode[MemoryMutation](request.payload).map { value =>
            val item = store.remember(
              scope = value.scope,
              kind = value.kind,
              text = value.text,
              importance = value.importance,
              pinned = value.pinned,
              sourceSessionId = value.sourceSessionId
            )
            item.toJson
          }
        case "list"             =>
          decode[MemoryListRequest](request.payload).map(value =>
            store.list(value.scope, value.status, value.limit).toJson
          )
        case "mutate"           =>
          decode[MemoryMutation](request.payload).map(value =>
            store.mutate(value).map(_.toJson).getOrElse(MemoryAck(ok = true, message = "deleted").toJson)
          )
        case "forget-session"   =>
          decode[MemoryForgetSession](request.payload).map { value =>
            MemoryAck(ok = true, count = store.forget(value)).toJson
          }
        case "rebuild-index"    =>
          Right(MemoryAck(ok = true, count = store.rebuildIndex()).toJson)
        case "extraction/next"  =>
          Right(store.claimNextJob() match {
            case Some(job) =>
              MemoryExtractionRequest(
                requestId = UUID.randomUUID().toString,
                jobId = job.id,
                provider = job.provider,
                prompt = job.prompt,
                promptMode = job.promptMode
              ).toJson
            case None      => "{}"
          })
        case "extraction/reply" =>
          val result = decode[MemoryExtractionResult](request.payload)
          result.map { value =>
            if (value.ok) {
              val accepted = store.completeJob(value.jobId, value.body)
              MemoryAck(ok = true, count = accepted).toJson
            } else {
              store.retryJob(value.jobId, Option(value.error).filter(_.nonEmpty).getOrElse("extraction failed"))
              MemoryAck(ok = false, message = "retry queued").toJson
            }
          }
        case other              =>
          Left(s"Unknown Memory action: $other")
      }
      payload match {
        case Right(json) => MemoryResponseEnvelope(request.requestId, ok = true, payload = json)
        case Left(error) => MemoryResponseEnvelope(request.requestId, ok = false, error = error)
      }
    } catch {
      case error: Throwable =>
        MemoryResponseEnvelope(
          request.requestId,
          ok = false,
          error = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)
        )
    }

  def status(): MemoryStatus = store.status()

  def settings(): MemorySettings = store.settings()

  private def decode[A: JsonDecoder](text: String): Either[String, A] =
    text.fromJson[A].left.map(error => s"Invalid Memory payload: $error")

  override def close(): Unit = store.close()
}
