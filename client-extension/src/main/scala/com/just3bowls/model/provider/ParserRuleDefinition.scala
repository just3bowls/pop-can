package com.just3bowls.model.provider

import com.just3bowls.facade.JsonValue

/** Runtime representation of the provider-owned parserRule DSL. */
final case class ParserRuleDefinition(
    schemaVersion: Int,
    memory: Map[String, String] = Map.empty,
    mode: Option[ParserMode] = None,
    handlers: List[ParserHandler] = Nil,
    finalActions: List[RuleAction] = Nil,
    contentTransforms: List[ContentTransformRule] = Nil,
    request: RequestRuleDefinition = RequestRuleDefinition()
)

final case class ParserMode(fromStream: List[ModeExtractor] = Nil)
final case class ModeExtractor(rawRegex: String, group: Int = 1)

final case class ParserHandler(
    id: String,
    matchSpec: MatchSpec,
    actions: List[RuleAction]
)

final case class MatchSpec(
    rawRegex: Option[String] = None,
    jsonGroup: Int = 0,
    eventsPath: Option[String] = None,
    path: Option[String] = None,
    regex: Option[String] = None,
    urlRegex: Option[String] = None,
    mode: Option[String] = None,
    memoryKey: Option[String] = None,
    memoryEquals: Option[String] = None,
    memoryNotEquals: Option[String] = None,
    memoryPath: Option[String] = None,
    allOf: List[MatchSpec] = Nil,
    anyOf: List[MatchSpec] = Nil
)

enum RuleActionOp:
  case AppendContent, AppendDelta, SetContent, ResetContent, TruncateContent
  case SetFinishReason, SetError, SetMemory

object RuleActionOp {
  def parse(value: String): Option[RuleActionOp] =
    value match {
      case "appendContent"   => Some(AppendContent)
      case "appendDelta"     => Some(AppendDelta)
      case "setContent"      => Some(SetContent)
      case "resetContent"    => Some(ResetContent)
      case "truncateContent" => Some(TruncateContent)
      case "setFinishReason" => Some(SetFinishReason)
      case "setError"        => Some(SetError)
      case "setMemory"       => Some(SetMemory)
      case _                 => None
    }
}

final case class RuleAction(
    op: RuleActionOp,
    path: Option[String] = None,
    value: Option[String] = None,
    key: Option[String] = None
)

sealed trait TransformSettlement
final case class TransformCandidatePrefix(prefix: String) extends TransformSettlement
final case class TransformHoldback(chars: Int)            extends TransformSettlement

final case class ContentTransformRule(
    id: String,
    regex: String,
    replacement: String,
    settlement: TransformSettlement
)

final case class RequestDeriveOp(
    op: String,
    path: String = "",
    slot: Int = 1,
    header: String = "",
    param: String = "",
    slice: Int = 0,
    pairSep: String = "&",
    kvSep: String = "="
)

final case class RequestRuleDefinition(
    userTextPaths: List[String] = Nil,
    deriveOps: List[RequestDeriveOp] = Nil
)

object ParserRuleDefinition {
  def parse(ruleJson: String): Either[String, ParserRuleDefinition] =
    JsonValue.parse(ruleJson).flatMap { value =>
      if (!JsonValue.isObject(value)) Left("parserRule must be a JSON object")
      else {
        val schemaVersion = JsonValue.intField(value, "schemaVersion").getOrElse(0)
        val memory        = JsonValue
          .objectField(value, "memory")
          .map(JsonValue.dictionary)
          .getOrElse(Map.empty)
          .map { case (key, raw) => key -> JsonValue.display(raw) }
        val mode          = parseMode(JsonValue.objectField(value, "mode"))
        val handlers      = JsonValue.arrayField(value, "handlers").flatMap(parseHandler)
        val finalActions  = JsonValue.arrayField(value, "finalActions").flatMap(parseAction)
        val transforms    = JsonValue.arrayField(value, "contentTransforms").map(parseTransform)
        val request       = parseRequest(JsonValue.objectField(value, "request"))
        Right(ParserRuleDefinition(schemaVersion, memory, mode, handlers, finalActions, transforms, request))
      }
    }

  private def parseMode(value: Option[com.just3bowls.facade.JsonValue]): Option[ParserMode] =
    value.flatMap(mode => JsonValue.field(mode, "fromStream")).map { from =>
      val items      =
        if (JsonValue.isArray(from)) from.asInstanceOf[scala.scalajs.js.Array[com.just3bowls.facade.JsonValue]].toList
        else List(from)
      val extractors = items.flatMap { item =>
        JsonValue.stringField(item, "rawRegex").map(_.trim).filter(_.nonEmpty).map { rawRegex =>
          ModeExtractor(rawRegex, JsonValue.intField(item, "group").getOrElse(1))
        }
      }
      ParserMode(extractors)
    }

