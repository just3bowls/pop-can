package com.just3bowls.ui.panel.pages

import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.ChromeAPI
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js

/** Reopens `@tabs` references in their original window, index, pin and group. */
private[pages] object ReferencedTabRestorer {
  def restore(refs: List[BrowserTabRef]): Future[(List[BrowserTabRef], List[String])] = {
    def currentWindowId: Future[Int]                                  =
      ChromeAPI.tabs.getCurrent().toFuture
        .map(_.windowId.toOption.getOrElse(0))
        .recover { case _ => 0 }
    def targetWindow(ref: BrowserTabRef): Future[Int]                 =
      if (ref.windowId <= 0) currentWindowId
      else
        ChromeAPI.windows
          .get(ref.windowId)
          .toFuture
          .map(_ => ref.windowId)
          .recoverWith(_ => currentWindowId)
    def groupRestoredTab(tabId: Int, ref: BrowserTabRef): Future[Int] = {
      def createGroup(): Future[Int] =
        ChromeAPI.tabs
          .group(js.Dictionary[js.Any]("tabIds" -> js.Array(tabId)))
          .toFuture
          .flatMap { groupId =>
            val updates = js.Dictionary[js.Any]()
            if (ref.groupTitle.nonEmpty) updates("title") = ref.groupTitle
            if (ref.groupColor.nonEmpty) updates("color") = ref.groupColor
            if (updates.isEmpty) Future.successful(groupId)
            else
              ChromeAPI.tabGroups
                .update(groupId, updates)
                .toFuture
                .map(_ => groupId)
                .recover { case _ => groupId }
          }
      if (ref.groupId < 0) Future.successful(-1)
      else
        ChromeAPI.tabGroups
          .get(ref.groupId)
          .toFuture
          .flatMap(_ =>
            ChromeAPI.tabs
              .group(js.Dictionary[js.Any](
                "groupId" -> ref.groupId,
                "tabIds"  -> js.Array(tabId)
              ))
              .toFuture
              .map(_ => ref.groupId)
          )
          .recoverWith(_ => createGroup())
    }
    def createTab(ref: BrowserTabRef): Future[Option[BrowserTabRef]]  =
      if (!ref.url.startsWith("http")) Future.successful(None)
      else
        targetWindow(ref)
          .flatMap { windowId =>
            val properties = js.Dictionary[js.Any](
              "url"    -> ref.url,
              "active" -> false,
              "pinned" -> ref.pinned
            )
            if (windowId > 0) properties("windowId") = windowId
            if (ref.index >= 0) properties("index") = ref.index
            ChromeAPI.tabs.create(properties).toFuture.flatMap { created =>
              created.id.toOption match {
                case None        => Future.successful(None)
                case Some(tabId) =>
                  groupRestoredTab(tabId, ref)
                    .flatMap { groupId =>
                      ChromeAPI.tabs
                        .update(tabId, js.Dictionary[js.Any]("index" -> ref.index))
                        .toFuture
                        .recover { case _ => created }
                        .map { actual =>
                          Some(ref.copy(
                            tabId = tabId,
                            title = actual.title.toOption.filter(_.nonEmpty).getOrElse(ref.title),
                            url = actual.url.toOption.filter(_.nonEmpty).getOrElse(ref.url),
                            windowId = actual.windowId.toOption.getOrElse(windowId),
                            index = actual.index.toOption.getOrElse(ref.index),
                            pinned = actual.pinned.toOption.getOrElse(ref.pinned),
                            groupId = groupId
                          ))
                        }
                    }
              }
            }
          }
    def restoreOne(ref: BrowserTabRef): Future[Option[BrowserTabRef]] =
      ChromeAPI.tabs
        .get(ref.tabId)
        .toFuture
        .map { actual =>
          Some(ref.copy(
            title = actual.title.toOption.filter(_.nonEmpty).getOrElse(ref.title),
            url = actual.url.toOption.filter(_.nonEmpty).getOrElse(ref.url),
            windowId = actual.windowId.toOption.getOrElse(ref.windowId),
            index = actual.index.toOption.getOrElse(ref.index),
            pinned = actual.pinned.toOption.getOrElse(ref.pinned),
            groupId = actual.groupId.toOption.getOrElse(ref.groupId)
          ))
        }
        .recoverWith(_ => createTab(ref))
    refs.foldLeft(Future.successful((List.empty[BrowserTabRef], List.empty[String]))) {
      case (accF, ref) =>
        accF.flatMap { case (restored, failures) =>
          restoreOne(ref)
            .map {
              case Some(tab) => (restored :+ tab, failures)
              case None      => (restored, failures :+ s"${ref.ref}: invalid URL")
            }
            .recover { case error =>
              (restored, failures :+ s"${ref.ref}: ${Option(error.getMessage).getOrElse("restore failed")}")
            }
        }
    }
  }
}
