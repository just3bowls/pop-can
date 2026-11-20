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

enum TextMatch:
  case Equals(value: String)
  case Contains(value: String)
  case StartsWith(value: String)
  case NotStartsWith(value: String)

  def matches(text: String): Boolean =
    this match {
      case Equals(expected)      => text == expected
      case Contains(expected)    => text.contains(expected)
      case StartsWith(prefix)    => text.startsWith(prefix)
      case NotStartsWith(prefix) => !text.startsWith(prefix)
    }

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
    textMatch: Option[TextMatch] = None,
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
  private val SseJsonLineRawRegex  = """(?m)^data:\s*(\{.*\})\s*$"""
  private val SseJsonLineTransport = "sse-json-line"
  final private case class PhaseUse(name: String, value: String, starts: Boolean)

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
        val finalActions  = JsonValue.arrayField(value, "finalActions").flatMap(parseAction)
        val transforms    = JsonValue.arrayField(value, "contentTransforms").map(parseTransform)
        val request       = parseRequest(JsonValue.objectField(value, "request"))
        for {
          phases   <- parsePhases(value, memory)
          handlers <- parseHandlers(value, phases)
        } yield {
          ParserRuleDefinition(schemaVersion, memory, mode, handlers, finalActions, transforms, request)
        }
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

  private def parsePhases(
      value: com.just3bowls.facade.JsonValue,
      initialMemory: Map[String, String]
  ): Either[String, Map[String, String]] =
    JsonValue.field(value, "phases") match {
      case None                                  => Right(Map.empty)
      case Some(raw) if !JsonValue.isObject(raw) => Left("phases must be an object")
      case Some(raw)                             =>
        JsonValue.dictionary(raw).toList
          .foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) {
            case (acc, (rawKey, rawValue)) =>
              for {
                parsed <- acc
                key = rawKey.trim
                _          <-
                  if (key.isEmpty) Left("phases keys must be non-empty")
                  else if (parsed.contains(key)) Left(s"phases: duplicate phase '$key'")
                  else if (initialMemory.contains(key))
                    Left(s"phases.$key: conflicts with root memory key")
                  else Right(())
                phaseValue <- JsonValue.stringValue(rawValue).map(_.trim).filter(_.nonEmpty) match {
                  case Some(next) => Right(next)
                  case None       => Left(s"phases.$key: must be a non-empty string")
                }
              } yield parsed.updated(key, phaseValue)
          }
    }

  private def parseHandlers(
      value: com.just3bowls.facade.JsonValue,
      phases: Map[String, String]
  ): Either[String, List[ParserHandler]] =
    JsonValue.field(value, "handlers") match {
      case None                                 => Right(Nil)
      case Some(raw) if !JsonValue.isArray(raw) => Left("handlers must be an array")
      case Some(raw)                            =>
        val items = raw.asInstanceOf[scala.scalajs.js.Array[com.just3bowls.facade.JsonValue]].toList
        items.zipWithIndex.foldLeft[Either[String, List[ParserHandler]]](Right(Nil)) {
          case (acc, (handler, index)) =>
            for {
              parsed <- acc
              next   <- parseHandler(handler, index, phases)
            } yield parsed :+ next
        }
    }

  private def parseHandler(
      value: com.just3bowls.facade.JsonValue,
      index: Int,
      phases: Map[String, String]
  ): Either[String, ParserHandler] =
    if (!JsonValue.isObject(value)) Left(s"handlers[$index] must be an object")
    else {
      val where = s"handlers[$index]"
      for {
        phaseUse  <- parsePhaseUse(value, where, phases)
        matchSpec <- parseMatch(
          JsonValue.objectField(value, "match").getOrElse(JsonValue.emptyObject),
          s"$where.match",
          allowTransport = true
        )
        _         <- validatePhaseMatch(phaseUse, matchSpec, where)
        actions   <- parseHandlerActions(value, where)
        _         <- validatePhaseActions(phaseUse, actions, where)
      } yield {
        val normalizedMatch   =
          phaseUse match {
            case Some(phase) if !phase.starts =>
              matchSpec.copy(memoryKey = Some(phase.name), memoryEquals = Some(phase.value))
            case _                            => matchSpec
          }
        val normalizedActions =
          phaseUse match {
            case Some(phase) if phase.starts =>
              RuleAction(RuleActionOp.SetMemory, value = Some(phase.value), key = Some(phase.name)) :: actions
            case _                           => actions
          }
        ParserHandler(
          id = JsonValue.stringField(value, "id").getOrElse(""),
          matchSpec = normalizedMatch,
          actions = normalizedActions
        )
      }
    }

  private def parsePhaseUse(
      value: com.just3bowls.facade.JsonValue,
      where: String,
      phases: Map[String, String]
  ): Either[String, Option[PhaseUse]] = {
    val phaseField  = JsonValue.field(value, "phase")
    val startsField = JsonValue.field(value, "startsPhase")
    startsField match {
      case Some(_) if JsonValue.boolField(value, "startsPhase").isEmpty =>
        Left(s"$where.startsPhase: must be a boolean")
      case _                                                            =>
        phaseField match {
          case None if startsField.nonEmpty => Left(s"$where.startsPhase: requires phase")
          case None                         => Right(None)
          case Some(raw)                    =>
            JsonValue.stringValue(raw).map(_.trim).filter(_.nonEmpty) match {
              case None       => Left(s"$where.phase: must be a non-empty string")
              case Some(name) =>
                phases.get(name) match {
                  case None       => Left(s"$where.phase: unknown phase '$name'")
                  case Some(next) =>
                    Right(Some(PhaseUse(name, next, JsonValue.boolField(value, "startsPhase").getOrElse(false))))
                }
            }
        }
    }
  }

  private def validatePhaseMatch(
      phase: Option[PhaseUse],
      matchSpec: MatchSpec,
      where: String
  ): Either[String, Unit] =
    if (phase.nonEmpty && hasMemoryGate(matchSpec)) Left(s"$where: phase cannot be combined with explicit memory gates")
    else Right(())

  private def hasMemoryGate(matchSpec: MatchSpec): Boolean =
    matchSpec.memoryKey.nonEmpty ||
      matchSpec.memoryEquals.nonEmpty ||
      matchSpec.memoryNotEquals.nonEmpty ||
      matchSpec.memoryPath.nonEmpty ||
      matchSpec.allOf.exists(hasMemoryGate) ||
      matchSpec.anyOf.exists(hasMemoryGate)

  private def validatePhaseActions(
      phase: Option[PhaseUse],
      actions: List[RuleAction],
      where: String
  ): Either[String, Unit] =
    phase.filter(_.starts) match {
      case Some(start)
          if actions.exists(action =>
            action.op == RuleActionOp.SetMemory && action.key.contains(start.name)
          ) =>
        Left(s"$where: startsPhase cannot be combined with setMemory for '${start.name}'")
      case _ => Right(())
    }

  private def parseHandlerActions(
      value: com.just3bowls.facade.JsonValue,
      where: String
  ): Either[String, List[RuleAction]] = {
    val actions = JsonValue.field(value, "actions")
    val content = JsonValue.field(value, "content")
    (actions, content) match {
      case (Some(_), Some(_)) => Left(s"$where: content and actions are mutually exclusive")
      case (None, Some(raw))  =>
        JsonValue.stringValue(raw).map(_.trim).filter(_.nonEmpty) match {
          case Some(path) if path.startsWith("$") =>
            Right(List(RuleAction(RuleActionOp.AppendContent, path = Some(path))))
          case Some(_)                            => Left(s"$where.content: must be a JSONPath starting with '$$'")
          case None                               => Left(s"$where.content: must be a non-empty string")
        }
      case (Some(raw), None)  =>
        if (!JsonValue.isArray(raw)) Left(s"$where.actions: must be an array")
        else {
          val items = raw.asInstanceOf[scala.scalajs.js.Array[com.just3bowls.facade.JsonValue]].toList
          items.zipWithIndex.foldLeft[Either[String, List[RuleAction]]](Right(Nil)) {
            case (acc, (action, index)) =>
              for {
                parsed <- acc
                next   <- parseAction(action).toRight(s"$where.actions[$index]: invalid action")
              } yield parsed :+ next
          }
        }
      case (None, None)       => Right(Nil)
    }
  }

  private def parseMatch(
      value: com.just3bowls.facade.JsonValue,
      where: String,
      allowTransport: Boolean
  ): Either[String, MatchSpec] =
    if (!JsonValue.isObject(value)) Left(s"$where: must be an object")
    else {
      val textKeys = List("equals", "contains", "startsWith", "notStartsWith")
        .filter(key => JsonValue.field(value, key).nonEmpty)
      val hasRegex = JsonValue.stringField(value, "regex").exists(_.trim.nonEmpty)
      if (textKeys.size > 1) Left(s"$where: only one text match shorthand is allowed")
      else if (textKeys.nonEmpty && hasRegex)
        Left(s"$where: regex and ${textKeys.head} are mutually exclusive")
      else
        for {
          transport <- parseTransport(value, where, allowTransport)
          _         <- validateTransportFields(value, transport, where)
          textMatch <- parseTextMatch(value, textKeys.headOption, where)
          allOf     <- parseConditions(value, "allOf", where)
          anyOf     <- parseConditions(value, "anyOf", where)
        } yield MatchSpec(
          rawRegex = transport.map(_ => SseJsonLineRawRegex).orElse(JsonValue.stringField(value, "rawRegex")),
          jsonGroup = if (transport.nonEmpty) 1 else JsonValue.intField(value, "jsonGroup").getOrElse(0),
          eventsPath = JsonValue.stringField(value, "eventsPath").map(_.trim).filter(_.nonEmpty),
          path = JsonValue.stringField(value, "path").map(_.trim).filter(_.nonEmpty),
          regex = JsonValue.stringField(value, "regex").map(_.trim).filter(_.nonEmpty),
          textMatch = textMatch,
          urlRegex = JsonValue.stringField(value, "urlRegex").map(_.trim).filter(_.nonEmpty),
          mode = JsonValue.stringField(value, "mode").map(_.trim).filter(_.nonEmpty),
          memoryKey = JsonValue.stringField(value, "memoryKey").map(_.trim).filter(_.nonEmpty),
          memoryEquals = JsonValue.stringField(value, "memoryEquals"),
          memoryNotEquals = JsonValue.stringField(value, "memoryNotEquals"),
          memoryPath = JsonValue.stringField(value, "memoryPath").map(_.trim).filter(_.nonEmpty),
          allOf = allOf,
          anyOf = anyOf
        )
    }

  private def parseTransport(
      value: com.just3bowls.facade.JsonValue,
      where: String,
      allowTransport: Boolean
  ): Either[String, Option[String]] =
    JsonValue.field(value, "transport") match {
      case None                       => Right(None)
      case Some(_) if !allowTransport => Left(s"$where.transport: only allowed on handler.match")
      case Some(raw)                  =>
        JsonValue.stringValue(raw).map(_.trim).filter(_.nonEmpty) match {
          case Some(SseJsonLineTransport) => Right(Some(SseJsonLineTransport))
          case Some(other)                => Left(s"$where.transport: unsupported transport '$other'")
          case None                       => Left(s"$where.transport: must be a non-empty string")
        }
    }

  private def validateTransportFields(
      value: com.just3bowls.facade.JsonValue,
      transport: Option[String],
      where: String
  ): Either[String, Unit] =
    if (
      transport.nonEmpty &&
      (JsonValue.field(value, "rawRegex").nonEmpty || JsonValue.field(value, "jsonGroup").nonEmpty)
    ) Left(s"$where: transport cannot be combined with rawRegex or jsonGroup")
    else Right(())

  private def parseTextMatch(
      value: com.just3bowls.facade.JsonValue,
      key: Option[String],
      where: String
  ): Either[String, Option[TextMatch]] =
    key match {
      case None       => Right(None)
      case Some(name) =>
        JsonValue.stringField(value, name).filter(_.trim.nonEmpty) match {
          case None    => Left(s"$where.$name: must be a non-empty string")
          case Some(v) =>
            val matchValue = name match {
              case "equals"        => TextMatch.Equals(v)
              case "contains"      => TextMatch.Contains(v)
              case "startsWith"    => TextMatch.StartsWith(v)
              case "notStartsWith" => TextMatch.NotStartsWith(v)
            }
            Right(Some(matchValue))
        }
    }

  private def parseConditions(
      value: com.just3bowls.facade.JsonValue,
      key: String,
      where: String
  ): Either[String, List[MatchSpec]] =
    JsonValue.field(value, key) match {
      case None                                 => Right(Nil)
      case Some(raw) if !JsonValue.isArray(raw) => Left(s"$where.$key: must be an array")
      case Some(raw)                            =>
        val items = raw.asInstanceOf[scala.scalajs.js.Array[com.just3bowls.facade.JsonValue]].toList
        items.zipWithIndex.foldLeft[Either[String, List[MatchSpec]]](Right(Nil)) {
          case (acc, (condition, index)) =>
            for {
              parsed <- acc
              next   <- parseMatch(condition, s"$where.$key[$index]", allowTransport = false)
            } yield parsed :+ next
        }
    }

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
