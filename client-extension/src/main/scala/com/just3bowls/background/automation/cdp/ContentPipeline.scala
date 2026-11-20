package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge

import scala.scalajs.js
import com.just3bowls.api.marker.StreamSettlement
import com.just3bowls.api.marker.StreamSettlement.{AtPrefix, Guard, LastChars, MatchSpan}
import com.just3bowls.model.provider.{
  ContentTransformRule,
  JsRegex,
  ParserRuleDefinition,
  TransformCandidatePrefix,
  TransformHoldback
}

/**
 * Ordered, streaming-safe transformations over the accumulated model response.
 *
 * Handlers extract transport fields; this is the only layer allowed to normalize visible content. A transform cannot
 * expose a mutable tail: its settlement guard supplies the oldest position that future chunks may still change.
 * Transform patterns are independent: replacements must not synthesize a token owned by a later transform.
 */
final private[background] class ContentPipeline(rule: ParserRuleDefinition) {
  sealed private trait Settlement {
    def guardOf(raw: String, matches: List[MatchSpan]): Guard
  }
  final private case class AtCandidate(prefix: String) extends Settlement {
    override def guardOf(raw: String, matches: List[MatchSpan]): Guard = AtPrefix(prefix, matches)
  }
  final private case class HoldBack(chars: Int)        extends Settlement {
    override def guardOf(raw: String, matches: List[MatchSpan]): Guard = LastChars(chars)
  }
  final private case class Transform(
      id: String,
      regex: js.RegExp,
      replacement: String,
      settlement: Settlement
  ) {
    def matches(raw: String): List[MatchSpan] = {
      regex.lastIndex = 0
      val out     = List.newBuilder[MatchSpan]
      var matched = regex.exec(raw)
      while (matched != null && !js.isUndefined(matched)) {
        val start = JsBridge.undefinedInt(matched.index).getOrElse(0)
        val end   = regex.lastIndex
        if (end > start) out += MatchSpan(start, end)
        else regex.lastIndex = start + 1
        matched = regex.exec(raw)
      }
      out.result()
    }
    def applyTo(text: String): String         = {
      regex.lastIndex = 0
      JsBridge.asString(JsBridge.call(text, "replace", regex, replacement))
    }
  }
  private val schemaVersion: Int = rule.schemaVersion
  require(schemaVersion == 1, s"parserRule.schemaVersion must be 1, got $schemaVersion")
  private val transforms: List[Transform] =
    rule.contentTransforms.map(compileTransform)
  private val duplicateIds                =
    transforms.groupBy(_.id).collect { case (id, items) if items.size > 1 => id }.toList.sorted
  require(duplicateIds.isEmpty, s"contentTransforms contains duplicate id(s): ${duplicateIds.mkString(", ")}")

  /** Applies all transforms to the currently settled prefix, or to the complete body at turnover. */
  def render(raw: String, complete: Boolean): String                  =
    if (transforms.isEmpty || raw.isEmpty) raw
    else {
      val guards     = transforms.map(transform =>
        transform.settlement.guardOf(raw, transform.matches(raw))
      )
      val safeLength =
        if (complete) raw.length
        else StreamSettlement.settledLength(raw, guards)
      transforms.foldLeft(raw.substring(0, safeLength)) { case (text, transform) =>
        transform.applyTo(text)
      }
    }
  private def compileTransform(spec: ContentTransformRule): Transform = {
    val id          = spec.id.trim
    val pattern     = spec.regex
    val replacement = spec.replacement
    require(id.nonEmpty, "contentTransforms[]: id must be a non-empty string")
    require(pattern.trim.nonEmpty, s"contentTransforms[$id].regex must be a non-empty string")
    val regex       = JsRegex
      .compile(pattern, global = true)
      .getOrElse(throw new IllegalArgumentException(s"contentTransforms[$id].regex does not compile"))
    val settlement  =
      spec.settlement match {
        case TransformCandidatePrefix(prefix) => AtCandidate(prefix)
        case TransformHoldback(chars)         => HoldBack(math.max(0, chars))
      }
    Transform(id, regex, replacement, settlement)
  }
}
