package com.just3bowls.server.native

import zio.test._

/** native messaging stdio 帧的金值：4 字节 little-endian 长度前缀 + UTF-8 JSON。 */
object StdioFramesSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("StdioFrames")(
    test("编码前缀是 little-endian 长度") {
      val bytes = StdioFrames.encode("""{"a":1}""")
      val text  = new String(java.util.Arrays.copyOfRange(bytes, 4, 11), java.nio.charset.StandardCharsets.UTF_8)
      assertTrue(
        bytes.length == 4 + 7,
        (bytes(0) & 0xff) == 7,
        (bytes(1) & 0xff) == 0,
        text == """{"a":1}"""
      )
    },
    test("round trip：一条一条解码") {
      val decoder = new StdioFrames.Decoder()
      val all     = StdioFrames.encode("""{"type":"a"}""") ++ StdioFrames.encode("""{"type":"b"}""")
      assertTrue(decoder.feed(all) == Right(List("""{"type":"a"}""", """{"type":"b"}""")))
    },
    test("任意切分：字节流怎么断都能拼回来") {
      val frame1  = StdioFrames.encode("""{"type":"client_response_chunk","data":"中文…"}""")
      val frame2  = StdioFrames.encode("""{"type":"client_response_end"}""")
      val stream  = frame1 ++ frame2
      val decoder = new StdioFrames.Decoder()
      val results = stream.grouped(3).toList.map(chunk => decoder.feed(chunk))
      val parsed  = results.flatMap(_.getOrElse(Nil))
      assertTrue(
        results.forall(_.isRight),
        parsed.length == 2,
        parsed.head.contains("""client_response_chunk"""),
        parsed.last == """{"type":"client_response_end"}"""
      )
    },
    test("空帧：长度 0 也算一条消息") {
      val decoder = new StdioFrames.Decoder()
      assertTrue(decoder.feed(Array[Byte](0, 0, 0, 0)) == Right(List("")))
    },
    test("超过上限的帧直接报错（不硬吞）") {
      val decoder = new StdioFrames.Decoder(maxBytes = 8)
      val frame   = StdioFrames.encode("0123456789")
      assertTrue(decoder.feed(frame).left.exists(_.contains("native frame too large")))
    },
    test("半个长度头不算消息，等后续字节") {
      val decoder = new StdioFrames.Decoder()
      val frame   = StdioFrames.encode("""{"x":1}""")
      assertTrue(
        decoder.feed(frame.take(2)) == Right(Nil),
        decoder.feed(frame.drop(2)) == Right(List("""{"x":1}"""))
      )
    }
  )
}