  private def parseHandler(value: com.just3bowls.facade.JsonValue): Option[ParserHandler] =
    if (!JsonValue.isObject(value)) None
    else
      Some(ParserHandler(
        id = JsonValue.stringField(value, "id").getOrElse(""),
        matchSpec = parseMatch(JsonValue.objectField(value, "match").getOrElse(JsonValue.emptyObject)),
        actions = JsonValue.arrayField(value, "actions").flatMap(parseAction)
      ))

  private def parseMatch(value: com.just3bowls.facade.JsonValue): MatchSpec =
    MatchSpec(
      rawRegex = JsonValue.stringField(value, "rawRegex"),
      jsonGroup = JsonValue.intField(value, "jsonGroup").getOrElse(0),
      eventsPath = JsonValue.stringField(value, "eventsPath").map(_.trim).filter(_.nonEmpty),
      path = JsonValue.stringField(value, "path").map(_.trim).filter(_.nonEmpty),
      regex = JsonValue.stringField(value, "regex").map(_.trim).filter(_.nonEmpty),
      urlRegex = JsonValue.stringField(value, "urlRegex").map(_.trim).filter(_.nonEmpty),
      mode = JsonValue.stringField(value, "mode").map(_.trim).filter(_.nonEmpty),
      memoryKey = JsonValue.stringField(value, "memoryKey").map(_.trim).filter(_.nonEmpty),
      memoryEquals = JsonValue.stringField(value, "memoryEquals"),
      memoryNotEquals = JsonValue.stringField(value, "memoryNotEquals"),
      memoryPath = JsonValue.stringField(value, "memoryPath").map(_.trim).filter(_.nonEmpty),
      allOf = JsonValue.arrayField(value, "allOf").map(parseMatch),
      anyOf = JsonValue.arrayField(value, "anyOf").map(parseMatch)
    )

  private def parseAction(value: com.just3bowls.facade.JsonValue): Option[RuleAction] =
    JsonValue
      .stringField(value, "op")
      .flatMap(RuleActionOp.parse)
      .map(op =>
        RuleAction(
          op = op,
          path = JsonValue.stringField(value, "path").map(_.trim).filter(_.nonEmpty),
          value = JsonValue.stringField(value, "value"),
          key = JsonValue.stringField(value, "key").map(_.trim).filter(_.nonEmpty)
        )
      )

  private def parseTransform(value: com.just3bowls.facade.JsonValue): ContentTransformRule = {
    val id          = JsonValue.stringField(value, "id").map(_.trim).getOrElse("")
    val regex       = JsonValue.stringField(value, "regex").getOrElse("")
    val replacement = JsonValue.stringField(value, "replacement").getOrElse("")
    val settle      = JsonValue.objectField(value, "settle").getOrElse(JsonValue.emptyObject)
    val prefix      = JsonValue.stringField(settle, "candidatePrefix").map(_.trim).filter(_.nonEmpty)
    val holdback    = JsonValue.intField(settle, "holdback")
    val settlement  =
      (prefix, holdback) match {
        case (Some(candidate), None) => TransformCandidatePrefix(candidate)
        case (None, Some(chars))     => TransformHoldback(math.max(0, chars))
        case _                       =>
          throw new IllegalArgumentException(
            s"contentTransforms[$id].settle requires exactly one of candidatePrefix or holdback"
          )
      }
    ContentTransformRule(id, regex, replacement, settlement)
  }

  private def parseRequest(value: Option[com.just3bowls.facade.JsonValue]): RequestRuleDefinition =
    value match {
      case None          => RequestRuleDefinition()
      case Some(request) =>
        val userTextPaths = JsonValue.field(request, "userText").toList.flatMap {
          case raw if JsonValue.isArray(raw) =>
            raw.asInstanceOf[scala.scalajs.js.Array[com.just3bowls.facade.JsonValue]].toList
              .flatMap(JsonValue.stringValue)
              .map(_.trim)
              .filter(_.nonEmpty)
          case raw                           => JsonValue.stringValue(raw).map(_.trim).filter(_.nonEmpty).toList
        }
        val deriveOps     = JsonValue.arrayField(request, "derive").flatMap { item =>
          JsonValue.stringField(item, "op").filter(_.nonEmpty).map { op =>
            RequestDeriveOp(
              op = op,
              path = JsonValue.stringField(item, "path").getOrElse(""),
              slot = JsonValue.intField(item, "slot").getOrElse(1),
              header = JsonValue.stringField(item, "header").getOrElse(""),
              param = JsonValue.stringField(item, "param").getOrElse(""),
              slice = JsonValue.intField(item, "slice").getOrElse(0),
              pairSep = JsonValue.stringField(item, "pairSep").filter(_.nonEmpty).getOrElse("&"),
              kvSep = JsonValue.stringField(item, "kvSep").filter(_.nonEmpty).getOrElse("=")
            )
          }
        }
        RequestRuleDefinition(userTextPaths, deriveOps)
    }
}
