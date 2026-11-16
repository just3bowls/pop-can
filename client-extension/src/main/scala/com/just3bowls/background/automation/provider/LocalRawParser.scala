package com.just3bowls.background.automation.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.background.automation.cdp.*

import scala.collection.mutable
import scala.scalajs.js
import com.just3bowls.model.provider.{
  JsonPath,
  JsRegex,
  MatchSpec,
  ParserHandler,
  ParserRuleDefinition,
  RuleAction,
  RuleActionOp
}
import com.just3bowls.model.diagnostics.PopCanLog
final case class LocalParsedDelta(
    contentDelta: String = "",
    thinkingDelta: String = "",
    done: Boolean = false,
    finishReason: String = "",
    error: String = "",
    /**
     * 让消费者**清空已累积的正文**再接收 `contentDelta`。
     *
     * 只在一种情况下出现：正文过滤回退了已经下发的内容（正则形态超出"结算点在原始文本上"的假设，例如贪婪量词）。 正常路径永远是 append-only，这个标志为 false。
     */
    resetContent: Boolean = false
)

/**
 * Minimal Scala.js parser that executes a PopCan ParserRule against provider raw text.
 *
 * This mirrors the server-side parser contract: raw chunks are accumulated in a buffer and rawRegex is matched across
 * chunk boundaries, including multi-line SSE event blocks.
 */
final class LocalRawParser(ruleJson: String, onHandlerHit: String => Unit = _ => ()) {
  private val rule            =
    ParserRuleDefinition.parse(ruleJson).fold(error => throw new IllegalArgumentException(error), identity)
  private val log             = PopCanLog.logger("parser")
  private val pipeline        = new ContentPipeline(rule)
  private val rawHandlers     = rule.handlers.filter(_.matchSpec.rawRegex.exists(_.nonEmpty))
  private val rawPatterns     = mutable.Map.empty[String, js.RegExp]
  rawHandlers.foreach { handler =>
    handler.matchSpec.rawRegex.filter(_.nonEmpty).flatMap(regexText => JsRegex.compile(regexText, global = true)).foreach {
      regex =>
        rawPatterns.update(handler.id, regex)
    }
  }
  private val rawHandlerOrder = rawHandlers.map(_.id).zipWithIndex.toMap

  /** `mode.fromStream`：模式声明帧的取法（对象或数组，数组按声明顺序取第一个非空捕获）。 */
  private val modeSpecs: List[(js.RegExp, Int)] =
    rule.mode.toList.flatMap(_.fromStream).flatMap(extractor =>
      JsRegex.compile(extractor.rawRegex, global = false).map(regex => (regex, extractor.group))
    )
  private val memory                            = mutable.Map.empty[String, String]
  private val content                           = new StringBuilder
  private val thinking                          = new StringBuilder
  private val rawBuffer                         = new StringBuilder
  private val rawRuleConsumed                   = mutable.Map.empty[String, Int]

  /**
   * 每段 raw 文本的来源 URL：存 (在 buffer 里的结束偏移, url)。
   *
   * 规则里的 `match.urlRegex` 用它做作用域：一家 provider 可以有多条端点/模式， handler 声明"我只吃这条传输"，而不是靠 payload 形状猜。未知来源记空串。
   */
  private val sourceSpans         = mutable.ArrayBuffer.empty[(Int, String)]
  private var rawConsumed         = 0
  private var currentJson: js.Any = JsBridge.obj()

  /** 已经下发给消费者的过滤后正文（append-only 的真相：下一条只能是它的延长）。 */
  private var emittedContent   = ""
  private var lastThinkingLen  = 0
  private var lastFinishReason = ""
  private var finishReason     = "stop"
  private var finishSignaled   = false
  private var done             = false
  private var modeTag          = ""
  private var errorMessage     = ""
  private var lastErrorSent    = ""
  private val candidateStarts  = mutable.Set.empty[Int]

  /** 规则是否已经判定"这轮到此为止"（handler 命中并执行了 `setFinishReason`）。 */
  def hasFinishSignal: Boolean = finishSignaled

  /** Fully transformed body: what downstream consumers receive after the turn completes. */
  def filteredContent: String = pipeline.render(content.result(), complete = true)

  /** 本轮锁存到的模式标签（空串 = 未知；见 `mode.fromStream`）。 */
  def currentMode: String = modeTag

  /** 规则报出来的 provider 侧错误（如 kimi 排队的 `resource_exhausted`）；空 = 没有。 */
  def currentError: String                                                = errorMessage
  def rawChars: Int                                                       = rawBuffer.length
  rule.memory.foreach { case (key, value) => memory.update(key, value) }
  def feed(chunk: String, sourceUrl: String = ""): List[LocalParsedDelta] =
    if (done || chunk.isEmpty) Nil
    else {
      rawBuffer.append(chunk)
      recordSource(sourceUrl)
      processRawHandlers()
    }

