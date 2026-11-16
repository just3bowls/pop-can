package com.just3bowls.api.protocol

/**
 * gRPC-Web 帧序列的纯解析 / 重建（JVM + Scala.js 通用，用 `server/test` 做单测）。
 *
 * 帧格式：`flag(1) + 长度(4, 大端) + payload`。实测里 kimi 的请求可能是**多帧**（前面一个非 JSON 帧 + 后面才是 JSON 帧），
 * 也可能因为客户端改版换形，所以解析必须结构化：只有"恰好消费完整段 body"才算帧序列，任何一帧头非法 / 长度越界都算"不是帧"。
 */
object GrpcWebFrames {

  final case class Frame(flag: Int, payload: Array[Byte])

  /** flag：bit0 = 压缩、bit7 = 尾帧；只认这四种组合（数据帧、压缩数据帧、尾帧、压缩尾帧）。 */
  private def validFlag(flag: Int): Boolean = flag == 0x00 || flag == 0x01 || flag == 0x80 || flag == 0x81

  private def lengthAt(bytes: Array[Byte], offset: Int): Int =
    ((bytes(offset) & 0xff) << 24) | ((bytes(offset + 1) & 0xff) << 16) |
      ((bytes(offset + 2) & 0xff) << 8) | (bytes(offset + 3) & 0xff)

  /** 逐帧解析；必须恰好消费完整段 body，否则 `None`（调用方按"不是帧序列"处理）。 */
  def parse(bytes: Array[Byte]): Option[List[Frame]] =
    if (bytes.length < 5) None
    else {
      val frames = scala.collection.mutable.ListBuffer.empty[Frame]
      var offset = 0
      var ok     = true
      while (ok && offset < bytes.length) {
        if (offset + 5 > bytes.length) ok = false
        else {
          val flag = bytes(offset) & 0xff
          val len  = lengthAt(bytes, offset + 1)
          if (!validFlag(flag) || len < 0 || offset + 5 + len > bytes.length) ok = false
          else {
            frames += Frame(flag, java.util.Arrays.copyOfRange(bytes, offset + 5, offset + 5 + len))
            offset += 5 + len
          }
        }
      }
      if (!ok || frames.isEmpty || offset != bytes.length) None else Some(frames.toList)
    }

  /** 4 字节大端长度字段（回写用）。 */
  def lengthBytes(length: Int): Array[Byte] =
    Array(
      ((length >>> 24) & 0xff).toByte,
      ((length >>> 16) & 0xff).toByte,
      ((length >>> 8) & 0xff).toByte,
      (length & 0xff).toByte
    )

  /** 一帧的字节表示（5 字节头 + payload，长度自动重算）。 */
  def frame(flag: Int, payload: Array[Byte]): Array[Byte] = {
    val out = new Array[Byte](5 + payload.length)
    out(0) = flag.toByte
    System.arraycopy(lengthBytes(payload.length), 0, out, 1, 4)
    System.arraycopy(payload, 0, out, 5, payload.length)
    out
  }
}
