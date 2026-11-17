package com.just3bowls.server.native
import com.just3bowls.api.protocol.JsonEnvelope
import com.just3bowls.server.runtime.*

import zio.test._

/**
 * 启动失败的收口：失败分类 + 回给扩展的那条 `server_fatal`。
 *
 * 这是"不再堆一串抢端口的 host"的前提：host 在 native messaging 模式下用户看不到 stderr，必须经协议说出原因，扩展才有依据进终态（否则它只会按退避不停重连）。
 */
object ServerFatalSpec extends ZIOSpecDefault {

  private def causeOf(error: Throwable): zio.Cause[Any] = zio.Cause.fail(error)

  def spec: Spec[Any, Any] = suite("ServerMain 启动失败收口")(
    test("端口被占（netty 的 bind 失败）归成 port_in_use，并带上地址") {
      val cause = causeOf(new java.net.BindException("bind(..) failed: Address already in use"))
      assertTrue(
        ServerNotices.fatalNotice(cause, "127.0.0.1", 8080) == (("port_in_use", "127.0.0.1:8080 is already in use"))
      )
    },
    test("别的启动失败归成 start_failed，message 是原因第一行") {
      val notice = ServerNotices.fatalNotice(
        causeOf(new RuntimeException("something else broke")),
        "127.0.0.1",
        8080
      )
      assertTrue(notice._1 == "start_failed", notice._2.contains("something else broke"))
    },
    test("server_fatal 是一条可解码的帧，带 code 与可读 message") {
      val sink     = new java.io.ByteArrayOutputStream()
      ServerNotices.announceFatal("port_in_use", "127.0.0.1:8080 is already in use", sink)
      val messages = new StdioFrames.Decoder().feed(sink.toByteArray).getOrElse(Nil)
      assertTrue(
        messages.length == 1,
        JsonEnvelope.typeOf(messages.head).contains("server_fatal"),
        JsonEnvelope.stringField(messages.head, "code").contains("port_in_use"),
        JsonEnvelope.stringField(messages.head, "message").contains("127.0.0.1:8080 is already in use")
      )
    },
    test("runtime_fatal 是一条独立的可解码帧") {
      val sink     = new java.io.ByteArrayOutputStream()
      ServerNotices.announceRuntimeFatal(
        RuntimeJoinError(RuntimeJoinError.OwnerUnverified, "pid 42 is not PopCanHost"),
        sink
      )
      val messages = new StdioFrames.Decoder().feed(sink.toByteArray).getOrElse(Nil)
      assertTrue(
        messages.length == 1,
        JsonEnvelope.typeOf(messages.head).contains("runtime_fatal"),
        JsonEnvelope.stringField(messages.head, "code").contains("runtime_owner_unverified"),
        JsonEnvelope.stringField(messages.head, "message").contains("pid 42 is not PopCanHost")
      )
    },
    test("runtime_retry 告诉扩展这是可重连的 leader 更替") {
      val sink     = new java.io.ByteArrayOutputStream()
      ServerNotices.announceRuntimeRetry("runtime leader closed", sink)
      val messages = new StdioFrames.Decoder().feed(sink.toByteArray).getOrElse(Nil)
      assertTrue(
        messages.length == 1,
        JsonEnvelope.typeOf(messages.head).contains("runtime_retry"),
        JsonEnvelope.stringField(messages.head, "message").contains("runtime leader closed")
      )
    }
  )
}
