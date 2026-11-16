package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge
import com.just3bowls.background.bootstrap.*

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.localapi.PageScriptProtocol
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 按需把 `location.js`（标记 / 抓取向导那套）注入到某个 tab。
 *
 * 托管 tab 走的是 `BackgroundMain` / `ManagedTabManager` 里的同名逻辑；这里是给"网页原生接管的 tab"用的——那些 tab 也要能打 new-chat / input
 * 标记，触发反馈才有地方可闪。
 *
 * 页面导航 / 扩展重载会让 content-script JS 上下文消失；本地 `Set[tabId]` 无法可靠知道这一点（之前的缓存正是这样造成 marker 永久丢失）。 所以这里每次 ensure 都先向页面
 * ping，只有页面明确回应同一协议版本才算活着，缺脚本时才重新注入。
 */
private[background] object ContentInjection {
  private val tails              = mutable.Map.empty[Int, Future[Unit]]
  private def scriptPath: String =
    try {
      JsBridge.undefinedString(JsBridge.locationScript).toOption.getOrElse("")
    } catch {
      case _: Throwable => ""
    }

  /** 页面上下文里的 location runtime 是否在线且版本一致。 */
  def ping(tabId: Int): Future[Option[js.Any]] =
    if (tabId <= 0) Future.successful(None)
    else
      ChromeAPI.tabs
        .sendMessage(
          tabId,
          JsBridge.obj("type" -> ("LOCATION_PING"))
        )
        .toFuture
        .map { raw =>
          val response = JsBridge.asObject(raw)
          val ready    = JsBridge.undefinedBoolean(response.ok).getOrElse(false)
          val version  = JsBridge.undefinedInt(response.protocol).getOrElse(0)
          if (ready && version == PageScriptProtocol.LocationVersion) Some(response) else None
        }
        .recover { case _ => None }

  /** 串行化同一 tab 的 ensure / inject，避免多个调用同时灌入多份 runtime。 */
  private def serialized[A](tabId: Int)(body: => Future[A]): Future[A] = {
    val previous = tails.getOrElse(tabId, Future.successful(()))
    val next     = previous.recover { case _ => () }.flatMap(_ => body)
    tails.update(tabId, next.map(_ => ()).recover { case _ => () })
    next
  }
  private def waitForPing(tabId: Int, attempts: Int): Future[js.Any]   =
    ping(tabId).flatMap {
      case Some(response)        => Future.successful(response)
      case None if attempts <= 0 =>
        Future.failed(new Exception(s"Location runtime did not answer after injection tab=$tabId"))
      case None                  =>
        delay(100).flatMap(_ => waitForPing(tabId, attempts - 1))
    }
  private def injectNow(tabId: Int): Future[Unit]                      = {
    val path = scriptPath
    if (path.isEmpty) Future.failed(new Exception("Location content script path is unavailable"))
    else
      ChromeAPI.scripting
        .executeScript(js.Dictionary(
          "target" -> JsBridge.obj("tabId" -> (tabId)),
          "files"  -> js.Array(path)
        ))
        .toFuture
        .flatMap { _ =>
          PopCanLog.debug("location", s"injected location script tab=$tabId")
          waitForPing(tabId, attempts = 8).map(_ => ())
        }
        .recoverWith { case e =>
          Future.failed(new Exception(s"inject location script failed tab=$tabId: ${e.getMessage}"))
        }
  }

  /**
   * 保证 location runtime 当前真的存活。
   *
   * `force=false` 是热路径：ping 命中就不碰页面；只有失联时才注入。`force=true` 用于 marker 恢复失败后的最后一次兜底。
   */
  def injectLocation(tabId: Int, force: Boolean = false): Future[Unit] =
    if (tabId <= 0) Future.successful(())
    else
      serialized(tabId) {
        if (force) injectNow(tabId)
        else
          ping(tabId).flatMap {
            case Some(_) => Future.successful(())
            case None    => injectNow(tabId)
          }
      }

  /** 页面导航开始时调用：旧上下文必然失效，顺手清掉 marker 健康状态。 */
  def invalidate(tabId: Int): Unit         =
    if (tabId > 0) {
      BackgroundState.markerRoleStates.remove(tabId)
      tails.remove(tabId)
    }
  private def delay(ms: Int): Future[Unit] = {
    val promise = Promise[Unit]()
    js.timers.setTimeout(ms)(promise.success(()))
    promise.future
  }
}
