package com.just3bowls.model.diagnostics

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.{ChromeAPI, TraceDto, TraceHandlerStatDto, TraceMarkersDto}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 结构化的 trace：每次调用在 done 或 error 时写一条（只留最近 N 条、每段截断）， 供事后排查解析问题——控制台没有历史回放，但这份记录可以直接从 storage 读出来。
 *
 * 原始侧（`noteRaw` / `noteHandler`）在 SW 里累计，marker 侧（`noteMarkers`）在扩展页里写；两边按 `gen` 归并到同一条记录， 所以谁先落库都不会丢字段。
 */
object TraceStore {
  val Key = "thTraces"

  /**
   * 索引保留条数。
   *
   * 网页原生每个被观察的 tab 都会每 3 分钟写一条空转记录（`no matching API request`），当它有 5 条时就足够把 刚刚出问题的那一轮挤掉——已经连续踩到过好几次。20
   * 条的索引体积可以忽略（正文随淘汰一起删）。
   */
  val MaxEntries = 20
  val MaxRaw     = 500

  /** 完整正文（raw / normalized / rule）的独立 storage key 前缀。 */
  private val BodyPrefix           = "thTraceBody:"
  def bodyKey(gen: String): String = s"$BodyPrefix$gen"

  /** 本次要覆盖/补充的字段；空串与负数表示"沿用已有记录"。 */
  final private case class TracePatch(
      provider: String = "",
      matchedUrl: String = "",
      requestId: String = "",
      chunks: Int = -1,
      rawChars: Int = -1,
      normalizedChars: Int = -1,
      durationMs: Double = -1.0,
      firstRaw: String = "",
      lastRaw: String = "",
      handlers: List[TraceHandlerStatDto] = Nil,
      markers: js.UndefOr[TraceMarkersDto] = js.undefined,
      timings: js.UndefOr[js.Dictionary[Double]] = js.undefined,
      error: String = ""
  )

  /** 原始侧的内存聚合（在 SW 里按 gen 累计，done/error 时落库）。 */
  final private class Buffer {
    var firstRaw: String        = ""
    var lastRaw: String         = ""
    var chunks: Int             = 0
    var rawChars: Int           = 0
    var normChars: Int          = 0
    val rawText                 = new StringBuilder
    val normalizedText          = new StringBuilder
    var finalNormalized: String = ""
    var rule: String            = ""

    /** 本次的投递方式（`hijack/delta` / `hijack/full` / `typed`…）与实际注入进去的模版正文。 */
    var injectTag: String  = ""
    var injectText: String = ""

    /** 投递证据：哪个请求被改写了、捕获命中的是哪个请求 —— 出事时按这几行就能判断"这一轮到底改没改成"。 */
    val evidence          = mutable.ArrayBuffer.empty[String]
    val handlers          = mutable.LinkedHashMap.empty[String, Int]
    var startedAt: Double = js.Date.now()
  }
  private val buffers = mutable.Map.empty[String, Buffer]

  /**
   * 已经收尾（成功 `noteResult` / 失败 `noteError`）的 gen。收尾之后的 `note*` 不再重建 buffer， 否则后到的零散打点会把已经写好的正文覆盖成空。
   */
  private val closed                      = mutable.LinkedHashSet.empty[String]
  private def closeGen(gen: String): Unit = {
    closed.remove(gen)
    closed.add(gen)
    while (closed.size > 64) closed.remove(closed.head)
  }

  /** 取该 gen 的聚合 buffer；已收尾的 gen 给 None（不再写正文）。 */
  private def ensureBuffer(gen: String): Option[Buffer] =
    if (closed.contains(gen)) None else Some(buffers.getOrElseUpdate(gen, new Buffer))

  /**
   * 索引是"读-改-写"（`get` 整份列表 → 合并 → `set` 回去），两个写并发就会互相覆盖：实测失败轮的错误条目被同一轮的收尾 timings 盖掉，storage 里只剩 body（错误、证据都在 body
   * 里，索引却是空的）。
   *
   * 这里把所有写排成一条链，保证每次 `get` 都看得见上一次 `set`。
   */
  private var writeChain: Future[Unit]                   = Future.successful(())
  private def enqueueWrite(write: => Future[Unit]): Unit =
    writeChain = writeChain
      .flatMap(_ => write)
      .recover { case _ => () }

  /**
   * 失败收尾：把错误写进 trace 索引（`noteResult` 只在流式会话里调用，失败路径以前什么都没有）， 并把这次 run 的正文（可能只有 `inject` / `evidence`）落库、释放内存。
   */
  def noteError(gen: String, message: String): Unit =
    if (gen.nonEmpty && message.nonEmpty) {
      val buffer = if (closed.contains(gen)) None else Some(buffers.remove(gen).getOrElse(new Buffer))
      closeGen(gen)
      upsert(gen, TracePatch(error = PopCanLog.short(message, 300)))
      buffer.foreach(writeBody(gen, _))
    }

