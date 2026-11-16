package com.just3bowls.server.native

import com.just3bowls.server.runtime.RuntimeJoinError

import zio._
import zio.json._
import zio.json.ast.Json

private[server] object ServerNotices {

  /** 启动失败 → 回给扩展的 `(code, message)`。 */
  def fatalNotice(cause: Cause[Any], bind: String, port: Int): (String, String) = {
    val text = cause.prettyPrint
    if (text.contains("Address already in use")) ("port_in_use", s"$bind:$port is already in use")
    else ("start_failed", text.linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("startup failed"))
  }

  /** native host 用户看不到 stderr，失败原因必须同步写进 stdout 帧后再退出。 */
  def announceFatal(
      code: String,
      message: String,
      stdout: java.io.OutputStream = java.lang.System.out
  ): Unit =
    writeFrame(
      Json.Obj(
        "type"    -> Json.Str("server_fatal"),
        "code"    -> Json.Str(code),
        "message" -> Json.Str(message)
      ),
      stdout
    )

  def announceRuntimeFatal(
      error: RuntimeJoinError,
      stdout: java.io.OutputStream = java.lang.System.out
  ): Unit =
    writeFrame(
      Json.Obj(
        "type"    -> Json.Str("runtime_fatal"),
        "code"    -> Json.Str(error.code),
        "message" -> Json.Str(error.detail)
      ),
      stdout
    )

  def announceRuntimeRetry(
      message: String,
      stdout: java.io.OutputStream = java.lang.System.out
  ): Unit =
    writeFrame(
      Json.Obj(
        "type"    -> Json.Str("runtime_retry"),
        "message" -> Json.Str(message)
      ),
      stdout
    )

  private def writeFrame(value: Json, stdout: java.io.OutputStream): Unit =
    try {
      stdout.write(StdioFrames.encode(value.toJson))
      stdout.flush()
    } catch { case _: Throwable => () }
}
