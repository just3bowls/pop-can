package com.just3bowls.api.protocol

import scala.collection.mutable.ArrayBuffer

/**
 * Stateful UTF-8 decoder for byte chunks that may split a multi-byte code point.
 *
 * Invalid complete sequences become U+FFFD. An incomplete sequence is held until the next feed, or emitted as U+FFFD
 * when `finalChunk` is true.
 */
final class Utf8StreamDecoder {
  private val pending = ArrayBuffer.empty[Int]

  def feed(bytes: Array[Byte], finalChunk: Boolean = false): String = {
    val input = pending ++ bytes.iterator.map(_ & 0xff)
    pending.clear()

    val out = new StringBuilder
    var i   = 0
    while (i < input.length) {
      val b0 = input(i)
      if (b0 < 0x80) {
        out.append(b0.toChar)
        i += 1
      } else {
        val width = expectedWidth(b0)
        if (width == 0) {
          out.append('\uFFFD')
          i += 1
        } else if (i + width > input.length) {
          if (finalChunk) {
            out.append('\uFFFD')
            if (validContinuationPrefix(input, i, width)) i = input.length
            else i += 1
          } else {
            pending ++= input.slice(i, input.length)
            i = input.length
          }
        } else if (!validSequence(input, i, width)) {
          out.append('\uFFFD')
          i += 1
        } else {
          val cp =
            if (width == 2)
              ((b0 & 0x1f) << 6) | (input(i + 1) & 0x3f)
            else if (width == 3)
              ((b0 & 0x0f) << 12) | ((input(i + 1) & 0x3f) << 6) | (input(i + 2) & 0x3f)
            else
              ((b0 & 0x07) << 18) | ((input(i + 1) & 0x3f) << 12) |
                ((input(i + 2) & 0x3f) << 6) | (input(i + 3) & 0x3f)
          appendCodePoint(out, cp)
          i += width
        }
      }
    }
    out.result()
  }

  private def expectedWidth(b0: Int): Int =
    if (b0 >= 0xc2 && b0 <= 0xdf) 2
    else if (b0 >= 0xe0 && b0 <= 0xef) 3
    else if (b0 >= 0xf0 && b0 <= 0xf4) 4
    else 0

  private def validSequence(bytes: scala.collection.IndexedSeq[Int], start: Int, width: Int): Boolean = {
    val b0 = bytes(start)
    val b1 = bytes(start + 1)
    if (!isContinuation(b1)) false
    else if (width == 2) true
    else if (width == 3) {
      val b2 = bytes(start + 2)
      isContinuation(b2) &&
      (b0 != 0xe0 || b1 >= 0xa0) &&
      (b0 != 0xed || b1 <= 0x9f)
    } else {
      val b2 = bytes(start + 2)
      val b3 = bytes(start + 3)
      isContinuation(b2) && isContinuation(b3) &&
      (b0 != 0xf0 || b1 >= 0x90) &&
      (b0 != 0xf4 || b1 <= 0x8f)
    }
  }

  private def isContinuation(byte: Int): Boolean =
    (byte & 0xc0) == 0x80

  private def validContinuationPrefix(
      bytes: scala.collection.IndexedSeq[Int],
      start: Int,
      width: Int
  ): Boolean = {
    var i  = start + 1
    val to = math.min(bytes.length, start + width)
    while (i < to && isContinuation(bytes(i))) i += 1
    i == to
  }

  private def appendCodePoint(out: StringBuilder, cp: Int): Unit =
    if (cp <= 0xffff) out.append(cp.toChar)
    else {
      val value = cp - 0x10000
      out.append((0xd800 + (value >> 10)).toChar)
      out.append((0xdc00 + (value & 0x3ff)).toChar)
    }
}
