package com.just3bowls.api.protocol

/**
 * native messaging 的分片规则（纯逻辑，JVM + Scala.js 通用，`server/test` 有金值）。
 *
 * 为什么必须分片：Chrome 对 **host → 扩展** 的单条消息有 ~1MB 上限（扩展 → host ~64MB），而一次 OpenAI 请求可能带 20MB 的 body， 这个方向正好是受限的那一侧。所以
 * `client_request` 拆成"头 + N 个 `client_request_chunk`"，头里带 `bodyFrames`。
 *
 * **线格式契约**（读侧只有这一种解释，`outbound` 是写侧的唯一出处）：
 *   - `bodyFrames == 1`：正文就放在**头消息的 `body`** 里，后面没有 chunk 帧（小请求别多发一条）。
 *   - `bodyFrames > 1`：头的 `body` 留空，正文按 `seq = 0..bodyFrames-1` 跟在 chunk 帧里，全部到齐才算一条请求。
 *   - `bodyFrames` 缺失按 1 处理（兼容只有头的写法）。
 *
 * 分片按**字符**切（消息体本来就是 JSON 里的字符串字段，字符边界即安全边界），每片默认 128K 字符 —— 最坏情况（每个字符 4 字节 UTF-8） 约 512KB，加上 JSON 包装仍稳稳低于 1MB。
 */
object NativeFragments {

  /** 单片字符数上限（保守取值，见上）。 */
  val ChunkChars: Int = 128 * 1024

  /** 一次请求最多允许多少片（128K × 200 ≈ 25MB，与 HTTP 侧 20MB 上限同量级）。 */
  val MaxFrames: Int = 200

  /** 需要几片（空 body 也算 1 片，避免"0 片"这种特例）。 */
  def frameCount(body: String, chunkChars: Int = ChunkChars): Int = {
    val text = Option(body).getOrElse("")
    val size = math.max(1, math.max(1, chunkChars))
    math.max(1, (text.length + size - 1) / size)
  }

  /** 按顺序切片；空 body 返回 `List("")`。 */
  def split(body: String, chunkChars: Int = ChunkChars): List[String] = {
    val text = Option(body).getOrElse("")
    val size = math.max(1, math.max(1, chunkChars))
    if (text.isEmpty) List("")
    else text.grouped(size).toList
  }

  /**
   * 写侧分帧：`(bodyFrames, chunks)`，语义见上面的线格式契约 —— 1 片时正文内联在头里（chunks 为空），多片时才跟在后面。
   *
   * 读侧（扩展 `ServerWorkerClient` / 测试里的 fake worker）只按这一个契约解释，两边不会再各写一套判断。
   */
  def outbound(body: String, chunkChars: Int = ChunkChars): (Int, List[String]) = {
    val parts = split(body, chunkChars)
    if (parts.length <= 1) (1, Nil) else (parts.length, parts)
  }

  /**
   * 接收侧装配：按 `bodyFrames` 建槽，`accept(seq, data)` 逐片填；全部到齐时返回整段 body。
   *
   * 越界/重复片一律拒绝（返回 `None` 且不改变状态）—— 分片是协议的一部分，宁可失败也别拼出半截请求。
   */
  final class Assembly(bodyFrames: Int) {
    private val frames = math.max(1, bodyFrames)
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
