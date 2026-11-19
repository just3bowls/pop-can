package com.just3bowls.server.http
import com.just3bowls.server.native.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable

import com.just3bowls.api.protocol.{ClientOperation, JsonEnvelope, NativeFragments}
import zio._
import zio.http.Server
import zio.json._
import zio.test._

/**
 * thin server 的集成测试：HTTP 面是真的（真跑一个 Server），worker 面换成**进程内 fake**。
 *
 * worker 侧以前是 WebSocket，现在改成 native messaging 的 stdio 帧；测试里没必要真接 stdio —— 直接拿 `BrowserWorkerBridge.outbound`
 * 当入站流、`bridge.onMessage` 当出站回调，走的就是线上同一条分片协议。
 */
object ThinServerIntegrationSpec extends ZIOSpecDefault {

  private def createTestBridge(): UIO[BrowserWorkerBridge] =
    BrowserWorkerBridge.create()

  private def withServer[A](
      bridge: BrowserWorkerBridge
  )(body: Int => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.scoped {
      for {
        port   <- Server.install(OpenAiRoutes.routes(bridge))
        result <- body(port)
      } yield result
    }.provide(
      Server.defaultWith(
        _.onAnyOpenPort.hybridRequestStreaming(20 * 1024 * 1024)
      )
    )

  private def postJson(port: Int, body: String, apiKey: String = "th_test_secret"): (Int, String) = {
    val client   = HttpClient.newHttpClient()
    val builder  = HttpRequest
      .newBuilder()
      .uri(URI.create(s"http://127.0.0.1:$port/v1/chat/completions"))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $apiKey")
    val request  = builder
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())
  }

  private def getModels(port: Int, apiKey: String = "th_test_secret"): (Int, String) = {
    val client   = HttpClient.newHttpClient()
    val request  = HttpRequest
      .newBuilder()
      .uri(URI.create(s"http://127.0.0.1:$port/v1/models"))
      .header("Authorization", s"Bearer $apiKey")
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())
  }

  final private case class SeenClientRequest(
      operation: String,
      authorization: String,
      body: String,
      bodyFrames: Int
  )

  /**
   * 进程内假 worker：收 `client_request`（含分片）→ 记下来 → 按脚本回 head/chunks/end。
   *
   * 读侧与扩展共用同一条契约：0 = 无 body；>=1 时 body 全部由 chunk 帧装配。
   */
  private def fakeWorker(
      bridge: BrowserWorkerBridge,
      seen: AtomicReference[SeenClientRequest],
      chunks: WorkerClientRequest => List[String]
  ): UIO[Fiber.Runtime[Any, Any]] = {
    val assemblies = mutable.Map.empty[String, (WorkerClientRequest, NativeFragments.Assembly)]

    def deliver(head: WorkerClientRequest, body: String): Task[Unit] = {
      seen.set(SeenClientRequest(head.operation, head.authorization, body, head.bodyFrames))
      bridge.onMessage(WorkerClientHead(head.requestId).toJson) *>
        ZIO.foreachDiscard(chunks(head))(data =>
          bridge.onMessage(WorkerClientChunk(head.requestId, data).toJson)
        ) *>
        bridge.onMessage(WorkerClientEnd(head.requestId).toJson)
    }

    bridge.outbound.runForeach { text =>
      if (JsonEnvelope.typeOf(text).contains("client_request_chunk")) {
        text.fromJson[WorkerClientRequestChunk] match {
          case Left(_)          => ZIO.unit
          case Right(chunkPart) =>
            ZIO.succeed(assemblies.get(chunkPart.requestId)).flatMap {
              case None                   => ZIO.unit
              case Some((head, assembly)) =>
                assembly.accept(chunkPart.seq, chunkPart.data) match {
                  case None       => ZIO.unit
                  case Some(body) =>
                    ZIO.succeed(assemblies.remove(chunkPart.requestId)).unit *> deliver(head, body)
                }
            }
        }
      } else
        text.fromJson[WorkerClientRequest] match {
          case Left(_)     => ZIO.unit
          case Right(head) =>
            if (head.bodyFrames == 0) deliver(head, "")
            else ZIO.succeed(assemblies.update(head.requestId, (head, new NativeFragments.Assembly(head.bodyFrames))))
        }
    }.fork
  }

  def spec =
    suite("thin server integration tests")(
      test("thin /v1/models forwards the plugin model list") {
        val modelsPayload =
          """{"object":"list","data":[{"id":"deepseek","object":"model","owned_by":"plugin"}]}"""
        val seen          = new AtomicReference[SeenClientRequest]()
        for {
          bridge <- createTestBridge()
          result <- withServer(bridge) { port =>
            for {
              worker <- fakeWorker(bridge, seen, _ => List(modelsPayload))
              answer <- ZIO.attemptBlocking(getModels(port)).ensuring(worker.interrupt)
            } yield assertTrue(
              answer._1 == 200,
              answer._2 == modelsPayload,
              seen.get() != null,
              seen.get().operation == ClientOperation.Models.wire,
              seen.get().authorization == "Bearer th_test_secret",
              seen.get().bodyFrames == 0,
              seen.get().body.isEmpty
            )
          }
        } yield result
      },
      test("thin /v1/chat/completions forwards the raw request and streams back chunks") {
        val requestBody =
          """{"model":"deepseek","messages":[{"role":"user","content":"你好"}],"stream":true}"""
        val chunkJson   =
          """{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}"""
        val ssePayload  = s"data: $chunkJson\n\ndata: [DONE]\n\n"
        val seen        = new AtomicReference[SeenClientRequest]()
        for {
          bridge <- createTestBridge()
          result <- withServer(bridge) { port =>
            for {
              worker <- fakeWorker(bridge, seen, _ => List(ssePayload))
              answer <- ZIO.attemptBlocking(postJson(port, requestBody)).ensuring(worker.interrupt)
            } yield {
              val captured = seen.get()
              assertTrue(
                answer._1 == 200,
                answer._2 == ssePayload,
                captured != null,
                captured.operation == ClientOperation.ChatCompletion.wire,
                captured.body == requestBody,
                captured.authorization == "Bearer th_test_secret",
                captured.bodyFrames == 1
              )
            }
          }
        } yield result
      },
      test("大 body 走分片：20 万字符的请求体也能原样到达 worker") {
        val bigBody  = "x" * 200000
        val captured = new AtomicReference[SeenClientRequest]()
        for {
          bridge <- createTestBridge()
          result <- withServer(bridge) { port =>
            for {
              worker <- fakeWorker(bridge, captured, _ => List("data: [DONE]\n\n"))
              answer <- ZIO.attemptBlocking(
                postJson(port, s"""{"model":"pop-can","messages":[{"role":"user","content":"$bigBody"}]}""")
              ).ensuring(worker.interrupt)
            } yield assertTrue(
              answer._1 == 200,
              captured.get() != null,
              captured.get().body.contains(bigBody),
              captured.get().bodyFrames > 1
            )
          }
        } yield result
      }
    )
}
