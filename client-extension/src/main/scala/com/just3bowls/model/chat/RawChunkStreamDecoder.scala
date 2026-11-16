package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import com.just3bowls.api.protocol.Utf8StreamDecoder

/** Decode one logical CDP byte stream while preserving truncated UTF-8 sequences across chunks. */
final class RawChunkStreamDecoder {
  private val utf8                                                               = new Utf8StreamDecoder
  def decode(data: String, base64: Boolean, finalChunk: Boolean = false): String =
    if (!base64) data
    else if (data.isEmpty) utf8.feed(Array.emptyByteArray, finalChunk)
    else {
      try {
        val binary = JsBridge.asString(JsBridge.atob(data))
        val bytes  = new Array[Byte](binary.length)
        var i      = 0
        while (i < binary.length) {
          bytes(i) = binary.charAt(i).toInt.toByte
          i += 1
        }
        utf8.feed(bytes, finalChunk)
      } catch {
        case _: Throwable => data
      }
    }
}
