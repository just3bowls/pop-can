package com.just3bowls.server.http

import com.just3bowls.server.native.*

import zio._
import zio.http._
import zio.json._
import zio.json.ast.Json
import zio.stream.ZStream

private[server] object ThinHttpProxy {

  def openAiError(
      status: Status.Error,
      message: String,
      errorType: String = "invalid_request_error",
      code: Option[String] = None
  ): Response =
    Response
      .json(OpenAiErrorResponse(OpenAiError(message, errorType, None, code)).toJson)
      .status(status)

  private def thinHttpResponse(
      bridge: BrowserWorkerBridge,
      method: String,
      path: String,
      headers: String,
      body: String
  ): IO[Response, Response] =
    for {
      handle     <- bridge.submitClientRequest(method, path, headers, body)
      headOption <- handle.head.await
        .mapError(error =>
          openAiError(
            Status.BadGateway,
            Option(error.getMessage).getOrElse("Worker disconnected"),
            errorType = "server_error",
            code = Some("worker_unavailable")
          )
        )
        // 协议纠错可能推迟首个 SSE 帧；在单轮 10 分钟预算外再留一分钟。
        .timeout(11.minutes)
        .onInterrupt(bridge.cancelClientRequest(handle.requestId))
      head       <- headOption match {
        case Some(value) => ZIO.succeed(value)
        case None        =>
          ZIO.fail(openAiError(
            Status.RequestTimeout,
            "Worker did not respond",
            errorType = "server_error",
            code = Some("worker_timeout")
          ))
      }
      bodyStream = ZStream
        .fromQueue(handle.chunks)
        .takeWhile(_.nonEmpty)
        .ensuring(handle.chunks.shutdown)
      _          <- ZIO.logInfo(
        s"Thin $method $path requestId=${handle.requestId} status=${head.status} chars=${head.contentType}"
      )
    } yield Response(
      status = Status.fromInt(head.status),
      headers = Headers("content-type" -> head.contentType),
      body = Body.fromCharSequenceStreamChunked(bodyStream)
    )

  def models(bridge: BrowserWorkerBridge): IO[Response, Response] =
    thinHttpResponse(bridge, "GET", "/v1/models", "", "")

  def chat(bridge: BrowserWorkerBridge, request: Request): IO[Response, Response] =
    for {
      auth     <- ZIO.succeed(
        request.headers
          .get(Header.Authorization)
          .map(_.renderedValue)
          .getOrElse("")
      )
      body     <- request.body.asString.orElseFail(
        openAiError(Status.BadRequest, "Missing request body")
      )
      response <- thinHttpResponse(
        bridge,
        "POST",
        "/v1/chat/completions",
        s"""{"authorization":${Json.Str(auth).toJson}}""",
        body
      )
    } yield response
}
