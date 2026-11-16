package com.just3bowls.api.protocol

/**
 * 字节 ↔ 文本的小工具（JVM + Scala.js 通用），刻意不依赖平台 charset，保证两侧行为一致。
 *
 * 存在的理由：网页 AI 的请求体在 CDP 两层表示不同（`Fetch.requestPaused.request.postData` 是字符串、`postDataEntries` 是 base64
 * 字节），而"帧长度"这类字段必须按真实字节算，所以整条改写链路统一走字节。
 */
object Bytes {

  /** UTF-8 编码（含代理对处理）。 */
  def utf8(text: String): Array[Byte] = {
    val out     = new Array[Byte](text.length * 4)
    var len     = 0
    var i       = 0
    while (i < text.length) {
      var cp = text.charAt(i).toInt
      if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
        val low = text.charAt(i + 1).toInt
        if (low >= 0xdc00 && low <= 0xdfff) {
          cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00)
          i += 1
        }
      }
      if (cp < 0x80) {
        out(len) = cp.toByte
        len += 1
      } else if (cp < 0x800) {
        out(len) = (0xc0 | (cp >> 6)).toByte
        out(len + 1) = (0x80 | (cp & 0x3f)).toByte
        len += 2
      } else if (cp < 0x10000) {
        out(len) = (0xe0 | (cp >> 12)).toByte
        out(len + 1) = (0x80 | ((cp >> 6) & 0x3f)).toByte
        out(len + 2) = (0x80 | (cp & 0x3f)).toByte
        len += 3
      } else {
        out(len) = (0xf0 | (cp >> 18)).toByte
        out(len + 1) = (0x80 | ((cp >> 12) & 0x3f)).toByte
        out(len + 2) = (0x80 | ((cp >> 6) & 0x3f)).toByte
        out(len + 3) = (0x80 | (cp & 0x3f)).toByte
        len += 4
      }
      i += 1
    }
    val trimmed = new Array[Byte](len)
    System.arraycopy(out, 0, trimmed, 0, len)
    trimmed
  }

  /** UTF-8 解码；非法序列按 U+FFFD 处理（不至于因为一个坏字节整段失败）。 */
  def text(bytes: Array[Byte]): String = {
    val sb = new StringBuilder(bytes.length)
    var i  = 0
    while (i < bytes.length) {
      val b0 = bytes(i) & 0xff
      if (b0 < 0x80) {
        sb.append(b0.toChar)
        i += 1
      } else {
        val extra = if (b0 >= 0xf0) 3 else if (b0 >= 0xe0) 2 else if (b0 >= 0xc0) 1 else -1
        if (extra < 0 || i + extra >= bytes.length) {
          sb.append('\ufffd')
          i += 1
        } else {
          var cp = b0 & (0x7f >> extra)
          var ok = true
          var k  = 1
          while (k <= extra) {
            val bn = bytes(i + k) & 0xff
            if ((bn & 0xc0) != 0x80) ok = false
            cp = (cp << 6) | (bn & 0x3f)
            k += 1
          }
          if (!ok) {
            sb.append('\ufffd')
            i += 1
          } else {
            if (cp > 0xffff) {
              val v = cp - 0x10000
              sb.append((0xd800 + (v >> 10)).toChar)
              sb.append((0xdc00 + (v & 0x3ff)).toChar)
            } else sb.append(cp.toChar)
            i += extra + 1
          }
        }
      }
    }
    sb.result()
  }

  /**
   * CDP 的 `postData`（字符串）→ 原始字节。
   *
   * Chrome 对合法 UTF-8 的体给真实文本（此时会有 > 0xFF 的字符），对二进制 / 帧化的体给"逐字节"表示（每个字符 ≤ 0xFF）； 两者互斥，所以：出现 > 0xFF 的字符就按 UTF-8
   * 编码，否则把字符当字节用。
   */
  def fromPostDataString(text: String): Array[Byte] =
    if (text.exists(_ > '\u00ff')) utf8(text)
    else {
      val out = new Array[Byte](text.length)
      var i   = 0
      while (i < text.length) {
        out(i) = (text.charAt(i) & 0xff).toByte
        i += 1
      }
      out
    }

  /** 诊断用的十六进制预览：`00 00 00 01 54 …`。 */
  def hexPreview(bytes: Array[Byte], max: Int = 32): String = {
    val n  = math.min(max, bytes.length)
    val sb = new StringBuilder(n * 3)
    var i  = 0
    while (i < n) {
      if (i > 0) sb.append(' ')
      val h = Integer.toHexString(bytes(i) & 0xff)
      if (h.length < 2) sb.append('0')
      sb.append(h)
      i += 1
    }
    if (bytes.length > n) sb.append(" …")
    sb.result()
  }
}
