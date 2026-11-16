package com.just3bowls.server.native

import zio._
import zio.json._
import zio.stream.ZStream

import com.just3bowls.api.memory.MemoryExtractionResult
import com.just3bowls.api.memory.MemoryRequestEnvelope
import com.just3bowls.api.memory.MemoryResponseEnvelope
import com.just3bowls.api.protocol.NativeFragments

/**
 * 极薄通道：Server 把完整 HTTP OpenAI 请求转给插件。
 *
 * `body` 不再随头消息发出：`bodyFrames` 说明这次请求体切成几片，body 走随后的 `client_request_chunk`（见 `api.NativeFragments`）—— 因为 native
 * messaging 的 host→扩展方向单条消息只有 ~1MB，而一次请求可能带 20MB。
 */
final case class WorkerClientRequest(
    requestId: String,
    method: String,
    path: String,
    headers: String = "",
    body: String = "",
    bodyFrames: Int = 1,
    `type`: String = "client_request"
)

object WorkerClientRequest {
  given JsonCodec[WorkerClientRequest] = DeriveJsonCodec.gen
}

/** 请求体分片：`seq` 从 0 开始，全部到齐才组装成完整 body。 */
final case class WorkerClientRequestChunk(
    requestId: String,
    seq: Int,
    data: String = "",
    `type`: String = "client_request_chunk"
)

object WorkerClientRequestChunk {
  given JsonCodec[WorkerClientRequestChunk] = DeriveJsonCodec.gen
}

final case class WorkerClientHead(
    requestId: String,
    status: Int = 200,
    contentType: String = "application/json",
    `type`: String = "client_response_head"
)

object WorkerClientHead {
  given JsonCodec[WorkerClientHead] = DeriveJsonCodec.gen
}

final case class WorkerClientChunk(
    requestId: String,
    data: String,
    `type`: String = "client_response_chunk"
)

object WorkerClientChunk {
  given JsonCodec[WorkerClientChunk] = DeriveJsonCodec.gen
}

final case class WorkerClientEnd(
    requestId: String,
    `type`: String = "client_response_end"
)

object WorkerClientEnd {
  given JsonCodec[WorkerClientEnd] = DeriveJsonCodec.gen
}

final case class WorkerClientCancel(
    requestId: String,
    `type`: String = "client_cancel"
)

object WorkerClientCancel {
  given JsonCodec[WorkerClientCancel] = DeriveJsonCodec.gen
}

final case class ClientHttpHandle(
    requestId: String,
    head: Promise[Throwable, WorkerClientHead],
    chunks: Queue[String]
)

final private[server] case class ClientHttpState(
    head: Promise[Throwable, WorkerClientHead],
    chunks: Queue[String]
)

sealed trait WorkerCommand

object WorkerCommand {
  final case class ClientRequest(request: WorkerClientRequest) extends WorkerCommand
  final case class ClientCancel(requestId: String)             extends WorkerCommand
  final case class RawMessage(text: String)                    extends WorkerCommand
}

final case class BrowserRuntimeHooks(
    memoryRequest: MemoryRequestEnvelope => Task[Unit],
    memoryExtractionResult: MemoryExtractionResult => Task[Unit],
    serverStart: Task[Unit],
    serverStop: Task[Unit]
)

