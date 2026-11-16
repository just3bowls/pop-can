package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge

import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.facade.{ChromeAPI, CdpCommandResults}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 一个 tab 的 CDP 挂载租约。
 *
 * 网页原生会话长期持有一份（owner `webchat:<tabId>`：用户随时可能发问，必须在请求发生前就挂着 `Network`），插件页任务 / 工具按需各持一份短租约； 同一个 tab 的多个 owner
 * 合流成**一次**真实 attach，谁先走都不影响别人。`release()` 幂等。
 */
final private[background] class CdpLease(val tabId: Int, val owner: String) {
  private[background] var released                   = false
  def release(reason: String = "done"): Future[Unit] =
    if (released) Future.successful(())
    else {
      released = true
      CdpSession.release(tabId, owner, reason)
    }
}

/**
 * 后台页面活动租约：不切真实 tab，让目标 renderer 按 visible/focused/active 页面运行。
 *
 * 必须用租约而不是裸命令：同一 provider tab 可能同时被任务、网页原生观察器和 browser tool 使用，最后一个 owner 走人时才能撤销模拟。
 */
final private[background] class CdpPageActivityLease(val tabId: Int, val owner: String) {
  private var released                               = false
  def release(reason: String = "done"): Future[Unit] =
    if (released) Future.successful(())
    else {
      released = true
      CdpSession.releasePageActivity(tabId, owner, reason)
    }
}

/**
 * 全扩展唯一的 CDP attach 真相。
 *
 *   - 租约：`acquire(tabId, owner)`；首个租约才真正 attach + `Runtime.enable` + `Network.enable`，最后一个 `release` 才 detach（并顺手
 *     `Fetch.disable`）。
 *   - 事件：按 owner 登记 `CdpResponseSession`，同一 tab 支持多个 owner，事件扇发（不再有"一 tab 一个会话、后写覆盖"）。
 *   - 意外 detach（用户在那个 tab 开 DevTools、Chrome 自己断）：标记未挂载、通知所有会话、只要还有租约就自动重挂（退避重试），失败则记错误供胶囊 / 任务报出。
 */
