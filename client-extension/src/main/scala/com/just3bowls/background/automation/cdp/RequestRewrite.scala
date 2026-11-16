package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge
import com.just3bowls.api.protocol.Bytes
import com.just3bowls.facade.{ChromeAPI, FetchPausedRequest, FetchRequestPaused}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

/** 一个被 `Fetch` 暂停的请求该怎么处理。 */
sealed private[background] trait RewriteDecision
private[background] object RewriteDecision {

  /**
   * 用新正文继续这次请求。
   *
   * `headers` 非空时**整份覆盖**请求头（CDP `Fetch.continueRequest` 的 headers 就是覆盖语义）：调用方给的就是"原始头 + 我们改过的那一项"， 这样带完整性字段的自定义头（例如文心的
   * `X-Chat-Message`）能和正文一起保持自洽。`Content-Length` 一律不传，交给 Chrome 按新正文重算。
   */
  final case class Write(body: Array[Byte], headers: js.UndefOr[js.Dictionary[String]] = js.undefined)
      extends RewriteDecision

  /** 不是我们要改的请求（或正文还没准备好）：原样放行；`keepArmed` 为真时继续等下一个请求。 */
  final case class Pass(reason: String, keepArmed: Boolean = false) extends RewriteDecision

  /** 认了就是我们该改的请求却改不了：原样放行并收工，`error` 交给调用方（例如让这一轮任务以可读错误结束）。 */
  final case class Fail(reason: String, error: String) extends RewriteDecision
}

/**
 * 请求体改写计划。
 *
 * `owner` 是计划的所有者标识（任务用 `task:<gen>`、网页原生会话用 `webchat:<tab>`）：只有所有者能 disarm 自己的计划，别的组件误调 disarm 不会把正在用的计划摘掉。`decide`
 * 在请求暂停时调用，输入是解码后的请求正文，输出见 [[RewriteDecision]]。它返回 Future，让网页原生路径可以在继续请求前执行有硬超时的 Memory recall；任务路径直接返回已完成
 * Future，保持原有语义。
 *
 * `decide` 的第二个入参是这条请求**当前的头**（CDP 给的 `{name: value}`）；需要改头时把改好的整份头通过 [[RewriteDecision.Write]] 回传。
 *
 * `oneShot` 为真 = "改写/放行一次就收工"（网页原生一次投递）；为假 = 这一轮内持续改写：凡是命中且能写的请求都改写，直到 owner 主动 disarm。插件页任务用后者 ——
 * 一轮里可能不止一个请求，"改没改成"要等这一轮结束再按证据判定。
 */
final private[background] case class RewritePlan(
    owner: String,
    provider: String,
    patterns: List[String],
    decide: (Array[Byte], js.Dictionary[String]) => Future[RewriteDecision],
    oneShot: Boolean = true,
    onDisarm: String => Unit = _ => (),
    onError: String => Unit = _ => (),
    /** 成功写回一次：带这次请求的 url 与**拦截前**的正文字节数，供调用方做"这一轮到底改没改成"的证据判定。 */
    onRewritten: (String, Int) => Unit = (_, _) => (),
    /** `> 0` 时异步 decide 超过这个时间就放行原请求，避免 CDP paused 请求永久悬挂。 */
    decisionTimeoutMs: Int = 0,
    timeoutMs: Int = 0
)

/**
 * CDP `Fetch` 请求体改写的公共部件：`Fetch.enable` → 请求暂停 → 交回调用方决定新正文 → `continueRequest` → 一次性 `Fetch.disable`。
 *
 * 网页原生会话（`WebChatService`）与插件页模型调用（`CdpFlowExecutor`）共用这一份；每个 tab 同一时刻只保留一个计划： `arm` 是"后到者替换"（替换时通知被顶掉的所有者），`disarm`
 * 是"所有者限定"（不是自己的计划一律不碰）。任何异常、超时都必须走到 `continueRequest`，绝不能把页面的请求挂在暂停态。
 */
