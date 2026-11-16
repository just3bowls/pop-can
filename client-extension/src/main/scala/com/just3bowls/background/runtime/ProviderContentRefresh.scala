package com.just3bowls.background.runtime

import com.just3bowls.background.automation.cdp.ContentInjection
import com.just3bowls.background.webchat.WebChatContentScripts
import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.provider.{ParserProviderLocal, ProviderHostAccess}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js

private[background] object ProviderContentRefresh {

  /** 权限变化后把 content script 注册面和已经打开的 provider tab 拉回一致。 */
  def run(reason: String): Future[Unit] =
    WebChatContentScripts
      .ensureRegistered()
      .flatMap { _ =>
        ParserProviderLocal.loadAll().flatMap { providers =>
          val active = providers.values.toList.filter(_.status == "active")
          ProviderHostAccess.filterReady(active).flatMap { ready =>
            val origins =
              ready.map(provider => ProviderHostAccess.originPattern(provider.pageUrl)).filter(_.nonEmpty).toSet
            if (origins.isEmpty) Future.successful(())
            else
              ChromeAPI.tabs.query(js.Dictionary[js.Any]()).toFuture.flatMap { tabs =>
                val tabIds = tabs.toList
                  .filter(tab =>
                    tab.url.toOption.exists(url => origins.contains(ProviderHostAccess.originPattern(url)))
                  )
                  .flatMap(_.id.toOption)
                Future
                  .sequence(tabIds.map { tabId =>
                    ContentInjection
                      .injectLocation(tabId, force = true)
                      .flatMap(_ => WebChatContentScripts.injectNow(tabId))
                  })
                  .flatMap(_ => TabOwnership.reconcileAll())
              }
          }
        }
      }
      .map(_ => PopCanLog.info("permissions", s"provider content refreshed ($reason)"))
      .recover { case e =>
        PopCanLog.warn("permissions", s"provider content refresh failed ($reason): ${e.getMessage}")
        ()
      }
}
