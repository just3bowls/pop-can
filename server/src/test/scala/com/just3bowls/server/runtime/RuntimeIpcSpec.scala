package com.just3bowls.server.runtime
import com.just3bowls.api.memory.MemoryResponseEnvelope
import com.just3bowls.server.native.*

import zio._
import zio.json._
import zio.test._

import java.io.{BufferedInputStream, BufferedOutputStream}
import java.net.{InetAddress, Socket}
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

object RuntimeIpcSpec extends ZIOSpecDefault {
  private val FastLimits = RuntimeJoinLimits(
    startupWait = 300.millis,
    probeAttempts = 1,
    probeRetryDelays = Nil,
    connectTimeoutMillis = 100,
    responseTimeoutMillis = 300,
    gracefulExitWait = 100.millis,
    forcedExitWait = 100.millis,
    lockWait = 500.millis
  )

  def spec = suite("RuntimeIpcSpec")(
    test("leader publishes starting then ready and requires an authenticated health probe") {
      withTempDirectory { directory =>
        ZIO.attempt {
          val started  = new AtomicBoolean(false)
          val stopped  = new AtomicBoolean(false)
          val acquired = RuntimeLeader.tryAcquire(directory).get
          val starting = RuntimeEndpointStore.read(directory)
          assertTrue(
            starting.exists(_.state == RuntimeEndpoint.Starting),
            starting.exists(_.leaderId == acquired.leaderId)
          )

          val server = RuntimeIpcServer.start(
            directory,
            acquired,
            RuntimeCommandHandler(
              memoryRequest = request =>
                MemoryResponseEnvelope(request.requestId, ok = false, error = "Memory runtime unavailable"),
              memoryExtractionResult = _ => (),
              startHttp = () => {
                started.set(true)
                true
              },
              stopHttp = () => stopped.set(true)
            )
          )
          try {
            val endpoint = RuntimeEndpointStore.read(directory).get
            assertTrue(endpoint.state == RuntimeEndpoint.Ready)
            val socket   = new Socket(InetAddress.getLoopbackAddress, endpoint.port)
            try {
              val in              = new BufferedInputStream(socket.getInputStream)
              val out             = new BufferedOutputStream(socket.getOutputStream)
              val decoder         = new StdioFrames.Decoder()
              write(
                out,
                zio.json.ast.Json.Obj(
                  "type"  -> zio.json.ast.Json.Str("runtime_auth"),
                  "token" -> zio.json.ast.Json.Str(endpoint.token)
                ).toJson
              )
              write(
                out,
                zio.json.ast.Json.Obj(
                  "type"            -> zio.json.ast.Json.Str("runtime_probe"),
                  "leaderId"        -> zio.json.ast.Json.Str(endpoint.leaderId),
                  "protocolVersion" -> zio.json.ast.Json.Num(RuntimeEndpoint.ProtocolVersion)
                ).toJson
              )
              val probeResponse   = readOne(in, decoder)
              write(
                out,
                zio.json.ast.Json.Obj(
                  "type" -> zio.json.ast.Json.Str("server_start")
                ).toJson
              )
              val response        = readOne(in, decoder)
              write(
                out,
                zio.json.ast.Json.Obj(
                  "type" -> zio.json.ast.Json.Str("server_stop")
                ).toJson
              )
              val stoppedResponse = readOne(in, decoder)
              assertTrue(
                started.get(),
                stopped.get(),
                probeResponse.contains("\"runtime_ready\""),
                response.contains("\"server_ready\""),
                stoppedResponse.contains("\"server_stopped\"")
              )
            } finally socket.close()
          } finally {
            RuntimeEndpointStore.remove(directory, acquired.leaderId)
            server.close()
          }
        }
      }
    },
    test("endpoint cleanup is conditional on the leader id") {
      withTempDirectory { directory =>
        val lease = RuntimeLeader.tryAcquire(directory).get
        try {
          val replacement = RuntimeEndpoint(
            state = RuntimeEndpoint.Ready,
            leaderId = "new-leader",
            protocolVersion = RuntimeEndpoint.ProtocolVersion,
            process = lease.process,
            port = 12345,
            token = "new-token",
            startedAt = java.lang.System.currentTimeMillis()
          )
          RuntimeEndpointStore.write(directory, replacement)
          RuntimeEndpointStore.remove(directory, lease.leaderId)
          assertTrue(RuntimeEndpointStore.read(directory).exists(_.leaderId == "new-leader"))
        } finally {
          RuntimeEndpointStore.removeAny(directory)
          lease.close()
        }
      }
    },
    test("process control never terminates the current JVM") {
      val identity = JvmRuntimeProcessControl.current()
      assertTrue(
        JvmRuntimeProcessControl.matches(identity),
        !JvmRuntimeProcessControl.terminate(identity, FastLimits)
      )
    },
    test("a healthy leader is joined only after the runtime handshake") {
      withTempDirectory { directory =>
        val control = new FakeProcessControl(
          RuntimeProcessIdentity(5101L, 1000L, "/tmp/PopCanHost", Nil)
        )
        val lease   = RuntimeLeader.tryAcquire(directory, control).get
        val server  = RuntimeIpcServer.start(
          directory,
          lease,
          RuntimeCommandHandler(
            memoryRequest = request =>
              MemoryResponseEnvelope(request.requestId, ok = false, error = "Memory runtime unavailable"),
            memoryExtractionResult = _ => (),
            startHttp = () => true,
            stopHttp = () => ()
          )
        )
        RuntimeLeader.join(directory, control, FastLimits).flatMap {
          case RuntimeProxyRole(connection)  =>
            ZIO.attempt {
              try
                assertTrue(
                  connection.endpoint.leaderId == lease.leaderId,
                  connection.endpoint.state == RuntimeEndpoint.Ready
                )
              finally connection.close()
            }
          case RuntimeLeaderRole(unexpected) =>
            unexpected.close()
            ZIO.fail(new AssertionError("an existing healthy leader must not be replaced"))
        }.ensuring(
          ZIO.succeed {
            RuntimeEndpointStore.remove(directory, lease.leaderId)
            server.close()
          }
        )
      }
    },
    test("a verified stale leader is terminated once and replaced") {
      withTempDirectory { directory =>
        val oldIdentity = RuntimeProcessIdentity(5001L, 1000L, "/tmp/PopCanHost", Nil)
        val newIdentity = RuntimeProcessIdentity(5002L, 2000L, "/tmp/PopCanHost", Nil)
        val oldControl  = new FakeProcessControl(oldIdentity)
        val oldLease    = RuntimeLeader.tryAcquire(directory, oldControl).get
        val oldServer   = RuntimeIpcServer.start(
          directory,
          oldLease,
          RuntimeCommandHandler(
            memoryRequest = request =>
              MemoryResponseEnvelope(request.requestId, ok = false, error = "Memory runtime unavailable"),
            memoryExtractionResult = _ => (),
            startHttp = () => true,
            stopHttp = () => ()
          )
        )
        oldServer.server.close()

        val terminated = new AtomicBoolean(false)
        val newControl = new FakeProcessControl(
          newIdentity,
          identities = Map(oldIdentity.pid -> oldIdentity),
          matchesResult = true,
          onTerminate = () => {
            terminated.set(true)
            oldLease.close()
          }
        )
        RuntimeLeader.join(directory, newControl, FastLimits).flatMap {
          case RuntimeLeaderRole(newLease)  =>
            ZIO.attempt {
              try {
                val endpoint = RuntimeEndpointStore.read(directory)
                assertTrue(
                  terminated.get(),
                  endpoint.exists(_.leaderId == newLease.leaderId),
                  endpoint.exists(_.state == RuntimeEndpoint.Starting)
                )
              } finally {
                RuntimeEndpointStore.remove(directory, newLease.leaderId)
                newLease.close()
              }
            }
          case RuntimeProxyRole(connection) =>
            connection.close()
            ZIO.fail(new AssertionError("expected takeover to elect this process as leader"))
        }
      }
    },
    test("an unverifiable stale owner is never terminated") {
      withTempDirectory { directory =>
        val oldIdentity = RuntimeProcessIdentity(6001L, 1000L, "/tmp/PopCanHost", Nil)
        val newIdentity = RuntimeProcessIdentity(6002L, 2000L, "/tmp/PopCanHost", Nil)
        val oldLease    = RuntimeLeader.tryAcquire(directory, new FakeProcessControl(oldIdentity)).get
        val oldServer   = RuntimeIpcServer.start(
          directory,
          oldLease,
          RuntimeCommandHandler(
            memoryRequest = request =>
              MemoryResponseEnvelope(request.requestId, ok = false, error = "Memory runtime unavailable"),
            memoryExtractionResult = _ => (),
            startHttp = () => true,
            stopHttp = () => ()
          )
        )
        oldServer.server.close()

        val terminated = new AtomicBoolean(false)
        val newControl = new FakeProcessControl(
          newIdentity,
          identities = Map(oldIdentity.pid -> oldIdentity),
          matchesResult = false,
          onTerminate = () => terminated.set(true)
        )
        RuntimeLeader.join(directory, newControl, FastLimits).either.flatMap { result =>
          ZIO.attempt {
            try
              assertTrue(
                !terminated.get(),
                result.left.exists(_.code == RuntimeJoinError.OwnerUnverified)
              )
            finally {
              RuntimeEndpointStore.removeAny(directory)
              oldServer.close()
            }
          }
        }
      }
    }
  )