private[background] object RequestRewrite {
  final private class Armed(val plan: RewritePlan, var timer: js.UndefOr[js.timers.SetTimeoutHandle])
  private val armed                                    = mutable.Map.empty[Int, Armed]
  def ownerOf(tabId: Int): Option[String]              = armed.get(tabId).map(_.plan.owner)
  def arm(tabId: Int, plan: RewritePlan): Future[Unit] =
    clearArmed(tabId, s"replaced-by:${plan.owner}").flatMap { _ =>
      if (plan.patterns.isEmpty) Future.successful(())
      else {
        val patterns = js.Array(plan.patterns.map(pattern =>
          JsBridge.obj("urlPattern" -> (s"*$pattern*"), "requestStage" -> ("Request"))
        )*)
        cdp(tabId, "Fetch.enable", JsBridge.obj("patterns" -> (patterns))).map { _ =>
          val entry = new Armed(plan, js.undefined)
          if (plan.timeoutMs > 0) {
            entry.timer = js.timers.setTimeout(plan.timeoutMs) {
              if (armed.get(tabId).contains(entry)) {
                PopCanLog.warn("rewrite", s"plan ${plan.owner} timed out tab=$tabId; disarming")
                disarmArmed(tabId, entry, "timeout").foreach(_ => ())
              }
            }
          }
          armed.update(tabId, entry)
          PopCanLog.debug("rewrite", s"armed tab=$tabId owner=${plan.owner} patterns=${plan.patterns.length}")
          ()
        }
      }
    }

  /** 只有计划的所有者才能摘掉它；别的组件误调一律忽略（并留一条日志，方便下次排查）。 */
  def disarm(tabId: Int, owner: String, reason: String): Future[Unit] =
    armed.remove(tabId) match {
      case None        => Future.successful(())
      case Some(entry) =>
        if (entry.plan.owner != owner) {
          armed.update(tabId, entry)
          PopCanLog.debug(
            "rewrite",
            s"disarm ignored tab=$tabId owner=$owner (armed owner=${entry.plan.owner}, reason=$reason)"
          )
          Future.successful(())
        } else {
          entry.timer.foreach(js.timers.clearTimeout)
          entry.plan.onDisarm(reason)
          fetchDisable(tabId)
        }
    }

  /** 无条件摘掉该 tab 上的计划（租约释放 / 连接断开时用）：谁在改都作废，`Fetch` 一并关掉。 */
  def clearTab(tabId: Int, reason: String): Future[Unit] =
    armed.remove(tabId) match {
      case None        => Future.successful(())
      case Some(entry) =>
        entry.timer.foreach(js.timers.clearTimeout)
        PopCanLog.debug("rewrite", s"cleared tab=$tabId owner=${entry.plan.owner} reason=$reason")
        entry.plan.onDisarm(reason)
        fetchDisable(tabId)
    }

  /** 无条件摘掉当前计划（arm 的替换语义用它）：被顶掉的所有者会收到 `onDisarm("replaced-by:…")`。 */
  private def clearArmed(tabId: Int, reason: String): Future[Unit]                =
    armed.remove(tabId) match {
      case None        => Future.successful(())
      case Some(entry) =>
        entry.timer.foreach(js.timers.clearTimeout)
        entry.plan.onDisarm(reason)
        fetchDisable(tabId)
    }
  private def disarmArmed(tabId: Int, entry: Armed, reason: String): Future[Unit] =
    if (!armed.get(tabId).contains(entry)) Future.successful(())
    else {
      armed.remove(tabId)
      entry.timer.foreach(js.timers.clearTimeout)
      entry.plan.onDisarm(reason)
      fetchDisable(tabId)
    }

  /** `BackgroundMain` 把 `Fetch.*` 事件转进来。 */
  def onEvent(tabId: Int, method: String, params: js.Object): Unit =
    if (method != "Fetch.requestPaused") ()
    else
      armed.get(tabId).foreach { entry =>
        val event     = params.asInstanceOf[FetchRequestPaused]
        val requestId = event.requestId.getOrElse("")
        val request   = event.request.toOption
        if (requestId.isEmpty || request.isEmpty) ()
        else {
          val paused          = request.get
          val url             = paused.url.getOrElse("")
          val (body, entries) = requestBodyOf(paused)
          val headers         = requestHeadersOf(paused)
          log(
            entry.plan.provider,
            url,
            "paused",
            s"owner=${entry.plan.owner} bodyBytes=${body.length} entries=$entries patterns=${entry.plan.patterns.length}"
          )
          val rawDecisionF    =
            try entry.plan.decide(body, headers)
            catch {
              case e: Throwable =>
                Future.successful(
                  RewriteDecision.Fail("decide-threw", Option(e.getMessage).getOrElse(e.toString))
                )
            }
          val decisionF       =
            if (entry.plan.decisionTimeoutMs <= 0)
              rawDecisionF.recover { case e =>
                RewriteDecision.Fail("decide-failed", Option(e.getMessage).getOrElse(e.toString))
              }
            else {
              val promise = scala.concurrent.Promise[RewriteDecision]()
              val timer   = js.timers.setTimeout(entry.plan.decisionTimeoutMs.toDouble) {
                promise.trySuccess(RewriteDecision.Pass("decision-timeout", keepArmed = false))
              }
              rawDecisionF.onComplete { result =>
                js.timers.clearTimeout(timer)
                result match {
                  case scala.util.Success(value) => promise.trySuccess(value)
                  case scala.util.Failure(error) =>
                    promise.trySuccess(
                      RewriteDecision.Fail("decide-failed", Option(error.getMessage).getOrElse(error.toString))
                    )
                }
              }
              promise.future
            }
          decisionF.foreach { decision =>
            decision match {
              case RewriteDecision.Write(newBody, newHeaders) =>
                val patched = newHeaders.toOption.filter(_.nonEmpty)
                log(
                  entry.plan.provider,
                  url,
                  "rewrite",
                  s"owner=${entry.plan.owner} bytes=${newBody.length} headers=${patched.map(_.size).getOrElse(0)}"
                )
                continueRequest(tabId, requestId, Some(newBody), newHeaders)
                entry.plan.onRewritten(url, body.length)
                if (entry.plan.oneShot) disarm(tabId, entry.plan.owner, "rewritten").foreach(_ => ())
              case RewriteDecision.Pass(reason, keep)         =>
                log(
                  entry.plan.provider,
                  url,
                  reason,
                  s"owner=${entry.plan.owner} bodyBytes=${body.length} keepArmed=$keep"
                )
                continueRequest(tabId, requestId, None)
                if (entry.plan.oneShot && !keep) disarm(tabId, entry.plan.owner, reason).foreach(_ => ())
              case RewriteDecision.Fail(reason, error)        =>
                log(entry.plan.provider, url, reason, s"owner=${entry.plan.owner} error=${PopCanLog.short(error, 160)}")
                continueRequest(tabId, requestId, None)
                entry.plan.onError(error)
                disarm(tabId, entry.plan.owner, reason).foreach(_ => ())
            }
          }
        }
      }

  /** 劫持决策日志：落到 storage（SW 控制台没有历史回放，出问题时得能事后看）。 */
  private def log(provider: String, url: String, action: String, detail: String): Unit =
    ChromeAPI.storage.local
      .get("thWebChatHijackLog")
      .toFuture
      .recover { case _ => js.Dictionary.empty[js.Any] }
      .foreach { values =>
        val entries = values
          .get("thWebChatHijackLog")
          .filter(js.Array.isArray(_))
          .map(JsBridge.asArray(_).toList)
          .getOrElse(Nil)
        val entry   = JsBridge.obj(
          "at"       -> js.Date.now(),
          "provider" -> provider,
          "url"      -> url.split('?').head.take(120),
          "action"   -> action,
          "detail"   -> detail.take(200)
        )
        ChromeAPI.storage.local
          .set(js.Dictionary[js.Any]("thWebChatHijackLog" -> js.Array((entry :: entries).take(20)*)))
          .toFuture
          .recover { case _ => () }
          .foreach(_ => ())
      }

  /**
   * 取暂停请求的正文，统一成**字节**：帧长度、JSON 区间、写回都必须按真实字节算。
   *
   * `postDataEntries`（base64）是精确字节，优先用它；没有时退回 `postData` 字符串并按 [[Bytes.fromPostDataString]] 还原 （合法 UTF-8
   * 的体是真实文本，二进制/帧化的体是"逐字节"表示）。
   */
  def requestBodyOf(request: FetchPausedRequest): (Array[Byte], Int) = {
    val entries = request.postDataEntries
      .toOption
      .getOrElse(js.Array())
      .toList
      .flatMap(_.bytes.toOption)
    if (entries.nonEmpty) {
      val chunks = entries.map(chunk => base64ToBytes(chunk))
      val total  = chunks.map(_.length).sum
      val out    = new Array[Byte](total)
      var at     = 0
      chunks.foreach { chunk =>
        System.arraycopy(chunk, 0, out, at, chunk.length)
        at += chunk.length
      }
      (out, entries.length)
    } else {
      val direct = request.postData.getOrElse("")
      (Bytes.fromPostDataString(direct), 0)
    }
  }

  /** base64 字符串（CDP 给的 `postDataEntries[].bytes`）→ 原始字节。 */
  private def base64ToBytes(value: String): Array[Byte] = {
    val binary = JsBridge.asString(JsBridge.atob(value))
    val out    = new Array[Byte](binary.length)
    var i      = 0
    while (i < binary.length) {
      out(i) = (binary.charAt(i) & 0xff).toByte
      i += 1
    }
    out
  }

  /** 原始字节 → base64（`Fetch.continueRequest` 的 postData 只认 base64 字符串）。 */
  private def base64Of(bytes: Array[Byte]): String = {
    val sb = new StringBuilder(bytes.length)
    var i  = 0
    while (i < bytes.length) {
      sb.append((bytes(i) & 0xff).toChar)
      i += 1
    }
    JsBridge.asString(JsBridge.btoa(sb.result()))
  }
  private def continueRequest(
      tabId: Int,
      requestId: String,
      postData: Option[Array[Byte]],
      headers: js.UndefOr[js.Dictionary[String]] = js.undefined
  ): Unit = {
    val params = JsBridge.obj("requestId" -> (requestId))
    postData.foreach { bytes =>
      // 必须用 `postData`（base64 字符串）：Chrome 会静默忽略 `postDataEntries`，请求体原样发出（实测 httpbin 回显仍是原文）。
      JsBridge.update(params, "postData", JsBridge.asAny(base64Of(bytes)))
    }
    headers.toOption.filter(_.nonEmpty).foreach { map =>
      // headers 是整份覆盖语义：剔除伪头（`:method` 之类）与 Content-Length（正文变了要由 Chrome 重算），其余原样回传。
      val list = map.toList
        .filterNot { case (name, _) => name.startsWith(":") || name.equalsIgnoreCase("content-length") }
        .map { case (name, value) => JsBridge.obj("name" -> (name), "value" -> (value)) }
      JsBridge.update(params, "headers", JsBridge.asAny(js.Array(list*)))
    }
    cdp(tabId, "Fetch.continueRequest", params).foreach(_ => ())
  }

  /** 暂停请求的当前请求头（`{name: value}`）；拿不到就返回空表。 */
  def requestHeadersOf(request: FetchPausedRequest): js.Dictionary[String] =
    request.headers
      .toOption
      .getOrElse(js.Dictionary.empty[String])
  private def fetchDisable(tabId: Int): Future[Unit]                       =
    cdp(tabId, "Fetch.disable").map(_ => ()).recover { case _ => () }
  private def cdp(tabId: Int, method: String, params: js.UndefOr[js.Object] = js.undefined): Future[js.Any] =
    // 走租约管理器：连接已经被外部抢走时它会顺手纠正状态并触发重挂，而不是在这里静默失败。
    CdpSession.command(tabId, "rewrite", method, params)
}