  /** 记录"到当前 buffer 末尾为止来自 sourceUrl"；连续同源就合并，避免 span 无限增长。 */
  private def recordSource(sourceUrl: String): Unit =
    sourceSpans.lastOption match {
      case Some((_, lastUrl)) if lastUrl == sourceUrl =>
        sourceSpans.update(sourceSpans.length - 1, (rawBuffer.length, sourceUrl))
      case _                                          =>
        sourceSpans += ((rawBuffer.length, sourceUrl))
    }

  /** 匹配起点落在哪一段来源里；没有记录（例如规则只喂过一段且 URL 未知）返回空串。 */
  private def sourceAt(offset: Int): String =
    sourceSpans.find(_._1 > offset).map(_._2).getOrElse(sourceSpans.lastOption.map(_._2).getOrElse(""))
  def finish(): List[LocalParsedDelta]      =
    if (!done) {
      val out = mutable.ListBuffer.empty[LocalParsedDelta]
      out ++= processRawHandlers()
      try runFinalActions()
      catch {
        case e: Throwable =>
          log.error("final action failed", e)
      }
      done = true
      out += emit(forceDone = true).headOption.getOrElse(LocalParsedDelta(done = true, finishReason = finishReason))
      out.toList
    } else Nil
  def resultContent: String                 = content.result()

  /** 空轮判定：finish 已到、正文仍为空 → 可读原因。 */
  def emptyTurnReason: Option[String] =
    if (finishSignaled) noContentAtEnd else None

  /** 传输已经结束但正文为空；不依赖 provider 自己是否发过 finish 帧。 */
  def noContentAtEnd: Option[String]                                  =
    if (filteredContent.trim.isEmpty) Some(noContentMessage) else None
  private def noContentMessage: String                                =
    if (errorMessage.nonEmpty) errorMessage
    else
      "Provider response ended without answer text (mode=" +
        (if (modeTag.isEmpty) "unknown" else modeTag) +
        s", rawChars=${rawBuffer.length}): the rule does not match this response shape"
  private def jsonTypeOf(payload: String): String                     =
    try {
      val parsed = JsBridge.asObject(js.JSON.parse(payload))
      val kind   = JsBridge.undefinedString(parsed.`type`).toOption.getOrElse("")
      if (kind.nonEmpty) kind else "json-other"
    } catch { case _: Throwable => "json-broken" }
  private def frameNameOf(payload: String, eventName: String): String =
    if (payload.nonEmpty && payload.charAt(0) == '{') jsonTypeOf(payload)
    else if (eventName.nonEmpty) eventName
    else "text"

  /**
   * 帧普查（只在失败路径上调用）：这一轮到底长什么样、哪一帧连 handler 的正则都没碰到。
   *
   * 名字优先取帧前的 SSE `event:` 名，其次取 JSON 的 `type`，再不然记 `text`；未命中样本最多 3 段、每段 160 字符。
   */
  def census: String = {
    val text        = rawBuffer.result()
    val names       = mutable.LinkedHashMap.empty[String, Int]
    val samples     = mutable.ListBuffer.empty[String]
    var frames      = 0
    var noCandidate = 0
    var eventName   = ""
    var offset      = 0
    text.split("\n", -1).foreach { rawLine =>
      val start = offset
      offset += rawLine.length + 1
      val line  = rawLine.trim
      if (line.startsWith("event:")) eventName = line.substring(6).trim
      else if (line.startsWith("data:")) {
        frames += 1
        val payload = line.substring(5).trim
        val name    = frameNameOf(payload, eventName)
        names.update(name, names.getOrElse(name, 0) + 1)
        val end     = start + rawLine.length
        val touched = candidateStarts.exists(candidateStart => candidateStart >= start && candidateStart <= end)
        if (!touched) {
          noCandidate += 1
          if (samples.length < 3) samples += payload.take(160)
        }
      }
    }
    val nameText    = names.map { case (name, count) => name + ":" + count }.mkString(",")
    val sampleText  = if (samples.isEmpty) "" else " samples=" + samples.mkString(" | ")
    "frames=" + frames + " noCandidate=" + noCandidate + " names=" + nameText + sampleText
  }
  final private case class RawCandidate(
      handler: ParserHandler,
      start: Int,
      end: Int,
      event: js.Any,
      sourceUrl: String
  )