  final private class FakeProcessControl(
      currentIdentity: RuntimeProcessIdentity,
      identities: Map[Long, RuntimeProcessIdentity] = Map.empty,
      matchesResult: Boolean = true,
      onTerminate: () => Unit = () => ()
  ) extends RuntimeProcessControl {
    private val currentRef = new AtomicReference(currentIdentity)

    override def current(): RuntimeProcessIdentity = currentRef.get()

    override def identify(pid: Long): Option[RuntimeProcessIdentity] =
      identities.get(pid).orElse {
        val value = currentRef.get()
        if (value.pid == pid) Some(value) else None
      }

    override def matches(expected: RuntimeProcessIdentity): Boolean = matchesResult

    override def terminate(expected: RuntimeProcessIdentity, limits: RuntimeJoinLimits): Boolean = {
      onTerminate()
      true
    }
  }

  private def withTempDirectory[R](use: Path => ZIO[Any, Throwable, R]): ZIO[Any, Throwable, R] = {
    val directory = Files.createTempDirectory("pop-can-runtime-ipc-")
    use(directory).ensuring(
      ZIO.succeed {
        RuntimeEndpointStore.removeAny(directory)
        if (Files.exists(directory))
          Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      }
    )
  }

  private def write(out: BufferedOutputStream, text: String): Unit = {
    out.write(StdioFrames.encode(text))
    out.flush()
  }

  private def readOne(in: BufferedInputStream, decoder: StdioFrames.Decoder): String = {
    val buffer = new Array[Byte](4096)
    var result = Option.empty[String]
    while (result.isEmpty) {
      val read = in.read(buffer)
      if (read < 0) return ""
      decoder.feed(buffer.take(read)) match {
        case Left(_)         => return ""
        case Right(messages) => result = messages.headOption
      }
    }
    result.getOrElse("")
  }

}
