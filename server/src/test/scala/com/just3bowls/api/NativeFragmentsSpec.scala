package com.just3bowls.api
import com.just3bowls.api.protocol.*

import zio.test._

/** native messaging 分片规则的金值。 */
object NativeFragmentsSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("NativeFragments")(
    test("按片数切开、顺序不变、拼回来等于原文") {
      val body  = ("x" * 1000) + "中文" + ("y" * 1000)
      val parts = NativeFragments.split(body, chunkChars = 500)
      assertTrue(
        parts.length == NativeFragments.frameCount(body, chunkChars = 500),
        parts.mkString == body,
        parts.forall(_.length <= 500)
      )
    },
    test("空 body 为零片") {
      assertTrue(
        NativeFragments.frameCount("") == 0,
        NativeFragments.split("").isEmpty
      )
    },
    test("刚好整除时不多出一片空片") {
      assertTrue(
        NativeFragments.frameCount("abcd", chunkChars = 4) == 1,
        NativeFragments.frameCount("abcde", chunkChars = 4) == 2
      )
    },
    test("装配：乱序也能拼对") {
      val asm = new NativeFragments.Assembly(3)
      assertTrue(
        asm.accept(2, "c").isEmpty,
        asm.accept(0, "a").isEmpty,
        asm.accept(1, "b").contains("abc")
      )
    },
    test("装配：越界 / 重复片一律拒绝") {
      val asm = new NativeFragments.Assembly(2)
      assertTrue(
        asm.accepts(1),
        !asm.accepts(2),
        !asm.accepts(-1),
        asm.accept(0, "a").isEmpty,
        !asm.accepts(0),
        asm.accept(0, "a2").isEmpty,
        asm.accept(1, "b").contains("ab")
      )
    },
    test("装配：单 chunk body") {
      val asm = new NativeFragments.Assembly(1)
      assertTrue(asm.accept(0, "hello").contains("hello"))
    },
    test("出站：小 body 也通过一个 chunk 发送") {
      val (frames, chunks) = NativeFragments.outbound("""{"a":1}""")
      assertTrue(frames == 1, chunks == List("""{"a":1}"""))
    },
    test("出站：空 body 没有 chunk") {
      val (frames, chunks) = NativeFragments.outbound("")
      assertTrue(frames == 0, chunks.isEmpty)
    },
    test("出站：超一片时头留空、正文按序跟在 chunk 帧里") {
      val body             = "x" * 1000
      val (frames, chunks) = NativeFragments.outbound(body, chunkChars = 300)
      assertTrue(
        frames == NativeFragments.frameCount(body, chunkChars = 300),
        chunks.length == frames,
        chunks.mkString == body
      )
    },
    test("片数上限常量与 20MB 量级对得上") {
      val capacity = NativeFragments.ChunkChars.toLong * NativeFragments.MaxFrames.toLong
      assertTrue(capacity >= 20L * 1024 * 1024)
    }
  )
}
