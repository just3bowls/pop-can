package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.{
  ChromeAPI,
  NetworkDataReceived,
  NetworkGetResponseBodyResult,
  NetworkLoadingFinished,
  NetworkRequestWillBeSent,
  NetworkResponseReceived,
  NetworkStreamResourceContentResult,
  NetworkWebSocketClosed,
  NetworkWebSocketCreated,
  NetworkWebSocketFrame
}
import com.just3bowls.model.provider.JsonPath
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.diagnostics.TraceStore
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** CDP 响应会话的统一接口，非流式和流式使用不同实现。 */
trait CdpResponseSession {
  def cancel(reason: String = "Task canceled"): Unit
  def isCancelled: Boolean
  def requestSignalVersion: Int
  def waitForResponse(timeoutMs: Int = 600000): Future[ParsedPrompt]
  def onEvent(method: String, params: js.Object): Unit
}

/**
 * 单个 tab 的 CDP 网络响应会话。 负责匹配 AI API 请求、用 Network.streamResourceContent 增量读取 body， 并在 streamResourceContent 不可用时回退到
 * loadingFinished + getResponseBody。
 */
final class CdpStreamingSession(
    tabId: Int,
    apiPatterns: List[String],
    generationId: String,
    onRaw: CdpRawChunk => Unit = _ => (),
    provider: String = "",
    onRequest: (String, String, String) => Unit = (_, _, _) => (),
    onCandidate: (String, String, String) => Unit = (_, _, _) => (),
    onCandidateResponse: (String, String, String, String) => Unit = (_, _, _, _) => (),
    discoveryPrompt: Option[String] = None,
    onDiscoveredApi: String => Unit = _ => (),
    captureMode: Boolean = false,
    /**
     * 这一轮的**补充来源**（provider 配置声明，如千问的 `/api/v1/chat/snap`）。
     *
     * 主响应收工时如果规则还没判定完成（没有 `setFinishReason`），本轮不立即收尾，而是继续观察这些 URL 的响应， 把它们的 body 按顺序喂给同一条解析管线，直到规则判定完成或窗口超时。 引擎不预设任何
     * provider 语义：来源、身份、完成判定全部来自配置与规则。
     */
    followUpPatterns: List[String] = Nil,
    /** 补充请求与本轮主请求的"同轮身份"路径（如 `$.req_id`）；任一侧取不到就退化成只按 URL 匹配。 */
    followUpIdentityPath: String = "",
    /** 跟随窗口的硬上限（从主响应收工算起）。 */
    followUpWindowMs: Int = 180000,
    /** 窗口内多久没有新的补充响应就收尾（规则的完成信号会立刻收尾，这是兜底）。 */
    followUpIdleMs: Int = 15000,
    /** 规则是否已判定这轮完成；由调用方接上解析器（引擎不认识规则语义）。 */
    isTurnComplete: () => Boolean = () => false
) extends CdpResponseSession {
  private val target                                                        = CdpTargets.tabTarget(tabId)
  private val promise                                                       = Promise[ParsedPrompt]()
  private var matchedRequestId                                              = ""
  private var matchedUrl                                                    = ""
  private var matched                                                       = false
  private var streamEnabled                                                 = false
  private var preStreamChunks: List[String]                                 = Nil
  private var rawSeq                                                        = 0
  private var timeoutHandle: js.UndefOr[js.timers.SetTimeoutHandle]         = js.undefined
  private var completionTimer: js.UndefOr[js.timers.SetTimeoutHandle]       = js.undefined
  private var captureQuietTimer: js.UndefOr[js.timers.SetTimeoutHandle]     = js.undefined
  private var cancelled                                                     = false
  private var matchedSignalVersion                                          = 0
  private var completed                                                     = false
  private var followUpWindowOpen                                            = false
  private var primaryIdentity                                               = Option.empty[String]
  private val followUpRequestIds                                            = mutable.Map.empty[String, String]
  private var followUpIdleTimer: js.UndefOr[js.timers.SetTimeoutHandle]     = js.undefined
  private var followUpDeadlineTimer: js.UndefOr[js.timers.SetTimeoutHandle] = js.undefined
  private val candidateMeta = mutable.Map.empty[String, (String, String, String)]

  /** WebSocket：没有 loadingFinished，用"最后一帧后静默 WsIdleDoneMs"或 socket 关闭来判定一輪回答结束。 */
  private val WsIdleDoneMs                                        = 2000
  private val wsUrls                                              = mutable.Map.empty[String, String]
  private val wsCandidateIds                                      = mutable.Set.empty[String]
  private val wsBuffers                                           = mutable.Map.empty[String, StringBuilder]
  private var wsMatchedId                                         = ""
  private var wsMatched                                           = false
  private var wsIdleTimer: js.UndefOr[js.timers.SetTimeoutHandle] = js.undefined
  private val log = PopCanLog.withContext("cdp", "gen" -> generationId, "provider" -> provider, "tab" -> tabId.toString)
  def cancel(reason: String = "Task canceled"): Unit                 = {
    cancelled = true
    fail(reason)
  }
  def isCancelled: Boolean                                           = cancelled
  def requestSignalVersion: Int                                      = matchedSignalVersion
  private def normalizeApiUrl(url: String): String                   = {
    val withoutParams = url.split("[?#]")(0).replaceAll("/+$", "")
    withoutParams
  }
  def waitForResponse(timeoutMs: Int = 600000): Future[ParsedPrompt] =
    if (cancelled) {
      promise.tryFailure(new Exception("Task canceled"))
      promise.future
    } else {
      if (!promise.isCompleted && timeoutHandle.isEmpty) {
        timeoutHandle = js.timers.setTimeout(timeoutMs) {
          if (!promise.isCompleted) {
            val reason =
              if (captureMode && candidateMeta.nonEmpty) s"capture candidates pending count=${candidateMeta.size}"
              else if (matched) s"matched $matchedUrl but response did not finish"
              else "no matching API request"
            TraceStore.noteResult(generationId, provider, matchedUrl, matchedRequestId, error = reason)
            log.error(s"network response timeout reason=$reason")
            promise.failure(new Exception(s"CDP network response timeout: $reason"))
          }
        }
      }
      promise.future
    }
  def onEvent(method: String, params: js.Object): Unit               = {
    if (method == "Network.requestWillBeSent") {
      val event          = params.asInstanceOf[NetworkRequestWillBeSent]
      val url            = event.request.url.getOrElse("")
      val requestMethod  = event.request.method.getOrElse("").toUpperCase
      val resourceType   = event.`type`.getOrElse("")
      val requestId      = event.requestId.getOrElse("")
      val postData       = event.request.postData.getOrElse("")
      val lowerUrl       = url.toLowerCase
      val patternMatched = apiPatterns.exists(pattern => url.contains(pattern))
      // 还没保存 apiPattern 时只能靠"请求体里出现提示词"来发现候选：带引号的原样匹配最精确，但没有引号/被引擎改写过
      // （实测 qianwen 会把同一句提示词写进 `content` 与 `meta_data.ori_query`，引号就不贴着提示词了）时也要认。
      val promptMatched  =
        apiPatterns.isEmpty &&
          discoveryPrompt.exists { prompt =>
            val trimmed = prompt.trim
            trimmed.nonEmpty &&
            (postData.contains(trimmed) || postData.contains(js.JSON.stringify(trimmed)))
          }
      val isRelevant     = (patternMatched || promptMatched) &&
        requestMethod != "OPTIONS" &&
        (resourceType == "XHR" || resourceType == "Fetch" || resourceType == "EventSource")
      if (isRelevant) {
        log.info(
          s"request requestId=$requestId method=$requestMethod type=$resourceType url=${PopCanLog.short(url)} hasPostData=${postData.nonEmpty}"
        )
      } else {
        log.debug(
          s"request-irrelevant requestId=$requestId method=$requestMethod type=$resourceType url=${PopCanLog.short(url)}"
        )
      }
      if (lowerUrl.contains("pow") || lowerUrl.contains("challenge")) {
        log.debug(s"possible PoW/challenge request url=${PopCanLog.short(url)} method=$requestMethod")
      }
      if (isRelevant && requestId.nonEmpty) {
        candidateMeta.update(requestId, (url, requestMethod, postData))
        onCandidate(url, requestMethod, postData)
        log.debug(s"CANDIDATE request: ${PopCanLog.short(url)}")
        matchedSignalVersion += 1
        if (captureMode) {
          log.debug(s"capture mode waiting for all candidates")
          // 抓取模式也要记一次请求体：规则里的 `request.userText` 路径要靠它来写。
          onRequest(url, requestMethod, postData)
        } else if (!matched) {
          matched = true
          matchedRequestId = requestId
          matchedUrl = url
          primaryIdentity = identityOf(postData)
          onRequest(url, requestMethod, postData)
          if (promptMatched) {
            val normalized = normalizeApiUrl(url)
            log.info(s"DISCOVERED api=$normalized")
            onDiscoveredApi(normalized)
          }
          log.info(s"MATCHED request: ${PopCanLog.short(url)}")
        }
      }
      // 补充来源：本轮主请求之外的响应（如异步卡片的快照轮询）。只在"主请求已命中且本轮未完成"时登记。
      if (
        !captureMode && matched && !completed && requestId.nonEmpty && requestMethod != "OPTIONS" &&
        followUpPatterns.exists(pattern => url.contains(pattern)) &&
        requestId != matchedRequestId && identityMatches(postData)
      ) {
        followUpRequestIds.update(requestId, url)
        log.info(s"follow-up request url=${PopCanLog.short(url)} windowOpen=$followUpWindowOpen")
        if (followUpWindowOpen) armFollowUpIdle()
      }
    }
    if (method == "Network.responseReceived" && matched && matchedRequestId.nonEmpty) {
      val event     = params.asInstanceOf[NetworkResponseReceived]
      val requestId = event.requestId.getOrElse("")
      if (requestId == matchedRequestId) {
        val status = event.response.status.getOrElse(0)
        val mime   = event.response.mimeType.getOrElse("")
        log.info(s"responseReceived requestId=$requestId status=$status mimeType=$mime")
        startStreaming(requestId)
      }
    }
    if (method == "Network.dataReceived" && matched && matchedRequestId.nonEmpty) {
      val event     = params.asInstanceOf[NetworkDataReceived]
      val requestId = event.requestId.getOrElse("")
      if (requestId == matchedRequestId) {
        val data = event.data.toOption.map(_.toString).getOrElse("")
        if (streamEnabled) {
          log.debug(s"dataReceived requestId=$requestId data=${data.length} chars")
          emitRaw("live", data, base64 = true, sourceUrl = matchedUrl)
        } else {
          log.debug(s"dataReceived buffered requestId=$requestId data=${data.length} chars")
          preStreamChunks = preStreamChunks :+ data
        }
      }
    }
    if (method == "Network.loadingFinished") {
      val event     = params.asInstanceOf[NetworkLoadingFinished]
      val requestId = event.requestId.getOrElse("")
      candidateMeta.remove(requestId).foreach { case (url, method, postData) =>
        ChromeAPI.debugger.sendCommand(
          target,
          "Network.getResponseBody",
          JsBridge.obj("requestId" -> (requestId))
        ).toFuture
          .map { raw =>
            val bodyResult =
              raw.toOption
                .getOrElse(throw new Exception("Network.getResponseBody returned no result"))
                .asInstanceOf[NetworkGetResponseBodyResult]
            val rawBody    = bodyResult.body.getOrElse("")
            val base64     = bodyResult.base64Encoded.getOrElse(false)
            val body       = SseBodyDecoder.decode(rawBody, base64)
            onCandidateResponse(url, method, postData, body)
            if (captureMode) {
              emitRaw("candidate", body, base64 = false, done = true, sourceUrl = url)
              scheduleCaptureCompletion()
            }
          }
          .recover { case e =>
            log.warn(s"candidate body failed url=${PopCanLog.short(url)} error=${e.getMessage}")
            if (captureMode) scheduleCaptureCompletion()
          }
          .foreach(_ => ())
      }
      if (!captureMode) {
        followUpRequestIds.get(requestId).foreach { url =>
          followUpRequestIds.remove(requestId)
          readFollowUpBody(requestId, url)
        }
      }
    }
    if (method == "Network.loadingFinished" && matched && matchedRequestId.nonEmpty) {
      val event     = params.asInstanceOf[NetworkLoadingFinished]
      val requestId = event.requestId.getOrElse("")
      if (requestId == matchedRequestId) {
        if (streamEnabled && rawSeq > 0) {
          if (followUpPatterns.nonEmpty && !isTurnComplete()) {
            log.info(
              s"loadingFinished requestId=$requestId chunks=$rawSeq but rule not complete; follow-up window opens"
            )
            openFollowUpWindow()
          } else {
            log.info(s"loadingFinished requestId=$requestId streaming complete chunks=$rawSeq")
            emitRaw("live", "", base64 = true, done = true, sourceUrl = matchedUrl)
            val parsed = ParsedPrompt("", "")
            log.info(
              s"streaming content=${parsed.content.length} thinking=${parsed.thinking.length} tokens=${parsed.totalTokens}"
            )
            completeWithDelay(parsed)
          }
        } else {
          // 流式通道一片内容都没给（gRPC-Web 这类分帧响应实测会这样）：回退读整段 body，别以一个空结果收工。
          if (streamEnabled)
            log.warn(s"loadingFinished requestId=$requestId but no streamed chunks; reading body via getResponseBody")
          else log.info(s"loadingFinished requestId=$requestId reading body fallback")
          readBodyAndFinish(requestId)
        }
      }
    }
    // ------------------------------------------------------------ WebSocket
    if (method == "Network.webSocketCreated") {
      val event     = params.asInstanceOf[NetworkWebSocketCreated]
      val requestId = event.requestId.getOrElse("")
      val url       = event.url.getOrElse("")
      if (requestId.nonEmpty && url.nonEmpty) {
        wsUrls.update(requestId, url)
        val patternMatched = apiPatterns.exists(pattern => url.contains(pattern))
        if (captureMode) {
          // 抓取阶段把所有 WS 连接都列成候选，让用户能自己挑（HTTP 也是把相关请求列出来）。
          registerWsCandidate(requestId, url, "capture")
        } else if (patternMatched) {
          registerWsCandidate(requestId, url, "pattern")
          matchWs(requestId, url, viaPrompt = false)
        } else {
          log.debug(s"ws-created (unmatched) requestId=$requestId url=${PopCanLog.short(url)}")
        }
      }
    }
    if (method == "Network.webSocketFrameSent") {
      val event     = params.asInstanceOf[NetworkWebSocketFrame]
      val requestId = event.requestId.getOrElse("")
      val payload   = event.response.payloadData.getOrElse("")
      val url       = wsUrls.getOrElse(requestId, "")
      // 发现：provider 还没配 pattern 时，用"发出的帧里是否含我们刚输入的问题"来认这条 socket。
      val promptHit =
        url.nonEmpty && payload.nonEmpty && apiPatterns.isEmpty && !wsMatched &&
          discoveryPrompt.exists(prompt => prompt.trim.nonEmpty && payload.contains(prompt))
      if (promptHit) {
        if (captureMode) {
          registerWsCandidate(requestId, url, "prompt")
          onDiscoveredApi(normalizeApiUrl(url))
          log.info(s"DISCOVERED ws api=${normalizeApiUrl(url)}")
        } else {
          registerWsCandidate(requestId, url, "prompt")
          matchWs(requestId, url, viaPrompt = true)
        }
      }
    }
    if (method == "Network.webSocketFrameReceived") {
      val event     = params.asInstanceOf[NetworkWebSocketFrame]
      val requestId = event.requestId.getOrElse("")
      val payload   = event.response.payloadData.getOrElse("")
      val opcode    = event.response.opcode.toOption.getOrElse(1)
      if (payload.nonEmpty && isWsTarget(requestId)) {
        wsBuffers.getOrElseUpdate(requestId, new StringBuilder).append(payload)
        log.debug(s"ws frame received requestId=$requestId opcode=$opcode chars=${payload.length}")
        // opcode 2 = binary，CDP 里 payloadData 就是 base64。
        emitRaw("ws", payload, base64 = opcode == 2, sourceUrl = wsUrls.getOrElse(requestId, ""))
        scheduleWsIdleDone(requestId)
      }
    }
    if (method == "Network.webSocketFrameError") {
      log.warn(s"ws frame error: ${js.JSON.stringify(params)}")
    }
    if (method == "Network.webSocketClosed") {
      val event     = params.asInstanceOf[NetworkWebSocketClosed]
      val requestId = event.requestId.getOrElse("")
      if (isWsTarget(requestId)) completeWsStream(requestId, "closed")
    }
  }

  /** 该 socket 是否是本次要读的目标：抓取阶段是所有已登记候选，运行时只认命中的那条。 */
  private def isWsTarget(requestId: String): Boolean                                 =
    requestId.nonEmpty && (if (captureMode) wsCandidateIds.contains(requestId) else wsMatchedId == requestId)
  private def registerWsCandidate(requestId: String, url: String, via: String): Unit =
    if (requestId.nonEmpty && url.nonEmpty && !candidateMeta.contains(requestId)) {
      wsCandidateIds += requestId
      candidateMeta.update(requestId, (url, "WS", ""))
      onCandidate(url, "WS", "")
      matchedSignalVersion += 1
      log.info(s"WS CANDIDATE ($via): ${PopCanLog.short(url)}")
    }
  private def matchWs(requestId: String, url: String, viaPrompt: Boolean): Unit      =
    if (!wsMatched) {
      wsMatched = true
      wsMatchedId = requestId
      matched = true
      matchedRequestId = requestId
      matchedUrl = url
      onRequest(url, "WS", "")
      if (viaPrompt) {
        val normalized = normalizeApiUrl(url)
        log.info(s"DISCOVERED ws api=$normalized")
        onDiscoveredApi(normalized)
      }
      log.info(s"MATCHED websocket: ${PopCanLog.short(url)}")
    }
  private def scheduleWsIdleDone(requestId: String): Unit                            = {
    wsIdleTimer.toOption.foreach(js.timers.clearTimeout)
    wsIdleTimer = js.timers.setTimeout(WsIdleDoneMs) {
      wsIdleTimer = js.undefined
      completeWsStream(requestId, "idle")
    }
  }
  private def completeWsStream(requestId: String, reason: String): Unit              = {
    wsIdleTimer.toOption.foreach(js.timers.clearTimeout)
    wsIdleTimer = js.undefined
    val url = wsUrls.getOrElse(requestId, "")
    if (captureMode) {
      val body = wsBuffers.get(requestId).map(_.result()).getOrElse("")
      wsBuffers.remove(requestId)
      candidateMeta.remove(requestId)
      onCandidateResponse(url, "WS", "", body)
      log.info(s"ws capture candidate done reason=$reason chars=${body.length}")
      scheduleCaptureCompletion()
    } else if (wsMatched && !completed) {
      log.info(s"ws stream complete reason=$reason")
      emitRaw("ws", "", base64 = false, done = true, sourceUrl = url)
      completeWithDelay(ParsedPrompt("", ""))
    }
  }

  /**
   * 用 `Network.getResponseBody` 把整段响应体读回来收尾。
   *
   * 两条路都会走到这里：`streamResourceContent` 不可用（老路径），以及**流式通道一片内容都没收到**——后者是 gRPC-Web 这类分帧响应的实测表现， 以前会直接以一个空结果收工，等于把这一轮的回答丢了。
   */
  private def readBodyAndFinish(requestId: String): Unit =
    ChromeAPI.debugger.sendCommand(
      target,
      "Network.getResponseBody",
      JsBridge.obj("requestId" -> (requestId))
    ).toFuture
      .map { raw =>
        val bodyResult =
          raw.toOption
            .getOrElse(throw new Exception("Network.getResponseBody returned no result"))
            .asInstanceOf[NetworkGetResponseBodyResult]
        if (bodyResult.body.toOption.isEmpty) {
          log.warn("getResponseBody result has no body field")
          throw new Exception("Network.getResponseBody returned no body")
        }
        val rawBody    = bodyResult.body.getOrElse("")
        val base64     = bodyResult.base64Encoded.getOrElse(false)
        val body       = SseBodyDecoder.decode(rawBody, base64)
        log.info(s"body length=${body.length} base64=$base64")
        emitRaw("fallback_body", rawBody, base64, sourceUrl = matchedUrl)
        finish()
      }
      .recover { case e =>
        fail(s"Failed to read network response body: ${e.getMessage}")
      }
  private def startStreaming(requestId: String): Unit    =
    if (!streamEnabled && requestId.nonEmpty) {
      log.debug(s"requesting streamResourceContent requestId=$requestId")
      val timeoutHandle = js.timers.setTimeout(5000) {
        if (!streamEnabled) {
          log.warn("streamResourceContent did not resolve within 5s, waiting for loadingFinished fallback")
        }
      }
      ChromeAPI.debugger.sendCommand(
        target,
        "Network.streamResourceContent",
        JsBridge.obj("requestId" -> (requestId))
      ).toFuture
        .map { raw =>
          js.timers.clearTimeout(timeoutHandle)
          val result =
            raw.toOption
              .getOrElse(throw new Exception("Network.streamResourceContent returned no result"))
              .asInstanceOf[NetworkStreamResourceContentResult]
          streamEnabled = true
          log.info(s"streamResourceContent enabled requestId=$requestId")
          if (preStreamChunks.nonEmpty) {
            log.debug(s"flushing pre-stream chunks=${preStreamChunks.size}")
            preStreamChunks.foreach { data =>
              log.debug(s"pre-stream data=${data.length} chars")
              emitRaw("live", data, base64 = true, sourceUrl = matchedUrl)
            }
          } else {
            result.bufferedData.toOption.filter(_.nonEmpty).foreach { buffered =>
              log.debug(s"streamResourceContent buffered=${buffered.length} chars")
              emitRaw("live", buffered.toString, base64 = true, sourceUrl = matchedUrl)
            }
          }
          preStreamChunks = Nil
        }
        .recover { case e =>
          js.timers.clearTimeout(timeoutHandle)
          streamEnabled = false
          preStreamChunks = Nil
          log.warn(s"streamResourceContent failed, will fallback: ${e.getMessage}")
        }
    }
  private def finish(): Unit                             =
    if (!promise.isCompleted) {
      timeoutHandle.toOption.foreach(js.timers.clearTimeout)
      timeoutHandle = js.undefined
      preStreamChunks = Nil
      captureQuietTimer.toOption.foreach(js.timers.clearTimeout)
      captureQuietTimer = js.undefined
      if (!captureMode && followUpPatterns.nonEmpty && !isTurnComplete()) openFollowUpWindow()
      else {
        emitRaw("fallback_body", "", base64 = true, done = true, sourceUrl = matchedUrl)
        val parsed = ParsedPrompt("", "")
        completeWithDelay(parsed)
      }
    }

  /** 主响应收工但规则未完成时进入跟随窗口：继续听 provider 声明的补充来源。 */
  private def openFollowUpWindow(): Unit                = {
    if (followUpWindowOpen || completed || promise.isCompleted) return
    followUpWindowOpen = true
    TraceStore.noteEvidence(generationId, s"follow-up window open patterns=${followUpPatterns.mkString(",")}")
    log.info(s"follow-up window open patterns=${followUpPatterns.mkString(",")} primary=${PopCanLog.short(matchedUrl)}")
    armFollowUpIdle()
    followUpDeadlineTimer.toOption.foreach(js.timers.clearTimeout)
    followUpDeadlineTimer = js.timers.setTimeout(followUpWindowMs) {
      followUpDeadlineTimer = js.undefined
      if (followUpWindowOpen) closeFollowUpWindow("deadline")
    }
  }
  private def armFollowUpIdle(): Unit                   =
    if (followUpWindowOpen && !promise.isCompleted) {
      followUpIdleTimer.toOption.foreach(js.timers.clearTimeout)
      followUpIdleTimer = js.timers.setTimeout(followUpIdleMs) {
        followUpIdleTimer = js.undefined
        if (followUpWindowOpen) {
          if (isTurnComplete()) closeFollowUpWindow("rule-complete") else closeFollowUpWindow("idle")
        }
      }
    }
  private def closeFollowUpWindow(reason: String): Unit = {
    if (!followUpWindowOpen) return
    followUpWindowOpen = false
    followUpIdleTimer.toOption.foreach(js.timers.clearTimeout)
    followUpIdleTimer = js.undefined
    followUpDeadlineTimer.toOption.foreach(js.timers.clearTimeout)
    followUpDeadlineTimer = js.undefined
    TraceStore.noteEvidence(generationId, s"follow-up window closed ($reason)")
    log.info(s"follow-up window closed reason=$reason")
    emitRaw("live", "", base64 = true, done = true, sourceUrl = matchedUrl)
    completeWithDelay(ParsedPrompt("", ""))
  }

  /** 补充响应整段读回来喂给同一条解析管线（快照轮询这类响应很短，不需要流式）。 */
  private def readFollowUpBody(requestId: String, sourceUrl: String): Unit =
    ChromeAPI.debugger.sendCommand(
      target,
      "Network.getResponseBody",
      JsBridge.obj("requestId" -> (requestId))
    ).toFuture
      .map { raw =>
        val bodyResult =
          raw.toOption
            .getOrElse(throw new Exception("Network.getResponseBody returned no result"))
            .asInstanceOf[NetworkGetResponseBodyResult]
        val rawBody    = bodyResult.body.getOrElse("")
        val base64     = bodyResult.base64Encoded.getOrElse(false)
        val body       = SseBodyDecoder.decode(rawBody, base64)
        log.info(s"follow-up body requestId=$requestId chars=${body.length}")
        TraceStore.noteEvidence(generationId, s"follow-up body chars=${body.length}")
        emitRaw("followup", body, base64 = false, sourceUrl = sourceUrl)
        if (isTurnComplete()) closeFollowUpWindow("rule-complete") else armFollowUpIdle()
      }
      .recover { case e =>
        log.warn(s"follow-up body failed requestId=$requestId error=${e.getMessage}")
        armFollowUpIdle()
      }
      .foreach(_ => ())
  private def identityOf(postData: String): Option[String]                 =
    if (followUpIdentityPath.trim.isEmpty || postData.trim.isEmpty) None
    else
      try JsonPath.getString(js.JSON.parse(postData), followUpIdentityPath)
      catch { case _: Throwable => None }

  /** 补充请求和主请求的"同轮身份"比较：任一侧取不到就退化成"按 URL 匹配"，不误伤。 */
  private def identityMatches(postData: String): Boolean    =
    primaryIdentity match {
      case None        => true
      case Some(value) => identityOf(postData).forall(_ == value)
    }
  private def completeWithDelay(parsed: ParsedPrompt): Unit =
    if (!promise.isCompleted) {
      if (captureMode) {
        // 抓取流程要等候选响应的异步回填，保留宽限期。
        completionTimer.toOption.foreach(js.timers.clearTimeout)
        completionTimer = js.timers.setTimeout(2500) {
          completionTimer = js.undefined
          if (!promise.isCompleted) {
            TraceStore.noteResult(generationId, provider, matchedUrl, matchedRequestId)
            promise.trySuccess(parsed)
          }
        }
      } else {
        // 普通会话的载荷已经通过终态 chunk 同步送出，立即收尾以释放 debugger 与 tab 锁。
        TraceStore.noteResult(generationId, provider, matchedUrl, matchedRequestId)
        promise.trySuccess(parsed)
      }
    }
  private def scheduleCaptureCompletion(): Unit             =
    if (captureMode && !promise.isCompleted) {
      captureQuietTimer.toOption.foreach(js.timers.clearTimeout)
      captureQuietTimer = js.timers.setTimeout(1500) {
        captureQuietTimer = js.undefined
        if (captureMode && candidateMeta.isEmpty && !promise.isCompleted) {
          log.debug("all capture candidates finished")
          finish()
        }
      }
    }
  private def emitRaw(
      captureSource: String,
      data: String,
      base64: Boolean,
      done: Boolean = false,
      error: String = "",
      sourceUrl: String
  ): Unit =
    if (completed) {
      log.debug(s"late chunk after completion source=$captureSource len=${data.length} done=$done error=$error")
    } else {
      rawSeq += 1
      onRaw(CdpRawChunk(
        generationId = generationId,
        seq = rawSeq,
        captureSource = captureSource,
        data = data,
        base64 = base64,
        done = done,
        error = error,
        sourceUrl = sourceUrl
      ))
      // 抓取流程保持原样：它在终态之后仍可能继续收到候选请求的 chunk。
      if (!captureMode && (done || error.nonEmpty)) completed = true
    }
  private def fail(message: String): Unit                   =
    if (!promise.isCompleted) {
      TraceStore.noteResult(generationId, provider, matchedUrl, matchedRequestId, error = message)
      log.error(s"failed: $message")
      if (!captureMode) completed = true
      timeoutHandle.toOption.foreach(js.timers.clearTimeout)
      timeoutHandle = js.undefined
      completionTimer.toOption.foreach(js.timers.clearTimeout)
      completionTimer = js.undefined
      captureQuietTimer.toOption.foreach(js.timers.clearTimeout)
      captureQuietTimer = js.undefined
      followUpIdleTimer.toOption.foreach(js.timers.clearTimeout)
      followUpIdleTimer = js.undefined
      followUpDeadlineTimer.toOption.foreach(js.timers.clearTimeout)
      followUpDeadlineTimer = js.undefined
      wsIdleTimer.toOption.foreach(js.timers.clearTimeout)
      wsIdleTimer = js.undefined
      preStreamChunks = Nil
      promise.tryFailure(new Exception(message))
    }
}