private[background] object CdpSession {
  final private class TabState(val tabId: Int) {
    val owners           = mutable.LinkedHashSet.empty[String]
    val sessions         = mutable.LinkedHashMap.empty[String, CdpResponseSession]
    val activityOwners   = mutable.LinkedHashMap.empty[String, Int]
    var activityEmulated = false
    var activityTail     = Future.successful(())
    var visibilityScript = Option.empty[String]
    var attached         = false
    var error            = ""
  }
  private val tabs = mutable.Map.empty[Int, TabState]
  private val tails                = mutable.Map.empty[Int, Future[Unit]]
  private var reattachHook         = (_: Int) => ()
  private var ownerSeq             = 0
  private val visibilityShimSource =
    """
      |(function(){
      |  var key = '__popCanVisibilityShim';
      |  if (window[key]) return 'already';
      |  var doc = document;
      |  var names = ['hidden', 'visibilityState', 'webkitHidden', 'webkitVisibilityState'];
      |  var saved = {};
      |  for (var i = 0; i < names.length; i++) saved[names[i]] = Object.getOwnPropertyDescriptor(doc, names[i]);
      |  function define(name, value) {
      |    try { Object.defineProperty(doc, name, { configurable: true, enumerable: true, get: function(){ return value; } }); } catch (e) {}
      |  }
      |  define('hidden', false);
      |  define('visibilityState', 'visible');
      |  define('webkitHidden', false);
      |  define('webkitVisibilityState', 'visible');
      |  window[key] = {
      |    restore: function() {
      |      for (var i = 0; i < names.length; i++) {
      |        var name = names[i];
      |        try { delete doc[name]; } catch (e) {}
      |        if (saved[name]) {
      |          try { Object.defineProperty(doc, name, saved[name]); } catch (e) {}
      |        }
      |      }
      |      try { delete window[key]; } catch (e) { window[key] = undefined; }
      |      try { doc.dispatchEvent(new Event('visibilitychange')); } catch (e) {}
      |    }
      |  };
      |  try { doc.dispatchEvent(new Event('visibilitychange')); } catch (e) {}
      |  try { window.dispatchEvent(new Event('focus')); } catch (e) {}
      |  return 'installed';
      |})()
      |""".stripMargin
  private val removeVisibilityShim =
    """
      |(function(){
      |  var shim = window.__popCanVisibilityShim;
      |  if (!shim) return 'absent';
      |  shim.restore();
      |  return 'restored';
      |})()
      |""".stripMargin

  /** 生成一个唯一的 owner id（helper / 一次性操作都用它，避免和别的租约撞 owner）。 */
  def nextOwner(prefix: String): String = {
    ownerSeq += 1
    s"$prefix#${js.Date.now()}-$ownerSeq"
  }

  /** 重挂成功后回调（网页原生用它重新解析归属 / 重新 arm 劫持窗口）。 */
  def setReattachHook(hook: Int => Unit): Unit = reattachHook = hook
  def isAttached(tabId: Int): Boolean          = tabs.get(tabId).exists(_.attached)

  /**
   * 连接真的还在吗（问浏览器，不信自己的缓存）。
   *
   * `chrome.debugger.detach()` 这类"自己摘"的路径不会触发 `onDetach`，缓存可能过期；调用方（网页原生的 refreshTab）用它兜底： 发现其实已经掉了就按意外 detach 处理（通知会话
   * + 有租约就重挂）。
   */
  def ensureHealthy(tabId: Int): Future[Unit] =
    tabs.get(tabId) match {
      case Some(state) if state.owners.nonEmpty =>
        ChromeAPI.debugger.getTargets().toFuture
          .map { targets =>
            val alive =
              targets.toList.exists(target => target.tabId.toOption.contains(tabId) && target.attached.getOrElse(false))
            if (!alive) onDetached(tabId, "not attached (verified)")
            ()
          }
          .recover { case _ => () }
      case _                                    => Future.successful(())
    }
  def lastError(tabId: Int): String           = tabs.get(tabId).map(_.error).getOrElse("")
  def owners(tabId: Int): Set[String]         = tabs.get(tabId).map(_.owners.toSet).getOrElse(Set.empty)

  /** 取一份租约；同 tab 已有连接就直接合流，不重复 attach。 */
  def acquire(tabId: Int, owner: String): Future[CdpLease] = {
    val state = tabs.getOrElseUpdate(tabId, new TabState(tabId))
    state.owners += owner
    val lease = new CdpLease(tabId, owner)
    if (state.attached) Future.successful(lease)
    else
      serialized(tabId) {
        if (state.attached) Future.successful(())
        else attach(tabId, state)
      }.map(_ => lease)
  }

  /** 释放 owner 的租约；最后一个 owner 走人时 detach（并清掉该 tab 的 Fetch 计划）。 */
  def release(tabId: Int, owner: String, reason: String): Future[Unit] =
    tabs.get(tabId) match {
      case None        => Future.successful(())
      case Some(state) =>
        state.owners -= owner
        // owner 走人时它的会话一并收掉（任务侧会先 `unregister` 再 release，所以不会误取消自己刚回过的那一轮）。
        state.sessions.remove(owner).foreach(_.cancel(s"owner released: $reason"))
        if (state.owners.nonEmpty) Future.successful(())
        else serialized(tabId)(detachNow(tabId, state, reason))
    }

  /**
   * 取一份后台页面活动租约。
   *
   * 首个 owner 打开 focus、lifecycle 和 visibility shim，其余 owner 只增加引用；命令按 tab 串行，避免并发 acquire 把“尚未打开”误判成“已经打开”。
   */
  def acquirePageActivity(tabId: Int, owner: String): Future[CdpPageActivityLease] = {
    val state = tabs.getOrElseUpdate(tabId, new TabState(tabId))
    state.activityOwners.update(owner, state.activityOwners.getOrElse(owner, 0) + 1)
    enqueueActivity(state) {
      if (state.activityOwners.getOrElse(owner, 0) <= 0)
        Future.failed(new Exception(s"Page activity owner released before activation: tab=$tabId owner=$owner"))
      else if (state.activityEmulated) Future.successful(())
      else {
        var focusEmulationEnabled = false
        var visibilityScript      = Option.empty[String]
        command(
          tabId,
          owner,
          "Emulation.setFocusEmulationEnabled",
          JsBridge.obj("enabled" -> (true))
        )
          .flatMap { _ =>
            focusEmulationEnabled = true
            command(
              tabId,
              owner,
              "Page.setWebLifecycleState",
              JsBridge.obj("state" -> ("active"))
            )
          }
          .flatMap { _ =>
            commandRaw(
              tabId,
              owner,
              "Page.addScriptToEvaluateOnNewDocument",
              JsBridge.obj("source" -> (visibilityShimSource))
            ).flatMap { result =>
              visibilityScript = CdpCommandResults.identifier(result)
              command(
                tabId,
                owner,
                "Runtime.evaluate",
                JsBridge.obj("expression" -> (visibilityShimSource), "returnByValue" -> (true))
              )
            }
          }
          .map { _ =>
            state.visibilityScript = visibilityScript
            state.activityEmulated = true
            PopCanLog.debug(
              "cdp",
              s"hidden page emulated tab=$tabId owner=$owner shim=${visibilityScript.getOrElse("none")}"
            )
            ()
          }
          .recoverWith { case e =>
            val restoreFocus =
              if (focusEmulationEnabled)
                command(
                  tabId,
                  owner,
                  "Emulation.setFocusEmulationEnabled",
                  JsBridge.obj("enabled" -> (false))
                ).map(_ => ())
              else Future.successful(())
            restoreFocus
              .recover { case _ => () }
              .flatMap { _ =>
                visibilityScript.fold(Future.successful(())) { identifier =>
                  commandRaw(
                    tabId,
                    owner,
                    "Page.removeScriptToEvaluateOnNewDocument",
                    JsBridge.obj("identifier" -> (identifier))
                  ).map(_ => ()).recover { case _ => () }
                }
              }
              .flatMap { _ =>
                command(
                  tabId,
                  owner,
                  "Runtime.evaluate",
                  JsBridge.obj("expression" -> (removeVisibilityShim), "returnByValue" -> (true))
                ).map(_ => ()).recover { case _ => () }
              }
              .flatMap { _ =>
                decrementActivityOwner(state, owner)
                Future.failed(new Exception(s"Could not prepare hidden page tab=$tabId: ${e.getMessage}"))
              }
          }
      }
    }.map(_ => new CdpPageActivityLease(tabId, owner))
  }

  /** 释放一份后台页面活动租约；最后一个 owner 释放后撤销 shim、lifecycle/focus 模拟。 */
  def releasePageActivity(tabId: Int, owner: String, reason: String): Future[Unit] =
    tabs.get(tabId) match {
      case None        => Future.successful(())
      case Some(state) =>
        decrementActivityOwner(state, owner)
        enqueueActivity(state) {
          if (state.activityOwners.nonEmpty || !state.activityEmulated) Future.successful(())
          else {
            val identifier     = state.visibilityScript
            state.visibilityScript = None
            val removeInjected =
              identifier.fold(Future.successful(())) { id =>
                commandRaw(
                  tabId,
                  owner,
                  "Page.removeScriptToEvaluateOnNewDocument",
                  JsBridge.obj("identifier" -> (id))
                ).map(_ => ()).recover { case _ => () }
              }
            removeInjected
              .flatMap { _ =>
                command(
                  tabId,
                  owner,
                  "Runtime.evaluate",
                  JsBridge.obj("expression" -> (removeVisibilityShim), "returnByValue" -> (true))
                ).map(_ => ()).recover { case _ => () }
              }
              .flatMap { _ =>
                command(
                  tabId,
                  owner,
                  "Emulation.setFocusEmulationEnabled",
                  JsBridge.obj("enabled" -> (false))
                ).map(_ => ())
              }
              .map { _ =>
                state.activityEmulated = false
                PopCanLog.debug("cdp", s"hidden page restored tab=$tabId reason=$reason")
                ()
              }
          }
        }
    }

  /** 登记本 owner 的响应会话（同一 owner 覆盖旧的）。 */
  def register(tabId: Int, owner: String, session: CdpResponseSession): Unit = {
    val state = tabs.getOrElseUpdate(tabId, new TabState(tabId))
    state.sessions.update(owner, session)
  }
  def unregister(tabId: Int, owner: String): Unit                            =
    tabs.get(tabId).foreach(_.sessions.remove(owner))

  /** 取消某 owner 的会话（任务取消 / 中止检查用）。 */
  def cancelOwner(owner: String, reason: String): Unit =
    tabs.values.foreach { state =>
      state.sessions.remove(owner).foreach(_.cancel(reason))
    }

  /** 取消某 tab 上所有 owner 的会话（切走 / 关 tab 时用）。 */
  def cancelTab(tabId: Int, reason: String): Unit =
    tabs.get(tabId).foreach { state =>
      state.sessions.toList.foreach { case (owner, session) =>
        state.sessions.remove(owner)
        session.cancel(reason)
      }
    }

  /** tab 关掉：释放它的全部租约（会话由 `cancelTab` 收）。 */
  def releaseTab(tabId: Int, reason: String): Future[Unit] =
    tabs.get(tabId) match {
      case None        => Future.successful(())
      case Some(state) =>
        cancelTab(tabId, reason)
        state.owners.clear()
        serialized(tabId)(detachNow(tabId, state, reason))
    }

  /** `BackgroundMain` 把该 tab 的 CDP 事件扇发到这里。 */
  def dispatch(tabId: Int, method: String, params: js.Object): Unit =
    tabs.get(tabId).foreach { state =>
      state.sessions.values.toList.foreach { session =>
        try session.onEvent(method, params)
        catch { case e: Throwable => PopCanLog.warn("cdp", s"session event failed tab=$tabId: ${e.getMessage}") }
      }
    }

  /** debugger 真的掉了（`chrome.debugger.onDetach`）：标未挂载、通知会话、有租约就重挂。 */
  def onDetached(tabId: Int, reason: String): Unit =
    tabs.get(tabId).foreach { state =>
      state.attached = false
      state.error = s"CDP detached: $reason"
      clearPageActivity(state)
      PopCanLog.warn("cdp", s"detached tab=$tabId reason=$reason owners=${state.owners.mkString(",")}")
      state.sessions.toList.foreach { case (owner, session) =>
        state.sessions.remove(owner)
        session.cancel(s"CDP detached: $reason")
      }
      if (state.owners.nonEmpty) reattach(tabId, state, attempt = 1)
      else tabs.remove(tabId)
    }

  /** 网络原生 / 任务都需要的 CDP 命令：租约失效时给可读错误，而不是静默失败。 */
  def command(
      tabId: Int,
      owner: String,
      method: String,
      params: js.UndefOr[js.Object] = js.undefined
  ): Future[js.Object] =
    commandRaw(tabId, owner, method, params).map(CdpCommandResults.commandResult)

  /** 完整 CDP result。仅用于返回值本身是结果对象的命令，例如 add/remove script 的 `identifier`。 */
  private def commandRaw(
      tabId: Int,
      owner: String,
      method: String,
      params: js.UndefOr[js.Object]
  ): Future[js.Any] =
    if (!isAttached(tabId))
      Future.failed(new Exception(s"CDP not attached to tab $tabId (owner=$owner, method=$method)"))
    else
      ChromeAPI.debugger
        .sendCommand(CdpTargets.tabTarget(tabId), method, params)
        .toFuture
        .map(raw => raw.toOption.getOrElse(JsBridge.obj()))
        .recoverWith { case e =>
          // 连接其实已经没了（onDetach 偶尔漏报，或连接被外部抢走）：把真相纠正过来并触发重挂。
          if (looksDetached(e.getMessage)) {
            onDetached(tabId, s"command failed: ${e.getMessage}")
            Future.failed(new Exception(s"CDP connection lost on tab $tabId (owner=$owner, method=$method)"))
          } else Future.failed(e)
        }
  private def looksDetached(message: String): Boolean                               = {
    val text = Option(message).getOrElse("").toLowerCase
    text.contains("not attached") || text.contains("no tab with") || text.contains("detached")
  }
  private def looksAlreadyAttached(message: String): Boolean                        =
    Option(message).getOrElse("").toLowerCase.contains("another debugger is already attached")
  // ---------------------------------------------------------------- 内部
  private def decrementActivityOwner(state: TabState, owner: String): Unit          =
    state.activityOwners.get(owner) match {
      case Some(count) if count > 1 => state.activityOwners.update(owner, count - 1)
      case Some(_)                  => state.activityOwners.remove(owner)
      case None                     => ()
    }
  private def enqueueActivity(state: TabState)(body: => Future[Unit]): Future[Unit] = {
    val next = state.activityTail.recover { case _ => () }.flatMap(_ => body)
    state.activityTail = next.recover { case _ => () }
    next
  }
  private def clearPageActivity(state: TabState): Unit                              = {
    state.activityOwners.clear()
    state.activityEmulated = false
    state.visibilityScript = None
    state.activityTail = Future.successful(())
  }
  private def serialized(tabId: Int)(body: => Future[Unit]): Future[Unit]           = {
    val previous = tails.getOrElse(tabId, Future.successful(()))
    val next     = previous.recover { case _ => () }.flatMap(_ => body)
    tails.update(tabId, next.recover { case _ => () })
    next
  }

  /** 真正断开：只在"还是这个 state、且没有 owner 了"时执行，避免和并发的 acquire 打架。 */
  private def detachNow(tabId: Int, state: TabState, reason: String): Future[Unit] =
    if (!tabs.get(tabId).contains(state) || state.owners.nonEmpty) Future.successful(())
    else {
      tabs.remove(tabId)
      clearPageActivity(state)
      if (!state.attached) Future.successful(())
      else {
        state.attached = false
        PopCanLog.debug("cdp", s"detach tab=$tabId reason=$reason")
        RequestRewrite.clearTab(tabId, s"lease released: $reason")
        CdpAttach.detachQuietly(CdpTargets.tabTarget(tabId))
      }
    }
  private def attach(tabId: Int, state: TabState): Future[Unit]                    =
    CdpAttach
      .attach(tabId, CdpTargets.tabTarget(tabId))
      .recoverWith {
        case error if looksAlreadyAttached(error.getMessage) =>
          ChromeAPI.debugger
            .sendCommand(
              CdpTargets.tabTarget(tabId),
              "Runtime.evaluate",
              JsBridge.obj("expression" -> ("1"), "returnByValue" -> (true))
            )
            .toFuture
            .map { _ =>
              PopCanLog.info("cdp", s"recovered existing debugger attachment tab=$tabId")
              ()
            }
            .recoverWith { case probeError =>
              Future.failed(new Exception(
                s"Tab $tabId is attached by another debugger: ${probeError.getMessage}"
              ))
            }
      }
      .flatMap { _ =>
        state.attached = true
        state.error = ""
        PopCanLog.debug("cdp", s"attached tab=$tabId owners=${state.owners.mkString(",")}")
        command(tabId, "system", "Runtime.enable")
          .flatMap(_ => command(tabId, "system", "Network.enable"))
          .flatMap(_ => command(tabId, "system", "Page.enable"))
          .map(_ => ())
      }
      .recoverWith { case e =>
        state.attached = false
        state.error = s"CDP attach failed: ${e.getMessage}"
        PopCanLog.warn("cdp", s"attach failed tab=$tabId: ${e.getMessage}")
        Future.failed(e)
      }

  /** 意外 detach 后的自动重挂；退避 200ms→1.5s，共 5 次，失败只记错（下一次 acquire 会再试）。 */
  private def reattach(tabId: Int, state: TabState, attempt: Int): Unit =
    if (attempt > 5) {
      state.error = s"CDP re-attach failed after 5 attempts"
      PopCanLog.warn("cdp", s"re-attach gave up tab=$tabId")
    } else {
      val delayMs = math.min(1500, 200 * (1 << (attempt - 1)))
      val p       = Promise[Unit]()
      js.timers.setTimeout(delayMs.toDouble)(p.success(()))
      p.future.foreach { _ =>
        if (!state.attached) {
          serialized(tabId) {
            if (state.attached || state.owners.isEmpty) Future.successful(())
            else attach(tabId, state)
          }.onComplete { result =>
            result match {
              case scala.util.Success(_) =>
                PopCanLog.info("cdp", s"re-attached tab=$tabId owners=${state.owners.mkString(",")}")
                reattachHook(tabId)
              case scala.util.Failure(_) => reattach(tabId, state, attempt + 1)
            }
          }
        }
      }
    }
}
