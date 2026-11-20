package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import scala.scalajs.js

/** Development-only projection of the effective parser rule after all authoring shorthands have been normalized. */
object ParserRuleExplain {
  def explain(ruleJson: String): Either[String, js.Any] =
    ParserRuleDefinition.parse(ruleJson).map(rule =>
      JsBridge.obj(
        "schemaVersion"    -> (rule.schemaVersion),
        "memory"           -> (js.Dictionary(rule.memory.toSeq*)),
        "mode"             -> modeJson(rule.mode),
        "handlers"         -> (js.Array(rule.handlers.map(handlerJson)*)),
        "finalActions"     -> (js.Array(rule.finalActions.map(actionJson)*)),
        "contentTransforms" -> (js.Array(rule.contentTransforms.map(transformJson)*)),
        "request"          -> requestJson(rule.request)
      )
    )

  private def handlerJson(handler: ParserHandler): js.Any =
    JsBridge.obj(
      "id"      -> (handler.id),
      "match"   -> matchJson(handler.matchSpec),
      "actions" -> (js.Array(handler.actions.map(actionJson)*))
    )

  private def modeJson(mode: Option[ParserMode]): js.Any =
    mode
      .map(value =>
        JsBridge.obj(
          "fromStream" -> (js.Array(value.fromStream.map(extractor =>
            JsBridge.obj("rawRegex" -> (extractor.rawRegex), "group" -> (extractor.group))
          )*))
        )
      )
      .getOrElse(null)

  private def matchJson(value: MatchSpec): js.Any =
    JsBridge.obj(
      "rawRegex"        -> value.rawRegex.orNull,
      "jsonGroup"       -> (value.jsonGroup),
      "eventsPath"      -> value.eventsPath.orNull,
      "path"            -> value.path.orNull,
      "regex"           -> value.regex.orNull,
      "textMatch"       -> value.textMatch.map(textMatchJson).orNull,
      "urlRegex"        -> value.urlRegex.orNull,
      "mode"            -> value.mode.orNull,
      "memoryKey"       -> value.memoryKey.orNull,
      "memoryEquals"    -> value.memoryEquals.orNull,
      "memoryNotEquals" -> value.memoryNotEquals.orNull,
      "memoryPath"      -> value.memoryPath.orNull,
      "allOf"           -> (js.Array(value.allOf.map(matchJson)*)),
      "anyOf"           -> (js.Array(value.anyOf.map(matchJson)*))
    )

  private def textMatchJson(value: TextMatch): js.Any = {
    val (op, literal) = value match {
      case TextMatch.Equals(next)        => ("equals", next)
      case TextMatch.Contains(next)      => ("contains", next)
      case TextMatch.StartsWith(next)    => ("startsWith", next)
      case TextMatch.NotStartsWith(next) => ("notStartsWith", next)
    }
    JsBridge.obj("op" -> (op), "value" -> (literal))
  }

  private def actionJson(action: RuleAction): js.Any =
    JsBridge.obj(
      "op"    -> (actionOp(action.op)),
      "path"  -> action.path.orNull,
      "value" -> action.value.orNull,
      "key"   -> action.key.orNull
    )

  private def actionOp(op: RuleActionOp): String =
    op match {
      case RuleActionOp.AppendContent   => "appendContent"
      case RuleActionOp.AppendDelta     => "appendDelta"
      case RuleActionOp.SetContent      => "setContent"
      case RuleActionOp.ResetContent    => "resetContent"
      case RuleActionOp.TruncateContent => "truncateContent"
      case RuleActionOp.SetFinishReason => "setFinishReason"
      case RuleActionOp.SetError        => "setError"
      case RuleActionOp.SetMemory       => "setMemory"
    }

  private def transformJson(value: ContentTransformRule): js.Any = {
    val settle = value.settlement match {
      case TransformCandidatePrefix(prefix) => JsBridge.obj("candidatePrefix" -> (prefix))
      case TransformHoldback(chars)         => JsBridge.obj("holdback" -> (chars))
    }
    JsBridge.obj(
      "id"          -> (value.id),
      "regex"       -> (value.regex),
      "replacement" -> (value.replacement),
      "settle"      -> settle
    )
  }

  private def requestJson(value: RequestRuleDefinition): js.Any =
    JsBridge.obj(
      "userText" -> (js.Array(value.userTextPaths*)),
      "derive"   -> (js.Array(value.deriveOps.map(op =>
        JsBridge.obj(
          "op"      -> (op.op),
          "path"    -> (op.path),
          "slot"    -> (op.slot),
          "header"  -> (op.header),
          "param"   -> (op.param),
          "slice"   -> (op.slice),
          "pairSep" -> (op.pairSep),
          "kvSep"   -> (op.kvSep)
        )
      )*))
    )
}
