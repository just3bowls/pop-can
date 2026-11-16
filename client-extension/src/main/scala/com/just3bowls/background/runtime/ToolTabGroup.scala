package com.just3bowls.background.runtime

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/**
 * 所有工具调用打开的 tab（`browser_*` 的常驻会话 tab、搜索/抓取的临时 tab、页面自己开的新 tab）统一归入的 tab group。
 *
 * 组名固定为 `tools`，按窗口记忆 group id（`chrome.storage.session` + 内存缓存）；组被用户关掉时自动重建。
 */
private[background] object ToolTabGroup {
  private val GroupTitle                        = "tools"
  private val GroupColor                        = "blue"
  private val StoragePrefix                     = "thToolsGroup:"
  private def storageKey(windowId: Int): String = s"$StoragePrefix$windowId"
  private val cache                             = mutable.Map.empty[Int, Int]

  /** 把 tab 收进 tools 组；返回最终 groupId，失败返回 -1（不影响调用方继续干活）。 */
  def attach(tabId: Int, windowId: Int): Future[Int]                =
    resolve(windowId)
      .flatMap {
        case Some(groupId) =>
          group(tabId, Some(groupId)).recoverWith { case _ =>
            // 组可能已被用户关掉：退化成新建一个。
            group(tabId, None)
          }
        case None          =>
          group(tabId, None)
      }
      .map { groupId =>
        if (groupId > 0) {
          cache.update(windowId, groupId)
          persist(windowId, groupId)
        }
        groupId
      }
      .recover { case e =>
        PopCanLog.warn("tool", s"tools tab group attach failed tab=$tabId window=$windowId: ${e.getMessage}")
        cache.remove(windowId)
        -1
      }
  private def group(tabId: Int, existing: Option[Int]): Future[Int] = {
    val options = js.Dictionary[js.Any]("tabIds" -> js.Array(tabId))
    existing.foreach(groupId => options.update("groupId", groupId))
    ChromeAPI.tabs.group(options).toFuture.flatMap { groupId =>
      ChromeAPI.tabGroups
        .update(groupId, js.Dictionary[js.Any]("title" -> GroupTitle, "color" -> GroupColor))
        .toFuture
        .map(_ => groupId)
        .recover { case _ => groupId }
    }
  }

  /** 解析窗口里已有的 tools 组：缓存 → 校验存在 → 按标题查 → 没有则交给 attach 新建。 */
  private def resolve(windowId: Int): Future[Option[Int]] =
    cached(windowId).flatMap {
      case Some(groupId) => exists(groupId).flatMap(if (_) Future.successful(Some(groupId)) else findByTitle(windowId))
      case None          => findByTitle(windowId)
    }
  private def cached(windowId: Int): Future[Option[Int]]  =
    cache.get(windowId) match {
      case Some(groupId) => Future.successful(Some(groupId))
      case None          =>
        ChromeAPI.storage.session.get(storageKey(windowId)).toFuture
          .map(values => intOf(values.get(storageKey(windowId))))
          .map { parsed =>
            parsed.foreach(groupId => cache.update(windowId, groupId))
            parsed
          }
          .recover { case _ => None }
    }

  /**
   * 关掉窗口里 tools 组的全部 tab，并把记住的 groupId 清掉（组随之消失，下次使用按需重建）。
   *
   * chat agent 每轮开跑前调用：上一轮打开的页面留到用户看够为止，新一轮一开始整组清空。
   */
  def closeAll(windowId: Int, preserveTabIds: Set[Int] = Set.empty): Future[Unit] =
    resolve(windowId)
      .flatMap {
        case None          => Future.successful(())
        case Some(groupId) =>
          ChromeAPI.tabs
            .query(js.Dictionary[js.Any]("groupId" -> groupId))
            .toFuture
            .flatMap { tabs =>
              val ids = tabs.toList.flatMap(_.id.toOption).filterNot(preserveTabIds.contains)
              if (ids.isEmpty) Future.successful(())
              else
                ChromeAPI.tabs
                  .remove(ids.toJSArray)
                  .toFuture
                  .map(_ => ())
                  .recover { case _ => () }
            }
            .map { _ =>
              cache.remove(windowId)
              ChromeAPI.storage.session
                .remove(storageKey(windowId))
                .toFuture
                .recover { case _ => () }
                .foreach(_ => ())
            }
      }
      .recover { case e =>
        PopCanLog.warn("tool", s"tools tab group clear failed window=$windowId: ${e.getMessage}")
        ()
      }
  private def exists(groupId: Int): Future[Boolean]                               =
    ChromeAPI.tabGroups
      .get(groupId)
      .toFuture
      .map(_ => true)
      .recover { case _ =>
        cache.filterInPlace { case (_, cachedId) => cachedId != groupId }
        false
      }
  private def findByTitle(windowId: Int): Future[Option[Int]]                     =
    ChromeAPI.tabGroups
      .query(js.Dictionary[js.Any]("title" -> GroupTitle, "windowId" -> windowId))
      .toFuture
      .map(groups => groups.toList.headOption.map(_.id))
      .recover { case _ => None }
  private def persist(windowId: Int, groupId: Int): Unit                          =
    ChromeAPI.storage.session
      .set(js.Dictionary[js.Any](storageKey(windowId) -> groupId))
      .toFuture
      .recover { case _ => () }
      .foreach(_ => ())
  private def intOf(raw: Option[js.Any]): Option[Int]                             =
    raw.map(_.toString).flatMap(_.toIntOption).filter(_ > 0)
}
