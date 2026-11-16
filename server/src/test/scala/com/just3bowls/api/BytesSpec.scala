package com.just3bowls.api
import com.just3bowls.api.protocol.*

import zio.test._

/**
 * 字节/文本转换的金值测试。
 *
 * 重点是 [[Bytes.fromPostDataString]]：Chrome 对合法 UTF-8 的请求体给"真实文本"（会有 > 0xFF 的字符）， 对二进制 / 帧化的体给"逐字节"表示（每个字符 ≤
 * 0xFF）。这条分界决定帧长度按什么算，算错整条请求就废。
 */
object BytesSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("Bytes")(
    test("utf8 / text 往返：ASCII、CJK、emoji") {
      val samples = List("", "abc", "你好", "😀", "1+1=？ 你好😀")
      assertTrue(samples.forall(s => Bytes.text(Bytes.utf8(s)) == s))
    },
    test("真实文本（含 CJK）→ 按 UTF-8 编码") {
      val bytes = Bytes.fromPostDataString("""{"query":"你好"}""")
      assertTrue(
        bytes.length == Bytes.utf8("""{"query":"你好"}""").length,
        Bytes.text(bytes) == """{"query":"你好"}"""
      )
    },
    test("逐字节表示（全 ≤ 0xFF）→ 原样还原字节") {
      // kimi 的 gRPC-Web 头 + 一段含 0xD0/0xFF 的体，在 Chrome 眼里就是"逐字节"字符串
      val raw   = Array[Byte](0x00, 0x00, 0x00, 0x01, 0xd0.toByte, '{'.toByte, 'a'.toByte, 0xff.toByte)
      val asStr = raw.map(b => (b & 0xff).toChar).mkString
      assertTrue(Bytes.fromPostDataString(asStr).toList == raw.toList)
    },
    test("hexPreview 截断并补零") {
      val preview = Bytes.hexPreview(Array[Byte](0x00, 0x0f, 0x7f), max = 32)
      assertTrue(preview == "00 0f 7f")
      val long    = Bytes.hexPreview(Array.fill[Byte](40)(0x2a), max = 4)
      assertTrue(long == "2a 2a 2a 2a …")
    }
  )
}
