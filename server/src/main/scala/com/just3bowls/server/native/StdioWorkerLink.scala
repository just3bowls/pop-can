package com.just3bowls.server.native

import java.io.{BufferedOutputStream, OutputStream}

import zio._
import zio.stream.ZStream

/**
 * native messaging 的 transport：把 `BrowserWorkerBridge` 接到 stdin/stdout 上。
 *
 * 纪律：**stdout 只写帧**（见 `StdioFrames`），日志/报错一律 stderr。stdin 读到 EOF = Chrome 关掉了 host（扩展断开、扩展页关闭、 浏览器退出）→ 把本轮在途的 HTTP
 * 请求全部失败掉，HTTP 面下一个请求会拿到可读的 `native host not connected`。
 */
private[server] object StdioWorkerLink {

  def run(bridge: BrowserWorkerBridge, stdout: OutputStream = java.lang.System.out): Task[Unit] =
    for {
      decoder <- Ref.make(new StdioFrames.Decoder())
      input   <- ZIO.succeed(new java.io.BufferedInputStream(java.lang.System.in, 64 * 1024))
      sink = new BufferedOutputStream(stdout, 64 * 1024)
      writer <- bridge.outbound
        .runForeach { text =>
          ZIO.attemptBlockingIO {
            sink.write(StdioFrames.encode(text))
            sink.flush()
          }
        }
        .fork
      _      <- readChunks(input).runForeach { chunk =>
        decoder.get.flatMap { decoder =>
          decoder.feed(chunk.toArray) match {
            case Left(error)     => ZIO.fail(new RuntimeException(error))
            case Right(messages) => ZIO.foreachDiscard(messages)(bridge.onMessage)
          }
        }
      }
      _      <- writer.interrupt
      _      <- bridge.failAllClientRequests("native host disconnected (stdin closed)")
      _      <- ZIO.logInfo("native host stdin closed; server keeps the HTTP face until the process exits")
    } yield ()

  /**
   * 按"块"读 stdin（块边界就是一次 `read` 的返回，`StdioFrames.Decoder` 负责把任意切分拼回来）。
   *
   * 不用 `ZStream.fromInputStream`：它是**逐字节**的流，20MB 的请求体会变成两千万次 fiber 步骤。
   */
  private def readChunks(input: java.io.InputStream): ZStream[Any, Throwable, Chunk[Byte]] =
    ZStream.repeatZIOOption {
      ZIO
        .attemptBlocking {
          val buffer = new Array[Byte](64 * 1024)
          val read   = input.read(buffer)
          if (read < 0) None
          else if (read == 0) Some(Chunk.empty[Byte])
          else Some(Chunk.fromArray(buffer).take(read))
        }
        .mapError(Option(_))
        .flatMap {
          case Some(chunk) => ZIO.succeed(chunk)
          case None        => ZIO.fail(Option.empty[Throwable])
        }
    }
}
