package com.just3bowls.server.native

/**
 * native messaging 的 stdio 帧编解码（Chrome 的线格式）：**4 字节 little-endian 长度前缀 + UTF-8 JSON**。
 *
 * 只服务 JVM 侧（扩展侧的帧由 Chrome 自己封/解，`postMessage` 拿到的是对象）。
 *
 * 注意两条纪律：
 *   - **stdout 只能出现帧**：所有日志必须走 stderr / 文件，否则协议流被污染（`ServerMain` 的 logger 换成 stderr）。
 *   - 单帧上限按 Chrome 的 inbound 上限（扩展 → host ~64MB）拒绝，超限直接报错而不是硬吞。
 */
object StdioFrames {

  /** 扩展 → host 单帧上限（Chrome 文档口径 64MB；留一点余量给 JSON 包装）。 */
  val MaxInboundBytes: Int = 64 * 1024 * 1024

  def encode(json: String): Array[Byte] = {
    val payload = Option(json).getOrElse("").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val out     = new Array[Byte](4 + payload.length)
    out(0) = (payload.length & 0xff).toByte
    out(1) = ((payload.length >>> 8) & 0xff).toByte
    out(2) = ((payload.length >>> 16) & 0xff).toByte
    out(3) = ((payload.length >>> 24) & 0xff).toByte
    System.arraycopy(payload, 0, out, 4, payload.length)
    out
  }

  /** 增量解码器：随便怎么切 chunk 喂进来，返回本次攒出的完整 JSON 消息。 */
  final class Decoder(maxBytes: Int = MaxInboundBytes) {
    private var buf = new Array[Byte](8192)
    private var len = 0

    def feed(chunk: Array[Byte]): Either[String, List[String]] = {
      if (chunk.nonEmpty) {
        if (len + chunk.length > buf.length) {
          val next = new Array[Byte](math.max(buf.length * 2, len + chunk.length))
          System.arraycopy(buf, 0, next, 0, len)
          buf = next
        }
        System.arraycopy(chunk, 0, buf, len, chunk.length)
        len += chunk.length
      }
      drain()
    }

    private def drain(): Either[String, List[String]] = {
      val out   = List.newBuilder[String]
      var pos   = 0
      var stop  = false
      var error = Option.empty[String]
      while (!stop && error.isEmpty) {
        if (len - pos < 4) stop = true
        else {
          val size =
            (buf(pos) & 0xff) | ((buf(pos + 1) & 0xff) << 8) | ((buf(pos + 2) & 0xff) << 16) |
              ((buf(pos + 3) & 0xff) << 24)
          if (size < 0 || size > maxBytes) {
            error = Some(s"native frame too large: $size bytes (limit $maxBytes)")
            stop = true
          } else if (len - pos - 4 < size) stop = true
          else {
            out += new String(
              java.util.Arrays.copyOfRange(buf, pos + 4, pos + 4 + size),
              java.nio.charset.StandardCharsets.UTF_8
            )
            pos += 4 + size
          }
        }
      }
      error match {
        case Some(message) => Left(message)
        case None          =>
          compact(pos)
          Right(out.result())
      }
    }

    private def compact(consumed: Int): Unit =
      if (consumed > 0) {
        System.arraycopy(buf, consumed, buf, 0, len - consumed)
        len -= consumed
      }
  }
}
