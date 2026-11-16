package com.just3bowls.api
import com.just3bowls.api.protocol.*

import zio.test._

/**
 * 帧解析的金值测试。
 *
 * 起因是 kimi 的请求体从"单帧 JSON"变成"前面一个 84 字节非 JSON 帧 + 后面才是 JSON 帧"（第一个 `{` 落在第 89 字节）， 老的单帧读法直接判成不认识、注入被跳过；这里把多帧 / 截断 / 裸
 * JSON / 尾帧几种形态都钉住。
 */
object GrpcWebFramesSpec extends ZIOSpecDefault {

  private def frame(flag: Int, payload: String): Array[Byte] = GrpcWebFrames.frame(flag, Bytes.utf8(payload))

  private def concat(parts: List[Array[Byte]]): Array[Byte] = {
    val out = new Array[Byte](parts.map(_.length).sum)
    var at  = 0
    parts.foreach { part =>
      System.arraycopy(part, 0, out, at, part.length)
      at += part.length
    }
    out
  }

  def spec: Spec[Any, Any] = suite("GrpcWebFrames")(
    test("单帧 JSON：解析出 payload，重建后逐字节相同") {
      val body   = frame(0x00, """{"chat_id":"x"}""")
      val parsed = GrpcWebFrames.parse(body)
      assertTrue(
        parsed.exists(_.size == 1),
        parsed.exists(frame => Bytes.text(frame.head.payload) == """{"chat_id":"x"}"""),
        parsed.exists(frame => frame.head.flag == 0x00),
        parsed.exists(frame => GrpcWebFrames.frame(frame.head.flag, frame.head.payload).toList == body.toList)
      )
    },
    test("多帧：84 字节非 JSON 帧 + JSON 帧 —— 两帧都在，且重建逐字节相同") {
      val first  = GrpcWebFrames.frame(0x00, Array.fill[Byte](84)(0x2a))
      val second = frame(0x00, """{"message":{"blocks":[{"text":{"content":"hi"}}]}}""")
      val body   = concat(List(first, second))
      val parsed = GrpcWebFrames.parse(body)
      assertTrue(
        parsed.exists(_.size == 2),
        parsed.exists(_.map(_.payload.length) == List(84, second.length - 5)),
        parsed.exists(frames => Bytes.text(frames(1).payload).contains("content")),
        parsed.exists(frames => concat(frames.map(f => GrpcWebFrames.frame(f.flag, f.payload))).toList == body.toList)
      )
    },
    test("带 0x80 尾帧的序列也能解析") {
      val data     = frame(0x00, """{"a":1}""")
      val trailers = frame(0x80, "grpc-status: 0")
      val parsed   = GrpcWebFrames.parse(concat(List(data, trailers)))
      assertTrue(parsed.exists(frames => frames.size == 2 && frames(1).flag == 0x80))
    },
    test("截断的帧 / 长度越界 → None") {
      val body = frame(0x00, """{"a":1}""")
      assertTrue(
        GrpcWebFrames.parse(java.util.Arrays.copyOfRange(body, 0, body.length - 1)).isEmpty,
        // 声明长度比实际长
        GrpcWebFrames.parse(concat(List(Array[Byte](0x00, 0x00, 0x00, 0x00, 0x10), Bytes.utf8("{}")))).isEmpty
      )
    },
    test("裸 JSON（不是帧序列）→ None") {
      assertTrue(GrpcWebFrames.parse(Bytes.utf8("""{"messages":[]}""")).isEmpty)
    },
    test("长度字段是大端") {
      def unsigned(bytes: Array[Byte]): List[Int] = bytes.toList.map(_ & 0xff)
      assertTrue(
        unsigned(GrpcWebFrames.lengthBytes(0x000001d0)) == List(0x00, 0x00, 0x01, 0xd0),
        unsigned(GrpcWebFrames.lengthBytes(0x00000fff)) == List(0x00, 0x00, 0x0f, 0xff)
      )
    }
  )
}
