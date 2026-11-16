package com.just3bowls.api
import com.just3bowls.api.protocol.*

import zio.test._

/**
 * MD5 的金值测试。
 *
 * 前两组是 RFC 1321 经典向量 + 跨块（1000 字节，走多轮 padding/分块）与代理对（emoji，验 UTF-8 编码器）； 后两组是**真实抓到的**文心 token 里嵌的那两段 hash（`你好` /
 * `1+11`），改写时重算错一个字节服务端就会拒。
 */
object Md5Spec extends ZIOSpecDefault {

  private val golden: List[(String, String)] = List(
    ""                                            -> "d41d8cd98f00b204e9800998ecf8427e",
    "abc"                                         -> "900150983cd24fb0d6963f7d28e17f72",
    "The quick brown fox jumps over the lazy dog" -> "9e107d9d372bb6826bd81d3542a419d6",
    "x" * 1000                                    -> "398533d48111e9f664b1f64cb10c4b63",
    "😀"                                          -> "2a02eac39d716a70ecf37579185927b6",
    "1+1=？ 你好😀"                                  -> "8e8280d413849f624fa7eb6bd00c8382",
    "你好"                                          -> "7eca689f0d3389d9dea66ae112e5cfd7",
    "1+11"                                        -> "e5b0554a88194c7f39a76c88756f66b2"
  )

  def spec: Spec[Any, Any] = suite("Md5")(
    test("hex 与已知金值一致") {
      val mismatches = golden.filter { case (input, want) => Md5.hex(input) != want }
      assertTrue(mismatches.isEmpty)
    },
    test("utf8 编码：ASCII 1 字节、CJK 3 字节、emoji 4 字节") {
      val unsigned = (bytes: Array[Byte]) => bytes.toList.map(_ & 0xff)
      assertTrue(
        unsigned(Md5.utf8("A")) == List(0x41),
        unsigned(Md5.utf8("你")) == List(0xe4, 0xbd, 0xa0),
        unsigned(Md5.utf8("😀")) == List(0xf0, 0x9f, 0x98, 0x80)
      )
    }
  )
}
