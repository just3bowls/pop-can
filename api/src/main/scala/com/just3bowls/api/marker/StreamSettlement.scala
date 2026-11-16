package com.just3bowls.api.marker

/**
 * Computes the longest prefix of an accumulated stream that can never be changed by a later settlement rule.
 *
 * Consumers are append-only: once text is emitted, it cannot be retracted. A guard describes why the tail may still
 * change:
 *   - [[StreamSettlement.AtPrefix]]: a regex replacement can only start at a declared literal prefix. Hold the earliest
 *     unclosed full prefix and any partial prefix at the end.
 *   - [[StreamSettlement.LastChars]]: the rule cannot expose a start marker, so retain a declared number of trailing
 *     characters as the maximum possible incomplete match.
 *
 * Multiple guards are intentionally conservative and settle at the earliest boundary across all of them.
 */
object StreamSettlement {

  /** A completed match in raw-text coordinates: `[start, end)` will be replaced. */
  final case class MatchSpan(start: Int, end: Int)

  sealed trait Guard

  /** Every match starts at this literal string; completed matches are supplied in raw-text coordinates. */
  final case class AtPrefix(prefix: String, matches: List[MatchSpan]) extends Guard

  /** The longest possible match is this many characters, so retain that much of the stream tail. */
  final case class LastChars(chars: Int) extends Guard

  /** Length of the raw prefix that can safely be transformed and emitted. */
  def settledLength(raw: String, guards: List[Guard]): Int = {
    val text = Option(raw).getOrElse("")
    if (text.isEmpty || guards.isEmpty) text.length
    else guards.map(guardLength(text, _)).min
  }

  /** All starts of `prefix` in `raw` in ascending order, including overlapping matches. */
  def occurrences(raw: String, prefix: String): List[Int] =
    if (Option(prefix).forall(_.isEmpty)) Nil
    else {
      val out = List.newBuilder[Int]
      var i   = raw.indexOf(prefix)
      while (i >= 0) {
        out += i
        i = raw.indexOf(prefix, i + 1)
      }
      out.result()
    }

  private def guardLength(raw: String, guard: Guard): Int =
    guard match {
      case LastChars(chars) =>
        raw.length - math.max(0, math.min(chars, raw.length))

      case AtPrefix(prefix, matches) if prefix.nonEmpty =>
        val afterLastMatch = matches.foldLeft(0)((acc, span) => math.max(acc, span.end))
        val openStart      =
          occurrences(raw, prefix).filter(_ >= afterLastMatch).minOption
        val partialStart   =
          partialTailStart(raw, prefix).filter(_ >= afterLastMatch)
        (openStart.toList ++ partialStart.toList).minOption.getOrElse(raw.length)

      case AtPrefix(_, _) =>
        raw.length
    }

  /** Start of the longest proper prefix of `prefix` at the end of `raw`, if any. */
  private def partialTailStart(raw: String, prefix: String): Option[Int] =
    (1 until prefix.length).reverseIterator
      .find(k => raw.endsWith(prefix.substring(0, k)))
      .map(k => raw.length - k)
}
