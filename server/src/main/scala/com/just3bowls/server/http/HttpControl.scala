package com.just3bowls.server.http

import com.just3bowls.server.native.BrowserWorkerBridge

import zio._
import zio.http._
import zio.json._
import zio.json.ast.Json

/**
 * HTTP is a child service of the native runtime. Host/Memory lifetime is owned by stdio, so a bind failure only marks
 * this service unavailable and never tears down Memory.
 */
final private[server] case class HttpControl(
    bridge: BrowserWorkerBridge,
    config: HttpConfig,
    started: Ref[Option[Fiber.Runtime[Any, Throwable]]],
    fatalMessage: Cause[Throwable] => (String, String)
) {
  private def routesEffect: ZIO[Any, Throwable, Nothing] =
    Server
      .serve(OpenAiRoutes.routes(bridge))
      .provideLayer(
        Server.defaultWith(
          _.binding(config.bind, config.port)
            .hybridRequestStreaming(20 * 1024 * 1024)
        )
      )

  private def emitJson(value: Json): UIO[Unit] =
    bridge.emit(value.toJson)

  private def emitFatal(cause: Cause[Throwable]): UIO[Unit] = {
    val (code, message) = fatalMessage(cause)
    emitJson(
      Json.Obj(
        "type"    -> Json.Str("server_fatal"),
        "code"    -> Json.Str(code),
        "message" -> Json.Str(message)
      )
    )
  }

  val start: UIO[Boolean] =
    started.get.flatMap {
      case Some(_) => ZIO.succeed(true)
      case None    =>
        routesEffect
          .onExit {
            case Exit.Failure(cause) =>
              if (cause.isInterruptedOnly) started.set(None)
              else
                started.set(None) *>
                  ZIO.logErrorCause(
                    s"HTTP face failed to start on ${config.bind}:${config.port}",
                    cause
                  ) *>
                  emitFatal(cause)
            case Exit.Success(_)     => started.set(None)
          }
          .forkDaemon
          .flatMap { fiber =>
            started.set(Some(fiber)) *>
              ZIO.sleep(150.millis) *>
              started.get.flatMap {
                case Some(current) if current == fiber =>
                  emitJson(Json.Obj("type" -> Json.Str("server_ready"))).as(true)
                case _                                 => ZIO.succeed(false)
              }
          }
    }

  val stop: UIO[Unit] =
    started.getAndSet(None).flatMap {
      case Some(fiber) =>
        fiber.interrupt *>
          emitJson(Json.Obj("type" -> Json.Str("server_stopped")))
      case None        =>
        emitJson(Json.Obj("type" -> Json.Str("server_stopped")))
    }
}
