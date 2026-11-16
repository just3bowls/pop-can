package com.just3bowls.background.runtime
import com.just3bowls.background.automation.provider.*

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
private[background] object TabKeepAlive {
  private[background] def protectTab(tabId: Int): Future[Unit] =
    ChromeAPI.tabs.get(tabId).toFuture.flatMap { tab =>
      if (tab.autoDiscardable.contains(false)) {
        Future.successful(())
      } else {
        ChromeAPI.tabs.update(
          tabId,
          js.Dictionary("autoDiscardable" -> false)
        ).toFuture.map { updated =>
          PopCanLog.logger("tabs").debug(
            s"protected tab=$tabId discarded=${tab.discarded.getOrElse(false)} " +
              s"autoDiscardable=${tab.autoDiscardable.getOrElse(true)} -> ${updated.autoDiscardable.getOrElse(false)}"
          )
          ()
        }
      }
    }
      .recover { case e =>
        PopCanLog.warn("tabs", s"failed to protect tab $tabId: ${e.getMessage}")
        ()
      }
  private[background] def ensureTabReady(
      tabId: Int,
      timeoutMs: Int = 30000
  ): Future[Unit] =
    ChromeAPI.tabs.get(tabId).toFuture.flatMap { tab =>
      val wasDiscarded = tab.discarded.getOrElse(false)
      val keepLog      = PopCanLog.withContext("tabs", "tab" -> tabId.toString)
      keepLog.debug(s"ensureTabReady discarded=$wasDiscarded status=${tab.status.getOrElse("unknown")}")
      protectTab(tabId).flatMap { _ =>
        if (wasDiscarded) {
          keepLog.debug("warming up discarded tab")
          warmupTab(tabId).flatMap(_ => waitForTabLoaded(tabId, timeoutMs))
        } else if (!tab.status.contains("complete")) {
          waitForTabLoaded(tabId, timeoutMs)
        } else {
          keepLog.debug("tab already ready")
          Future.successful(())
        }
      }
    }
  private[background] def sweepManagedTabs(): Unit             = {
    // 面板是"当前窗口里的一个 tab"，可能落在任何窗口（用户还会把它拖来拖去），所以这里**全窗口**扫，不再按插件窗口过滤。
    ServerTabManager
      .panelTab()
      .foreach { panel =>
        val panelTabs = panel.toList
        PopCanLog.debug("tabs", s"sweep mainTabs=${panelTabs.size}")
        panelTabs.foreach { case (tabId, _) =>
          ChromeAPI.tabs.get(tabId).toFuture.foreach { tab =>
            protectTab(tabId).foreach(_ => ())
            if (tab.discarded.getOrElse(false)) {
              PopCanLog.warn("tabs", s"main page discarded, reloading tab $tabId")
              ChromeAPI.tabs.reload(tabId).toFuture
                .flatMap(_ => protectTab(tabId))
                .recover { case e =>
                  PopCanLog.error("tabs", s"failed to reload main page $tabId", e)
                }
                .foreach(_ => ())
            }
          }
        }
      }
    ManagedTabManager.protectAllManagedTabs().foreach(_ => ())
  }
  private def warmupTab(tabId: Int): Future[Unit]              =
    ChromeAPI.tabs.warmup(tabId).toFuture
      .map { _ =>
        PopCanLog.debug("tabs", s"warmup requested for tab=$tabId")
        ()
      }
      .recoverWith { case e =>
        PopCanLog.warn("tabs", s"warmup unavailable, reloading tab $tabId: ${e.getMessage}")
        ChromeAPI.tabs.reload(tabId).toFuture.map { _ =>
          PopCanLog.debug("tabs", s"fallback reload requested for tab=$tabId")
          ()
        }
      }

  /** 等 tab 加载完成：事件（`tabs.onUpdated`）优先，轮询兜底（见 `Wait.forTabComplete`）。 */
  private def waitForTabLoaded(tabId: Int, timeoutMs: Int): Future[Unit] =
    Wait.forTabComplete(tabId, timeoutMs)
}
