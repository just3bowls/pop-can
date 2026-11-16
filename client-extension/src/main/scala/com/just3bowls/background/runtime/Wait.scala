package com.just3bowls.background.runtime

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.facade.{TabChangeInfo, Tab => ChromeTab}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 统一的条件等待原语 —— 取代过去散落各处的手写轮询与裸 `setTimeout`。
 *
 * 两条纪律：
 *
 *   1. **能等事件就等事件**（`forTabComplete` 用 `tabs.onUpdated`），事件不可用时才按 `intervalMs` 轮询兜底； 2. **超时不失败**：返回 `ok=false`
 *      由调用方决定（例如发送链路超时也放行，靠后面的回车重按与请求观测兜底）， 需要失败的场景由调用方自己判 `outcome.ok`。
 */
private[background] object Wait {
  final case class Outcome(ok: Boolean, waitedMs: Double)

  /**
   * 轮询 `predicate` 直到为真或超时。
   *
   * @param label
   *   进日志用（`PopCanLog.debug("tabs", ...)`）
   * @param intervalMs
   *   轮询间隔
   * @param timeoutMs
   *   超时（返回 `ok=false`，不抛错）
   * @param minDelayMs
   *   先等一小段（给页面事务/合成事件一个缓冲）再开始判定
   */
  def until(
      label: String,
      intervalMs: Int = 100,
      timeoutMs: Int = 3000,
      minDelayMs: Int = 0
  )(predicate: () => Future[Boolean]): Future[Outcome] = {
    val started                    = js.Date.now()
    def attempt(): Future[Outcome] =
      predicate()
        .map(ok => if (ok) Some(Outcome(ok = true, js.Date.now() - started)) else None)
        .recover { case _ => None }
        .flatMap {
          case Some(outcome) => Future.successful(outcome)
          case None          =>
            val elapsed = js.Date.now() - started
            if (elapsed >= timeoutMs) {
              PopCanLog.debug("tabs", s"wait $label timed out after ${elapsed.toInt}ms")
              Future.successful(Outcome(ok = false, elapsed))
            } else sleep(intervalMs).flatMap(_ => attempt())
        }
    sleep(minDelayMs).flatMap(_ => attempt())
  }

  /**
   * 等 tab 加载完成（`status === complete` 且未被丢弃）。
   *
   * 事件优先：注册 `tabs.onUpdated`，命中即完成；`intervalMs` 轮询只作兜底（事件偶发丢失时仍能在超时前收敛）。
   */
  def forTabComplete(tabId: Int, timeoutMs: Int = 30000, intervalMs: Int = 250): Future[Unit] = {
    val promise                                                          = Promise[Unit]()
    val completed                                                        = js.Date.now() + timeoutMs
    var settled                                                          = false
    var pollTimer: js.timers.SetTimeoutHandle                            = null
    def finish(result: Either[Throwable, Unit]): Unit                    =
      if (!settled) {
        settled = true
        ChromeAPI.tabs.onUpdated.removeListener(listener)
        js.timers.clearTimeout(pollTimer)
        result.fold(promise.failure, promise.success)
      }
    def check(): Unit                                                    =
      ChromeAPI.tabs.get(tabId).toFuture.foreach { tab =>
        if (tab.status.contains("complete") && !tab.discarded.getOrElse(false)) finish(Right(()))
        else if (js.Date.now() > completed)
          finish(Left(new Exception(s"Tab $tabId did not become ready before timeout")))
        else pollTimer = js.timers.setTimeout(intervalMs.toDouble)(check())
      }
    lazy val listener: js.Function3[Int, TabChangeInfo, ChromeTab, Unit] = (updatedTabId, changeInfo, _) => {
      if (updatedTabId == tabId && changeInfo.status.contains("complete")) finish(Right(()))
    }
    ChromeAPI.tabs.onUpdated.addListener(listener)
    check()
    promise.future
  }
  private[background] def sleep(ms: Int): Future[Unit]                                        = {
    val promise = Promise[Unit]()
    js.timers.setTimeout(ms)(promise.success(()))
    promise.future
  }
}
