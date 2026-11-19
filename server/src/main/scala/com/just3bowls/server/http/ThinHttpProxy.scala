package com.just3bowls.server.http

import com.just3bowls.server.native.*
import com.just3bowls.api.protocol.ClientOperation

import zio._
import zio.http._
import zio.json._
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
      operation: ClientOperation,
      authorization: String,
      body: String
  ): IO[Response, Response] =
    for {
      handle     <- bridge.submitClientRequest(operation, authorization, body)
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
        s"Thin ${operation.wire} requestId=${handle.requestId} status=${head.status} chars=${head.contentType}"
      )
    } yield Response(
      status = Status.fromInt(head.status),
      headers = Headers("content-type" -> head.contentType),
      body = Body.fromCharSequenceStreamChunked(bodyStream)
    )

  private def authorizationOf(request: Request): String =
    request.headers
      .get(Header.Authorization)
      .map(_.renderedValue)
      .getOrElse("")

  def models(bridge: BrowserWorkerBridge, request: Request): IO[Response, Response] =
    thinHttpResponse(bridge, ClientOperation.Models, authorizationOf(request), "")

  def chat(bridge: BrowserWorkerBridge, request: Request): IO[Response, Response] =
    for {
      auth     <- ZIO.succeed(authorizationOf(request))
      body     <- request.body.asString.orElseFail(
        openAiError(Status.BadRequest, "Missing request body")
      )
      response <- thinHttpResponse(
        bridge,
        ClientOperation.ChatCompletion,
        auth,
        body
      )
    } yield response
}
