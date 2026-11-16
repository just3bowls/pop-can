package com.just3bowls.api.protocol

/**
 * RFC 1321 MD5，UTF-8 字节输入、小写十六进制输出。
 *
 * 放共享模块（JVM + Scala.js）是为了能在 `server/test` 里对着已知金值做单测：插件侧要重算网页 AI 自己算出来的完整性字段 （例如文心的 `chat_token` 里就嵌着
 * `md5(输入框文本)`），算错一个字节服务端就会拒。
 *
 * 刻意不依赖 `crypto.subtle`（它不支持 MD5）也不引入第三方包；UTF-8 编码自己写，避免 JVM / JS 两侧的 charset 差异。
 */
object Md5 {

  private val Shift = Array(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
  )

  private val Table = Array(
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391
  )

  /** 文本的 MD5，小写十六进制（32 字符）。 */
  def hex(text: String): String =
    digest(utf8(text)).map(byte => f"${byte & 0xff}%02x").mkString

  /** 文本的 MD5 原始字节（UTF-8）。 */
  def digestUtf8(text: String): Array[Byte] = digest(utf8(text))

  /** UTF-8 编码（含代理对处理），不依赖平台 charset；实现委托给共享的 [[Bytes.utf8]]，避免两份实现漂移。 */
  def utf8(text: String): Array[Byte] = Bytes.utf8(text)

  /** 原始字节的 MD5（16 字节摘要）。 */
  def digest(message: Array[Byte]): Array[Byte] = {
    val bitLength = message.length.toLong * 8L
    val zeroPad   = {
      val rem = (message.length + 1) % 64
      (56 - rem + 64) % 64
    }
    val padded    = new Array[Byte](message.length + 1 + zeroPad + 8)
    System.arraycopy(message, 0, padded, 0, message.length)
    padded(message.length) = 0x80.toByte
    var i         = 0
    while (i < 8) {
      padded(padded.length - 8 + i) = ((bitLength >>> (8 * i)) & 0xffL).toByte
      i += 1
    }

    var a0     = 0x67452301
    var b0     = 0xefcdab89
    var c0     = 0x98badcfe
    var d0     = 0x10325476
    var offset = 0
    while (offset < padded.length) {
      val chunk = new Array[Int](16)
      var j     = 0
      while (j < 16) {
        chunk(j) =
          (padded(offset + j * 4) & 0xff) |
            ((padded(offset + j * 4 + 1) & 0xff) << 8) |
            ((padded(offset + j * 4 + 2) & 0xff) << 16) |
            ((padded(offset + j * 4 + 3) & 0xff) << 24)
        j += 1
      }

      var a = a0
      var b = b0
      var c = c0
      var d = d0
      var k = 0
      while (k < 64) {
        var f         = 0
        var g         = 0
        if (k < 16) {
          f = (b & c) | (~b & d)
          g = k
        } else if (k < 32) {
          f = (d & b) | (~d & c)
          g = (5 * k + 1) % 16
        } else if (k < 48) {
          f = b ^ c ^ d
          g = (3 * k + 5) % 16
        } else {
          f = c ^ (b | ~d)
          g = (7 * k) % 16
        }
        val previousD = d
        d = c
        c = b
        val sum       = a + f + Table(k) + chunk(g)
        b = b + Integer.rotateLeft(sum, Shift(k))
        a = previousD
        k += 1
      }
      a0 += a
      b0 += b
      c0 += c
      d0 += d
      offset += 64
    }

    val out = new Array[Byte](16)
    var n   = 0
    while (n < 4) {
      out(n) = ((a0 >>> (8 * n)) & 0xff).toByte
      out(n + 4) = ((b0 >>> (8 * n)) & 0xff).toByte
      out(n + 8) = ((c0 >>> (8 * n)) & 0xff).toByte
      out(n + 12) = ((d0 >>> (8 * n)) & 0xff).toByte
      n += 1
    }
    out
  }
}
