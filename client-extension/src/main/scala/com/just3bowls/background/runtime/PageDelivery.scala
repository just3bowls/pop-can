package com.just3bowls.background.runtime

import com.just3bowls.api.provider.ProviderTurnDiagnostics
import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.automation.provider.LocalParsedDelta
import com.just3bowls.background.bootstrap.BackgroundState
import com.just3bowls.background.webchat.WebChatHosts
import com.just3bowls.facade.{ChromeAPI, ConsumedResponse}
import com.just3bowls.model.diagnostics.{PopCanLog, RawCaptureEvent, TraceStore}
import com.just3bowls.model.chat.RawChunkStreamDecoder
import com.just3bowls.model.localapi.Msg
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

private[background] object PageDelivery {

  /** 已经回过执 / 已经按投递失败收口过的 gen（有界台账，只保留最近若干个）。 */
  private val deliveryAcked     = mutable.LinkedHashSet.empty[String]
  private val deliveryLost      = mutable.LinkedHashSet.empty[String]
  private val DeliveryLedgerMax = 64
  private def rememberDelivery(ledger: mutable.LinkedHashSet[String], gen: String): Boolean = {
    val first = !ledger.contains(gen)
    ledger.remove(gen)
    ledger.add(gen)
    while (ledger.size > DeliveryLedgerMax) ledger.remove(ledger.head)
    first
  }

  /**
   * 把一条 run 相关的消息投给扩展页，并确认**真的有人接住**。
   *
   * 回执由页面侧 `TabAiExecutor` 里唯一持有这个 gen 的页面给出（`consumed=true`）；没人回执说明这条 run 已经没有消费者 —— 插件页被关掉 /
   * 刷新过、或者这一轮根本不属于当前页面。此时立刻按投递失败收口：写 trace 证据与错误、 取消该 gen 的 CDP 流，让它走既有失败出口把 tab / slot 还回来，而不是把分片扔进黑洞里等超时。
   */
  private[background] def deliverToPage(
      generationId: String,
      provider: String,
      message: js.Object,
      label: String
  ): Unit =
    ChromeAPI.runtime
      .sendMessage(message)
      .toFuture
      .map { response =>
        val consumed =
          response != null &&
            !js.isUndefined(response) &&
            response.asInstanceOf[ConsumedResponse].consumed.getOrElse(false)
        if (consumed) {
          if (rememberDelivery(deliveryAcked, generationId)) {
            TraceStore.noteEvidence(generationId, s"delivery acked by the extension page ($label)")
          }
        } else {
          lostDelivery(generationId, provider, label, "no extension page consumed it")
        }
      }
      .recover { case e =>
        lostDelivery(generationId, provider, label, Option(e.getMessage).getOrElse(e.toString))
      }
      .foreach(_ => ())

  /** 投递失败：留证据 + 错误，取消这一轮，同一个 gen 只收口一次。 */
  private def lostDelivery(generationId: String, provider: String, label: String, detail: String): Unit =
    if (generationId.nonEmpty && rememberDelivery(deliveryLost, generationId)) {
      val reason = s"Browser page did not receive this run's $label: $detail"
      PopCanLog.withContext("cdp", "gen" -> generationId, "provider" -> provider).warn(reason)
      TraceStore.noteEvidence(generationId, s"delivery lost ($label): $detail")
      TraceStore.noteError(generationId, reason)
      // 还没进 flow 的（例如注入提示发出时页面就已经没了）走取消集，进入 flow 的走会话取消。
      BackgroundState.cancelledCdpTasks += generationId
      CdpSession.cancelOwner(s"task:$generationId", reason)
    }

  /**
   * 原始分片 → 归一化 → `NORMALIZED_CHUNK`：slot 分配路径与网页原生 host tab 路径**共用同一份流处理**。
   *
   * 返回这一片的 `RawCaptureEvent`，需要留 trace 的调用方自己收着（host 轮不收，`TraceStore.noteRaw` 已经够了）。
   */
  private[background] def forwardRawChunk(
      generationId: String,
      provider: String,
      raw: CdpRawChunk
  ): RawCaptureEvent = {
    val flowLog    = PopCanLog.withContext("cdp", "gen" -> generationId, "provider" -> provider)
    val finalRaw   = raw.done || raw.error.nonEmpty
    val decoder    = BackgroundState.rawChunkDecoders.getOrElseUpdate(
      generationId,
      new RawChunkStreamDecoder
    )
    val decodedRaw = decoder.decode(raw.data, raw.base64, finalRaw)
    if (finalRaw) {
      flowLog.info(
        s"RAW_CHUNK final seq=${raw.seq} source=${raw.captureSource} " +
          s"rawChars=${decodedRaw.length} done=${raw.done} error=${raw.error} " +
          s"url=${PopCanLog.short(raw.sourceUrl, 90)}"
      )
    } else {
      flowLog.debug(
        s"RAW_CHUNK seq=${raw.seq} source=${raw.captureSource} chars=${decodedRaw.length} " +
          s"url=${PopCanLog.short(raw.sourceUrl, 90)}"
      )
    }
    TraceStore.noteRaw(generationId, decodedRaw)
    val event      = RawCaptureEvent(
      seq = raw.seq,
      captureSource = raw.captureSource,
      data = raw.data,
      base64 = raw.base64,
      done = raw.done,
      error = raw.error,
      timestamp = js.Date.now()
    )
    BackgroundState.localRawParsers.get(generationId).flatten.foreach { parser =>
      val rawChars = BackgroundState.rawCharsByTask.getOrElse(generationId, 0) + decodedRaw.length
      BackgroundState.rawCharsByTask.update(generationId, rawChars)
      val buffered = new mutable.ArrayBuffer[LocalParsedDelta]
      if (decodedRaw.nonEmpty) buffered ++= parser.feed(decodedRaw, raw.sourceUrl)
      if (finalRaw) buffered ++= parser.finish()
      val deltas   = buffered.toList
      if (finalRaw) {
        TraceStore.noteNormalizedFinal(generationId, parser.filteredContent)
        // 诊断口径只留一处：模式标签每轮一记；"finish 了却没正文"额外记帧普查。
        TraceStore.noteEvidence(
          generationId,
          s"parserMode=${if (parser.currentMode.isEmpty) "unknown" else parser.currentMode}"
        )
        val noContent = parser.noContentAtEnd.map(_ =>
          ProviderTurnDiagnostics.noContent(provider, rawChars, parser.currentMode)
        )
        Option(parser.currentError).filter(_.nonEmpty).orElse(noContent).foreach { reason =>
          BackgroundState.emptyTurnByTask.update(generationId, reason)
          TraceStore.noteEvidence(
            generationId,
            s"parserCensus ${parser.census}; endpoint=${PopCanLog.short(raw.sourceUrl, 100)}"
          )
        }
      }
      val baseSeq  = BackgroundState.normalizedSeqByTask.getOrElse(generationId, 1)
      deltas.zipWithIndex.foreach { case (delta, index) =>
        val seq = baseSeq + index
        TraceStore.noteNormalized(generationId, delta.contentDelta, reset = delta.resetContent)
        if (delta.done || delta.error.nonEmpty) {
          flowLog.info(
            s"NORMALIZED_CHUNK final seq=$seq contentChars=${delta.contentDelta.length} " +
              s"thinkingChars=${delta.thinkingDelta.length} done=${delta.done} " +
              s"finish=${delta.finishReason} error=${delta.error}"
          )
        } else {
          flowLog.debug(
            s"NORMALIZED_CHUNK seq=$seq contentChars=${delta.contentDelta.length} " +
              s"thinkingChars=${delta.thinkingDelta.length}"
          )
        }
        deliverToPage(
          generationId,
          provider,
          Msg.normalizedChunk(
            generationId,
            seq,
            delta.contentDelta,
            delta.done,
            delta.finishReason,
            delta.error,
            rawChars,
            delta.resetContent
          ),
          "response stream"
        )
      }
      BackgroundState.normalizedSeqByTask.update(generationId, baseSeq + deltas.size)
    }
    if (finalRaw) BackgroundState.rawChunkDecoders.remove(generationId)
    event
  }

  /**
   * 一轮投递失败时的可诊断信息（都在失败路径上跑，纯只读）。
   *
   * ① 把错误写进 trace 索引（`noteResult` 只覆盖流式会话，以前失败路径在 trace 里什么都不留）； ② 如果这个 tab 当前 URL 与这条会话在该 provider 上绑定的 URL 不一致，补一行
   * evidence —— 那说明我们是在别的对话上打字。
   */
  private[background] def recordFailureEvidence(
      gen: String,
      provider: String,
      sessionId: String,
      tabId: Int,
      error: Throwable
  ): Unit = {
    val message   = Option(error.getMessage).getOrElse(error.toString)
    val mismatchF =
      if (gen.isEmpty || sessionId.isEmpty) Future.successful(())
      else
        for {
          url   <- ChromeAPI.tabs
            .get(tabId)
            .toFuture
            .map(tab => tab.url.toOption.getOrElse(""))
            .recover { case _ => "" }
          hosts <- WebChatHosts.findBySession(sessionId).recover { case _ => Nil }
        } yield {
          val bound = hosts.find(_.provider == provider).map(_.convKey).getOrElse("")
          if (url.nonEmpty && bound.nonEmpty && bound != url) {
            TraceStore.noteEvidence(gen, s"ownership mismatch: tab=$url sessionBound=$bound")
          }
        }
    mismatchF
      .recover { case _ => () }
      .foreach(_ => TraceStore.noteError(gen, message))
  }
}