  /** 收到一片原始数据：记首片/末片/片数与字符数。 */
  def noteRaw(gen: String, decoded: String): Unit =
    if (gen.nonEmpty && decoded.nonEmpty) {
      ensureBuffer(gen).foreach { buffer =>
        val text = PopCanLog.short(decoded, MaxRaw)
        if (buffer.firstRaw.isEmpty) buffer.firstRaw = text
        buffer.lastRaw = text
        buffer.chunks += 1
        buffer.rawChars += decoded.length
        buffer.rawText.append(decoded)
      }
    }

  /** 归一化增量累计（同时留全文，用于对比"捕获到多少"与"解析出多少"）。 */
  def noteNormalized(gen: String, text: String, reset: Boolean = false): Unit =
    if (gen.nonEmpty) {
      ensureBuffer(gen).foreach { buffer =>
        if (reset) {
          buffer.normChars = 0
          buffer.normalizedText.clear()
        }
        if (text.nonEmpty) {
          buffer.normChars += text.length
          buffer.normalizedText.append(text)
        }
      }
    }

  /** Final filtered content is authoritative; streamed deltas remain as a consistency check. */
  def noteNormalizedFinal(gen: String, text: String): Unit =
    if (gen.nonEmpty) {
      ensureBuffer(gen).foreach(_.finalNormalized = text)
    }

  /** 记下本次生效的解析规则，方便规则改动后仍能回放旧 trace。 */
  def noteRule(gen: String, ruleJson: String): Unit =
    if (gen.nonEmpty && ruleJson.nonEmpty) {
      ensureBuffer(gen).foreach(_.rule = ruleJson)
    }

  /** 记下这一次到底是"输入框打模版"还是"改写请求体注入模版"，以及注入的正文（事后复盘模型到底收到了什么）。 */
  def noteInject(gen: String, tag: String, injected: Option[String]): Unit =
    if (gen.nonEmpty && tag.nonEmpty) {
      ensureBuffer(gen).foreach { buffer =>
        buffer.injectTag = tag
        injected.foreach(text => buffer.injectText = text)
      }
    }

  /** 投递证据（改写了哪个请求 / 捕获命中哪个请求）；按发生顺序累加，收尾时一并写进 trace body。 */
  def noteEvidence(gen: String, line: String): Unit =
    if (gen.nonEmpty && line.nonEmpty) {
      ensureBuffer(gen).foreach(buffer => buffer.evidence.append(line))
    }

  /** 规则命中统计：哪个 handler 命中了几次。 */
  def noteHandler(gen: String, handler: String): Unit =
    if (gen.nonEmpty && handler.nonEmpty) {
      ensureBuffer(gen).foreach(buffer => buffer.handlers.update(handler, buffer.handlers.getOrElse(handler, 0) + 1))
    }

  /**
   * 分阶段耗时：phase → 毫秒，按 key 并入同一条记录。
   *
   * SW 侧与扩展页各自只写自己那一段的 phase，后写者读改时会带上先前那批键（不与 `noteResult` / `noteMarkers` 抢字段）。
   */
  def noteTimings(gen: String, phases: Map[String, Double]): Unit =
    if (gen.nonEmpty && phases.nonEmpty) {
      upsert(gen, TracePatch(timings = js.Dictionary[Double](phases.toSeq*)))
    }

  /** marker 侧的解析结果；可能在页面上下文里调用，直接 upsert 落库。 */
  def noteMarkers(
      gen: String,
      contentChars: Int,
      thinkingChars: Int,
      toolCalls: Int,
      valid: Boolean,
      error: String
  ): Unit =
    upsert(
      gen,
      TracePatch(markers =
        TraceDtoFactory.markers(contentChars, thinkingChars, toolCalls, valid, PopCanLog.short(error, 300))
      )
    )

  /** 原始侧收尾：把内存聚合与本次结果一起写进 storage，并释放内存。 */
  def noteResult(
      gen: String,
      provider: String,
      matchedUrl: String,
      requestId: String = "",
      error: String = ""
  ): Unit = {
    val buffer = buffers.remove(gen)
    buffer.foreach { current =>
      if (current.finalNormalized.nonEmpty && current.normalizedText.result() != current.finalNormalized) {
        current.evidence.append(
          s"normalized divergence streamed=${current.normalizedText.length} final=${current.finalNormalized.length}"
        )
      }
    }
    closeGen(gen)
    upsert(
      gen,
      TracePatch(
        provider = provider,
        matchedUrl = matchedUrl,
        requestId = requestId,
        chunks = buffer.map(_.chunks).getOrElse(-1),
        rawChars = buffer.map(_.rawChars).getOrElse(-1),
        normalizedChars = buffer
          .map(current =>
            if (current.finalNormalized.nonEmpty) current.finalNormalized.length else current.normChars
          )
          .getOrElse(-1),
        durationMs = buffer.map(b => js.Date.now() - b.startedAt).getOrElse(-1.0),
        firstRaw = buffer.map(_.firstRaw).getOrElse(""),
        lastRaw = buffer.map(_.lastRaw).getOrElse(""),
        handlers = buffer
          .map(_.handlers.toList.map { case (id, hits) => TraceDtoFactory.handlerStat(id, hits) })
          .getOrElse(Nil),
        error = error
      )
    )
    buffer.foreach(writeBody(gen, _))
  }

