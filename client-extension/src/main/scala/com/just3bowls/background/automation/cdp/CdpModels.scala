package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge

import java.lang.Character
final case class ParsedPrompt(
    content: String,
    thinking: String,
    totalTokens: Int = 0,
    finishReason: String = "stop"
)
final case class CdpRawChunk(
    generationId: String,
    seq: Int,
    captureSource: String,
    data: String,
    base64: Boolean = true,
    done: Boolean = false,
    error: String = "",
    /**
     * 这段 payload 的来源 URL（HTTP 用请求 URL、WS 用 socket URL；未知则空）。
     *
     * 规则里的 `match.urlRegex` 就是拿它做作用域：同一家 provider 可以有多条端点/模式， handler 用 URL 声明"我只吃这条传输"，而不是靠 payload 形状去猜。
     */
    sourceUrl: String = ""
)
private[background] object SseBodyDecoder {
  def decode(rawBody: String, base64: Boolean): String          =
    if (base64) {
      try {
        val binary = JsBridge.atob(rawBody).toString
        decodeUtf8Bytes(toBytes(binary))
      } catch {
        case _: Throwable => rawBody
      }
    } else if (looksLikeUtf8Mojibake(rawBody)) {
      decodeUtf8Bytes(toBytes(rawBody))
    } else {
      rawBody
    }
  private def looksLikeUtf8Mojibake(text: String): Boolean      = {
    var utf8PairCount = 0
    var cjkCount      = 0
    var i             = 0
    while (i < text.length - 1) {
      val b0 = windows1252Byte(text.charAt(i))
      val b1 = windows1252Byte(text.charAt(i + 1))
      if (b0 >= 0xc0 && b1 >= 0x80 && b1 <= 0xbf) utf8PairCount += 1
      if (isCjk(text.charAt(i))) cjkCount += 1
      i += 1
    }
    if (text.nonEmpty && isCjk(text.charAt(text.length - 1))) cjkCount += 1
    utf8PairCount > 0 && cjkCount == 0
  }
  private def isCjk(c: Char): Boolean                           = {
    val cp = c.toInt
    (cp >= 0x3400 && cp <= 0x4dbf) ||
    (cp >= 0x4e00 && cp <= 0x9fff) ||
    (cp >= 0xf900 && cp <= 0xfaff)
  }
  private def toBytes(text: String): Array[Int]                 = {
    val bytes = new Array[Int](text.length)
    var i     = 0
    while (i < text.length) {
      bytes(i) = windows1252Byte(text.charAt(i))
      i += 1
    }
    bytes
  }
  private def windows1252Byte(c: Char): Int                     = c match {
    case '\u20AC'             => 0x80
    case '\u201A'             => 0x82
    case '\u0192'             => 0x83
    case '\u201E'             => 0x84
    case '\u2026'             => 0x85
    case '\u2020'             => 0x86
    case '\u2021'             => 0x87
    case '\u02C6'             => 0x88
    case '\u2030'             => 0x89
    case '\u0160'             => 0x8a
    case '\u2039'             => 0x8b
    case '\u0152'             => 0x8c
    case '\u017D'             => 0x8e
    case '\u2018'             => 0x91
    case '\u2019'             => 0x92
    case '\u201C'             => 0x93
    case '\u201D'             => 0x94
    case '\u2022'             => 0x95
    case '\u2013'             => 0x96
    case '\u2014'             => 0x97
    case '\u02DC'             => 0x98
    case '\u2122'             => 0x99
    case '\u0161'             => 0x9a
    case '\u203A'             => 0x9b
    case '\u0153'             => 0x9c
    case '\u017E'             => 0x9e
    case '\u0178'             => 0x9f
    case _ if c.toInt <= 0xff => c.toInt
    case _                    => -1
  }
  private def decodeUtf8Bytes(bytes: Array[Int]): String        = {
    val sb = new StringBuilder
    var i  = 0
    while (i < bytes.length) {
      val b0 = bytes(i)
      if (b0 >= 0 && b0 < 0x80) {
        sb.append(b0.toChar)
        i += 1
      } else if (b0 >= 0xc2 && b0 <= 0xdf && i + 1 < bytes.length && isContinuation(bytes(i + 1))) {
        sb.append((((b0 & 0x1f) << 6) | (bytes(i + 1) & 0x3f)).toChar)
        i += 2
      } else if (
        b0 >= 0xe0 && b0 <= 0xef && i + 2 < bytes.length &&
        isContinuation(bytes(i + 1)) && isContinuation(bytes(i + 2))
      ) {
        val cp = ((b0 & 0x0f) << 12) | ((bytes(i + 1) & 0x3f) << 6) | (bytes(i + 2) & 0x3f)
        appendCodePoint(sb, cp)
        i += 3
      } else if (
        b0 >= 0xf0 && b0 <= 0xf4 && i + 3 < bytes.length &&
        isContinuation(bytes(i + 1)) && isContinuation(bytes(i + 2)) && isContinuation(bytes(i + 3))
      ) {
        val cp = ((b0 & 0x07) << 18) | ((bytes(i + 1) & 0x3f) << 12) |
          ((bytes(i + 2) & 0x3f) << 6) | (bytes(i + 3) & 0x3f)
        appendCodePoint(sb, cp)
        i += 4
      } else {
        sb.append('\uFFFD')
        i += 1
      }
    }
    sb.result()
  }
  private def isContinuation(b: Int): Boolean                   =
    (b & 0xc0) == 0x80
  private def appendCodePoint(sb: StringBuilder, cp: Int): Unit =
    if (cp < 0x10000) sb.append(cp.toChar)
    else sb.appendAll(Character.toChars(cp))
}
