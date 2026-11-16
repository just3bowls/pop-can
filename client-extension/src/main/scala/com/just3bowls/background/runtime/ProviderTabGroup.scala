package com.just3bowls.background.runtime

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.localapi.ApiKeyStore
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

/**
 * provider tab 的 API key 分组：同一个 key 拉起的 provider tab 收进同一个 tab group，组名就是 key 名称。
 *
 * 只做"新建 / 复用准备时确保归组"，不做定期强制拉回（用户手动拖出组我们尊重）；组按窗口 + key 记忆 group id （`chrome.storage.session` + 内存缓存），只认自己记下的 id ——
 * 组被用户关掉就新建一个，不按标题去猜（避免撞上 用户自己建的同名组）。只设标题、不设颜色，颜色交给 Chrome 默认。
 */
private[background] object ProviderTabGroup {
  private val StoragePrefix                                    = "thProviderGroup:"
  private def storageKey(windowId: Int, keyId: String): String = s"$StoragePrefix$windowId:$keyId"
  private val cache                                            = mutable.Map.empty[(Int, String), Int]
  private val groupOwner                                       = mutable.Map.empty[(Int, Int), String]

  /** key 在 Chrome 组里用的标题（组名 = key 名；没名字时用 key 前缀）。 */
  private[background] def groupTitleFor(keyId: String, name: String): String = {
    val trimmed = Option(name).getOrElse("").trim
    if (trimmed.nonEmpty) trimmed else s"key ${keyId.take(8)}"
  }

  /** 同一 (window, key) 的归组串行化：并发 attach 不再各建一个同名组。 */
  private val locks = mutable.Map.empty[(Int, String), Future[Unit]]
  private def withLock[A](windowId: Int, keyId: String)(body: => Future[A]): Future[A] = {
    val key  = (windowId, keyId)
    val prev = locks.getOrElse(key, Future.successful(()))
    val next = prev.recover { case _ => () }.flatMap(_ => body)
    locks.update(key, next.map(_ => ()).recover { case _ => () })
    next
  }

  /** 只看"现在解析出来的组"，不新建：给"采纳已有 tab"找候选用。 */
  private[background] def existing(windowId: Int, keyId: String): Future[Option[Int]] =
    resolve(windowId, keyId)

  /**
   * 反查 Chrome group 当前属于哪个 key。
   *
   * 候选 key 来自现有 slot 与 API key 目录；这里只读、不创建组。用户把 tab 拖进别的 key group 时，ownership 以这个结果为准。
   */
  private[background] def keyIdForGroup(windowId: Int, groupId: Int): Future[Option[String]] =
    if (windowId <= 0 || groupId <= 0) Future.successful(None)
    else
      groupOwner.get((windowId, groupId)) match {
        case Some(keyId) => exists(groupId).map(if (_) Some(keyId) else None)
        case None        =>
          for {
            store <- TabStore.load()
            keys  <- ApiKeyStore.load().map(_.map(_.id)).recover { case _ => Nil }
            candidates = (TabStore
              .allSlots(store)
              .map(_._2.keyId) ++ keys :+ ApiKeyStore.SystemApiKeyId).filter(_.nonEmpty).distinct
            resolved <- Future.sequence(candidates.map(keyId => existing(windowId, keyId).map(keyId -> _)))
          } yield {
            val owner = resolved.collectFirst { case (keyId, Some(id)) if id == groupId => keyId }
            owner.foreach(keyId => groupOwner.update((windowId, groupId), keyId))
            owner
          }
      }

  /** 把 tab 收进它所属 key 的分组；返回 groupId，失败返回 -1（不影响调用方继续干活）。 */
  def attach(tabId: Int, keyId: String): Future[Int]                                         =
    if (keyId.trim.isEmpty) Future.successful(-1)
    else
      windowOf(tabId).flatMap {
        case None         => Future.successful(-1)
        case Some(window) =>
          keyNameOf(keyId).flatMap { title =>
            withLock(window, keyId)(attachIn(tabId, window, keyId, title))
          }
      }
  private def attachIn(tabId: Int, windowId: Int, keyId: String, title: String): Future[Int] =
    resolve(windowId, keyId)
      .flatMap {
        case Some(groupId) =>
          group(tabId, keyId, title, Some(groupId)).recoverWith { case _ =>
            // 组可能已被用户关掉 / 被重建：重新解析一次（含标题兜底），仍失败才新建。
            resolve(windowId, keyId).flatMap {
              case Some(again) if again != groupId => group(tabId, keyId, title, Some(again))
              case _                               => group(tabId, keyId, title, None)
            }
          }
        case None          =>
          group(tabId, keyId, title, None)
      }
      .map { groupId =>
        if (groupId > 0) {
          cache.update((windowId, keyId), groupId)
          groupOwner.update((windowId, groupId), keyId)
          persist(windowId, keyId, groupId)
        }
        groupId
      }
      .recover { case e =>
        PopCanLog.warn("tabs", s"provider tab group attach failed tab=$tabId key=$keyId: ${e.getMessage}")
        cache.remove((windowId, keyId))
        -1
      }

  /** 新建组时按 keyId 稳定挑一个 Chrome 支持的色（同 key 永远同色），Tabs 面板据此上色。 */
  private val Palette = Array("blue", "red", "yellow", "green", "pink", "purple", "cyan", "orange")
  private def colorFor(keyId: String): String                                                     = {
    var hash = 0
    keyId.foreach(ch => hash = (hash * 31 + ch.toInt) | 0)
    Palette(math.abs(hash) % Palette.length)
  }
  private def group(tabId: Int, keyId: String, title: String, existing: Option[Int]): Future[Int] = {
    val options = js.Dictionary[js.Any]("tabIds" -> js.Array(tabId))
    existing.foreach(groupId => options.update("groupId", groupId))
    ChromeAPI.tabs.group(options).toFuture.flatMap { groupId =>
      // 每次归组都重设标题：key 改名后下一次复用会自动更新组名。
      // 颜色：新建组直接上本 key 的颜色；已有组只在"还是 Chrome 默认灰"时补上 —— 用户手动改过色就尊重它
      // （Tabs 面板永远读浏览器里的实际颜色，所以两边不会不一致）。
      val colorF: Future[Option[String]] =
        if (existing.isEmpty) Future.successful(Some(colorFor(keyId)))
        else
          ChromeAPI.tabGroups
            .get(groupId)
            .toFuture
            .map { group =>
              val current = group.color.toOption.getOrElse("")
              if (current.isEmpty || current == "grey") Some(colorFor(keyId)) else None
            }
            .recover { case _ => None }
      colorF.flatMap { maybeColor =>
        val props = js.Dictionary[js.Any]("title" -> title)
        maybeColor.foreach(color => props.update("color", color))
        ChromeAPI.tabGroups
          .update(groupId, props)
          .toFuture
          .map(_ => groupId)
          .recover { case _ => groupId }
      }
    }
  }

  /**
   * 解析这个窗口里该 key 的分组：缓存/存储的 id（校验仍存在）→ 按标题反查同名组（优先含本 key 已登记 tab 的那个， 否则取唯一同名组，多个且都不含时取 id 最小即最早的那个）→ 都没有才新建。
   *
   * 标题兜底是为了修"浏览器重启清空 session 存储、Chrome 又恢复了原组，于是插件再建一个同名组"的重复问题。
   */
  private def resolve(windowId: Int, keyId: String): Future[Option[Int]] =
    cached(windowId, keyId).flatMap {
      case Some(groupId) =>
        exists(groupId).flatMap(if (_) Future.successful(Some(groupId)) else fallbackByTitle(windowId, keyId))
      case None          => fallbackByTitle(windowId, keyId)
    }

  /** 按 (窗口, key 名) 找同名组；优先含本 key 已登记 tab 的那个。 */
  private def fallbackByTitle(windowId: Int, keyId: String): Future[Option[Int]] =
    keyNameOf(keyId).flatMap { title =>
      ChromeAPI.tabGroups
        .query(js.Dictionary[js.Any]("title" -> title, "windowId" -> windowId))
        .toFuture
        .flatMap { groups =>
          val ids = groups.toList.map(_.id).sorted
          if (ids.isEmpty) Future.successful(None)
          else
            TabStore.load().flatMap { window =>
              val ours                        = TabStore
                .allSlots(window)
                .filter { case (_, managed) => managed.keyId == keyId }
                .flatMap { case (_, managed) => managed.liveTabId }
                .toSet
              val prefer: Future[Option[Int]] =
                if (ours.isEmpty) Future.successful(None)
                else
                  ChromeAPI.tabs
                    .query(js.Dictionary[js.Any]("windowId" -> windowId))
                    .toFuture
                    .map(_.toList.filter(tab => tab.id.toOption.exists(ours.contains)).flatMap(_.groupId.toOption).find(
                      ids.contains
                    ))
                    .recover { case _ => None }
              prefer.map(_.orElse(Some(ids.head)))
            }
        }
        .recover { case _ => None }
    }
  private def cached(windowId: Int, keyId: String): Future[Option[Int]]          =
    cache.get((windowId, keyId)) match {
      case Some(groupId) => Future.successful(Some(groupId))
      case None          =>
        ChromeAPI.storage.session.get(storageKey(windowId, keyId)).toFuture
          .map(values => intOf(values.get(storageKey(windowId, keyId))))
          .map { parsed =>
            parsed.foreach(groupId => cache.update((windowId, keyId), groupId))
            parsed
          }
          .recover { case _ => None }
    }
  private def exists(groupId: Int): Future[Boolean]                              =
    ChromeAPI.tabGroups
      .get(groupId)
      .toFuture
      .map(_ => true)
      .recover { case _ =>
        cache.filterInPlace { case (_, cachedId) => cachedId != groupId }
        false
      }
  private def windowOf(tabId: Int): Future[Option[Int]]                          =
    ChromeAPI.tabs
      .get(tabId)
      .toFuture
      .map(tab => tab.windowId.toOption.filter(_ > 0))
      .recover { case _ => None }
  private def keyNameOf(keyId: String): Future[String]                           =
    if (keyId == "memory-extraction") Future.successful("Memory")
    else
      ApiKeyStore
        .load()
        .map(keys =>
          keys
            .find(_.id == keyId)
            .map(_.name.trim)
            .filter(_.nonEmpty)
            .getOrElse(s"key ${keyId.take(8)}")
        )
        .recover { case _ => s"key ${keyId.take(8)}" }
  private def persist(windowId: Int, keyId: String, groupId: Int): Unit          =
    ChromeAPI.storage.session
      .set(js.Dictionary[js.Any](storageKey(windowId, keyId) -> groupId))
      .toFuture
      .recover { case _ => () }
      .foreach(_ => ())
  private def intOf(raw: Option[js.Any]): Option[Int]                            =
    raw.map(_.toString).flatMap(_.toIntOption).filter(_ > 0)
}