/** 极薄浏览器 worker 桥：只转发 client_request/client_response，不做鉴权、解析或任务管理。 */
final case class BrowserWorkerBridge(
    commandQueue: Queue[WorkerCommand],
    clientHttp: Ref[Map[String, ClientHttpState]],
    runtimeHooks: Ref[Option[BrowserRuntimeHooks]]
) {

  def submitClientRequest(
      method: String,
      path: String,
      headers: String,
      body: String
  ): UIO[ClientHttpHandle] =
    for {
      requestId <- Random.nextUUID.map(_.toString)
      head      <- Promise.make[Throwable, WorkerClientHead]
      chunks    <- Queue.unbounded[String]
      _         <- clientHttp.update(_ + (requestId -> ClientHttpState(head, chunks)))
      _         <- commandQueue.offer(WorkerCommand.ClientRequest(
        WorkerClientRequest(
          requestId = requestId,
          method = method,
          path = path,
          headers = headers,
          body = body
        )
      ))
    } yield ClientHttpHandle(requestId, head, chunks)

  def cancelClientRequest(requestId: String): UIO[Unit] =
    clientHttp.modify { map =>
      (map.get(requestId).map(_.chunks), map - requestId)
    }.flatMap {
      case Some(chunks) =>
        chunks.offer("") *> chunks.shutdown *>
          commandQueue.offer(WorkerCommand.ClientCancel(requestId)).unit
      case None         =>
        ZIO.unit
    }

  private def clientHead(head: WorkerClientHead): UIO[Unit] =
    clientHttp.get.map(_.get(head.requestId)).flatMap {
      case Some(state) => state.head.succeed(head).unit
      case None        => ZIO.unit
    }

  private def clientChunk(requestId: String, data: String): UIO[Unit] =
    clientHttp.get.map(_.get(requestId)).flatMap {
      case Some(state) => if (data.nonEmpty) state.chunks.offer(data).unit else ZIO.unit
      case None        => ZIO.unit
    }

  private def clientEnd(requestId: String): UIO[Unit] =
    clientHttp.modify { map =>
      (map.get(requestId), map - requestId)
    }.flatMap {
      case Some(state) => state.chunks.offer("").unit
      case None        => ZIO.unit
    }

  def failAllClientRequests(reason: String): UIO[Unit] =
    clientHttp.modify { map =>
      (map.values.toList, Map.empty[String, ClientHttpState])
    }.flatMap { states =>
      ZIO.foreachDiscard(states) { state =>
        state.head.fail(new RuntimeException(reason)) *>
          state.chunks.offer("") *>
          state.chunks.shutdown
      }
    }

  /**
   * 出站流：只产出"要发给扩展的 JSON 消息"，谁把它送出去由 transport 决定（native stdio 或测试里的 fake worker）。
   *
   * 请求体按 `api.NativeFragments.outbound` 分帧：1 片 = 正文内联在头里，多片 = 头留空、正文跟在 chunk 帧后面。
   */
  val outbound: ZStream[Any, Nothing, String] =
    ZStream.fromQueue(commandQueue).flatMap {
      case WorkerCommand.ClientRequest(request)  => ZStream.fromIterable(requestFrames(request))
      case WorkerCommand.ClientCancel(requestId) =>
        ZStream.succeed(WorkerClientCancel(requestId = requestId).toJson)
      case WorkerCommand.RawMessage(text)        => ZStream.succeed(text)
    }

  def emit(text: String): UIO[Unit] = commandQueue.offer(WorkerCommand.RawMessage(text)).unit

  private def requestFrames(request: WorkerClientRequest): List[String] =
    NativeFragments.outbound(request.body) match {
      case (frames, Nil)   => List(request.copy(bodyFrames = frames).toJson)
      case (frames, parts) =>
        request.copy(body = "", bodyFrames = frames).toJson :: parts.zipWithIndex.map { case (data, seq) =>
          WorkerClientRequestChunk(requestId = request.requestId, seq = seq, data = data).toJson
        }
    }

  /**
   * 入站：扩展发来的每条 JSON 消息在这里分发（stdio 读循环 / 测试里的 fake worker 都走这里）。
   *
   * 只认 `client_response_*`；别的类型记一条 debug 就丢（协议里没有 ping/hello，连接可用性看 transport 的断开事件）。
   */
  def onMessage(text: String): Task[Unit] =
    if (text.contains("\"type\":\"client_response_head\"")) {
      text.fromJson[WorkerClientHead] match {
        case Right(head) => clientHead(head)
        case Left(error) => ZIO.logWarning(s"Ignoring invalid client response head: $error").unit
      }
    } else if (text.contains("\"type\":\"client_response_chunk\"")) {
      text.fromJson[WorkerClientChunk] match {
        case Right(chunk) => clientChunk(chunk.requestId, chunk.data)
        case Left(error)  => ZIO.logWarning(s"Ignoring invalid client response chunk: $error").unit
      }
    } else if (text.contains("\"type\":\"shutdown\"")) {
      // 扩展主动关服务（Stop / 重连前清理旧 host）：直接硬退 —— netty 的 shutdown hook 会卡住 System.exit。
      ZIO.logInfo("shutdown requested by extension") *>
        ZIO.succeed(java.lang.Runtime.getRuntime.halt(0))
    } else if (text.contains("\"type\":\"client_response_end\"")) {
      text.fromJson[WorkerClientEnd] match {
        case Right(end)  => clientEnd(end.requestId)
        case Left(error) => ZIO.logWarning(s"Ignoring invalid client response end: $error").unit
      }
    } else if (text.contains("\"type\":\"memory_request\"")) {
      text.fromJson[MemoryRequestEnvelope] match {
        case Right(request) =>
          runtimeHooks.get.flatMap {
            case Some(hooks) => hooks.memoryRequest(request)
            case None        =>
              emit(MemoryResponseEnvelope(request.requestId, ok = false, error = "Memory runtime is not ready").toJson)
          }
        case Left(error)    => ZIO.logWarning(s"Ignoring invalid memory request: $error")
      }
    } else if (text.contains("\"type\":\"memory_extract_result\"")) {
      text.fromJson[MemoryExtractionResult] match {
        case Right(result) =>
          runtimeHooks.get.flatMap {
            case Some(hooks) => hooks.memoryExtractionResult(result)
            case None        =>
              ZIO.logWarning(s"Memory extraction result arrived before runtime was ready: ${result.requestId}")
          }
        case Left(error)   => ZIO.logWarning(s"Ignoring invalid memory extraction result: $error")
      }
    } else if (text.contains("\"type\":\"server_start\"")) {
      runtimeHooks.get.flatMap {
        case Some(hooks) => hooks.serverStart
        case None        => ZIO.logWarning("server_start arrived before runtime hooks were installed")
      }
    } else if (text.contains("\"type\":\"server_stop\"")) {
      runtimeHooks.get.flatMap {
        case Some(hooks) => hooks.serverStop
        case None        => ZIO.unit
      }
    } else ZIO.logDebug(s"Ignoring non-thin worker message: ${text.take(120)}")

}

object BrowserWorkerBridge {
  def create(): UIO[BrowserWorkerBridge] =
    for {
      commandQueue <- Queue.unbounded[WorkerCommand]
      clientHttp   <- Ref.make(Map.empty[String, ClientHttpState])
      hooks        <- Ref.make(Option.empty[BrowserRuntimeHooks])
    } yield BrowserWorkerBridge(commandQueue, clientHttp, hooks)
}
