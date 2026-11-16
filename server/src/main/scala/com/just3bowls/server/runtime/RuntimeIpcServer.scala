package com.just3bowls.server.runtime

import com.just3bowls.server.native.StdioFrames

import zio.json._
import zio.json.ast.Json

import java.io.{BufferedInputStream, BufferedOutputStream, OutputStream}
import java.net.{InetAddress, ServerSocket}
import java.security.{MessageDigest, SecureRandom}
import java.nio.charset.StandardCharsets
import java.nio.file.Path

final case class RuntimeIpcServer(
    server: ServerSocket,
    endpoint: RuntimeEndpoint,
    lease: RuntimeLeaderLease,
    acceptThread: Thread
) extends AutoCloseable {
  override def close(): Unit = {
    try server.close()
    catch { case _: Throwable => () }
    lease.close()
  }
}

object RuntimeIpcServer {
  private val FrameBuffer = 64 * 1024

  def start(
      dataDir: Path,
      lease: RuntimeLeaderLease,
      commands: RuntimeCommandHandler
  ): RuntimeIpcServer = {
    val socket   = new ServerSocket(0, 64, InetAddress.getLoopbackAddress)
    val token    = randomToken()
    val endpoint = RuntimeEndpoint(
      state = RuntimeEndpoint.Ready,
      leaderId = lease.leaderId,
      protocolVersion = RuntimeEndpoint.ProtocolVersion,
      process = lease.process,
      port = socket.getLocalPort(),
      token = token,
      startedAt = lease.startedAt
    )
    RuntimeEndpointStore.write(dataDir, endpoint)
    val server   = new RuntimeIpcServer(socket, endpoint, lease, null)
    val thread   = new Thread(
      () => acceptLoop(server, commands),
      "pop-can-runtime-ipc"
    )
    thread.setDaemon(true)
    thread.start()
    RuntimeIpcServer(socket, endpoint, lease, thread)
  }

  private def acceptLoop(server: RuntimeIpcServer, commands: RuntimeCommandHandler): Unit =
    while (!server.server.isClosed) {
      try {
        val client = server.server.accept()
        val thread = new Thread(
          () => clientLoop(client, server.endpoint, commands),
          s"pop-can-runtime-client-${client.getPort}"
        )
        thread.setDaemon(true)
        thread.start()
      } catch {
        case _: Throwable => ()
      }
    }

  private def clientLoop(
      client: java.net.Socket,
      endpoint: RuntimeEndpoint,
      commands: RuntimeCommandHandler
  ): Unit = {
    val in      = new BufferedInputStream(client.getInputStream, FrameBuffer)
    val out     = new BufferedOutputStream(client.getOutputStream, FrameBuffer)
    val decoder = new StdioFrames.Decoder()
    try {
      var authenticated = false
      var verified      = false
      var running       = true
      while (running) {
        val buffer = new Array[Byte](FrameBuffer)
        val read   = in.read(buffer)
        if (read < 0) running = false
        else if (read > 0) {
          decoder.feed(buffer.take(read)) match {
            case Left(_)         => running = false
            case Right(messages) =>
              messages.foreach { raw =>
                if (!authenticated) {
                  val supplied =
                    raw.fromJson[Json].toOption
                      .collect { case Json.Obj(fields) =>
                        fields.toMap.get("token").collect { case Json.Str(value) => value }.getOrElse("")
                      }
                      .getOrElse("")
                  if (messageTypeOf(raw) == "runtime_auth" && constantTimeEquals(supplied, endpoint.token)) {
                    authenticated = true
                  } else running = false
                } else if (!verified) {
                  val fields =
                    raw.fromJson[Json].toOption
                      .collect { case Json.Obj(values) => values.toMap }
                      .getOrElse(Map.empty)
                  val valid  =
                    messageTypeOf(raw) == "runtime_probe" &&
                      fieldString(fields, "leaderId") == endpoint.leaderId &&
                      fieldInt(fields, "protocolVersion") == RuntimeEndpoint.ProtocolVersion
                  if (valid) {
                    verified = true
                    writeFrame(
                      out,
                      Json.Obj(
                        "type"            -> Json.Str("runtime_ready"),
                        "leaderId"        -> Json.Str(endpoint.leaderId),
                        "protocolVersion" -> Json.Num(RuntimeEndpoint.ProtocolVersion),
                        "pid"             -> Json.Num(endpoint.process.pid)
                      ).toJson
                    )
                  } else running = false
                } else commands.handle(raw, out)
              }
          }
        }
      }
    } finally {
      try client.close()
      catch { case _: Throwable => () }
    }
  }

  private def messageTypeOf(raw: String): String =
    raw.fromJson[Json].toOption
      .collect { case Json.Obj(fields) =>
        fields.toMap.get("type").collect { case Json.Str(value) => value }.getOrElse("")
      }
      .getOrElse("")

  private def fieldString(fields: Map[String, Json], name: String): String =
    fields.get(name).collect { case Json.Str(value) => value }.getOrElse("")

  private def fieldInt(fields: Map[String, Json], name: String): Int =
    fields
      .get(name)
      .collect { case Json.Num(value) => scala.util.Try(value.intValueExact()).toOption }
      .flatten
      .getOrElse(-1)

  private def writeFrame(out: OutputStream, text: String): Unit = {
    out.write(StdioFrames.encode(text))
    out.flush()
  }

  private def randomToken(): String = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString
  }

  private def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8)
    )
}
