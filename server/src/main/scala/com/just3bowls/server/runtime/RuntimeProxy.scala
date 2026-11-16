package com.just3bowls.server.runtime

import com.just3bowls.server.native.StdioFrames

import zio._
import zio.json._
import zio.json.ast.Json

import java.io.{BufferedInputStream, BufferedOutputStream, InputStream, OutputStream}
import java.net.{InetAddress, InetSocketAddress, Socket}
import java.util.UUID

private[server] object RuntimeProxy {
  private val FrameBuffer = 64 * 1024

  def bridgeProxy(connection: RuntimeProxyConnection): Task[Unit] = ZIO.attemptBlocking {
    try {
      val upstream = new Thread(
        () => {
          try copyFrames(java.lang.System.in, connection.out)
          catch { case _: Throwable => () }
          finally connection.close()
        },
        "pop-can-runtime-stdin"
      )
      upstream.setDaemon(true)
      upstream.start()
      copyFrames(connection.in, java.lang.System.out)
    } finally connection.close()
  }

  def probeWithRetries(
      dataDir: java.nio.file.Path,
      initial: RuntimeEndpoint,
      limits: RuntimeJoinLimits
  ): Either[RuntimeJoinError, RuntimeProxyConnection] = {
    var endpoint = initial
    var attempt  = 0
    var last     = RuntimeJoinError(RuntimeJoinError.ProbeFailed, "runtime probe did not run")
    while (attempt < math.max(1, limits.probeAttempts)) {
      try return Right(openVerified(endpoint, limits))
      catch {
        case error: RuntimeJoinError =>
          last = error
          attempt += 1
          if (attempt < limits.probeAttempts) {
            val delay = limits.probeRetryDelays.lift(attempt - 1).getOrElse(500.millis)
            Thread.sleep(delay.toMillis)
            endpoint = RuntimeEndpointStore.read(dataDir).getOrElse(endpoint)
            if (endpoint.state == RuntimeEndpoint.Starting)
              endpoint = RuntimeLeader.awaitEndpoint(dataDir, limits)
          }
      }
    }
    Left(last)
  }

  private def openVerified(
      endpoint: RuntimeEndpoint,
      limits: RuntimeJoinLimits
  ): RuntimeProxyConnection = {
    val socket = new Socket()
    try {
      socket.connect(
        new InetSocketAddress(InetAddress.getLoopbackAddress, endpoint.port),
        limits.connectTimeoutMillis
      )
      socket.setSoTimeout(limits.responseTimeoutMillis)
      val in       = new BufferedInputStream(socket.getInputStream, FrameBuffer)
      val out      = new BufferedOutputStream(socket.getOutputStream, FrameBuffer)
      writeFrame(
        out,
        Json.Obj(
          "type"  -> Json.Str("runtime_auth"),
          "token" -> Json.Str(endpoint.token)
        ).toJson
      )
      val probeId  =
        if (endpoint.version < RuntimeEndpoint.SchemaVersion) {
          val requestId = s"runtime-probe-${UUID.randomUUID()}"
          writeFrame(
            out,
            Json.Obj(
              "type"      -> Json.Str("memory_request"),
              "requestId" -> Json.Str(requestId),
              "action"    -> Json.Str("status"),
              "payload"   -> Json.Str("{}")
            ).toJson
          )
          requestId
        } else {
          writeFrame(
            out,
            Json.Obj(
              "type"            -> Json.Str("runtime_probe"),
              "leaderId"        -> Json.Str(endpoint.leaderId),
              "protocolVersion" -> Json.Num(RuntimeEndpoint.ProtocolVersion)
            ).toJson
          )
          ""
        }
      val response = readOneFrame(in)
      val fields   =
        response
          .flatMap(_.fromJson[Json].toOption)
          .collect { case Json.Obj(values) => values.toMap }
          .getOrElse(Map.empty)
      val kind     = fieldString(fields, "type")
      val healthy  =
        if (endpoint.version < RuntimeEndpoint.SchemaVersion)
          kind == "memory_response" && fieldString(fields, "requestId") == probeId
        else
          kind == "runtime_ready" &&
          fieldString(fields, "leaderId") == endpoint.leaderId &&
          fieldInt(fields, "protocolVersion") == RuntimeEndpoint.ProtocolVersion
      if (!healthy)
        throw RuntimeJoinError(
          RuntimeJoinError.ProbeFailed,
          s"unexpected runtime probe response from ${endpoint.process.pid}: ${response.getOrElse("closed")}"
        )
      socket.setSoTimeout(0)
      new RuntimeProxyConnection(endpoint, socket, in, out)
    } catch {
      case error: RuntimeJoinError =>
        try socket.close()
        catch { case _: Throwable => () }
        throw error
      case error: Throwable =>
        try socket.close()
        catch { case _: Throwable => () }
        throw RuntimeJoinError(
          RuntimeJoinError.ProbeFailed,
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        )
    }
  }

  private def copyFrames(in: InputStream, out: OutputStream): Unit = {
    val decoder = new StdioFrames.Decoder()
    var running = true
    val buffer  = new Array[Byte](FrameBuffer)
    while (running) {
      val read = in.read(buffer)
      if (read < 0) running = false
      else if (read > 0)
        decoder.feed(buffer.take(read)) match {
          case Left(_)         => running = false
          case Right(messages) => messages.foreach(writeFrame(out, _))
        }
    }
  }

  private def readOneFrame(in: InputStream): Option[String] = {
    val decoder = new StdioFrames.Decoder()
    val buffer  = new Array[Byte](FrameBuffer)
    var result  = Option.empty[String]
    while (result.isEmpty) {
      val read = in.read(buffer)
      if (read < 0) return None
      else if (read > 0)
        decoder.feed(buffer.take(read)) match {
          case Left(_)         => return None
          case Right(messages) => result = messages.headOption
        }
    }
    result
  }

  private def writeFrame(out: OutputStream, text: String): Unit = {
    out.write(StdioFrames.encode(text))
    out.flush()
  }

  private def fieldString(fields: Map[String, Json], name: String): String =
    fields.get(name).collect { case Json.Str(value) => value }.getOrElse("")

  private def fieldInt(fields: Map[String, Json], name: String): Int =
    fields
      .get(name)
      .collect { case Json.Num(value) => scala.util.Try(value.intValueExact()).toOption }
      .flatten
      .getOrElse(-1)
}
