package com.just3bowls.background.runtime

import com.just3bowls.facade.JsBridge
import com.just3bowls.background.webchat.*

import com.just3bowls.api.webchat.ConversationUrl
import com.just3bowls.facade.{ChromeAPI, Tab, TabActiveInfo, TabChangeInfo, TabGroup, TabRemoveInfo}
import com.just3bowls.model.provider.SessionFreshChats
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 「这条会话在用哪些 provider URL」的会话级视图（Chat 输入框上方 `urls: N ▾` 的数据源）。
 *
 * **绑定为准**：列表 = `thWebChatHosts` 里归属这条会话的绑定（每个 `(session, provider)` 一条唯一 URL）， 活 tab 只提供"当前 URL / now 标记 / 可点性"：
 *
 *   - 绑定行：`convKey` 找到活 tab（同一条不变式 `ConversationUrl.isExtensionOf`，URL 细化后仍算同一段）→ 行里用 tab 的当前 URL； 找不到活 tab（被关 / 还没打开）→
 *     用绑定 URL、`tabId = 0`（插件页点它会按需新开一个 tab 打开该 URL）。
 *
 * 同一个构建器（`sessionRows` / `sessionRowsForAll`）同时服务插件页的 `urls: N` 与网页原生胶囊的 session 列表，两处永远一致。 `browser_*`
 * 打开过的普通页面不进这个列表。行上的 × 表示 **结束这段 URL 在本会话里的使命**（见 `endUrl`），不是关 tab。浏览器 tab / 组变化与 `TabStore` 写入都会 debounce 后推
 * `SESSION_URLS_UPDATED`。
 */
