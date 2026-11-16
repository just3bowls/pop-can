package com.just3bowls.api
import com.just3bowls.api.marker.*

import zio.test._

/**
 * Golden-value tests for streaming-safe replacement boundaries.
 *
 * A consumer may only receive a prefix that no future chunk can alter. The tail can remain mutable because a token is
 * only partially visible, or because a regex without a declared start marker needs a defensive holdback.
 */
object StreamSettlementSpec extends ZIOSpecDefault {

  import StreamSettlement._

  private val Think = AtPrefix("<think>", Nil)
  private val Turn  = AtPrefix("【turn", Nil)

  def spec: Spec[Any, Any] = suite("StreamSettlement")(
    test("an unclosed candidate holds the entire candidate even when it is several kilobytes long") {
      val raw = "<think>\u0000The user asks a long question and the block is still open"
      assertTrue(settledLength(raw, List(Think)) == 0)
    },
    test("a completed match releases the text after it without losing the marker head") {
      val raw     = "<think>x</think>\u0000@@BEG:response@@\n@@BEG:content@@\nanswer"
      val closeAt = raw.indexOf("</think>") + "</think>".length + 1
      val guard   = AtPrefix("<think>", List(MatchSpan(0, closeAt)))
      val settled = settledLength(raw, List(guard))
      assertTrue(settled == raw.length, raw.substring(0, settled).contains("@@BEG:response@@"))
    },
    test("ordinary bracketed prose is not treated as a candidate") {
      val raw = "答案里提到【引用】这种普通写法，后面接着讲均摊分析"
      assertTrue(settledLength(raw, List(Turn)) == raw.length)
    },
    test("partial prefixes are held at the exact start of the partial candidate") {
      assertTrue(
        settledLength("答案<thi", List(Think)) == "答案".length,
        settledLength("答案【tur", List(Turn)) == "答案".length
      )
    },
    test("multiple open candidates settle at the earliest one") {
      val guards = List(AtPrefix("<think>", Nil), AtPrefix("【citation:", Nil))
      assertTrue(settledLength("开头<think>a【citation:2", guards) == "开头".length)
    },
    test("multiple guards use the most conservative boundary") {
      val guards = List(AtPrefix("<think>", Nil), LastChars(8))
      val raw    = "ordinary answer text with a defensive tail"
      assertTrue(settledLength(raw, guards) == raw.length - 8)
    },
    test("a completed candidate does not hide later text unless another candidate starts") {
      val raw          = "<think>a</think>b【cit"
      val span         = MatchSpan(0, "<think>a</think>".length)
      val expectedSafe = raw.indexOf("【cit")
      assertTrue(
        settledLength(raw, List(AtPrefix("<think>", List(span)), AtPrefix("【citation:", Nil))) == expectedSafe
      )
    },
    test("LastChars falls back to a controlled holdback and clamps invalid sizes") {
      val raw = "ordinary answer text"
      assertTrue(
        settledLength(raw, List(LastChars(5))) == raw.length - 5,
        settledLength(raw, List(LastChars(999))) == 0,
        settledLength(raw, List(LastChars(-1))) == raw.length
      )
    },
    test("no guards means the complete accumulated text is already safe") {
      val raw = "plain answer"
      assertTrue(settledLength(raw, Nil) == raw.length)
    },
    test("settlement is monotonic as more text arrives") {
      val steps = List(
        "answer",
        "answer<",
        "answer<thi",
        "answer<think>still open",
        "answer<think>closed</think>done"
      )
      val sizes = steps.map { raw =>
        val close = raw.indexOf("</think>")
        val span  =
          if (close < 0) Nil
          else List(MatchSpan(raw.indexOf("<think>"), close + "</think>".length))
        settledLength(raw, List(AtPrefix("<think>", span)))
      }
      assertTrue(sizes == sizes.sorted)
    }
  )
}
