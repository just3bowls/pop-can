package com.just3bowls.api
import com.just3bowls.api.protocol.*

import zio.test._

object Utf8StreamDecoderSpec extends ZIOSpecDefault {

  private def bytes(values: Int*): Array[Byte] =
    values.map(_.toByte).toArray

  def spec: Spec[Any, Any] = suite("Utf8StreamDecoder")(
    test("splits a three-byte CJK character after the first byte") {
      val decoder = new Utf8StreamDecoder
      val first   = decoder.feed(bytes(0xe5))
      val second  = decoder.feed(bytes(0x85, 0xac), finalChunk = true)
      assertTrue(first.isEmpty, second == "公")
    },
    test("splits a three-byte CJK character before the last byte") {
      val decoder = new Utf8StreamDecoder
      val first   = decoder.feed(bytes(0xe5, 0x85))
      val second  = decoder.feed(bytes(0xac), finalChunk = true)
      assertTrue(first.isEmpty, second == "公")
    },
    test("decodes ASCII around a split CJK character") {
      val decoder = new Utf8StreamDecoder
      val first   = decoder.feed(bytes('a'.toInt, 0xe5))
      val second  = decoder.feed(bytes(0x85, 0xac, 'b'.toInt), finalChunk = true)
      assertTrue(first == "a", second == "公b")
    },
    test("splits a four-byte emoji across chunks") {
      val decoder = new Utf8StreamDecoder
      val first   = decoder.feed(bytes(0xf0, 0x9f))
      val second  = decoder.feed(bytes(0x98))
      val third   = decoder.feed(bytes(0x80), finalChunk = true)
      assertTrue(first.isEmpty, second.isEmpty, third == "😀")
    },
    test("flushes an incomplete trailing sequence as replacement text") {
      val decoder = new Utf8StreamDecoder
      assertTrue(decoder.feed(bytes(0xe5), finalChunk = true) == "\uFFFD")
    },
    test("emits replacement text for an invalid sequence without losing following ASCII") {
      val decoder = new Utf8StreamDecoder
      assertTrue(decoder.feed(bytes(0x80, 'x'.toInt), finalChunk = true) == "\uFFFDx")
    },
    test("does not discard ASCII after an incomplete leading byte at end of stream") {
      val decoder = new Utf8StreamDecoder
      assertTrue(decoder.feed(bytes(0xe5, 'x'.toInt), finalChunk = true) == "\uFFFDx")
    }
  )
}