private[background] object SessionUrls {
  private val PushDelayMs = 150

  /** 同时订阅的会话上限（插件页一次只看一条会话，留点余量给多窗口）。 */
  private val WatchedLimit = 5

  /** 插件页问过的会话（最近问的在后）；只给这些会话推更新。 */
  private val watched   = mutable.LinkedHashSet.empty[String]
  private var pushTimer = js.undefined: js.UndefOr[js.timers.SetTimeoutHandle]
  private var started   = false
  def start(): Unit     =
    if (!started) {
      started = true
      val touch = () => notifyChanged()
      ChromeAPI.tabs.onCreated.addListener((_: Tab) => touch())
      ChromeAPI.tabs.onRemoved.addListener((_: Int, _: TabRemoveInfo) => touch())
      ChromeAPI.tabs.onUpdated.addListener((_: Int, _: TabChangeInfo, _: Tab) => touch())
      ChromeAPI.tabs.onMoved.addListener((_: Int, _: js.Object) => touch())
      ChromeAPI.tabs.onAttached.addListener((_: Int, _: js.Object) => touch())
      ChromeAPI.tabs.onDetached.addListener((_: Int, _: js.Object) => touch())
      ChromeAPI.tabs.onActivated.addListener((_: TabActiveInfo) => touch())
      ChromeAPI.tabGroups.onCreated.addListener((_: TabGroup) => touch())
      ChromeAPI.tabGroups.onUpdated.addListener((_: TabGroup) => touch())
      ChromeAPI.tabGroups.onRemoved.addListener((_: js.Object) => touch())
      ChromeAPI.tabGroups.onMoved.addListener((_: js.Object) => touch())
      PopCanLog.debug("tabs", "session urls listeners installed")
    }

  /** 插件页问一次 = 订阅这条会话；之后 tab / 绑定有变化就推最新快照。 */
  def watch(sessionId: String): Future[js.Object] = {
    val id = sessionId.trim
    if (id.nonEmpty) {
      watched.remove(id)
      watched.add(id)
      while (watched.size > WatchedLimit) watched.remove(watched.head)
    }
    snapshot(id)
  }

  /** 有事发生：debounce 后给订阅过的会话各推一次（推失败 = 插件页没开，静默忽略）。 */
  def notifyChanged(): Unit =
    if (pushTimer.isEmpty) {
      pushTimer = js.timers.setTimeout(PushDelayMs.toDouble) {
        pushTimer = js.undefined
        pushWatched().foreach(_ => ())
      }
    }

  /**
   * 行上的 ×：结束这段 URL 在本会话里的使命。
   *
   * tab 不动；删掉这段 URL 的归属、把本会话从该 provider 的 slot 使用记录里摘掉，并记下「下次这个 provider 要开新对话」。
   */
  def endUrl(sessionId: String, provider: String, url: String): Future[Unit] =
    if (sessionId.isEmpty || provider.isEmpty) Future.successful(())
    else {
      val dropBinding = if (url.isEmpty) Future.successful(()) else WebChatHosts.remove(url).recover { case _ => () }
      dropBinding
        // 显式结束：让还记着这段归属的网页原生运行时也忘掉它（否则页面一导航又会自动绑回来）。
        .map { _ =>
          WebChatService.forgetBinding(url)
          ()
        }
        .flatMap(_ => ManagedTabManager.forgetSessionUsage(provider, sessionId).recover { case _ => () })
        .flatMap(_ => SessionFreshChats.mark(sessionId, provider, url))
        .map { _ =>
          notifyChanged()
          PopCanLog.info(
            "tabs",
            s"session url ended session=$sessionId provider=$provider url=${PopCanLog.short(url, 80)}"
          )
        }
        .recover { case e =>
          PopCanLog.warn("tabs", s"session url end failed session=$sessionId provider=$provider: ${e.getMessage}")
        }
    }
  private def pushWatched(): Future[Unit]                                    =
    Future
      .sequence(watched.toList.map { sessionId =>
        snapshot(sessionId).flatMap { payload =>
          val dynamic = JsBridge.asObject(payload)
          ChromeAPI.runtime
            .sendMessage(JsBridge.obj(
              "type"           -> ("SESSION_URLS_UPDATED"),
              "sessionId"      -> (sessionId),
              "latestProvider" -> (dynamic.latestProvider),
              "urls"           -> (dynamic.urls)
            ))
            .toFuture
            .map(_ => ())
            .recover { case _ => () }
        }
      })
      .map(_ => ())
      .recover { case e =>
        PopCanLog.warn("tabs", s"session urls push failed: ${e.getMessage}")
        ()
      }

  /** `{ok, sessionId, latestProvider, urls:[{tabId, url, provider, toolProfileId, isCurrent, isLast}]}`。 */
  def snapshot(sessionId: String): Future[js.Object] =
    if (sessionId.trim.isEmpty)
      Future.successful(payload("", Nil, latestProvider = ""))
    else
      for {
        rows  <- sessionRows(sessionId, currentUrl = "")
        hosts <- WebChatHosts.findBySession(sessionId).recover { case _ => Nil }
      } yield {
        // 插件页的 provider 选择器跟着这条会话"最后一次对话"的 provider 走（两侧的提交都会刷 updatedAt）。
        val latest = hosts.sortBy(-_.updatedAt).headOption.map(_.provider).getOrElse("")
        payload(sessionId, rows, latest)
      }

  /**
   * 两处 UI 共用的行集合（**绑定为准**）：这条会话的每个绑定一行；URL 优先取活 tab 的当前 URL（即跟随后的值）， 没有活 tab（被关 / 还没打开）时取绑定 URL、`tabId = 0`。
   *
   * 另外把 `TabStore` 里"这条会话用过、但归属表还没记这条 provider"的 slot 活 tab 补进来（兜底，不重复占行）。 `isCurrent` = 某活 tab 正显示这段对话（若给了
   * `currentUrl`）；`isLast` = 这段是这条会话最近聊过的那段。
   */
  def sessionRows(sessionId: String, currentUrl: String): Future[List[js.Object]] =
    if (sessionId.trim.isEmpty) Future.successful(Nil)
    else
      loadWorld()
        .map(world => toJsRows(sessionId, currentUrl, world))
        .recover { case e =>
          PopCanLog.warn("tabs", s"session urls snapshot failed session=$sessionId: ${e.getMessage}")
          Nil
        }

  /** 批量版：胶囊一次要显示多条 session，只查一遍 tabs / TabStore / 归属表。 */
  def sessionRowsForAll(sessionIds: List[String], currentUrl: String): Future[Map[String, List[js.Object]]] =
    loadWorld()
      .map(world => sessionIds.filter(_.nonEmpty).map(id => id -> toJsRows(id, currentUrl, world)).toMap)
      .recover { case _ => Map.empty[String, List[js.Object]] }
  final private case class World(tabs: List[Tab], hosts: List[WebChatHost])
  private def loadWorld(): Future[World]                                                                    =
    for {
      tabsRaw <- ChromeAPI.tabs.query(js.Dictionary[js.Any]()).toFuture.map(_.toList)
      hosts   <- WebChatHosts.list()
    } yield World(tabsRaw, hosts)
  private def toJsRows(sessionId: String, currentUrl: String, world: World): List[js.Object]                = {
    val hostRows = world.hosts.filter(_.sessionId == sessionId)
    val lastKey  = WebChatHosts.latestConversationOf(hostRows).map(_.convKey).getOrElse("")
    def liveTabOf(convKey: String): Option[Tab] =
      world.tabs.find(tab =>
        tab.url.toOption.exists(url => ConversationUrl.sameConversation(convKey, url))
      )
    // 1) 绑定行：归属表一 (session, provider) 只有一条，直接一行。
    val bound                                   = hostRows.map { host =>
      val tab = liveTabOf(host.convKey)
      Row(
        tabId = tab.flatMap(_.id.toOption).getOrElse(0),
        provider = host.provider,
        url = tab.flatMap(_.url.toOption).getOrElse(host.convKey),
        convKey = host.convKey,
        toolProfileId = host.toolProfileId,
        index = tab.flatMap(_.index.toOption).getOrElse(Int.MaxValue)
      )
    }
    bound
      .sortBy(row => (row.index, row.tabId))
      .map { row =>
        JsBridge.obj(
          "tabId"         -> (row.tabId),
          "url"           -> (row.url),
          "provider"      -> (row.provider),
          "toolProfileId" -> (row.toolProfileId),
          "isCurrent"     -> (currentUrl.nonEmpty &&
            (row.url == currentUrl || ConversationUrl.sameConversation(row.convKey, currentUrl))),
          "isLast"        -> (lastKey.nonEmpty && row.convKey == lastKey)
        )

      }
  }
  // ---------------------------------------------------------------- 合并
  final private case class Row(
      tabId: Int,
      provider: String,
      url: String,
      convKey: String,
      toolProfileId: String,
      index: Int = Int.MaxValue
  )
  private def payload(sessionId: String, rows: List[js.Object], latestProvider: String): js.Object          =
    JsBridge.obj(
      "ok"             -> (true),
      "sessionId"      -> (sessionId),
      "latestProvider" -> (latestProvider),
      "urls"           -> (js.Array(rows*))
    )

}