  /** 把该次 run 的完整正文写进独立 key；失败只告警，不影响主流程。 */
  private def writeBody(gen: String, buffer: Buffer): Unit =
    if (gen.nonEmpty) {
      val body = js.JSON.stringify(JsBridge.obj(
        "raw"                  -> (buffer.rawText.result()),
        "normalized"           -> (if (buffer.finalNormalized.nonEmpty) buffer.finalNormalized
                         else buffer.normalizedText.result()),
        "streamedNormalized"   -> (buffer.normalizedText.result()),
        "normalizedDivergence" -> (buffer.finalNormalized.nonEmpty && buffer.finalNormalized != buffer.normalizedText
          .result()),
        "rule"                 -> (buffer.rule),
        "inject"               -> (buffer.injectTag),
        "injectedTemplate"     -> (buffer.injectText),
        "evidence"             -> (js.Array(buffer.evidence.toList*))
      ))
      enqueueWrite {
        ChromeAPI.storage.local
          .set(js.Dictionary[js.Any](bodyKey(gen) -> body))
          .toFuture
          .map(_ => ())
          .recover { case e =>
            PopCanLog.warn("parser", s"trace body write failed gen=${PopCanLog.short(gen)}: ${e.getMessage}")
            ()
          }
      }
    }
  private def upsert(gen: String, patch: TracePatch): Unit =
    if (gen.nonEmpty) enqueueWrite {
      ChromeAPI.storage.local.get(Key).toFuture.flatMap { values =>
        val entries  = TraceDtoFactory.readAll(values.get(Key))
        val existing = entries.find(entry => entry.gen.toOption.contains(gen))
        def text(next: String, previous: js.UndefOr[String]): String =
          if (next.nonEmpty) next else previous.getOrElse("")
        def count(next: Int, previous: js.UndefOr[Int]): Int         =
          if (next >= 0) next else previous.getOrElse(0)
        def span(next: Double, previous: js.UndefOr[Double]): Double =
          if (next >= 0) next else previous.getOrElse(0.0)
        def old[A](pick: TraceDto => js.UndefOr[A]): js.UndefOr[A]   =
          existing.map(pick).getOrElse(js.undefined)
        val merged                                                   = TraceDtoFactory.trace(
          gen = gen,
          at = js.Date.now(),
          provider = text(patch.provider, old(_.provider)),
          matchedUrl = text(patch.matchedUrl, old(_.matchedUrl)),
          requestId = text(patch.requestId, old(_.requestId)),
          chunks = count(patch.chunks, old(_.chunks)),
          rawChars = count(patch.rawChars, old(_.rawChars)),
          normalizedChars = count(patch.normalizedChars, old(_.normalizedChars)),
          durationMs = span(patch.durationMs, old(_.durationMs)),
          firstRaw = text(patch.firstRaw, old(_.firstRaw)),
          lastRaw = text(patch.lastRaw, old(_.lastRaw)),
          parserHandlers = if (patch.handlers.nonEmpty) patch.handlers
          else old(_.parserHandlers).toOption.toList.flatten,
          markers = if (patch.markers.isDefined) patch.markers else old(_.markers),
          // timings 按 key 合并：两侧各写各的 phase，谁后写都不会抹掉另一侧。
          timings = mergeTimings(patch.timings, old(_.timings)),
          error = text(patch.error, old(_.error))
        )
        val updated       = (merged :: entries.filterNot(entry => entry.gen.toOption.contains(gen))).take(MaxEntries)
        // 索引与正文同生共死：被挤出 MaxEntries 的 gen 连同它的 body key 一起删掉。
        val keptGens      = updated.flatMap(_.gen.toOption).toSet
        val droppedBodies = entries
          .flatMap(entry => entry.gen.toOption)
          .filter(dropped => dropped.nonEmpty && !keptGens.contains(dropped))
          .map(bodyKey)
        val dropOldBodies = if (droppedBodies.nonEmpty) {
          ChromeAPI.storage.local
            .remove(js.Array(droppedBodies*))
            .toFuture
            .map(_ => ())
            .recover { case _ => () }
        } else Future.successful(())
        dropOldBodies.flatMap { _ =>
          ChromeAPI.storage.local
            .set(js.Dictionary[js.Any](Key -> TraceDtoFactory.writeAll(updated)))
            .toFuture
            .map(_ => ())
            .recover { case _ => () }
        }
      }
    }

  /** 两次写入的 timings 按 phase 合并；都没有就返回 undefined（不做空字典）。 */
  private def mergeTimings(
      next: js.UndefOr[js.Dictionary[Double]],
      previous: js.UndefOr[js.Dictionary[Double]]
  ): js.UndefOr[js.Dictionary[Double]] =
    if (!next.isDefined) previous
    else if (!previous.isDefined) next
    else {
      val merged = js.Dictionary[Double](previous.get.toSeq*)
      next.get.foreach { case (key, ms) => merged.update(key, ms) }
      if (merged.isEmpty) js.undefined else merged
    }
}
