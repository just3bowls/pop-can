package com.just3bowls.server.bootstrap
import com.just3bowls.server.http.*
import com.just3bowls.server.native.*
import com.just3bowls.server.runtime.*
import com.just3bowls.server.bootstrap.ServerOptions.Options

import zio._
import zio.json._

import com.just3bowls.api.memory.MemoryRequestEnvelope
import com.just3bowls.api.memory.MemoryResponseEnvelope
import com.just3bowls.memory.MemoryRuntime

object ServerMain extends ZIOAppDefault {

  private def unsafeRun[A](effect: UIO[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  override val bootstrap: ZLayer[Any, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(ProcessLogging.stderrLogger)

  def run =
    for {
      rawArgs <- ZIOAppArgs.getArgs
      options = ServerOptions.parse(rawArgs.toList)
      _ <- ZIO.succeed(
        ProcessLogging.redirectStderr(
          ProcessLogging.resolveLogPath(
            options.logFile,
            Option(java.lang.System.getProperty(ProcessLogging.LogDirProperty))
          ).orElse(
            Option.when(options.nativeHost)(
              ProcessLogging.nativeHostDefaultLogPath()
            )
          )
        )
      )
      _ <- runServer(options)
    } yield ()

  /**
   * 默认只绑 `127.0.0.1`：HTTP 面（opencode / cli）本来就是本机用的，而 worker 侧不再有独立端口（走 stdio）， 绑全网卡只会把"驱动你已登录 AI 账号"的能力暴露给局域网。需要
   * Docker / 局域网时显式 `--bind 0.0.0.0`。
   */
  private def runServer(options: Options): ZIO[Any, Nothing, Unit] = {
    val dataDir = AppPaths.memoryDir()
    RuntimeLeader.join(dataDir).foldZIO(
      error =>
        ZIO.logError(s"runtime join failed code=${error.code}: ${error.detail}") *>
          ZIO.succeed {
            if (options.nativeHost) ServerNotices.announceRuntimeFatal(error)
            java.lang.Runtime.getRuntime.halt(1)
          },
      {
        case RuntimeLeaderRole(lease)     =>
          runLeader(options, dataDir, lease)
        case RuntimeProxyRole(connection) =>
          ZIO.logInfo(
            s"runtime follower connected leader=${connection.endpoint.leaderId} " +
              s"pid=${connection.endpoint.process.pid} endpoint=${connection.endpoint.port}"
          ) *> RuntimeProxy.bridgeProxy(connection).tapErrorCause(cause =>
            ZIO.logErrorCause(s"runtime proxy to ${connection.endpoint.port} failed", cause)
          ).exit.flatMap {
            case Exit.Success(_) => ZIO.logInfo("runtime proxy disconnected")
            case Exit.Failure(_) =>
              ZIO.succeed {
                if (options.nativeHost)
                  ServerNotices.announceRuntimeRetry(
                    s"runtime proxy to port ${connection.endpoint.port} disconnected"
                  )
                java.lang.Runtime.getRuntime.halt(1)
              }
          }
      }
    )
  }

  private def runLeader(
      options: Options,
      dataDir: java.nio.file.Path,
      lease: RuntimeLeaderLease
  ): ZIO[Any, Nothing, Unit] =
    ZIO.scoped {
      for {
        bridge    <- BrowserWorkerBridge.create()
        memory    <- ZIO.acquireRelease(
          ZIO
            .attempt(new MemoryRuntime(dataDir))
            .tapErrorCause(cause =>
              ZIO.logErrorCause(
                "Memory runtime failed to initialize; chat will continue without Memory",
                cause
              )
            )
            .option
        )(runtime => ZIO.succeed(runtime.foreach(_.close())))
        httpState <- Ref.make(Option.empty[Fiber.Runtime[Any, Throwable]])
        http = HttpControl(
          bridge,
          HttpConfig(options.bind, options.port),
          httpState,
          cause => ServerNotices.fatalNotice(cause, options.bind, options.port)
        )
        _      <- bridge.runtimeHooks.set(Some(BrowserRuntimeHooks(
          memoryRequest = request =>
            memory match {
              case Some(runtime) =>
                ZIO.succeed(runtime.handle(request)).flatMap(response => bridge.emit(response.toJson))
              case None          => bridge.emit(MemoryResponseEnvelope(
                  request.requestId,
                  ok = false,
                  error = "Memory runtime unavailable"
                ).toJson)
            },
          memoryExtractionResult = result =>
            memory match {
              case Some(runtime) =>
                ZIO
                  .succeed(runtime.handle(MemoryRequestEnvelope(
                    requestId = result.requestId,
                    action = "extraction/reply",
                    payload = result.toJson
                  )))
                  .unit
              case None          => ZIO.unit
            },
          serverStart = http.start.unit,
          serverStop = http.stop
        )))
        ipc    <- ZIO.acquireRelease(
          ZIO.attempt(
            RuntimeIpcServer.start(
              dataDir = dataDir,
              lease = lease,
              commands = RuntimeCommandHandler(
                memoryRequest = request =>
                  memory
                    .map(_.handle(request))
                    .getOrElse(MemoryResponseEnvelope(
                      request.requestId,
                      ok = false,
                      error = "Memory runtime unavailable"
                    )),
                memoryExtractionResult = result =>
                  memory.foreach(_.handle(MemoryRequestEnvelope(
                    requestId = result.requestId,
                    action = "extraction/reply",
                    payload = result.toJson
                  ))),
                startHttp = () => unsafeRun(http.start),
                stopHttp = () => unsafeRun(http.stop)
              )
            )
          ).tapErrorCause(cause =>
            ZIO.logErrorCause("failed to start runtime IPC leader", cause) *>
              ZIO.succeed(lease.close())
          ).orDie
        )(server =>
          ZIO.succeed {
            RuntimeEndpointStore.remove(dataDir, lease.leaderId)
            server.close()
          }
        )
        _      <- ZIO.logInfo(
          s"runtime leader id=${ipc.endpoint.leaderId} endpoint=${ipc.endpoint.port} pid=${ipc.endpoint.process.pid}"
        )
        _      <- ZIO.logInfo(
          // `os`/`pid` 是为了**日志能自证身份**：dev（Linux）与各平台的 app-image 都往 `native-host/*` 里写日志，
          // 一旦有人把两边的 log 目录指到一起，这一行就能立刻看出哪些行来自哪台机器 / 哪个进程。
          s"Pop Can server starting bind=${options.bind} port=${options.port} " +
            s"nativeHost=${options.nativeHost} os=${java.lang.System.getProperty("os.name")} " +
            s"pid=${ProcessHandle.current().pid()} memory=$dataDir"
        )
        worker <-
          if (options.nativeHost) StdioWorkerLink.run(bridge).fork
          else
            http.start.unit *> ZIO.logInfo("started without --native-host: HTTP face is up in direct mode").fork
        // Native host life is owned by stdio. HTTP is a child service: a bind failure is reported to
        // the extension but does not tear down Memory or the host process.
        exit   <- worker.join.exit
        _      <- exit match {
          case Exit.Success(_)     => ZIO.logInfo("Pop Can server exiting")
          case Exit.Failure(cause) =>
            // 失败原因经协议回给扩展（用户看不到 stderr）—— 它据此进终态，而不是把这次失败当成"port 断了"去无限重连。
            ZIO.logErrorCause("Pop Can server exiting after failure", cause) *>
              ZIO.succeed {
                if (options.nativeHost) {
                  val (code, message) = ServerNotices.fatalNotice(cause, options.bind, options.port)
                  ServerNotices.announceFatal(code, message)
                }
              }
        }
        // **必须硬退**（`Runtime.halt`，不能用 `System.exit`）：netty 留了非守护线程 + shutdown hook，
        // `System.exit` 实测会卡住，进程继续占着端口 —— 重试再 spawn 就堆出一串僵尸 host。
        _      <- ZIO.succeed(java.lang.Runtime.getRuntime.halt(if (exit.isSuccess) 0 else 1))
      } yield ()
    }
}