  /** 从累计 buffer 里锁存本轮模式标签（首个非空捕获生效，之后不再变）。 */
  private def latchMode(): Unit                                        =
    if (modeTag.isEmpty && modeSpecs.nonEmpty) {
      val text = rawBuffer.result()
      modeSpecs.iterator
        .map { case (regex, group) =>
          regex.lastIndex = 0
          val matched = regex.exec(text)
          if (matched == null || js.isUndefined(matched)) ""
          else {
            val groups = JsBridge.asStringArray(matched)
            if (group >= 0 && group < groups.length) Option(groups(group)).getOrElse("").trim else ""
          }
        }
        .find(_.nonEmpty)
        .foreach(tag => modeTag = tag)
    }
  private def processRawHandlers(): List[LocalParsedDelta]             = {
    val out           = mutable.ListBuffer.empty[LocalParsedDelta]
    if (rawPatterns.isEmpty || rawBuffer.isEmpty) return out.toList
    // 模式声明在内容之前，先锁存再匹配：handler 的 `match.mode` 依赖它。
    latchMode()
    val text          = rawBuffer.result()
    val rawCandidates = mutable.ArrayBuffer.empty[RawCandidate]
    rawHandlers.foreach { handler =>
      val id = handler.id
      rawPatterns.get(id).foreach { pattern =>
        val cursor  = rawRuleConsumed.getOrElse(id, 0)
        pattern.lastIndex = cursor
        var matched = pattern.exec(text)
        while (matched != null && !js.isUndefined(matched)) {
          val start   = JsBridge.asInt(matched.index)
          val end     = JsBridge.asInt(pattern.lastIndex)
          val group   = handler.matchSpec.jsonGroup
          val arr     = JsBridge.asStringArray(matched)
          val rawJson =
            if (group >= 0 && group < arr.length) arr(group)
            else ""
          val event   =
            try JsBridge.asObject(js.JSON.parse(rawJson))
            catch { case _: Throwable => null }
          if (event != null && !js.isUndefined(event)) {
            currentJson = event
            if (candidateStarts.size < 5000) candidateStarts += start
            val sourceUrl  = sourceAt(start)
            val eventsPath = handler.matchSpec.eventsPath.getOrElse("")
            if (eventsPath.isEmpty) rawCandidates += RawCandidate(handler, start, end, event, sourceUrl)
            else {
              val items = JsonPath.expand(event, eventsPath)
              if (items.isEmpty) log.debug(s"eventsPath matched nothing handler=${handler.id} path=$eventsPath")
              else
                items.foreach(item =>
                  rawCandidates += RawCandidate(handler, start, end, item, sourceUrl)
                )
            }
          }
          if (end == start) pattern.lastIndex = start + 1
          matched = pattern.exec(text)
        }
      }
    }
    var remaining     = rawCandidates.toList
    while (remaining.nonEmpty) {
      val ordered           = remaining.sortBy(candidate => (candidate.start, rawHandlerOrder(candidate.handler.id)))
      val head              = ordered.head
      val group             = ordered.filter(_.start == head.start)
      val snapshot          = memory.toMap
      val accepted          = group
        .flatMap { candidate =>
          try {
            if (
              rawMatches(
                candidate.handler.matchSpec,
                candidate.event,
                snapshot,
                candidate.sourceUrl
              )
            )
              Some(candidate)
            else None
          } catch {
            case e: Throwable =>
              log.error(
                s"match failed handler=${candidate.handler.id} start=${candidate.start} end=${candidate.end}",
                e
              )
              None
          }
        }
        .sortBy(candidate => rawHandlerOrder(candidate.handler.id))
      val changedMemoryKeys = mutable.Set.empty[String]
      accepted.foreach { candidate =>
        val condition  = candidate.handler.matchSpec
        val memoryKey  = condition.memoryKey
        val memoryOnly =
          condition.path.forall(_.trim.isEmpty) &&
            condition.regex.forall(_.trim.isEmpty) &&
            condition.allOf.isEmpty &&
            condition.anyOf.isEmpty &&
            memoryKey.nonEmpty
        if (!memoryOnly || !changedMemoryKeys.contains(memoryKey.get)) {
          val actionOut = mutable.ListBuffer.empty[LocalParsedDelta]
          candidate.handler.actions
            .foreach { action =>
              try {
                runAction(action, candidate.event, changedMemoryKeys)
              } catch {
                case e: Throwable =>
                  log.error(
                    s"action failed handler=${candidate.handler.id} start=${candidate.start} end=${candidate.end}",
                    e
                  )
              }
            }
          val deltas    = emit()
          deltas.foreach { delta =>
            actionOut += delta
            onHandlerHit(candidate.handler.id)
            log.debug(
              s"handler=${candidate.handler.id} start=${candidate.start} end=${candidate.end} " +
                s"deltaChars=${delta.contentDelta.length} thinkingChars=${delta.thinkingDelta.length}"
            )
          }
          out ++= actionOut
        }
      }
      group.foreach { candidate =>
        rawRuleConsumed.update(
          candidate.handler.id,
          math.max(rawRuleConsumed.getOrElse(candidate.handler.id, 0), candidate.end)
        )
      }
      remaining = remaining.filterNot(_.start == head.start)
    }
    rawConsumed = rawPatterns.keys.toList.map(id => rawRuleConsumed.getOrElse(id, 0)).minOption.getOrElse(0)
    compactRawBuffer()
    validateCursors()
    out.toList
  }
  private def rawMatches(
      condition: MatchSpec,
      event: js.Any,
      memoryState: Map[String, String],
      sourceUrl: String
  ): Boolean = {
    // `urlRegex`：这段 payload 必须来自匹配的传输（HTTP 请求 URL / WS socket URL）。
    // 声明了但来源未知（空串）⇒ 不匹配 —— 宁可不认，也不要跨模式猜。
    val urlOk        = condition.urlRegex.map(_.trim).filter(_.nonEmpty) match {
      case None          => true
      case Some(pattern) => sourceUrl.nonEmpty && regexMatches(sourceUrl, pattern)
    }
    val memoryOk     = condition.memoryKey match {
      case None      => true
      case Some(key) =>
        val value = memoryState.getOrElse(key, "")
        condition.memoryEquals.forall(_ == value) &&
        condition.memoryNotEquals.forall(_ != value)
    }
    // `memoryPath`：事件在这个路径上的值必须等于 `memory[memoryKey]`（两个值都取自规则自己写的路径，引擎不预设任何语义）。
    // 两种放行：memory 还没定音（定音动作可能与这次匹配落在同一个分组里，见 snapshot 的取法）；事件在该路径上没有非空值（心跳 / 控制帧）。
    // 典型配方（多候选取第一条）：一个 handler 用 `memoryKey` + `memoryEquals:""` + `setMemory` 定音，
    // 其余 handler 挂 `memoryKey` + `memoryPath` 只认那一条。
    val memoryPathOk = condition.memoryKey.zip(condition.memoryPath) match {
      case Some((key, path)) if path.trim.nonEmpty =>
        val pinned = memoryState.getOrElse(key, "")
        evalPath(event, path).map(stringify).map(_.trim).filter(_.nonEmpty) match {
          case None        => true
          case Some(value) => pinned.isEmpty || pinned == value
        }
      case _                                       => true
    }
    val directOk     = condition.path match {
      case Some(path) =>
        evalPath(event, path).exists { value =>
          condition.regex.forall(regexMatches(
            stringify(value),
            _
          ))
        }
      case None       =>
        condition.regex.forall(regexMatches(
          stringify(event),
          _
        ))
    }
    // `mode`：模式的**额外约束**。标签已知时按它筛；标签未知（站点改了声明）时不生效（fail-open），
    // 免得一个改名把本来能用的形状匹配也一起打挂。
    val modeOk       =
      condition.mode.map(_.trim).filter(_.nonEmpty) match {
        case None          => true
        case Some(pattern) => modeTag.isEmpty || regexMatches(modeTag, pattern)
      }
    val allOk        = condition.allOf.forall(sub => rawMatches(sub, event, memoryState, sourceUrl))
    val anyOf        = condition.anyOf
    val anyOk        = anyOf.isEmpty || anyOf.exists(sub => rawMatches(sub, event, memoryState, sourceUrl))
    urlOk && memoryOk && memoryPathOk && modeOk && directOk && allOk && anyOk
  }
  private def runAction(
      action: RuleAction,
      event: js.Any,
      changedMemoryKeys: mutable.Set[String]
  ): Unit = {
    val value = resolveValue(action, event)
    action.op match {
      case RuleActionOp.AppendContent   =>
        if (value.nonEmpty) content.append(value)
      case RuleActionOp.AppendDelta     =>
        val previous = content.result()
        val delta    =
          if (value.length > previous.length && value.startsWith(previous)) value.substring(previous.length)
          else if (value.nonEmpty && !previous.contains(value)) value
          else ""
        if (delta.nonEmpty) content.append(delta)
      case RuleActionOp.SetContent      =>
        content.clear()
        if (value.nonEmpty) content.append(value)
      case RuleActionOp.ResetContent    =>
        content.clear()
      case RuleActionOp.TruncateContent =>
        value.toIntOption.foreach { target =>
          if (content.length > target) content.setLength(target)
        }
      case RuleActionOp.SetFinishReason =>
        if (value.nonEmpty) {
          finishReason = value
          finishSignaled = true
        }
      case RuleActionOp.SetError        =>
        if (value.nonEmpty) errorMessage = value
      case RuleActionOp.SetMemory       =>
        val key = action.key.getOrElse("")
        if (key.nonEmpty && value.nonEmpty) {
          memory.update(key, value)
          changedMemoryKeys += key
        }
    }
  }
  private def resolveValue(action: RuleAction, event: js.Any): String  =
    action.path
      .flatMap(path => evalPath(event, path))
      .map(stringify)
      .orElse(action.value)
      .getOrElse("")
  private def evalPath(json: js.Any, path: String): Option[js.Any]     = {
    // `[*]` 只服务 handler 的 `eventsPath`（由 `JsonPath.expand` 扇出）；match / action 的普通路径遇到它按"不匹配"处理。
    val trimmed = path.trim.stripPrefix("$")
    if (path.contains("[*]")) None
    else if (trimmed.isEmpty) Some(json)
    else
      try JsonPath.get(json, path)
      catch {
        case error: Throwable =>
          throw new IllegalStateException(s"JsonPath.get failed for '$path'", error)
      }
  }
  private def stringify(value: js.Any): String                         =
    if (value == null || js.isUndefined(value)) ""
    else if (js.typeOf(value) == "string") JsBridge.asString(value)
    else js.JSON.stringify(value)
  private def regexMatches(value: String, pattern: String): Boolean    =
    JsRegex.compile(pattern).exists(_.test(value))
  private def runFinalActions(): Unit                                  =
    rule.finalActions.foreach(action => runAction(action, currentJson, mutable.Set.empty[String]))
  private def compactRawBuffer(): Unit                                 =
    if (rawConsumed > 65536) {
      val shift = rawConsumed
      rawBuffer.delete(0, shift)
      rawConsumed = 0
      rawRuleConsumed.keys.foreach { key =>
        val cursor = rawRuleConsumed.getOrElse(key, 0)
        rawRuleConsumed.update(key, math.max(0, cursor - shift))
      }
      // 来源区间同样要跟着裁剪：丢掉已消费段落，剩下的整体前移。
      val kept  = sourceSpans.filter(_._1 > shift).map { case (end, url) => (end - shift, url) }
      sourceSpans.clear()
      sourceSpans ++= kept
      if (sourceSpans.isEmpty) sourceSpans += ((rawBuffer.length, ""))
    }
  private def validateCursors(): Unit                                  = {
    val invalid = rawRuleConsumed.collect {
      case (id, cursor) if cursor < 0 || cursor > rawBuffer.length => id -> cursor
    }
    if (invalid.nonEmpty)
      throw new IllegalStateException(
        s"raw parser cursor out of bounds buffer=${rawBuffer.length} invalid=${invalid.mkString(",")}"
      )
  }
  private def emit(forceDone: Boolean = false): List[LocalParsedDelta] = {
    val rawText         = content.result()
    val visible         = pipeline.render(rawText, complete = forceDone)
    // 正常路径 visible 永远是 emittedContent 的延长；一旦过滤回退了已下发内容（正则超出结算假设），
    // 就带 `resetContent` 让消费者清空重收 —— 通用兜底，不是给某家 provider 的特例。
    val reset           = !visible.startsWith(emittedContent)
    val contentDelta    = if (reset) visible else visible.substring(emittedContent.length)
    emittedContent = visible
    val currentThinking = thinking.result()
    val thinkingDelta   =
      if (
        currentThinking.length >= lastThinkingLen && currentThinking.startsWith(currentThinking.substring(
          0,
          lastThinkingLen
        ))
      )
        currentThinking.substring(lastThinkingLen)
      else if (lastThinkingLen == 0) currentThinking
      else ""
    lastThinkingLen = currentThinking.length
    val finishChanged   = finishReason != lastFinishReason
    lastFinishReason = finishReason
    val errorDelta      =
      if (errorMessage.nonEmpty && errorMessage != lastErrorSent) {
        lastErrorSent = errorMessage
        errorMessage
      } else ""
    if (forceDone || reset || contentDelta.nonEmpty || thinkingDelta.nonEmpty || finishChanged || errorDelta.nonEmpty) {
      List(LocalParsedDelta(
        contentDelta = contentDelta,
        thinkingDelta = thinkingDelta,
        done = forceDone,
        finishReason = if (finishChanged || forceDone) finishReason else "",
        error = errorDelta,
        resetContent = reset
      ))
    } else Nil
  }
}
