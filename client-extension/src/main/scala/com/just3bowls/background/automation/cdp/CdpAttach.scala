package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.{ChromeAPI, DebuggerSession}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 统一的 CDP attach 入口：遇到「已有调试器挂在该 tab」时先清理本扩展遗留连接再重试， 重试耗尽则说明该 tab 被外部调试器（通常是 DevTools）占用，返回可操作的错误。
 */
private[background] object CdpAttach {
  private val AlreadyAttachedFragment                           = "Another debugger is already attached"
  private val MaxRetries                                        = 5
  private val InitialDelayMs                                    = 200
  private val MaxDelayMs                                        = 1000
  def attach(tabId: Int, target: DebuggerSession): Future[Unit] =
    attempt(tabId, target, retriesLeft = MaxRetries, delayMs = InitialDelayMs)

  /** 忽略一切失败的 detach，用于清理可能残留的自有连接。 */
  def detachQuietly(target: DebuggerSession): Future[Unit]      =
    try {
      ChromeAPI.debugger.detach(target).toFuture.map(_ => ()).recover { case _ => () }
    } catch {
      case _: Throwable => Future.successful(())
    }
  private def attempt(
      tabId: Int,
      target: DebuggerSession,
      retriesLeft: Int,
      delayMs: Int
  ): Future[Unit] =
    attachOnce(target).recoverWith {
      case e if isAlreadyAttached(e) && retriesLeft > 0 =>
        PopCanLog.warn(
          "cdp",
          s"tab=$tabId already attached, detaching stale session and retrying retriesLeft=${retriesLeft - 1}"
        )
        detachQuietly(target)
          .flatMap(_ => delay(delayMs))
          .flatMap(_ => attempt(tabId, target, retriesLeft - 1, math.min(delayMs * 2, MaxDelayMs)))
      case e if isAlreadyAttached(e)                    =>
        Future.failed(new Exception(
          s"Tab $tabId is already attached by another debugger; close DevTools (or other debugger) " +
            s"on that tab and retry: ${e.getMessage}"
        ))
      case e                                            =>
        Future.failed(e)
    }
  private def attachOnce(target: DebuggerSession): Future[Unit] =
    try {
      ChromeAPI.debugger.attach(target, "1.3").toFuture.map(_ => ())
    } catch {
      case e: Throwable => Future.failed(e)
    }
  private def isAlreadyAttached(error: Throwable): Boolean      =
    Option(error.getMessage).exists(_.contains(AlreadyAttachedFragment))
  private def delay(ms: Int): Future[Unit]                      = {
    val p = Promise[Unit]()
    js.timers.setTimeout(ms)(p.success(()))
    p.future
  }
}
