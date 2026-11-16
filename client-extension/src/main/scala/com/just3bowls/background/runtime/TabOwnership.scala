package com.just3bowls.background.runtime
import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.webchat.*

import com.just3bowls.api.provider.PageIdentity
import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.localapi.ApiKeyStore
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

/**
 * Provider tab 的实际 owner 只由 Chrome tab group 决定。
 *
 * URL 决定它是哪家 provider，group 决定它属于哪个 key；slot 只是当前 owner 的可复用状态。tab 被拖出 group 就 detach，拖进 group 就由 incoming tab 接管 slot。
 */
private[background] object TabOwnership {
  private val locks                                                  = mutable.Map.empty[Int, Future[Unit]]
  def reconcileAll(): Future[Unit]                                   =
    ChromeAPI.tabs
      .query(js.Dictionary[js.Any]())
      .toFuture
      .map(_.toList.flatMap(_.id.toOption))
      .recover { case _ => Nil }
      .flatMap(tabIds => tabIds.foldLeft(Future.successful(()))((acc, id) => acc.flatMap(_ => reconcileTab(id, "all"))))
  def reconcileTab(tabId: Int, reason: String = ""): Future[Unit]    =
    if (tabId <= 0) Future.successful(())
    else {
      val key  = tabId
      val prev = locks.getOrElse(key, Future.successful(()))
      val next = prev.recover { case _ => () }.flatMap(_ => reconcileNow(tabId, reason))
      locks.update(key, next.map(_ => ()).recover { case _ => () })
      next.map(_ => ()).recover { case e =>
        PopCanLog.warn("tabs", s"ownership reconcile failed tab=$tabId reason=$reason: ${e.getMessage}")
        ()
      }
    }
  private def reconcileNow(tabId: Int, reason: String): Future[Unit] =
    ChromeAPI.tabs
      .get(tabId)
      .toFuture
      .flatMap { tab =>
        val windowId = tab.windowId.toOption.filter(_ > 0)
        val groupId  = tab.groupId.toOption.filter(_ > 0)
        val url      = tab.url.toOption.getOrElse("")
        WebChatService.resolveProviderByUrl(url).flatMap { providerOpt =>
          (windowId, groupId) match {
            case (Some(window), Some(group)) =>
              for {
                keyOpt <- ProviderTabGroup.keyIdForGroup(window, group)
                _      <- (keyOpt, providerOpt) match {
                  case (Some(keyId), Some((provider, config))) =>
                    for {
                      _ <- ManagedTabManager.claimTabOwnership(provider, keyId, tabId, url)
                      _ <- closeDuplicateProviderTabs(tab, provider, config.pageUrl)
                      _ <- ContentInjection.injectLocation(tabId)
                      _ <- Future.successful(LocationSignals.setVisible(tabId, visible = true))
                      _ <-
                        if (keyId == ApiKeyStore.SystemApiKeyId)
                          WebChatService.setCapsuleEnabled(tabId, enabled = true)
                        else
                          WebChatContentScripts
                            .injectNow(tabId)
                            .flatMap(_ => WebChatService.setCapsuleEnabled(tabId, enabled = false))
                    } yield {
                      PopCanLog.info(
                        "tabs",
                        s"ownership armed tab=$tabId provider=$provider key=$keyId reason=$reason"
                      )
                      SessionUrls.notifyChanged()
                    }
                  case _                                       =>
                    unown(tabId, reason, providerOpt.isDefined)
                }
              } yield ()
            case _                           => unown(tabId, reason, providerOpt.isDefined)
          }
        }
      }
      .recover { case e =>
        PopCanLog.debug("tabs", s"ownership inspect skipped tab=$tabId reason=$reason: ${e.getMessage}")
        ()
      }
  private def closeDuplicateProviderTabs(
      incoming: com.just3bowls.facade.Tab,
      provider: String,
      providerUrl: String
  ): Future[Unit] = {
    val incomingId = incoming.id.toOption.getOrElse(-1)
    for {
      windowId <- Future.successful(incoming.windowId.toOption.filter(_ > 0))
      groupId  <- Future.successful(incoming.groupId.toOption.filter(_ > 0))
      _        <- (windowId, groupId) match {
        case (Some(window), Some(group)) =>
          ChromeAPI.tabs
            .query(js.Dictionary[js.Any]("windowId" -> window, "groupId" -> group))
            .toFuture
            .map(_.toList)
            .recover { case _ => Nil }
            .flatMap { tabs =>
              val duplicates = tabs.filter { tab =>
                tab.id.toOption.exists(_ != incomingId) &&
                tab.url.toOption.exists(url => PageIdentity.sameProviderPage(providerUrl, url))
              }
              Future
                .sequence(duplicates.flatMap(_.id.toOption).map { id =>
                  PopCanLog.info("tabs", s"provider duplicate removed provider=$provider tab=$id incoming=$incomingId")
                  ChromeAPI.tabs.remove(id).toFuture.map(_ => ()).recover { case _ => () }
                })
                .map(_ => ())
            }
        case _                           => Future.successful(())
      }
    } yield ()
  }
  private def unown(tabId: Int, reason: String, providerKnown: Boolean): Future[Unit] =
    TabStore.load().flatMap { window =>
      val wasOwned   = TabStore.findByTabId(window, tabId).isDefined
      val hadRuntime = WebChatService.hasRuntime(tabId)
      if (!wasOwned && !hadRuntime && !providerKnown) Future.successful(())
      else
        for {
          _ <- ManagedTabManager.detachTabByTabId(tabId)
          _ <-
            if (providerKnown) WebChatContentScripts.injectNow(tabId)
            else Future.successful(())
          _ <- WebChatService.setCapsuleEnabled(tabId, enabled = false)
        } yield {
          LocationSignals.setVisible(tabId, visible = false)
          PopCanLog.info("tabs", s"ownership removed tab=$tabId reason=$reason")
          SessionUrls.notifyChanged()
        }
    }
}
