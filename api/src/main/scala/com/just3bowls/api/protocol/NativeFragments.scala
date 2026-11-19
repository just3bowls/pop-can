package com.just3bowls.api.protocol

/**
 * native messaging 的分片规则（纯逻辑，JVM + Scala.js 通用，`server/test` 有金值）。
 *
 * 为什么必须分片：Chrome 对 **host → 扩展** 的单条消息有 ~1MB 上限（扩展 → host ~64MB），而一次 OpenAI 请求可能带 20MB 的 body， 这个方向正好是受限的那一侧。所以
 * `client_request` 拆成"头 + N 个 `client_request_chunk`"，头里带 `bodyFrames`。
 *
 * **线格式契约**（读侧只有这一种解释，`outbound` 是写侧的唯一出处）：
 *   - `bodyFrames == 0`：请求没有 body，不发送 chunk。
 *   - `bodyFrames >= 1`：正文按 `seq = 0..bodyFrames-1` 全部跟在 chunk 帧里，全部到齐才算一条请求；单 chunk 也不例外。
 *
 * 分片按**字符**切（消息体本来就是 JSON 里的字符串字段，字符边界即安全边界），每片默认 128K 字符 —— 最坏情况（每个字符 4 字节 UTF-8） 约 512KB，加上 JSON 包装仍稳稳低于 1MB。
 */
object NativeFragments {

  /** 单片字符数上限（保守取值，见上）。 */
  val ChunkChars: Int = 128 * 1024

  /** 一次请求最多允许多少片（128K × 200 ≈ 25MB，与 HTTP 侧 20MB 上限同量级）。 */
  val MaxFrames: Int = 200

  /** 需要几片；没有 body 时为 0。 */
  def frameCount(body: String, chunkChars: Int = ChunkChars): Int = {
    val text = Option(body).getOrElse("")
    val size = math.max(1, math.max(1, chunkChars))
    if (text.isEmpty) 0 else (text.length + size - 1) / size
  }

  /** 按顺序切片；空 body 返回空列表。 */
  def split(body: String, chunkChars: Int = ChunkChars): List[String] = {
    val text = Option(body).getOrElse("")
    val size = math.max(1, math.max(1, chunkChars))
    if (text.isEmpty) Nil
    else text.grouped(size).toList
  }

  /** 写侧分帧：没有 body 为 `(0, Nil)`；有 body 时所有片都显式返回。 */
  def outbound(body: String, chunkChars: Int = ChunkChars): (Int, List[String]) = {
    val parts = split(body, chunkChars)
    (parts.length, parts)
  }

  /**
   * 接收侧装配：按 `bodyFrames` 建槽，`accept(seq, data)` 逐片填；全部到齐时返回整段 body。
   *
   * 越界/重复片一律拒绝（返回 `None` 且不改变状态）—— 分片是协议的一部分，宁可失败也别拼出半截请求。
   */
  final class Assembly(bodyFrames: Int) {
    private val frames = math.max(0, bodyFrames)
    private val parts  = Array.fill[String](frames)("")
    private val filled = Array.fill[Boolean](frames)(false)
    private var seen   = 0

    def expected: Int = frames

    /** 显式拒绝：越界或者这片已经收过。 */
    def accepts(seq: Int): Boolean = seq >= 0 && seq < frames && !filled(seq)

    def accept(seq: Int, data: String): Option[String] =
      if (!accepts(seq)) None
      else {
        parts(seq) = Option(data).getOrElse("")
        filled(seq) = true
        seen += 1
        if (seen == frames) Some(parts.mkString) else None
      }
  }
}
