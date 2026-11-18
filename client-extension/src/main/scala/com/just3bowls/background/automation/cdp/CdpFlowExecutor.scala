package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge
import com.just3bowls.background.bootstrap.*
import com.just3bowls.background.runtime.*
import com.just3bowls.background.automation.provider.*
import com.just3bowls.background.webchat.*

import com.just3bowls.model.localapi.Msg
import com.just3bowls.model.chat.{RunPhase, RunProgress}
import com.just3bowls.model.provider.PromptAttachment
import com.just3bowls.model.provider.ProviderRequestRule
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.diagnostics.TraceStore
import com.just3bowls.facade.{CdpCommandResults, ChromeAPI, DebuggerSession}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.collection.mutable
import scala.scalajs.js
private[background] object CdpFlowExecutor {
  private def command(
      target: DebuggerSession,
      method: String,
      params: js.UndefOr[js.Object]
  ): Future[js.Object] =
    ChromeAPI.debugger
      .sendCommand(target, method, params)
      .toFuture
      .map(raw => CdpCommandResults.commandResult(raw.toOption.getOrElse(JsBridge.obj())))

  private def evaluateExpression(target: DebuggerSession, expression: String): Future[js.Any] =
    command(
      target,
      "Runtime.evaluate",
      JsBridge.obj("expression" -> (expression), "returnByValue" -> (true))
    ).map(CdpCommandResults.evaluateValue)

  private[background] def executeCDPFlow(
      provider: String,
      tabId: Int,
      prompt: String,
      inputXPath: String,
      generationId: String,
      onRawChunk: CdpRawChunk => Unit = _ => (),
      onSendStatus: String => Unit = _ => (),
      onInputStatus: String => Unit = _ => (),
      onTypeStatus: (Boolean, String) => Unit = (_, _) => (),
      onResultStatus: (Boolean, String) => Unit = (_, _) => (),
      onProgress: RunProgress => Unit = _ => (),
      apiPatterns: Option[List[String]] = None,
      onRequest: (String, String, String) => Unit = (_, _, _) => (),
      onCandidate: (String, String, String) => Unit = (_, _, _) => (),
      onCandidateResponse: (String, String, String, String) => Unit = (_, _, _, _) => (),
      onDiscoveredApi: String => Unit = _ => (),
      captureMode: Boolean = false,
      findTimeoutMs: Int = 10000,
      retryIntervalMs: Int = 500,
      /** 定义了就把 `prompt` 当"输入框里显示的文本"，真正发给模型的模版由请求体改写注入。 */
      injectTemplate: Option[String] = None,
      injectRule: String = "",
      /** 回车之后最多等多久"这次请求出现"（捕获会话命中 apiPattern）；provider 的发送管线可能要好几秒。 */
      sendWaitMs: Int = 30000,
      /** 请求确认发出后回调：用来推进"这一轮投递到哪里"的上下文水位线（模板是打进去的还是注入的都算）。 */
      onDelivered: () => Unit = () => (),
      onInjectFailed: String => Unit = _ => (),
      /** `file` 模式：先把这份 body 造成附件上传，再打 `prompt`（上传失败即整轮失败，不回退）。 */
      attachment: Option[PromptAttachment] = None,
      /** provider 配置里的附件投递方式：`input` / `paste` / `drop`（空 = 没配）。 */
      attachmentStrategy: String = "",
      /** 上面那条策略的目标选择器（`input` 可留空）。 */
      attachmentSelector: String = "",
      /** provider 配置里声明的**本轮补充来源**（如异步快照轮询的 URL 片段）；主响应没判定完成时继续跟随。 */
      followUpPatterns: List[String] = Nil,
      /** 补充请求与主请求的"同轮身份"路径（如 `$.req_id`）；空 = 只按 URL 匹配。 */
      followUpIdentityPath: String = "",
      /** 跟随窗口硬上限（毫秒）。 */
      followUpWindowMs: Int = 180000
  ): js.Promise[js.Object] =
    new js.Promise[js.Object]((resolve, _) => {
      val log                  = PopCanLog.withContext(
        "cdp",
        "gen"      -> generationId,
        "provider" -> provider,
        "tab"      -> tabId.toString
      )
      val captureLog           = PopCanLog.withContext(
        "capture",
        "gen"      -> generationId,
        "provider" -> provider,
        "tab"      -> tabId.toString
      )
      val target               = CdpTargets.tabTarget(tabId)
      // 这一轮持一份 CDP 租约（owner 和取消路径一致）；同 tab 的其它 owner 会合流，不会互相 detach。
      val leaseOwner           = s"task:$generationId"
      var lease                = Option.empty[CdpLease]
      var pageActivity         = Option.empty[CdpPageActivityLease]
      val defaultApiPatterns   = Nil
      val effectiveApiPatterns = apiPatterns.getOrElse(defaultApiPatterns)

      /** 页面忙（输入框被锁）时最多等多久。 */
      val BusyWaitMs = 15000

      /** 回车没打出请求时，隔多久再按一次；最多按几次（含第一次）。 */
      val PressRetryGapMs = 3000
      val MaxEnterPresses = 3
      log.info(s"apiPatterns=${effectiveApiPatterns.mkString(",")} captureMode=$captureMode")

      /** 捕获会话命中的那个请求（这一轮回答就是从它来的）；判断"改写有没有落到它身上"就靠它。 */
      var matchedUrl: String                                                     = ""
      val session: CdpResponseSession                                            =
        new CdpStreamingSession(
          tabId,
          effectiveApiPatterns,
          generationId,
          onRawChunk,
          provider,
          onRequest = (url, method, postData) => {
            if (matchedUrl.isEmpty) matchedUrl = url
            onRequest(url, method, postData)
          },
          onCandidate = onCandidate,
          onCandidateResponse = onCandidateResponse,
          discoveryPrompt = Some(prompt),
          onDiscoveredApi = onDiscoveredApi,
          captureMode = captureMode,
          followUpPatterns = followUpPatterns,
          followUpIdentityPath = followUpIdentityPath,
          followUpWindowMs = followUpWindowMs,
          isTurnComplete = () =>
            BackgroundState.localRawParsers.get(generationId).flatten.exists(_.hasFinishSignal)
        )
      def cdp(method: String, params: js.UndefOr[js.Object]): Future[js.Object]  =
        CdpFlowExecutor.command(target, method, params)
      def evaluate(expr: String): Future[js.Any]                                 =
        CdpFlowExecutor.evaluateExpression(target, expr)
      def pageStateExpr: String                                                  =
        s"""
          |(function(){
          |  return JSON.stringify({
          |    visibility: document.visibilityState,
          |    hasFocus: document.hasFocus(),
          |    ready: document.readyState,
          |    url: location.href
          |  });
          |})()
          |""".stripMargin
      def logPageState(): Future[Unit]                                           =
        evaluate(pageStateExpr)
          .map(state => log.debug(s"page state: ${PopCanLog.short(state.toString, 300)}"))
          .recover { case e => log.debug(s"page state unavailable: ${e.getMessage}") }
      def delayUnit(ms: Int): Future[Unit]                                       = {
        val p = Promise[Unit]()
        js.timers.setTimeout(ms)(p.success(()))
        p.future
      }
      def retryStatus(expr: String, accepted: String => Boolean): Future[String] = {
        val deadline                  = js.Date.now() + findTimeoutMs
        var lastLoggedStatus          = ""
        def attempt(): Future[String] =
          if (BackgroundState.cancelledCdpTasks.contains(generationId)) {
            Future.failed(new Exception("Test canceled"))
          } else {
            captureLog.debug(s"retry expression=${PopCanLog.short(expr, 120)}")
            evaluate(expr)
              .map(_.toString)
              .flatMap { status =>
                if (status != lastLoggedStatus) {
                  log.debug(s"retry status=$status expression=${PopCanLog.short(expr, 100)}")
                  lastLoggedStatus = status
                }
                if (accepted(status) || js.Date.now() >= deadline) Future.successful(status)
                else {
                  val p = Promise[Unit]()
                  js.timers.setTimeout(retryIntervalMs)(p.success(()))
                  p.future.flatMap(_ => attempt())
                }
              }
          }
        attempt()
      }
      def inputTargetExpr: String = CdpFlowExpressions.safePoint("input", "")

      /** 输入框的"触发了"信号每次 run 只发一次（重试/轮询不该反复闪）。 */
      var inputSignalSent = false

      /** 页面上还留着我们打进去的那段文本吗（只看输入框自身的值，不读/改别的状态）。 */
      def inputTextExpr: String =
        """
          |(function(){
          |  var el = document.querySelector('[data-th-role="input"]');
          |  if (!el) return '';
          |  if (typeof el.value === 'string') return el.value;
          |  return el.innerText || el.textContent || '';
          |})()
          |""".stripMargin

      /** 输入框是不是"现在就能发"：可见 + 没被禁用/只读（页面在生成时通常会把输入框锁上）。 */
      def inputUsableExpr: String =
        """
          |(function(){
          |  var el = document.querySelector('[data-th-role="input"]');
          |  if (!el) return 'notfound';
          |  var tag = (el.tagName || '').toLowerCase();
          |  if (el.disabled) return 'disabled';
          |  if (el.getAttribute && el.getAttribute('aria-disabled') === 'true') return 'disabled';
          |  if ((tag === 'textarea' || tag === 'input') && el.readOnly) return 'readonly';
          |  if (tag !== 'textarea' && tag !== 'input' && el.getAttribute && el.getAttribute('contenteditable') === 'false')
          |    return 'readonly';
          |  var rect = el.getBoundingClientRect();
          |  if (rect.width <= 0 || rect.height <= 0) return 'hidden';
          |  return 'usable';
          |})()
          |""".stripMargin

      /** 焦点是不是真在输入框里（contenteditable 的 editing host 命中即可）。 */
      def inputFocusExpr: String =
        """
          |(function(){
          |  var el = document.querySelector('[data-th-role="input"]');
          |  if (!el) return 'notfound';
          |  var a = document.activeElement;
          |  if (!a || a === document.body) return 'nofocus';
          |  return (a === el || el.contains(a)) ? 'focused' : ('elsewhere:' + (a.tagName || ''));
          |})()
          |""".stripMargin

      /** 只靠 JS focus 兜底：可信点击落不上时的最后手段（实测 kimi 的 Lexical 上 focus 后回车有效）。 */
      def focusInputJsExpr: String =
        """
          |(function(){
          |  var el = document.querySelector('[data-th-role="input"]');
          |  if (!el || !el.focus) return false;
          |  try { el.focus(); } catch (e) {}
          |  var a = document.activeElement;
          |  return !!(a && (a === el || el.contains(a)));
          |})()
          |""".stripMargin

      /** 失败时要写进 trace 的页面状态（纯只读：URL / 输入框字符数 / 输入框可用性 / 有没有"停止生成"按钮）。 */
      def deliveryStateExpr: String =
        """
          |(function(){
          |  var el = document.querySelector('[data-th-role="input"]');
          |  var text = '';
          |  if (el) text = (typeof el.value === 'string') ? el.value : (el.innerText || el.textContent || '');
          |  var tag = el ? (el.tagName || '').toLowerCase() : '';
          |  var usable = !!el && !el.disabled && el.getAttribute('aria-disabled') !== 'true' &&
          |    !((tag === 'textarea' || tag === 'input') && el.readOnly);
          |  var active = document.activeElement;
          |  var activeIsInput = !!el && !!active && (active === el || el.contains(active));
          |  var rect = el ? el.getBoundingClientRect() : null;
          |  var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
          |  var onScreen = !!rect && rect.right > 0 && rect.bottom > 0 && rect.left < vw && rect.top < vh;
          |  var stop = 0;
          |  var nodes = document.querySelectorAll('button,[role="button"],[aria-label]');
          |  for (var i = 0; i < nodes.length; i++) {
          |    var label = (nodes[i].getAttribute('aria-label') || nodes[i].getAttribute('title') ||
          |      nodes[i].innerText || '').trim();
          |    if (/^(stop|停止|stop generating)/i.test(label)) stop++;
          |  }
          |  return JSON.stringify({
          |    url: location.href,
          |    inputChars: text.length,
          |    inputUsable: usable,
          |    activeIsInput: activeIsInput,
          |    inputOnScreen: onScreen,
          |    inputRect: rect ? {t: Math.round(rect.top), l: Math.round(rect.left),
          |      w: Math.round(rect.width), h: Math.round(rect.height), vw: vw, vh: vh} : null,
          |    stopButtons: stop,
          |    title: (document.title || '').slice(0, 60)
          |  });
          |})()
          |""".stripMargin

      /** 页面在生成中（输入框被锁）时先等它空闲；最多等 `BusyWaitMs`，超时以明确文案失败。 */
      def waitUntilInputUsable(): Future[Unit] = {
        val deadline                = js.Date.now() + BusyWaitMs
        def attempt(): Future[Unit] =
          evaluate(inputUsableExpr)
            .map(_.toString)
            .flatMap { status =>
              if (status == "usable") Future.successful(())
              // 还没有输入框（位置脚本没注入 / 页面在换文档）：不当作"忙"，交给后面的 focus 逻辑去报明确错误。
              else if (status == "notfound") Future.successful(())
              else if (js.Date.now() >= deadline)
                Future.failed(new Exception(
                  s"Input is not usable ($status): the page looks busy (still generating?). Waited ${BusyWaitMs}ms"
                ))
              else {
                captureLog.debug(s"input not usable yet status=$status; waiting")
                delayUnit(250).flatMap(_ => attempt())
              }
            }
        attempt()
      }
      def focusInputViaCdp(): Future[String]   =
        retryStatus(inputTargetExpr, raw => CdpCommandResults.inputTargetProbe(raw).status == "found")
          .flatMap { raw =>
            val target = CdpCommandResults.inputTargetProbe(raw)
            if (target.status != "found") {
              Future.successful(target.status)
            } else {
              cdp(
                "Input.dispatchMouseEvent",
                JsBridge.obj("type" -> ("mouseMoved"), "x" -> (target.x), "y" -> (target.y))
              ).flatMap { _ =>
                cdp(
                  "Input.dispatchMouseEvent",
                  JsBridge.obj(
                    "type"       -> ("mousePressed"),
                    "x"          -> (target.x),
                    "y"          -> (target.y),
                    "button"     -> ("left"),
                    "clickCount" -> (1)
                  )
                ).flatMap { _ =>
                  cdp(
                    "Input.dispatchMouseEvent",
                    JsBridge.obj(
                      "type"       -> ("mouseReleased"),
                      "x"          -> (target.x),
                      "y"          -> (target.y),
                      "button"     -> ("left"),
                      "clickCount" -> (1)
                    )
                  ).map { _ =>
                    captureLog.debug(s"CDP mouse clicked input at x=${target.x} y=${target.y}")
                    if (!inputSignalSent) {
                      inputSignalSent = true
                      LocationSignals.trigger(tabId, "input")
                    }
                    "found"
                  }
                }
              }
            }
          }
      def inputFocusStatus(): Future[String]   =
        evaluate(inputFocusExpr).map(_.toString).recover { case _ => "unknown" }
      def focusInputViaJs(): Future[Boolean]   =
        evaluate(focusInputJsExpr).map(_.toString == "true").recover { case _ => false }

      /**
       * 回车前必须确认焦点真的在输入框里：附件投递会重渲染 composer、取点也可能落空 （旧取点会点到视口外，见 `safePointExpr` 的注释）。顺序：点击（新算法）→ 再点一次 → JS focus →
       * 可读错误。
       */
      def ensureInputFocused(context: String): Future[Unit] = {
        def report(outcome: String, was: String): Unit             =
          TraceStore.noteEvidence(generationId, s"$context focus=$outcome (was $was)")
        def fail(was: String): Future[Unit]                        =
          report("failed", was)
          Future.failed(new Exception(
            s"Could not focus the input ($context): the click point was off-screen or covered, " +
              s"or the page keeps stealing focus (activeElement=$was)"
          ))
        def clickOnce(was: String, label: String): Future[Boolean] =
          focusInputViaCdp()
            .flatMap(_ => inputFocusStatus().map { case "focused" => report(label, was); true; case _ => false })
            .recover { case _ => false }
        inputFocusStatus().flatMap {
          case "focused" => Future.successful(())
          case was       =>
            clickOnce(was, "clicked").flatMap {
              case true  => Future.successful(())
              case false =>
                // 再点一次：页面可能刚重渲染完，这一下才落到真正的输入框上。
                clickOnce(was, "clicked-retry").flatMap {
                  case true  => Future.successful(())
                  case false =>
                    focusInputViaJs().flatMap {
                      case true  => report("js-focus", was); Future.successful(())
                      case false => fail(was)
                    }
                }
            }
        }
      }
      def pressEnterViaCdp(): Future[Unit]                  =
        cdp(
          "Input.dispatchKeyEvent",
          JsBridge.obj(
            "type"                  -> ("rawKeyDown"),
            "key"                   -> ("Enter"),
            "code"                  -> ("Enter"),
            "windowsVirtualKeyCode" -> (13),
            "nativeVirtualKeyCode"  -> (13)
          )
        ).flatMap { _ =>
          cdp(
            "Input.dispatchKeyEvent",
            JsBridge.obj(
              "type"                  -> ("keyUp"),
              "key"                   -> ("Enter"),
              "code"                  -> ("Enter"),
              "windowsVirtualKeyCode" -> (13),
              "nativeVirtualKeyCode"  -> (13)
            )
          ).map { _ =>
            captureLog.debug("CDP Enter dispatched")
            ()
          }
        }
      // ---------------------------------------------------------------- 投递（事件驱动）
      //
      // 这一轮成功的定义：**产生这轮回答的那个请求，被我们改写过了**。判定只看事件，不看"此刻注入有没有生效"：
      //   - Fetch 事件：命中且写成功的请求 → rewrittenUrls（连同拦截前正文记进 trace）
      //   - 捕获会话：命中 apiPattern 的请求 → matchedUrl；流结束 → 拿到回答
      //   回答到手时再做判定：matchedUrl 在 rewrittenUrls 里 → 成功；不在 → 模型看到的是输入框里的显示文本 → 按证据报错。
      // 顺序上的事实：`Network.requestWillBeSent` 会**先于** Fetch 的暂停/continue 到达，所以"信号一回来就查改写有没有发生"必然偶发误判 —— 这里不再做这种判断。
      var injectError                                       = Option.empty[String]

      /** rule 读不出/写不进时的诊断（最后一次），用来给最终失败一个可读原因。 */
      var passReason                           = ""
      val rewrittenUrls                        = mutable.ArrayBuffer.empty[String]
      val injectOwner                          = s"task:$generationId"
      def failInjection(message: String): Unit = {
        if (injectError.isEmpty) injectError = Some(message)
        onInjectFailed(message)
      }

      /** 回车前挂上改写计划；一轮内持续改写（oneShot=false），收工交给这一轮结束时的 cleanup。 */
      def armInjection(): Future[Unit] =
        injectTemplate match {
          case None           => Future.successful(())
          case Some(template) =>
            val plan = RewritePlan(
              owner = injectOwner,
              provider = provider,
              patterns = effectiveApiPatterns,
              oneShot = false,
              decide = (body, headers) =>
                Future.successful {
                  if (body.isEmpty) RewriteDecision.Pass("task-bodyless", keepArmed = true)
                  else
                    ProviderRequestRule.writeUserText(injectRule, body, template) match {
                      case Some(rewritten) =>
                        // 头里嵌着正文的 provider（文心 X-Chat-Message）要连头一起改，否则"头里说 A、正文是 B"必被服务端拒。
                        if (!ProviderRequestRule.hasHeaderDerives(injectRule)) RewriteDecision.Write(rewritten)
                        else
                          ProviderRequestRule.rewriteHeaders(injectRule, headers, template) match {
                            case Right(patched) => RewriteDecision.Write(rewritten, patched)
                            case Left(reason)   =>
                              passReason = s"header derive failed: $reason"
                              RewriteDecision.Pass(s"task-unwritable: $passReason", keepArmed = true)
                          }
                      case None            =>
                        // 改了要改的那个请求却写不进去：原样放行，等这一轮结束时按"匹配到的请求没被改写"报错。
                        passReason = ProviderRequestRule.diagnose(injectRule, body)
                        RewriteDecision.Pass(s"task-unwritable: $passReason", keepArmed = true)
                    }
                },
              onError = message => failInjection(message),
              onRewritten = (url, originalBytes) => {
                rewrittenUrls += url
                TraceStore.noteEvidence(
                  generationId,
                  s"rewritten url=${PopCanLog.short(url, 90)} originalBytes=$originalBytes"
                )
              },
              timeoutMs = 0
            )
            captureLog.debug(s"arming template injection templateChars=${template.length}")
            RequestRewrite
              .arm(tabId, plan)
              .recover { case e =>
                failInjection(TemplateInjection.failureText(s"Fetch.enable failed: ${e.getMessage}"))
                ()
              }
        }

      /** 回车，然后等"这次请求出现"（命中 apiPattern 的请求被捕获会话看到）。不出现就报没观察到。 */
      def pressEnterAndWaitForRequest(): Future[Unit] = {
        val deadline          = js.Date.now() + sendWaitMs
        var presses           = 0
        var nextRetry         = js.Date.now() + PressRetryGapMs
        var textGone          = false
        var lastPrePressFocus = "unknown"

        /** 失败前把"此刻页面是什么样"写进证据：URL / 输入框字符数 / 是否可用 / 有没有停止生成按钮。 */
        def collectDeliveryEvidence(): Future[Unit] =
          evaluate(deliveryStateExpr)
            .map(_.toString)
            .map(state => TraceStore.noteEvidence(generationId, s"page state at failure: $state"))
            .recover { case _ => () }
        def failure(reason: String): Future[Unit]   =
          collectDeliveryEvidence().flatMap(_ => Future.failed(new Exception(reason)))
        def press(reason: String): Future[Unit]     =
          if (reason.nonEmpty) TraceStore.noteEvidence(generationId, reason)
          presses += 1
          val attemptNo = presses
          inputFocusStatus()
            .flatMap { status =>
              lastPrePressFocus = status
              TraceStore.noteEvidence(generationId, s"pre-press #$attemptNo focus=$status")
              val ready =
                if (status == "focused") Future.successful(()) else ensureInputFocused(s"pre-press #$attemptNo")
              ready
                // 焦点拿不回来就别盲按三次：把"这一刻页面什么样"写进证据后按可读错误失败。
                .recoverWith { case e => collectDeliveryEvidence().flatMap(_ => Future.failed(e)) }
            }
            .flatMap { _ =>
              inputFocusStatus().map { status =>
                lastPrePressFocus = status; ()
              }.recover { case _ => () }
            }
            .flatMap(_ => pressEnterViaCdp())
            .flatMap(_ => delayUnit(100))
            .flatMap(_ => attempt())
        def attempt(): Future[Unit]                 =
          if (matchedUrl.nonEmpty) Future.successful(())
          else if (injectError.nonEmpty) Future.failed(new Exception(injectError.get))
          else if (js.Date.now() >= deadline)
            failure(
              if (textGone)
                s"Enter did not trigger a request: the input no longer holds the prompt after $presses press(es) — " +
                  "the request may have been sent without matching the apiPattern (rule may be outdated, or a WS provider)"
              else
                s"Enter did not trigger a request after $presses press(es): the input still holds the prompt — " +
                  s"the page ignored the synthetic Enter (last pre-press focus=$lastPrePressFocus)"
            )
          else if (presses < MaxEnterPresses && !textGone && js.Date.now() >= nextRetry)
            // 到点了还没请求：先看看输入框里我们打的字还在不在 —— 在说明页面压根没认这次回车，可以再按一次。
            evaluate(inputTextExpr)
              .map(_.toString)
              .map(text => text.contains(prompt.take(40)))
              .recover { case _ => false }
              .flatMap { stillHas =>
                if (!stillHas) {
                  textGone = true
                  TraceStore.noteEvidence(generationId, "input no longer holds the prompt; stop re-pressing Enter")
                  attempt()
                } else {
                  // 只在输入框"现在还能打字"时才重按：页面在生成时会把输入框锁上，那时请求多半已经发出、
                  // 只是没被 apiPattern 命中 —— 在生成中再敲回车有重复发送的风险。
                  evaluate(inputUsableExpr)
                    .map(_.toString)
                    .recover { case _ => "usable" }
                    .flatMap { status =>
                      if (status != "usable") {
                        nextRetry = js.Date.now() + PressRetryGapMs
                        TraceStore.noteEvidence(
                          generationId,
                          s"input is $status while waiting for the request; holding Enter"
                        )
                        attempt()
                      } else {
                        nextRetry = js.Date.now() + PressRetryGapMs
                        press(s"send retry #${presses + 1} (input still holds ${prompt.length} chars)")
                      }
                    }
                }
              }
          else delayUnit(100).flatMap(_ => attempt())
        press("")
      }

      /**
       * 回答到手后的证据判定：注入模式下，产生这轮回答的请求必须被改写。
       *
       * 只有这一条路径会判"拦截没生效"（而不是在收到网络信号的那一刻猜），所以不会再有先后顺序上的竞态。
       */
      def classifyDelivery(): Unit =
        injectTemplate.foreach { _ =>
          val rewritten = rewrittenUrls.exists(url => sameRequest(url, matchedUrl))
          if (!rewritten) {
            val reason = if (passReason.nonEmpty) s"request.userText did not match: $passReason"
            else "the request was dispatched without interception"
            failInjection(TemplateInjection.failureText(reason))
            throw new Exception(TemplateInjection.failureText(reason))
          }
        }

      /** 保守的请求同一性比较：去掉 query/hash 与结尾斜杠。 */
      def sameRequest(left: String, right: String): Boolean = {
        val a = com.just3bowls.api.provider.PageIdentity.base(left)
        val b = com.just3bowls.api.provider.PageIdentity.base(right)
        a.nonEmpty && b.nonEmpty && a == b
      }
      def releasePageActivity(reason: String): Future[Unit] = {
        val current = pageActivity
        pageActivity = None
        current.map(_.release(reason)).getOrElse(Future.successful(()))
      }
      def cleanup(): Future[Unit]                           = {
        // 留一份 disarm 兜底：任何退出路径都不能把页面的请求留在 Fetch 暂停态。
        RequestRewrite.disarm(tabId, injectOwner, "cleanup").foreach(_ => ())
        CdpSession.unregister(tabId, leaseOwner)
        // 页面活动模拟必须先撤，再释放 CDP 租约；否则最后一个租约 detach 后就没有命令通道可收尾。
        releasePageActivity("flow done")
          .recover { case e =>
            log.warn(s"page activity restore failed: ${e.getMessage}")
            ()
          }
          .flatMap { _ =>
            val current = lease
            lease = None
            current.map(_.release("flow done")).getOrElse(Future.successful(()))
          }
          .map { _ =>
            // 任务把这个 tab 交还回去：网页原生观察器按当前归属重新武装劫持窗口（任务期间它刻意不抢 `task:` 的计划）。
            WebChatService.onTaskReleased(tabId)
            ()
          }
      }
      var waitingForResponse                                = false
      log.info("waiting for tab lock")
      ServerTabManager.withTabLock(tabId) {
        log.debug("attaching to tab")
        PhaseTiming
          .timedFuture(generationId, "attach") {
            CdpSession
              .acquire(tabId, leaseOwner)
              .flatMap { acquired =>
                lease = Some(acquired)
                CdpSession.register(tabId, leaseOwner, session)
                log.debug("attached via lease (Runtime + Network enabled by CdpSession)")
                Future.successful(())
              }
          }
          .flatMap { _ =>
            log.debug("preparing hidden page activity")
            CdpSession.acquirePageActivity(tabId, leaseOwner).map { acquired =>
              pageActivity = Some(acquired)
              JsBridge.obj()
            }
          }
          .flatMap(_ => logPageState())
          // 页面控制面可能刚经历导航 / 扩展重载；在碰选择器之前先确保 marker runtime 真的活着。
          .flatMap { _ =>
            onProgress(RunProgress(RunPhase.PreparingPage, provider, detail = "input"))
            PageMarkers.ensure(tabId, provider, List("input"))
          }
          // 页面在生成中时输入框通常被锁：先等它空闲，别把文本打进去然后干等回车（那种状态回车必然没反应）。
          .flatMap { _ =>
            onProgress(RunProgress(RunPhase.WaitingForInput, provider))
            waitUntilInputUsable()
          }
          // Slate（qianwen）这类编辑器会检查页面可见性；HiddenPageLease 已统一处理 shim、focus 和 lifecycle。
          .flatMap(_ => new CdpInputActions(evaluate, cdp, delayMarker).ensureInteractive(tabId))
          .flatMap { _ =>
            log.debug("focusing input element")
            onProgress(RunProgress(RunPhase.PreparingPrompt, provider))
            PhaseTiming.timedFuture(generationId, "focusInput")(focusInputViaCdp())
          }
          .flatMap { focusResult =>
            val focusStatus = focusResult.toString
            log.info(s"input focus status=$focusStatus")
            onInputStatus(focusStatus)
            if (focusStatus != "found") {
              throw new Exception(s"Input element not found: $inputXPath")
            }
            log.info(s"inserting prompt promptChars=${prompt.length}")
            PhaseTiming
              .timedFuture(generationId, "type")(new CdpInputActions(evaluate, cdp, delayMarker).fill(prompt))
              .map { actual =>
                captureLog.debug(s"prompt filled actualChars=${actual.length}")
                onTypeStatus(true, actual)
                JsBridge.obj()
              }
              .recover { case e =>
                onTypeStatus(false, e.getMessage)
                throw e
              }
          }
          // file 模式：文字**先**填（此时 composer 还是"干净"状态，页面才认程序化写入），之后再把 request body 作为附件交出去。
          // 反过来的顺序会踩坑：附件一挂上，qianwen 这类编辑器的内部 model 就不再采纳我们写进 DOM 的文字 —— 结果既不发送，
          // 用户也删不掉（实测）。
          .flatMap { _ =>
            attachment match {
              case None         => Future.successful(())
              case Some(attach) =>
                onProgress(RunProgress(RunPhase.AttachingFile, provider, detail = attach.name))
                PhaseTiming.timedFuture(generationId, "attachFile")(
                  FileAttachment
                    .attach(
                      evaluate,
                      delayUnit,
                      provider,
                      attach.name,
                      attach.content,
                      attachmentStrategy,
                      attachmentSelector
                    )
                    .map { tag =>
                      TraceStore.noteEvidence(
                        generationId,
                        s"attach=$tag name=${attach.name} bytes=${attach.content.length}"
                      )
                      if (tag == "none")
                        throw new Exception(
                          s"File prompt mode: $provider did not accept the attachment (${attach.name})"
                        )
                      ()
                    }
                )
            }
          }
          // 附件投递可能把 composer 重渲染 / 把焦点抢走，回车就会落空：回车前重新聚焦一次；标记没了就先让内容脚本重打。
          .flatMap { _ =>
            if (attachment.isEmpty) Future.successful(())
            else {
              // 附件投递可能把 composer 的节点换掉（标记也会跟着丢）：先确认标记还在，不在就让内容脚本重打。
              def ensureMarker(attempt: Int): Future[Unit] =
                evaluate(inputTargetExpr)
                  .map(raw =>
                    JsBridge
                      .stringField(JsBridge.asObject(js.JSON.parse(raw.toString)), "status")
                      .getOrElse("notfound")
                  )
                  .recover { case _ => "unknown" }
                  .flatMap {
                    case "notfound" | "unknown" if attempt < 2 =>
                      reapplyMarker(tabId, "input", inputXPath, null)
                        .flatMap(_ => delayUnit(300))
                        .flatMap(_ => ensureMarker(attempt + 1))
                    case _                                     => Future.successful(())
                  }
              ensureMarker(0).flatMap(_ => ensureInputFocused("post-attach"))
            }
          }
          .flatMap { _ =>
            // 回车前不再盲等 800ms：等"输入框确实装着本轮文本、仍然聚焦、且没有被写重"（`inputStateExpr` 的 ok）。
            // 最短 150ms 给页面事务一个缓冲，最多 2s；**超时也放行** —— 后面还有"按到有请求为止"（≤3 次重按）与请求观测兜底。
            PhaseTiming
              .timedFuture(generationId, "preSend")(
                Wait
                  .until("preSend", intervalMs = 100, timeoutMs = 2000, minDelayMs = 150) { () =>
                    evaluate(CdpFlowExpressions.inputState(prompt))
                      .map(raw =>
                        JsBridge
                          .booleanField(JsBridge.asObject(js.JSON.parse(raw.toString)), "ok")
                          .getOrElse(false)
                      )
                      .recover { case _ => false }
                  }
                  .map { outcome =>
                    captureLog.debug(s"preSend condition ok=${outcome.ok} waited=${outcome.waitedMs.toInt}ms")
                    if (!outcome.ok)
                      TraceStore.noteEvidence(
                        generationId,
                        "preSend: input not confirmed within 2000ms; proceeding to Enter"
                      )
                    ()
                  }
              )
          }
          // 注入必须在回车之前挂上：请求一发出就被 Fetch 暂停，改完正文再放行。
          .flatMap { _ =>
            onProgress(RunProgress(RunPhase.SendingPrompt, provider))
            PhaseTiming.timedFuture(generationId, "send")(armInjection().flatMap(_ => pressEnterAndWaitForRequest()))
          }
          .flatMap { _ =>
            captureLog.info("send confirmed by observed request")
            logPageState()
              .flatMap(_ =>
                releasePageActivity("request sent").recover { case e =>
                  log.warn(s"page activity restore after send failed: ${e.getMessage}")
                  ()
                }
              )
              .map { _ =>
                onSendStatus("sent")
                onProgress(RunProgress(RunPhase.WaitingForResponse, provider))
                waitingForResponse = true
                captureLog.info("waiting for network response")
                PhaseTiming.timedFuture(generationId, "stream")(session.waitForResponse())
              }
              .flatten
          }
          .map { parsed =>
            log.info(s"parsed response contentChars=${parsed.content.length} thinkingChars=${parsed.thinking.length}")
            // 通用收口（与 provider 无关）：规则判定"这一轮结束了"却一个字都没解析出来 → 按失败收口。
            // 注意正文不在 `parsed` 里（普通会话的 payload 早已随 NORMALIZED_CHUNK 送出，`ParsedPrompt` 恒为空），
            // 口径只有一处：`BackgroundMain` 在终态分片上用真解析器判定，这里只读结论。
            BackgroundState.emptyTurnByTask.get(generationId).foreach(reason => throw new RuntimeException(reason))
            // 证据判定放在回答到手之后：此时改写/未改写的事件都已经到齐，不存在先后顺序上的竞态。
            classifyDelivery()
            onDelivered()
            cleanup()
            onResultStatus(true, "")
            Msg.promptResponse(
              true,
              parsed.content,
              parsed.thinking,
              parsed.totalTokens,
              parsed.finishReason
            )
          }
          .recover { case e =>
            log.error("CDP flow failed", e)
            // 失败时别把自己写进去的文字留在页面上：它在某些编辑器里"发不出去也删不掉"（qianwen 实测）。
            // best-effort：先清 composer，清完再走既有 cleanup（释放租约 / 摘掉改写计划）。
            TraceStore.noteEvidence(generationId, "cleanup: clearing composer after failure")
            new CdpInputActions(evaluate, cdp, delayMarker).clear(prompt).recover { case _ => () }.foreach(_ =>
              cleanup()
            )
            if (waitingForResponse) onResultStatus(false, e.getMessage)
            Msg.promptResponse(false, e.getMessage)
          }
      }.foreach(r => resolve(r))
    })

  private[background] def clickXPathViaCDP(
      tabId: Int,
      selector: String,
      role: String = "",
      provider: String = ""
  ): Future[Boolean] =
    ServerTabManager.withTabLock(tabId) {
      val target      = CdpTargets.tabTarget(tabId)
      var attached    = false
      var helperLease = Option.empty[CdpLease]
      def cdp(method: String, params: js.UndefOr[js.Object] = js.undefined): Future[js.Object] =
        CdpFlowExecutor.command(target, method, params)
      def cleanup(): Unit                                                                      =
        if (attached) {
          attached = false
          helperLease.foreach(_.release("helper done").foreach(_ => ()))
          helperLease = None
        }
      // 标记优先（它是用 fingerprint 持续校准过的），但**不是硬依赖**：标记还没打上时退回存的选择器。
      val expr        = CdpFlowExpressions.safePoint(role, selector)
      val markerReady =
        if (provider.nonEmpty && role.nonEmpty) PageMarkers.ensure(tabId, provider, List(role))
        else Future.successful(())
      markerReady
        .flatMap(_ => CdpSession.acquire(tabId, CdpSession.nextOwner("helper")))
        .flatMap { acquired =>
          helperLease = Some(acquired)
          attached = true
          cdp("Runtime.enable").flatMap { _ =>
            cdp(
              "Runtime.evaluate",
              JsBridge.obj("expression" -> (expr), "returnByValue" -> (true))
            ).map(result => CdpCommandResults.evaluateValue(result).toString)
          }
            .flatMap { raw =>
              val parsed = js.JSON.parse(raw)
              val status = JsBridge.undefinedString(parsed.status).getOrElse("notfound")
              if (status != "found") {
                PopCanLog.debug(
                  "tabs",
                  s"click preflight status=$status role=$role selector=${PopCanLog.short(selector)}"
                )
                Future.successful(false)
              } else {
                val x = JsBridge.undefinedDouble(parsed.x).getOrElse(0.0)
                val y = JsBridge.undefinedDouble(parsed.y).getOrElse(0.0)
                cdp(
                  "Input.dispatchMouseEvent",
                  JsBridge.obj("type" -> ("mouseMoved"), "x" -> (x), "y" -> (y))
                ).flatMap { _ =>
                  cdp(
                    "Input.dispatchMouseEvent",
                    JsBridge.obj(
                      "type"       -> ("mousePressed"),
                      "x"          -> (x),
                      "y"          -> (y),
                      "button"     -> ("left"),
                      "clickCount" -> (1)
                    )
                  ).flatMap { _ =>
                    cdp(
                      "Input.dispatchMouseEvent",
                      JsBridge.obj(
                        "type"       -> ("mouseReleased"),
                        "x"          -> (x),
                        "y"          -> (y),
                        "button"     -> ("left"),
                        "clickCount" -> (1)
                      )
                    ).map { _ =>
                      PopCanLog.debug(
                        "capture",
                        s"CDP mouse clicked selector=${PopCanLog.short(selector)} at x=$x y=$y"
                      )
                      if (role == "new-chat") LocationSignals.trigger(tabId, "new-chat")
                      true
                    }
                  }
                }
              }
            }
        }
        .map { ok =>
          cleanup()
          PopCanLog.debug("tabs", s"click XPath tab=$tabId ok=$ok sel=${PopCanLog.short(selector)}")
          ok
        }
        .recover { case e =>
          cleanup()
          PopCanLog.warn(
            "tabs",
            s"click XPath failed tab=$tabId sel=${PopCanLog.short(selector)} error=${e.getMessage}"
          )
          false
        }
    }
  private def delayMarker(ms: Int): Future[Unit]                                              = {
    val p = Promise[Unit]()
    js.timers.setTimeout(ms)(p.success(()))
    p.future
  }
  private def reapplyMarker(
      tabId: Int,
      role: String,
      selector: String,
      fingerprint: js.Any
  ): Future[Unit] =
    ChromeAPI.tabs.sendMessage(
      tabId,
      JsBridge.obj(
        "type"        -> ("LOCATION_APPLY"),
        "role"        -> (role),
        "selector"    -> (selector),
        "fingerprint" -> (if (fingerprint != null && !js.isUndefined(fingerprint)) fingerprint
                          else js.undefined)
      )
    ).toFuture.map(_ => ()).recover { case e =>
      PopCanLog.error("location", s"reapply marker failed tab=$tabId role=$role", e)
      ()
    }
}
