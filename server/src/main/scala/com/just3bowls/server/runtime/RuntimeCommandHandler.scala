package com.just3bowls.server.runtime

import com.just3bowls.api.memory.{MemoryExtractionResult, MemoryRequestEnvelope, MemoryResponseEnvelope}
import com.just3bowls.api.protocol.JsonEnvelope
import com.just3bowls.server.native.StdioFrames

import zio.json._
import zio.json.ast.Json

import java.io.OutputStream

final private[server] case class RuntimeCommandHandler(
    memoryRequest: MemoryRequestEnvelope => MemoryResponseEnvelope,
    memoryExtractionResult: MemoryExtractionResult => Unit,
    startHttp: () => Boolean,
    stopHttp: () => Unit
) {

  def handle(raw: String, out: OutputStream): Unit =
    JsonEnvelope.typeOf(raw).getOrElse("") match {
      case "memory_request"        =>
        raw.fromJson[MemoryRequestEnvelope] match {
          case Right(request) => writeFrame(out, memoryRequest(request).toJson)
          case Left(error)    =>
            writeFrame(
              out,
              Json.Obj(
                "type"    -> Json.Str("runtime_error"),
                "message" -> Json.Str(s"invalid memory request: $error")
              ).toJson
            )
        }
      case "memory_extract_result" =>
        raw.fromJson[MemoryExtractionResult].foreach(memoryExtractionResult)
      case "server_start"          =>
        if (startHttp())
          writeFrame(out, Json.Obj("type" -> Json.Str("server_ready")).toJson)
        else
          writeFrame(
            out,
            Json.Obj(
              "type"    -> Json.Str("server_fatal"),
              "code"    -> Json.Str("start_failed"),
              "message" -> Json.Str("HTTP service failed to start")
            ).toJson
          )
      case "server_stop"           =>
        stopHttp()
        writeFrame(out, Json.Obj("type" -> Json.Str("server_stopped")).toJson)
      case _                       => ()
    }

  private def writeFrame(out: OutputStream, text: String): Unit = {
    out.write(StdioFrames.encode(text))
    out.flush()
  }

}
