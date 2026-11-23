package com.just3bowls.background.webchat

import com.just3bowls.facade.JsBridge
import com.just3bowls.background.bootstrap.*
import com.just3bowls.background.runtime.*
import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.automation.provider.*

import com.just3bowls.api.protocol.Bytes
import com.just3bowls.api.webchat.ConversationUrl
import com.just3bowls.api.memory.MemoryQuery
import com.just3bowls.api.marker.PromptArtifact
import com.just3bowls.api.provider.ProviderTurnDiagnostics
import com.just3bowls.api.webchat.WebChatErrorPresentation
import com.just3bowls.facade.{ChromeAPI, NativeRunAckResponse, StorageChange, Tab}
import com.just3bowls.model.chat.ChatMessage
import com.just3bowls.model.chat.ChatStore
import com.just3bowls.model.provider.ParserProviderInfo
import com.just3bowls.model.localapi.ApiKeyStore
import com.just3bowls.model.chat.{AgentToolRegistry, AgentToolSurface, SessionSettings, SessionSettingsStore}
import com.just3bowls.api.marker.{DeclaredToolPolicy, ProtocolRepair}
import com.just3bowls.model.chat.FinalSseProducer
import com.just3bowls.model.provider.ProviderRequestRule
import com.just3bowls.model.chat.RawChunkStreamDecoder
import com.just3bowls.model.provider.SessionFreshChats
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.diagnostics.TraceStore
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 「网页原生会话」：用户在 provider 网页上直接聊天，插件在后台旁听记账，并在"欠账窗口"里用 CDP Fetch 劫持把上下文塞进请求体。
 *
 * 归属以**原生对话 URL**（convKey）为准：tab 只是当前显示的容器，切到哪个 tab 就按它的 URL 查"这段对话属于哪条 session"； 未归属的对话只观察不记录。同一条 session 可以挂多段
 * provider 对话，每段各自记自己的水位线。
 */
private[background] object WebChatService {

  /**
   * 一轮原生问答的最长等待；超时就把这一轮丢掉、重开监听。
   *
   * 10 分钟：provider 进入自己的「深度搜索 / 思考」模式时会连着几分钟只吐思考帧（yuanbao 实测），答案很晚才来；
   * 这里与后台任务那条链路的流等待（`CdpStreamingSession.waitForResponse` 默认 10 分钟）保持同一个口径。
   */
  private val TurnTimeoutMs = 600000

  /** `pendingAssoc`（"刚新建 session，等它的新对话 URL 出现"）的有效期。 */
  private val PendingAssocTtlMs = 8000.0

  /** 网页原生回合落库的 marker reason 上限（防极端长思考把单条消息撑得过大）。 */
  private val MaxWebTurnThinking                       = 20000
  private def capWebTurnThinking(text: String): String =
    if (text.length <= MaxWebTurnThinking) text else text.take(MaxWebTurnThinking) + "…(truncated)"

  /** 点完 New Chat 后等 URL 落定的节奏（SPA 的地址变化是异步的；原地复位的 provider 会一直不变）。 */
  private val NewChatUrlWaitMs                  = 250
  private val NewChatUrlWaitAttempts            = 12
  private val runtimes                          = mutable.Map.empty[Int, Runtime]
  // ---------------------------------------------------------------- 对外 API
  /**
   * 这个 tab 现在承载的是**哪条会话**的原生对话（没有绑定 / 没见过这个 tab 时给 None）。
   *
   * `assoc` 会被导航中间态清掉，所以退回 `lastBinding`（"跟随当前 tab"用的那份记忆）。
   */
  def hostSessionId(tabId: Int): Option[String] =
    runtimes.get(tabId).flatMap(runtime => runtime.assoc.orElse(runtime.lastBinding)).map(_.sessionId)

  /** 内容脚本每次都问这个：当前 tab 的对话属于哪条 session、要不要补上下文。 */
  def stateFor(tabId: Int): Future[js.Object] =
    refreshTitles().flatMap(_ => refreshTab(tabId)).map(_ => stateJson(tabId))

  /**
   * 会话摘要列表（面板用）：每条 session 带上它在 `<session, provider>` 上的原生对话 URL。
   *
   * `isCurrent` = 这一段就是**调用方这个 tab 正在显示**的对话；`isLast` = 这条 session 里最近更新过的那一段（跨 provider 取 `updatedAt` 最大）。
   */
  def sessionOptions(tabId: Int): Future[js.Array[js.Any]] =
    for {
      sessions      <- ChatStore.listSessions()
      now           <- currentUrl(tabId).recover { case _ => "" }
      // 行集合与插件页共用 SessionUrls 的构建器：绑定为准（含没有活 tab 的行），活 tab 只提供当前 URL 与 now/last 标记。
      rowsBySession <- SessionUrls.sessionRowsForAll(sessions.map(_.id), now)
    } yield {
      js.Array(sessions.sortBy(-_.updatedAt).take(30).map { session =>
        JsBridge.obj(
          "id"        -> (session.id),
          "title"     -> (session.title),
          "provider"  -> (session.provider),
          "source"    -> (session.source),
          "updatedAt" -> (session.updatedAt),
          "urls"      -> (JsBridge.asObject(js.Array(rowsBySession.getOrElse(session.id, Nil)*)))
        )
      }*)
    }

  /** 把当前这段对话接到指定 session。 */
  def assign(tabId: Int, sessionId: String): Future[js.Object] =
    for {
      url      <- currentUrl(tabId)
      provider <- resolveProviderByUrl(url)
      _        <- provider match {
        case None            => Future.successful(())
        case Some((name, _)) =>
          SessionSettingsStore.load(sessionId).flatMap { settings =>
            WebChatHosts.upsertExclusive(
              WebChatHost(
                convKey = url,
                sessionId = sessionId,
                provider = name,
                lastTabId = tabId,
                toolProfileId = toolProfileFor(settings.browserTools)
              )
            )
          }
      }
      _        <- refreshTab(tabId)
    } yield stateJson(tabId)

  /**
   * 新建 session：创建 + 点 New Chat，**只有点到了**才把新对话 URL 绑上去。
   *
   * 点 New Chat 走的是插件页同一条 `CdpFlowExecutor.clickXPathViaCDP`（自己拿 CDP 租约、marker 优先、可见性/遮挡预检、真鼠标点击）—— 之前这里直接
   * `chrome.debugger.sendCommand` 却没拿租约，未归属（未 attach）的 tab 上命令必然失败，于是"点不动却把当前这段对话绑给新 session"。 点不到就删掉刚建的 session
   * 并报错，绝不改归属。
   */
  def newSession(tabId: Int): Future[js.Object] =
    for {
      before   <- currentUrl(tabId)
      provider <- resolveProviderByUrl(before)
      result   <- provider match {
        case None              => Future.successful(stateJson(tabId))
        case Some((name, cfg)) =>
          val xpath = Option(cfg.newChatSelector).getOrElse("").trim
          if (xpath.isEmpty) Future.failed(new Exception(s"$name has no New Chat selector configured"))
          else
            for {
              // 占位标题与插件页一致：首条用户消息到位后由 `ChatStore.appendWebTurn` 顶掉。
              session <- ChatStore.createWebSession(ChatStore.NewSessionTitle, name)
              clicked <- CdpFlowExecutor.clickXPathViaCDP(tabId, xpath, role = "new-chat", provider = name)
              _       <-
                if (clicked) Future.successful(())
                else
                  ChatStore.deleteSession(session.id).flatMap(_ =>
                    Future.failed(
                      new Exception(
                        s"New chat click failed on $name - the New Chat selector did not hit a clickable element"
                      )
                    )
                  )
              runtime <- Future.successful(runtimes.getOrElseUpdate(tabId, new Runtime(tabId)))
              _       <- SessionSettingsStore.ensure(session.id, SessionSettings.Default.browserTools)
              _       <- Future.successful {
                sessionBrowserTools.update(session.id, SessionSettings.Default.browserTools)
              }
              _       <- Future.successful {
                // 兜底：URL 稍晚才变（或变了但事件来得晚）时，这段时间内的新对话 URL 绑到这条新 session。
                runtime.pendingAssoc = Some(session.id)
                runtime.pendingAssocAt = js.Date.now()
              }
              newUrl  <- waitForUrlChange(tabId, before)
              _       <- WebChatHosts.upsertExclusive(
                WebChatHost(
                  convKey = newUrl,
                  sessionId = session.id,
                  provider = name,
                  lastTabId = tabId,
                  toolProfileId = toolProfileFor(SessionSettings.Default.browserTools)
                )
              )
              _       <- refreshTab(tabId)
              // 别的 provider tab 上的胶囊也要看到"这条新 session 已经是当前"。
              _       <- pushState(tabId)
              _       <- resyncHosts()
            } yield stateJson(tabId)
      }
    } yield result

  /**
   * 点完 New Chat 之后等 URL 落定：deepseek 这类 SPA 的地址变化是异步的（实测 <500ms）， 原地复位的 provider 则一直不变 —— 超时就接受当前值（"新对话"确实发生在同一个 URL 上）。
   */
  private def waitForUrlChange(tabId: Int, previous: String, attempt: Int = 0): Future[String] =
    currentUrl(tabId).flatMap { now =>
      if (now.nonEmpty && now != previous) Future.successful(now)
      else if (attempt >= NewChatUrlWaitAttempts) Future.successful(now)
      else sleep(NewChatUrlWaitMs).flatMap(_ => waitForUrlChange(tabId, previous, attempt + 1))
    }

  /** 归属表变了：把所有已知 tab 的胶囊状态刷一遍（新 session 立刻对每条 tab 可见）。 */
  private def resyncHosts(): Future[Unit] =
    runtimes.keys.toList.foldLeft(Future.successful(())) { (acc, id) =>
      acc.flatMap(_ => pushState(id)).recover { case _ => () }
    }

  /**
   * 插件页的 chat / Retry 轮**观察到 provider 请求后**把这条对话绑给会话（`thWebChatHosts`）。
   *
   * 绑定时机是"请求真的发出"，不是"tab 分配完"：附件未挂载、focus/type 失败等 pre-send 错误不能产生 session usage 或幽灵对话。 请求一旦发出，
   * 即使后续思考超时、流断开或解析失败，也要保留绑定，因为 provider 页面里的对话上下文已经真实变化。
   *
   * 只写 URL / tab / session / tool revision，**不动水位线**（`seedToMessageId` / `seedToBodyCount` / `lastTurnAt` 原样保留，
   * 绑定不等于跑完一轮）：没有水位线的对话，下一轮自然走全量投递。
   */
  def bindTabConversation(
      tabId: Int,
      sessionId: String,
      provider: String,
      toolProfileId: String = ""
  ): Future[Unit] =
    if (tabId <= 0 || sessionId.isEmpty || provider.isEmpty) Future.successful(())
    else
      writeConversationBinding(tabId, sessionId, provider, toolProfileId)
        .recover { case e => PopCanLog.warn("webchat", s"bind conversation failed tab=$tabId: ${e.getMessage}") }
  private def writeConversationBinding(
      tabId: Int,
      sessionId: String,
      provider: String,
      toolProfileId: String
  ): Future[Unit] =
    currentUrl(tabId).flatMap { url =>
      resolveProviderByUrl(url).flatMap {
        case Some((name, _)) if name == provider =>
          WebChatHosts
            .find(url)
            .flatMap {
              case some @ Some(_) => Future.successful(some)
              case None           =>
                // 页面可能把 URL 规范化 / 去掉一次性参数（gml 实测：导航过去之后 &refer__991 / &u_atoken / &u_asig 不见了）：
                // 同一条对话换个 URL 形态也要接着算，否则旧 key 会被 upsertExclusive 删掉、水位线跟着丢（下一轮退化成全量）。
                WebChatHosts.findBySession(sessionId).map { hosts =>
                  hosts.find(host => host.provider == provider && ConversationUrl.sameConversation(host.convKey, url))
                }
            }
            .flatMap { existing =>
              // 同一段对话已有记录时只搬 key / 补 tab 与时间，**不碰水位线**（绑定 ≠ 跑完一轮）。
              val keep = existing.filter(host => host.sessionId == sessionId && host.provider == provider)
              WebChatHosts.upsertExclusive(
                keep
                  .map(host =>
                    host.copy(
                      convKey = url,
                      lastTabId = tabId,
                      toolProfileId = if (toolProfileId.nonEmpty) toolProfileId else host.toolProfileId,
                      updatedAt = js.Date.now()
                    )
                  )
                  .getOrElse(WebChatHost(
                    convKey = url,
                    sessionId = sessionId,
                    provider = provider,
                    lastTabId = tabId,
                    toolProfileId = toolProfileId
                  ))
              )
            }
            .flatMap { _ =>
              PopCanLog.info(
                "webchat",
                s"bind conversation session=$sessionId provider=$provider tab=$tabId url=${PopCanLog.short(url, 80)}"
              )
              SessionUrls.notifyChanged()
              refreshTab(tabId)
            }
        case _                                   => Future.successful(())
      }
    }

  /**
   * 点列表里一条 session：
   *
   *   - 当前这段对话**未归属** → 接上去（用户是在"选一个会话"）；
   *   - 当前这段对话**已归属** → 切到该 session 最近那段对话（复用该 provider 的 tab 就地切 URL，见 `focusConversation`）。
   */
  def clickSession(tabId: Int, sessionId: String): Future[js.Object] =
    refreshTab(tabId).flatMap { _ =>
      val assoc = runtimes.get(tabId).flatMap(_.assoc)
      if (assoc.isEmpty) assign(tabId, sessionId)
      else
        // 失败（例如目标 tab 正忙）要冒到胶囊上，别静默吞掉。
        jumpToLatestConversation(sessionId, tabId).map(_ => stateJson(tabId))
    }

  /** 删掉一条 session：插件侧会话 + 消息 + 归属记录；provider 网页上的原生对话不动。 */
  def deleteSession(tabId: Int, sessionId: String): Future[js.Object] =
    if (sessionId.isEmpty) Future.successful(stateJson(tabId))
    else
      ChatStore
        .deleteSession(sessionId)
        .flatMap(_ => WebChatHosts.removeBySession(sessionId))
        .flatMap(_ => refreshAllRuntimes())
        .flatMap(_ => notifyPageDeleted(sessionId))
        .flatMap(_ => pushState(tabId))
        .map(_ => stateJson(tabId))
        .recover { case e =>
          PopCanLog.warn("webchat", s"delete session failed: ${e.getMessage}")
          stateJson(tabId)
        }
  private def refreshAllRuntimes(): Future[Unit]                      =
    runtimes.keys.toList.foldLeft(Future.successful(())) { (acc, tabId) =>
      acc.flatMap(_ => refreshTab(tabId))
    }

  /**
   * 手动重命名会话（胶囊每行的 ✎）。 只改标题：不碰 `updatedAt`、不碰归属与水位线；改完刷新标题缓存、推一次胶囊状态，并让插件页重新拉列表。
   */
  def renameSession(tabId: Int, sessionId: String, title: String): Future[js.Object] =
    if (sessionId.isEmpty) Future.successful(stateJson(tabId))
    else
      ChatStore
        .renameSession(sessionId, title)
        .flatMap(_ => refreshTitles())
        .flatMap(_ => notifyPage(sessionId))
        .flatMap(_ => pushState(tabId))
        .map(_ => stateJson(tabId))
        .recover { case e =>
          PopCanLog.warn("webchat", s"rename session failed: ${e.getMessage}")
          stateJson(tabId)
        }

  /** 切到该 session 最近那段对话；复用 provider tab 就地切 URL（细节见 `focusConversation`）。 */
  private def jumpToLatestConversation(sessionId: String, fromTabId: Int): Future[Unit] =
    WebChatHosts
      .findBySession(sessionId)
      // 与 `last` 徽标同一口径：最后一次**真正聊过**的那段（刚绑定没开聊的不算）。
      .map(hosts => WebChatHosts.latestConversationOf(hosts).map(_.convKey).getOrElse(""))
      .flatMap(convKey => focusConversation(convKey, fromTabId))

  /**
   * 切到某一段原生对话。**tab 是绑 provider 的，不是绑对话的**：优先复用该 provider 的 tab 就地 `tabs.update` 切 URL， 只有该 provider 一个 tab
   * 都没有时才在当前窗口新建（并归进它的 key group）。胶囊里点一行 URL 也走这里。
   *
   * `fromTabId` = 发起点（胶囊所在 tab）：同 provider 时优先就地切它，跨 provider 时优先它窗口里的 provider tab。
   *
   * 顺序：① 已经有 tab 停在这个 URL → 只激活；② 发起点同 provider → 用它；③ 该 provider 的 slot tab；④ 任何该 provider 的活 tab； ⑤ 都没有 → 新建。②③④
   * 命中时要先过 `tabBusy`（正在跑 agent / 有活动任务 / 正在产出一轮）——忙就拒绝，别把人家正在跑的东西顶掉。
   */
  def focusConversation(convKey: String, fromTabId: Int = -1): Future[Unit] =
    if (convKey.isEmpty) Future.successful(())
    else
      ChromeAPI.tabs.query(js.Dictionary[js.Any]()).toFuture.flatMap { tabsRaw =>
        val tabs   = tabsRaw.toList
        val exact  = tabs.find(tab => tab.url.toOption.contains(convKey)).flatMap(_.id.toOption)
        val window = tabs.find(tab => tab.id.toOption.contains(fromTabId)).flatMap(_.windowId.toOption)
        // 认不出 provider（配置缺失）时退回旧行为：精确 URL 的 tab 激活，否则当前窗口新建。
        val providerF: Future[Option[WebChatHost]] =
          WebChatHosts
            .list()
            .map(hosts => hosts.find(_.convKey == convKey))
            .recover { case _ => None }
            .flatMap {
              case some @ Some(_) => Future.successful(some)
              case None           =>
                resolveProviderByUrl(convKey).map(_.map { case (id, _) =>
                  WebChatHost(convKey = convKey, sessionId = "", provider = id)
                })
            }
        providerF.flatMap { hostOpt =>
          exact match {
            case Some(tabId) => ServerTabManager.focusServerTab(tabId)
            case None        =>
              val pageUrlF = hostOpt.map(host => ServerTabManager.loadProviderUrl(host.provider))
                .getOrElse(Future.successful(""))
              pageUrlF.flatMap { pageUrl =>
                switchToProviderTab(convKey, hostOpt, pageUrl, fromTabId, window, tabs).recoverWith { case e =>
                  PopCanLog.warn("webchat", s"focus conversation failed: ${e.getMessage}")
                  Future.failed(e)
                }
              }
          }
        }
      }

  /** 切对话时目标 tab 正忙的提示：和胶囊的错误行用的是同一份文案。 */
  private val TabBusyMessage =
    "This provider tab is busy (an agent run or a generation is in progress) - finish or stop it first."

  /** 分配器要切这个 tab 的对话前先问一句：它是不是正在跑（原生 agent run / 我们的任务 / 正在产出一轮）。 */
  private[background] def tabBusyForAllocator(tabId: Int): Boolean = tabBusy(tabId)

  /** 上面那句提示的对外文案（分配器直接复用，避免两处各写一份）。 */
  private[background] def tabBusyMessage: String = TabBusyMessage

  /** 这个 tab 上是不是正在跑东西：原生 agent run / 有活动的 CDP 任务 / 正在产出一轮回答。 */
  private def tabBusy(tabId: Int): Boolean =
    nativeRuns.forTab(tabId).exists(_.tabId == tabId) ||
      BackgroundState.activeCdpTasks.values.exists(_ == tabId) ||
      runtimes.get(tabId).exists(_.streaming)
  private def switchToProviderTab(
      convKey: String,
      hostOpt: Option[WebChatHost],
      pageUrl: String,
      fromTabId: Int,
      fromWindow: Option[Int],
      tabs: List[Tab]
  ): Future[Unit] = {
    def sameProvider(tab: Tab): Boolean =
      hostOpt.exists(_ => pageUrl.nonEmpty && tab.url.toOption.exists(url => urlMatches(pageUrl, url)))
    val providerTabs                    = tabs.filter(tab => tab.id.isDefined && sameProvider(tab))
    val senderTab                       = providerTabs.find(_.id.toOption.contains(fromTabId))
    val anyProviderTab                  =
      providerTabs.filter(tab => fromWindow.exists(w => tab.windowId.toOption.contains(w))) match {
        case sameWindow if sameWindow.nonEmpty =>
          sameWindow.find(_.active.toOption.getOrElse(false)).orElse(sameWindow.headOption)
        case _                                 =>
          providerTabs.find(_.active.toOption.getOrElse(false)).orElse(providerTabs.headOption)
      }
    val slotTabF                        = hostOpt match {
      case Some(host) =>
        TabStore
          .load()
          .map { store =>
            val slots = store.groups.get(host.provider).toList.flatMap(_.tabs.values).filter(_.liveTabId.isDefined)
            def pick(candidates: List[ManagedTab]): Option[Int] =
              candidates
                .filter(_.usedBySessionIds.contains(host.sessionId))
                .sortBy(-_.updatedAt)
                .headOption
                .orElse(candidates.sortBy(-_.updatedAt).headOption)
                .flatMap(_.liveTabId)
                .filter(tabId => tabs.exists(_.id.toOption.contains(tabId)))
            // slot 模型里没有 windowId：用活 tab 快照反查，优先发起点所在窗口。
            def windowOfSlot(slot: ManagedTab): Option[Int]     =
              slot.liveTabId.flatMap(id => tabs.find(_.id.toOption.contains(id)).flatMap(_.windowId.toOption))
            pick(slots.filter(slot => fromWindow.exists(w => windowOfSlot(slot).contains(w)))).orElse(pick(slots))
          }
          .recover { case _ => None }
      case None       => Future.successful(None)
    }
    slotTabF.flatMap { slotTab =>
      senderTab.flatMap(_.id.toOption)
        .orElse(slotTab)
        .orElse(anyProviderTab.flatMap(_.id.toOption)) match {
        case Some(tabId) =>
          if (tabBusy(tabId)) Future.failed(new RuntimeException(TabBusyMessage))
          else
            ChromeAPI.tabs
              .update(tabId, js.Dictionary[js.Any]("url" -> convKey, "active" -> true))
              .toFuture
              .flatMap(_ => ServerTabManager.focusServerTab(tabId))
              .flatMap(_ => refreshTab(tabId))
              .map(_ => ())
        case None        =>
          createConversationTab(convKey, fromWindow, hostOpt)
      }
    }
  }

  /** 该 provider 一个 tab 都没有：在当前窗口（发起点所在窗口）新建，并按 provider 的 key group 归组。 */
  private def createConversationTab(
      convKey: String,
      windowId: Option[Int],
      hostOpt: Option[WebChatHost]
  ): Future[Unit] = {
    val props = js.Dictionary[js.Any]("url" -> convKey, "active" -> true)
    windowId.foreach(id => props.update("windowId", id))
    ChromeAPI.tabs.create(props).toFuture.flatMap { created =>
      val tabId  = created.id.toOption.getOrElse(-1)
      val groupF = hostOpt match {
        case Some(host) =>
          groupKeyIdFor(host.provider, host.sessionId).flatMap { keyId =>
            ProviderTabGroup.attach(tabId, keyId).map(_ => ()).recover { case _ => () }
          }
        case None       => Future.successful(())
      }
      groupF.flatMap(_ => if (tabId > 0) refreshTab(tabId).map(_ => ()) else Future.successful(()))
    }
  }

  /** 新建的 provider tab 收进哪个 key group：这条会话用过的 slot key 优先，其次该 provider 最近用过的 slot，最后系统 key。 */
  private def groupKeyIdFor(provider: String, sessionId: String): Future[String] =
    TabStore
      .load()
      .map { store =>
        val slots = store.groups.get(provider).toList.flatMap(_.tabs.values)
        slots
          .filter(_.usedBySessionIds.contains(sessionId))
          .sortBy(-_.updatedAt)
          .headOption
          .orElse(slots.sortBy(-_.updatedAt).headOption)
          .map(_.keyId)
          .getOrElse(ApiKeyStore.SystemApiKeyId)
      }
      .recover { case _ => ApiKeyStore.SystemApiKeyId }

  /** 通知插件页"这条会话被删了"：同样广播给所有扩展页（多个插件页时只通知第一个会让其余的留着幽灵条目）。 */
  private def notifyPageDeleted(sessionId: String): Future[Unit] =
    ChromeAPI.runtime
      .sendMessage(
        JsBridge.obj("type" -> ("WEBCHAT_SESSION_DELETED"), "sessionId" -> (sessionId))
      )
      .toFuture
      .map(_ => ())
      .recover { case _ => () }

  /** tab 的 URL 变了（tabs.onUpdated）/ 激活变了：重新解析归属、开关观察与劫持窗口。 */
  def setCapsuleEnabled(tabId: Int, enabled: Boolean): Future[Unit] = {
    val existing = runtimes.get(tabId)
    if (!enabled && existing.isEmpty) Future.successful(())
    else {
      val runtime = existing.getOrElse {
        val created = new Runtime(tabId)
        runtimes.update(tabId, created)
        created
      }
      runtime.capsuleEnabled = enabled
      if (enabled)
        WebChatContentScripts
          .injectNow(tabId)
          .flatMap(_ => refreshTab(tabId))
          .flatMap(_ => pushState(tabId))
          .recover { case _ => () }
      else {
        runtime.assoc = None
        runtime.lastBinding = None
        runtime.turnAssoc = None
        stopObserving(runtime)
          .flatMap(_ => pushState(tabId))
          .recover { case _ => () }
      }
    }
  }
  private[background] def hasRuntime(tabId: Int): Boolean           = runtimes.contains(tabId)

  /** tab 的 URL / group 变了：重新解析归属、开关观察与劫持窗口。 */
  def refreshTab(tabId: Int): Future[Unit] = {
    val runtime = runtimes.getOrElseUpdate(tabId, new Runtime(tabId))
    for {
      url      <- currentUrl(tabId)
      provider <- resolveProviderByUrl(url)
      _        <- Future.successful(runtime.lastKnownUrl = url)
      _        <- provider match {
        case None                                      => stopObserving(runtime)
        case Some((_, cfg)) if !runtime.capsuleEnabled =>
          runtime.config = Some(cfg)
          stopObserving(runtime)
        case Some((name, cfg))                         =>
          val urlChanged = runtime.assoc.isEmpty || runtime.assoc.exists(_.convKey != url)
          for {
            _     <- runtime.pendingAssoc match {
              case Some(sessionId) if urlChanged                                         =>
                // "新建"之后页面换了对话（或者终于生成了对话 id）：把绑定挪到新 URL。
                WebChatHosts
                  .upsertExclusive(
                    WebChatHost(
                      convKey = url,
                      sessionId = sessionId,
                      provider = name,
                      lastTabId = tabId,
                      toolProfileId = toolProfileFor(
                        sessionBrowserTools.getOrElse(sessionId, SessionSettings.Default.browserTools)
                      )
                    )
                  )
                  .map { _ =>
                    PopCanLog.info(
                      "webchat",
                      s"fresh conversation session=$sessionId provider=$name " +
                        s"toolProfile=${PopCanLog.short(
                            toolProfileFor(
                              sessionBrowserTools.getOrElse(sessionId, SessionSettings.Default.browserTools)
                            ),
                            32
                          )}"
                    )
                  }
                  .flatMap(_ => Future.successful(runtime.pendingAssoc = None))
              case Some(_) if js.Date.now() - runtime.pendingAssocAt > PendingAssocTtlMs =>
                // 点了 New Chat 但 URL 一直没变（有些 provider 原地复位）：过期就丢掉，别让以后某次无关导航被绑到这条新 session。
                Future.successful(runtime.pendingAssoc = None)
              case _                                                                     => Future.successful(())
            }
            found <- WebChatHosts.findConversation(url)
            // 列表里按过 × 的 provider：这个页面上出现了一个未归属的新对话 URL 就自动绑回那条会话（`ended` 里的旧 URL 不会被回绑）。
            _     <- if (found.isEmpty) adoptPendingFresh(name, url, tabId) else Future.successful(())
            // 归属跟随当前 tab：这个 tab 原本绑在本会话的某段对话上，provider 自己换了号 / 页内 New Chat / 切了对话
            // → 把绑定挪到当前 URL（旧 URL 由 upsertExclusive 一并删掉），胶囊保持已关联。
            _     <- if (found.isEmpty) followAssoc(runtime, url, name, urlChanged, tabId) else Future.successful(())
            host  <- if (found.isEmpty) WebChatHosts.findConversation(url) else Future.successful(found)
            // 老记录（lastTabId=0）第一次被这个 tab 认领时补上归属，之后 URL 细化（`/chat` → `/chat/<id>`）才跟得动。
            _     <- host match {
              case Some(h) if h.lastTabId == 0 && h.sessionId.nonEmpty =>
                WebChatHosts.upsertExclusive(h.copy(lastTabId = tabId)).recover { case _ => () }
              case _                                                   => Future.successful(())
            }
            _     <- Future.successful(runtime.config = Some(cfg))
            _     <- Future.successful(runtime.assoc = host)
            // 记下"这个 tab 上一次属于哪段对话"，供导航到同 provider 的新对话时跟随（中间态清 assoc 也不会丢）。
            _     <- Future.successful(host.foreach(h => runtime.lastBinding = Some(h)))
            _     <- if (host.isDefined) ensureObserving(runtime)
            else
              runtime.turnAssoc match {
                case Some(turn) if turnInFlight(runtime) && turn.provider == name =>
                  // 这一轮还在飞、tab 却换了 URL（豆包实测：发送时 `.../chat/local_<id>` → `.../chat/<真实 id>`，
                  // 同一个对话被页面换了号）。这时**不能**把观察放掉——否则这一轮的回答直接丢；
                  // 归属挪到当前 URL（同 tab、同 provider，且这个 URL 还没人认领），会话保持不变。
                  PopCanLog.info(
                    "webchat",
                    s"turn in flight; re-binding tab=$tabId session=${turn.sessionId} old=${PopCanLog.short(turn.convKey)} new=${PopCanLog.short(url)}"
                  )
                  WebChatHosts
                    .upsertExclusive(turn.copy(convKey = url, lastTabId = tabId))
                    .flatMap(_ => WebChatHosts.find(url))
                    .flatMap { rebound =>
                      runtime.assoc = rebound
                      runtime.lastBinding = rebound
                      ensureObserving(runtime)
                    }
                    .recover { case e =>
                      PopCanLog.warn("webchat", s"re-bind in flight failed tab=$tabId: ${e.getMessage}")
                      ()
                    }
                case _                                                            =>
                  // 当前 URL 已明确不属于任何已绑定对话（例如手动 New Chat 回到 `/chat`）。
                  // 归属和"上一次绑定"都必须一起清掉，不能留给下一条请求或后续 URL 变化复用。
                  runtime.turnAssoc = None
                  runtime.lastBinding = None
                  stopObserving(runtime)
              }
            // 未归属也要跑：`refreshArm` 顺便把"当前生效的 context"刷新进 runtime（胶囊底部要显示它，未归属时显示全局默认）。
            _     <- refreshArm(runtime)
          } yield ()
      }
    } yield ()
  }

  /**
   * 归属跟随：**只跟"同一段对话的 URL 延伸"**（`/chat` → `/chat/<id>`、`#/c` → `#/chat/<id>`、追加 query 参数、 kimi 那种"新对话页 → 会话路由"）。
   *
   * 判定只有一条不变式（`ConversationUrl.isExtensionOf`）：URL **只增不减**——同 origin、路径 / hash 前缀延伸、旧 query 键值原样保留。 换
   * id、变短、参数被就地改写一律算新对话，**不跟** —— gml 那种"对话 id 在 query 里、手动 new chat 换 `t`"不会把绑定和水位线搬过去。
   *
   * 不跟的时候什么都不改（归属表里旧 URL 留着，当前 tab 显示未绑定）：会话里记的 URL 是他显式绑过的那一段，等他主动把新 URL 绑回这条会话时再用 `upsertExclusive` 替换该 provider 的旧
   * URL。新绑定进来的对话没有水位线，首投自然走全量。会话已经删掉时同样不绑。
   *
   * "同一段对话必须还在同一个 tab 上继续"由 `lastTabId` 保证：内存里的 `lastBinding`（同一 SW 生命周期内最快）优先； 丢了（SW 重启 / 扩展重载）就退回持久化记录里 `lastTabId ==
   * 本 tab` 的那条，不再依赖内存态。
   */
  private def followAssoc(
      runtime: Runtime,
      url: String,
      provider: String,
      urlChanged: Boolean,
      tabId: Int
  ): Future[Unit] = {
    def sameConversation(previous: WebChatHost): Boolean =
      previous.provider == provider && previous.convKey != url &&
        ConversationUrl.sameConversation(previous.convKey, url)
    def migrate(previous: WebChatHost): Future[Unit]     =
      sessionExists(previous.sessionId).flatMap { alive =>
        if (!alive) {
          runtime.lastBinding = None
          Future.successful(())
        } else {
          PopCanLog.info(
            "webchat",
            s"host follows tab (same conversation refined) session=${previous.sessionId} provider=$provider " +
              s"url=${PopCanLog.short(previous.convKey, 60)} -> ${PopCanLog.short(url, 60)}"
          )
          WebChatHosts
            .upsertExclusive(
              WebChatHost(
                convKey = url,
                sessionId = previous.sessionId,
                provider = provider,
                lastTabId = tabId,
                toolProfileId = previous.toolProfileId
              )
            )
            .recover { case _ => () }
        }
      }
    def skip(previous: WebChatHost): Future[Unit]        =
      PopCanLog.info(
        "webchat",
        s"host follows tab skipped (new conversation) provider=$provider " +
          s"bound=${PopCanLog.short(previous.convKey, 60)} now=${PopCanLog.short(url, 60)} -> unassigned"
      )
      Future.successful(())
    // 内存态优先；它没记着（SW 重启 / 第一次见这个 tab）就看持久化的 lastTabId。
    val inMemory  = runtime.lastBinding.filter(_.provider == provider).filter(_.convKey != url)
    val candidate = inMemory match {
      case Some(previous) if urlChanged => Future.successful(Some(previous))
      case _                            =>
        WebChatHosts
          .list()
          .map(_.find(host => host.provider == provider && host.convKey != url && host.lastTabId == tabId))
          .recover { case _ => None }
    }
    candidate.flatMap {
      case Some(previous) if sameConversation(previous) => migrate(previous)
      case Some(previous)                               => skip(previous)
      case None                                         => Future.successful(())
    }
  }

  private def sessionExists(sessionId: String): Future[Boolean] =
    if (sessionId.isEmpty) Future.successful(false)
    else
      ChatStore
        .listSessions()
        .map(sessions => sessions.exists(_.id == sessionId))
        .recover { case _ => true }

  /** 显式结束一段 URL（列表里按 ×）后，让还"记得"这段归属的 tab 忘掉它，避免下次导航又绑回来。 */
  def forgetBinding(convKey: String): Unit =
    if (convKey.nonEmpty) {
      runtimes.values.foreach { runtime =>
        val matched = runtime.assoc.exists(_.convKey == convKey) || runtime.lastBinding.exists(_.convKey == convKey)
        if (matched) {
          runtime.assoc = None
          runtime.lastBinding = None
          stopObserving(runtime).foreach(_ => ())
        }
      }
    }

  /** 归属 / 会话 / 上下文设置变了：所有已知 runtimes 重算归属并推一次状态（胶囊据此刷新）。 */
  def resyncAll(): Future[Unit] =
    // 覆盖**所有 provider tab**，而不只是已经有 runtime 的那些：归属写进去但那个 tab 从没 refresh 过时，
    // 胶囊仍旧停在 Unassigned（正是"归属有了、界面没跟上"的那种滞后）。
    ChromeAPI.tabs
      .query(js.Dictionary[js.Any]())
      .toFuture
      .map(_.toList)
      .recover { case _ => Nil }
      .flatMap { tabs =>
        tabs.foldLeft(Future.successful(())) { (acc, tab) =>
          acc.flatMap { _ =>
            tab.id.toOption match {
              case None        => Future.successful(())
              case Some(tabId) =>
                resolveProvider(tabId).flatMap {
                  case None    => Future.successful(())
                  case Some(_) =>
                    refreshTab(tabId).flatMap(_ => pushState(tabId)).recover { case _ => () }
                }
            }
          }
        }
      }
      .recover { case _ => () }

  /** 挂在 `chrome.storage.onChanged` 上：绑定的写入方可能是插件页 / 胶囊 / SW 自己，统一在这里重算。 */
  def onStorageChanged(changes: js.Dictionary[StorageChange], area: String): Unit =
    if (area == "local") {
      val changedSessionSettings =
        changes.keys.flatMap(SessionSettingsStore.sessionIdFromKey).toSet
      if (changedSessionSettings.nonEmpty) {
        Future
          .sequence(changedSessionSettings.toList.map { sessionId =>
            SessionSettingsStore.get(sessionId).map {
              case Some(settings) => sessionBrowserTools.update(sessionId, settings.browserTools)
              case None           => sessionBrowserTools.remove(sessionId)
            }
          })
          .flatMap(_ =>
            changedSessionSettings.toList.foldLeft(Future.successful(())) { (acc, sessionId) =>
              acc.flatMap(_ => resyncSession(sessionId))
            }
          )
          .recover { case _ => () }
          .foreach(_ => ())
      }
      // 会话现在是"一条记录一个 key"（`thChatS:<id>`），所以看前缀而不是某一个固定 key。
      val sessionListChanged     = changes.keys.exists(_.startsWith(ChatStore.SessionKeyPrefix))
      val bindingKeys            = List("thWebChatHosts", "thSessionProviderContext", "popCanChatContext")
      if (sessionListChanged || bindingKeys.exists(changes.contains)) {
        val prune =
          if (!sessionListChanged) Future.successful(())
          else
            ChatStore
              .listSessions()
              .flatMap { sessions =>
                val liveIds = sessions.map(_.id).toSet
                // 会话没了：清掉指向它的运行时记忆，否则跟随逻辑会把它又绑回来。
                val stale   = (h: WebChatHost) => !liveIds.contains(h.sessionId)
                runtimes.values.foreach { runtime =>
                  if (runtime.assoc.exists(stale) || runtime.lastBinding.exists(stale)) {
                    runtime.assoc = runtime.assoc.filterNot(stale)
                    runtime.lastBinding = runtime.lastBinding.filterNot(stale)
                  }
                }
                WebChatHosts.pruneOrphans(liveIds)
              }
              .recover { case _ => () }
        prune.flatMap(_ => resyncAll()).foreach(_ => ())
      }
    }

  /**
   * 「按下 × 之后下次用这个 provider 要新对话」在网页原生侧的收口：把当前这个未归属的对话 URL 绑给还在等它的那条会话。
   *
   * 没有待办 / URL 正是刚被结束掉的那段（`ended`）时什么都不做。
   */
  private def adoptPendingFresh(provider: String, url: String, tabId: Int): Future[Unit] =
    SessionFreshChats
      .newestSessionFor(provider, url)
      .flatMap {
        case None            => Future.successful(())
        case Some(sessionId) =>
          PopCanLog.info(
            "webchat",
            s"adopt fresh chat provider=$provider session=$sessionId url=${PopCanLog.short(url, 80)}"
          )
          WebChatHosts
            .upsertExclusive(WebChatHost(
              convKey = url,
              sessionId = sessionId,
              provider = provider,
              lastTabId = tabId,
              toolProfileId = toolProfileFor(sessionBrowserTools.getOrElse(sessionId, false))
            ))
            .flatMap(_ => SessionFreshChats.clear(sessionId, provider))
      }
      .recover { case _ => () }
  def refreshWindows(): Unit                                                             = {
    runtimes.values.toList.foreach { runtime =>
      isActiveTab(runtime.tabId).foreach { active =>
        if (active && runtime.capsuleEnabled && runtime.assoc.isDefined) refreshArm(runtime)
        else if ((!active || !runtime.capsuleEnabled) && runtime.hijacking) disarm(runtime)
      }
    }
    // 活动 tab 重新按实际 group 对账；只有 System Default group 会被武装胶囊。
    ChromeAPI.tabs.query(js.Dictionary[js.Any]("active" -> true)).toFuture.foreach { tabs =>
      tabs.toList.flatMap(_.id.toOption).foreach { tabId =>
        TabOwnership.reconcileTab(tabId, "active").foreach(_ => ())
      }
    }
  }

  /** tab 关掉：停止观察，并把"没有任何 tab 还在显示"的对话从归属表里清掉。 */
  def onTabClosed(tabId: Int): Future[Unit] =
    stopRuntime(tabId).flatMap { _ =>
      ChromeAPI.tabs.query(js.Dictionary[js.Any]()).toFuture
        .map(tabs => tabs.toList.flatMap(_.url.toOption).toSet)
        .recover { case _ => Set.empty[String] }
        .flatMap(liveUrls => WebChatHosts.list().map(hosts => hosts.map(_.convKey).filterNot(liveUrls.contains)))
        .flatMap(stale =>
          stale.foldLeft(Future.successful(()))((acc, key) => acc.flatMap(_ => WebChatHosts.remove(key)))
        )
        .recover { case _ => () }
    }

  /** SW 启动 / 扩展重载恢复：注册内容脚本，并把"URL 已归属"的 tab 重新接上。 */
  def restore(): Unit =
    // 这个功能任何一步都不该把整个 SW 的启动打断：整体兜一层，出问题只记日志。
    CdpSession.setReattachHook(tabId => onReattached(tabId))
    try { restoreInner() }
    catch { case e: Throwable => PopCanLog.error("webchat", "restore failed", e) }

  /** 意外 detach 后租约自动重挂成功：把观察会话重新挂上（旧会话已经被取消）。 */
  private def onReattached(tabId: Int): Unit =
    runtimes.get(tabId).foreach { runtime =>
      if (runtime.capsuleEnabled && runtime.observing && runtime.config.isDefined) {
        PopCanLog.info("webchat", s"re-attached, restarting observation tab=$tabId")
        applyMarkers(tabId)
        startTurn(runtime)
        refreshArm(runtime).foreach(_ => ())
      }
    }
  private def restoreInner(): Unit           =
    WebChatContentScripts.ensureRegistered().foreach(_ => TabOwnership.reconcileAll().foreach(_ => ()))

  // ---------------------------------------------------------------- 状态
  private def stateJson(tabId: Int): js.Object = {
    val runtime     = runtimes.get(tabId)
    val assoc       = runtime.flatMap(_.assoc)
    val cdpDetail   = Option(CdpSession.lastError(tabId)).filter(_.nonEmpty)
    val canTakeover = runtime.exists(runtime =>
      runtime.config.exists(config => ProviderRequestRule.hasRule(ruleOf(config)))
    )
    val run         = assoc.flatMap(host => nativeRuns.find(host.sessionId, host.provider))
    WebChatStateView.encode(
      tabId,
      runtime,
      sessionTitles,
      cdpDetail,
      run,
      canTakeover
    )
  }

  private def toolProfileFor(browserTools: Boolean): String =
    AgentToolSurface(AgentToolRegistry.chatToolsFor(browserTools)).revision

  /** 记录一条可持续展示、可复制、可用稳定 id 去重的页面侧错误。 */
  private def recordError(runtime: Runtime, id: String, detail: String): Unit = {
    val text = Option(detail).getOrElse("").trim
    if (text.nonEmpty) {
      runtime.lastErrorId = if (id.trim.nonEmpty) id.trim else s"web:${runtime.tabId}:${js.Date.now()}"
      runtime.lastErrorDetail = if (text.length <= 8000) text else text.take(8000) + "…(truncated)"
      runtime.lastErrorAt = js.Date.now()
    }
  }
  private def clearError(runtime: Runtime): Unit                              = {
    runtime.lastErrorId = ""
    runtime.lastErrorDetail = ""
    runtime.lastErrorAt = 0.0
  }
  private val sessionTitles                                                   = mutable.Map.empty[String, String]

  /** Derived cache of `thSessionSettings:v1:<sessionId>`; storage remains the only source of truth. */
  private val sessionBrowserTools                                            = mutable.Map.empty[String, Boolean]
  private def refreshSessionSettings(sessionIds: List[String]): Future[Unit] = {
    val ids     = sessionIds.distinct.filter(_.nonEmpty)
    val missing = ids.filterNot(sessionBrowserTools.contains)
    SessionSettingsStore
      .migrate(ids)
      .flatMap { _ =>
        Future
          .sequence(missing.map(sessionId =>
            SessionSettingsStore
              .load(sessionId)
              .map(settings => sessionId -> settings.browserTools)
          ))
          .map { loaded =>
            sessionBrowserTools.filterInPlace((sessionId, _) => ids.contains(sessionId))
            loaded.foreach { case (sessionId, enabled) => sessionBrowserTools.update(sessionId, enabled) }
            ()
          }
      }
      .recover { case _ => () }
  }

  /** 会话标题缓存（胶囊 chip / 面板头部用）：读一次会话表就补齐，别再让状态用上一次的旧标题。 */
  private def refreshTitles(): Future[Unit]       =
    ChatStore
      .listSessions()
      .flatMap { sessions =>
        val ids = sessions.map(_.id)
        sessionTitles.filterInPlace((sessionId, _) => ids.contains(sessionId))
        sessions.foreach(session => sessionTitles.update(session.id, session.title))
        refreshSessionSettings(ids)
      }
      .recover { case _ => () }
  private def pushState(tabId: Int): Future[Unit] =
    refreshTitles().flatMap { _ =>
      ChromeAPI.tabs
        .sendMessage(
          tabId,
          JsBridge.obj("type" -> ("WEBCHAT_STATE"), "state" -> (stateJson(tabId)))
        )
        .toFuture
        .map(_ => ())
        .recover { case _ => () }
    }

  private def resyncSession(sessionId: String): Future[Unit] =
    runtimes.values.toList
      .filter(_.assoc.exists(_.sessionId == sessionId))
      .foldLeft(Future.successful(())) { (acc, runtime) =>
        acc.flatMap(_ => pushState(runtime.tabId).recover { case _ => () })
      }
  // ---------------------------------------------------------------- 观察 / attach
  /** 网页原生接管的 tab 也要把 new-chat / input 标记打上，触发反馈才有地方可闪。 */
  private def applyMarkers(tabId: Int): Unit                 =
    ChromeAPI.tabs
      .sendMessage(tabId, JsBridge.obj("type" -> ("LOCATION_APPLY")))
      .toFuture
      .recover { case _ => () }
      .foreach(_ => ())
  private def ensureObserving(runtime: Runtime): Future[Unit] =
    // 已经观察中也要顺手校验连接还在（自己摘 / 外部抢走都不会触发 onDetach，缓存会过期）。
    if (runtime.observing) CdpSession.ensureHealthy(runtime.tabId)
    else
      ContentInjection
        .injectLocation(runtime.tabId)
        .recover { case _ => () }
        .flatMap { _ =>
          ensureAttached(runtime).map { _ =>
            runtime.observing = true
            applyMarkers(runtime.tabId)
            startTurn(runtime)
            ()
          }
        }
        .recover { case e =>
          recordError(
            runtime,
            s"attach:${runtime.tabId}:${js.Date.now()}",
            s"attach failed: ${Option(e.getMessage).getOrElse(e.toString)}"
          )
          ()
        }

  /** 这一轮回答还在飞（请求已发出、规则还没判完成）。 */
  private def turnInFlight(runtime: Runtime): Boolean               =
    runtime.streaming && runtime.parser != null && !runtime.parser.hasFinishSignal
  private def stopObserving(runtime: Runtime): Future[Unit]         = {
    runtime.observing = false
    runtime.streaming = false
    runtime.assoc = None
    runtime.pendingDelta = Nil
    runtime.pendingDeltaReady = false
    // 本轮的用户原话只属于"这一轮"：观察停掉就作废，免得下一轮误用它。
    runtime.turnUserText = ""
    disarm(runtime)
    runtime.session = None
    runtime.rawDecoder = null
    // 释放自己的长租约：同 tab 上还有别的 owner（例如任务）时只是减少一个引用，不会 detach 别人。
    runtime.lease match {
      case None      => Future.successful(())
      case Some(own) =>
        runtime.lease = None
        own.release("stop observing")
    }
  }
  private def stopRuntime(tabId: Int): Future[Unit]                 =
    runtimes.remove(tabId) match {
      case None          => Future.successful(())
      case Some(runtime) =>
        runtime.lastBinding = None
        stopObserving(runtime).map(_ => runtime.assoc = None)
    }
  private def ensureAttached(runtime: Runtime): Future[Unit]        =
    CdpSession.acquire(runtime.tabId, webchatOwner(runtime)).map(acquired => runtime.lease = Some(acquired))
  private def webchatOwner(runtime: Runtime): String                = s"webchat:${runtime.tabId}"
  private def startTurn(runtime: Runtime): Unit                     =
    if (!runtime.observing || runtime.config.isEmpty) ()
    else {
      val provider = runtime.assoc.map(_.provider).getOrElse("")
      runtime.turnAssoc = runtime.assoc
      runtime.turnAllowedTools = Set.empty
      runtime.turnBrowserTools = false
      runtime.turnToolProfileId = ""
      runtime.content.clear()
      // 新的一轮开始：清掉上一轮的错误，免得胶囊一直挂着旧报错。
      clearError(runtime)
      runtime.turnGen = s"web-${runtime.tabId}-${js.Date.now()}"
      runtime.rawDecoder = new RawChunkStreamDecoder
      // handler 命中统计也进 trace：网页原生这条链路以前什么都不打点，出问题时只能靠肉眼翻页面 DOM。
      runtime.parser = new LocalRawParser(
        runtime.config.map(ruleOf).getOrElse(""),
        handler => TraceStore.noteHandler(runtime.turnGen, handler)
      )
      runtime.hijackUserText = ""
      runtime.turnTaskDriven = None
      val session  = new CdpStreamingSession(
        tabId = runtime.tabId,
        apiPatterns = runtime.config.map(_.apiPatterns).getOrElse(Nil),
        generationId = runtime.turnGen,
        onRaw = chunk => handleRaw(runtime, chunk),
        provider = provider,
        onRequest = (url, _, postData) => {
          runtime.pendingUrl = url
          runtime.lastPostData = postData
          runtime.streaming = true
          // 本轮的"用户原话"快照：CDP 的 `Network.requestWillBeSent` 早于 `Fetch` 暂停/改写，所以这里读到的是**改写前**正文；
          // 先到先得（同一轮后续请求事件不覆盖），真正改写发生时 `decideHijack` 会用权威值再覆盖一次。
          if (runtime.turnUserText.isEmpty && postData.nonEmpty) {
            runtime.turnUserText = runtime.config
              .map(config =>
                ProviderRequestRule
                  .readUserText(ruleOf(config), Bytes.fromPostDataString(postData))
                  .getOrElse("")
              )
              .getOrElse("")
          }
          if (runtime.turnTaskDriven.isEmpty) {
            // 采样要发生在"任务还在跑"的时刻：这一轮是谁驱动的，看此刻 tab 上挂着谁的租约 / 改写计划。
            val driven = taskOwnsTab(runtime.tabId)
            runtime.turnTaskDriven = Some(driven)
            if (driven) {
              val owner = RequestRewrite.ownerOf(runtime.tabId).filter(_.startsWith("task:")).getOrElse("task:*")
              TraceStore.noteEvidence(runtime.turnGen, s"turn driven by $owner; not recording")
            }
          }
          rememberRequest(provider, url, postData)
        },
        followUpPatterns = runtime.config.map(_.followUpPatterns).getOrElse(Nil),
        followUpIdentityPath = runtime.config.map(_.followUpIdentityPath).getOrElse(""),
        isTurnComplete = () => runtime.parser.hasFinishSignal
      )
      runtime.session = Some(session)
      CdpSession.register(runtime.tabId, webchatOwner(runtime), session)
      session.waitForResponse(TurnTimeoutMs).onComplete { result =>
        if (runtime.observing) {
          result.failed.foreach(e => PopCanLog.debug("webchat", s"turn ended: ${e.getMessage}"))
          finishTurn(runtime).foreach(_ => startTurn(runtime))
        }
      }
    }
  private def handleRaw(runtime: Runtime, chunk: CdpRawChunk): Unit =
    try {
      val terminal = chunk.done || chunk.error.nonEmpty
      val decoded  = runtime.rawDecoder.decode(chunk.data, chunk.base64, terminal)
      if (decoded.nonEmpty) TraceStore.noteRaw(runtime.turnGen, decoded)
      // 终态分片是"空 data + done=true"，**不能**因为它空就跳过：`finish()` 才会让 ContentPipeline
      // 对完整正文做最终变换并释放尾部（gml 实测：绕过 finish 会丢 `@@END:response@@`）。
      if (decoded.nonEmpty || terminal) {
        val buffered = new mutable.ArrayBuffer[LocalParsedDelta]
        if (decoded.nonEmpty) buffered ++= runtime.parser.feed(decoded, chunk.sourceUrl)
        if (terminal) buffered ++= runtime.parser.finish()
        val deltas   = buffered.toList
        deltas.foreach { delta =>
          // 正文过滤回退：这一轮已经累积的归一化正文作废，从这份重新开始（append-only 的"回退"通道）。
          if (delta.resetContent) runtime.content.clear()
          runtime.content.append(delta.contentDelta)
          TraceStore.noteNormalized(runtime.turnGen, delta.contentDelta, reset = delta.resetContent)
        }
        if (terminal) TraceStore.noteNormalizedFinal(runtime.turnGen, runtime.parser.filteredContent)
      }
    } catch {
      case e: Throwable => PopCanLog.warn("webchat", s"normalize observed chunk failed: ${e.getMessage}")
    }
  private def finishTurn(runtime: Runtime): Future[Unit]            =
    runtime.streaming = false
    runtime.rawDecoder = null
    runtime.assoc match {
      case None       =>
        PopCanLog.debug("webchat", s"observed a turn on an unassigned conversation tab=${runtime.tabId}; not recording")
        runtime.turnUserText = ""
        Future.successful(())
      case Some(host) =>
        val parserContent = Option(runtime.parser).map(_.filteredContent).getOrElse("")
        val normalized    = if (parserContent.nonEmpty) parserContent else runtime.content.toString
        if (normalized.trim.isEmpty) {
          // 规则判定"这一轮结束"却一个字都没解析出来：别再静默（用户看到的就是"网页有回答、插件页没有"）。
          val parser    = runtime.parser
          TraceStore.noteEvidence(
            runtime.turnGen,
            s"parserMode=${if (parser.currentMode.isEmpty) "unknown" else parser.currentMode}"
          )
          val provider  = host.provider
          val noContent = parser.noContentAtEnd.map(_ =>
            ProviderTurnDiagnostics.noContent(provider, parser.rawChars, parser.currentMode)
          )
          Option(parser.currentError).filter(_.nonEmpty).orElse(noContent).foreach { reason =>
            TraceStore.noteEvidence(runtime.turnGen, s"parserCensus ${parser.census}")
            recordError(runtime, runtime.turnGen, reason)
            PopCanLog.warn("webchat", s"turn parsed to nothing tab=${runtime.tabId}: $reason")
          }
          runtime.turnUserText = ""
          pushState(runtime.tabId)
        } else if (runtime.turnTaskDriven.contains(true)) {
          // 这一轮是插件页任务驱动的：消息记账是插件页的事（它自己写 user + 成功/失败的 assistant），
          // 观察器再追加一对 [user, assistant] 就是同一句话记两次。这里只刷新胶囊状态，不写消息、不写水位线。
          PopCanLog.debug("webchat", s"turn driven by task tab=${runtime.tabId}; not recording")
          runtime.turnUserText = ""
          refreshTab(runtime.tabId).flatMap(_ => pushState(runtime.tabId))
        } else {
          val userText = resolveUserText(runtime)
          // 这一轮已经记账（或已交给插件页 agent 跑）：本轮的用户原话用完即清，别漏给下一轮。
          runtime.turnUserText = ""
          handoffOrRecord(runtime, host, userText, normalized)
        }
    }
  private def resolveUserText(runtime: Runtime): String =
    // 顺序：本轮快照（改写前原话）→ 改写时截下来的原话 → 最后才退回"读最后一次看到的请求体"（注入之后那份可能已是模版）。
    val raw =
      if (runtime.turnUserText.nonEmpty) runtime.turnUserText
      else if (runtime.hijackUserText.nonEmpty) runtime.hijackUserText
      else
        runtime.config
          .map(config =>
            ProviderRequestRule
              .readUserText(ruleOf(config), Bytes.fromPostDataString(runtime.lastPostData))
              .getOrElse("")
          )
          .getOrElse("")
    PromptArtifact.userTextOrOriginal(raw)

  /** “没改内容再发”沿用既有语义，但两边都先剥离插件 prompt 包装。 */
  private def isRegenerate(userText: String, host: WebChatHost): Boolean =
    PromptArtifact.sameUserTurn(userText, host.lastUserText)
  // ---------------------------------------------------------------- 原生轮 → 插件页 agent
  /** 原生首轮的解析结果（只需 content / thinking / toolCalls，和 marker 解析器解耦）。 */
  final private case class AgentRound(content: String, thinking: String, toolCalls: List[(String, String)])

  /** 按 runId 记账（不是 convKey）：provider 在跑的过程中可能给对话 URL 换形，会话 × provider 才是稳定身份。 */
  private val nativeRuns = new NativeRunRegistry

  /**
   * 原生这一轮要不要接 agent：只有「有归属会话 + browser-tools on + marker 解析成功 + 模型确实发了工具轮」才接。
   *
   * 直接作答 / 关工具 / 解析失败都保持今天的一轮行为；同一会话×provider 已经有一个 run 在跑时也不重入。交接失败（插件页开不出来 / 不 ack）同样退回一轮行为，并把模型原文留给用户看。
   */
  private def handoffOrRecord(runtime: Runtime, host: WebChatHost, userText: String, normalized: String): Future[Unit] =
    agentRoundOf(runtime, normalized).flatMap {
      case Some(round) =>
        handoffToAgent(runtime, host, userText, round).recoverWith { case e =>
          PopCanLog.warn("webchat", s"native agent handoff failed: ${e.getMessage}; recording this turn only")
          TraceStore.noteEvidence(
            runtime.turnGen,
            s"native agent handoff failed: ${PopCanLog.short(e.getMessage, 160)}"
          )
          record(runtime, host, userText, normalized, forceRaw = true)
        }
      case None        => record(runtime, host, userText, normalized)
    }
  private def agentRoundOf(runtime: Runtime, normalized: String): Future[Option[AgentRound]] = {
    val convKey = runtime.assoc.map(_.convKey).getOrElse("")
    val active  = runtime.assoc.exists(host => nativeRuns.exists(host.sessionId, host.provider))
    // 注意：稳态 delta 轮**本来就不注入**（用户在输入框打的那句就是增量），所以这里不能要求 `hijackUserText` 非空 ——
    // 只要这一轮真的按 marker 协议回了工具调用，就说明这段对话在我们协议之下，可以交给 agent 接着跑。
    runtime.assoc match {
      case None if convKey.isEmpty => Future.successful(None)
      case _ if active             => Future.successful(None)
      case Some(_)                 =>
        try {
          Future.successful(
            new FinalSseProducer(runtime.turnGen, runtime.assoc.map(_.provider).getOrElse(""))
              .parseMarkersText(normalized)
              .toOption
              .filter(result =>
                DeclaredToolPolicy
                  .validate(result.content, result.toolCalls, runtime.turnAllowedTools)
                  .isEmpty
              )
              .map(result => AgentRound(result.content, result.thinking, result.toolCalls))
              .filter(_.toolCalls.nonEmpty)
          )
        } catch { case _: Throwable => Future.successful(None) }
      case None                    => Future.successful(None)
    }
  }
  private def handoffToAgent(
      runtime: Runtime,
      host: WebChatHost,
      userText: String,
      round: AgentRound
  ): Future[Unit] = {
    val runId   = s"native-${runtime.tabId}-${js.Date.now()}"
    val payload = JsBridge.obj(
      "type"              -> ("NATIVE_RUN_START"),
      "runId"             -> (runId),
      "sessionId"         -> (host.sessionId),
      "provider"          -> (host.provider),
      "convKey"           -> (host.convKey),
      "tabId"             -> (runtime.tabId),
      "userText"          -> (userText),
      "treatAsRegenerate" -> (isRegenerate(userText, host)),
      "useBrowserTools"   -> (runtime.turnBrowserTools),
      "firstRound"        -> (JsBridge.obj(
        "content"   -> (round.content),
        "thinking"  -> (round.thinking),
        "toolCalls" -> (js.Array(
          round.toolCalls.map { case (name, arguments) =>
            JsBridge.obj("name" -> (name), "arguments" -> (arguments))
          }*
        ))
      ))
    )
    for {
      pageTabId <- ServerTabManager.ensurePluginPageTab()
      ack       <- sendToPageWithRetry(pageTabId, payload, attempts = 40)
      nativeAck   = ack.asInstanceOf[NativeRunAckResponse]
      accepted    = nativeAck.ok.getOrElse(false)
      userId      = nativeAck.userId.getOrElse("")
      assistantId = nativeAck.assistantId.getOrElse("")
      _ <- if (!accepted) Future.failed(new Exception("plugin page rejected the native run"))
      else {
        nativeRuns.put(
          NativeRun(
            runId,
            host.sessionId,
            host.provider,
            host.convKey,
            runtime.tabId,
            host,
            pageTabId,
            assistantId
          )
        )
        commitNativeStart(host, userId, runtime.tabId)
      }
      _ <- pushState(runtime.tabId)
    } yield ()
  }

  /**
   * 交接成功后立刻把水位线推到「用户这一句」：agent 的第一条驱动轮（工具结果）因此只投新增内容，不会把共享的上下文再发一遍。 `seedToBodyCount` 归零：那条计数只服务一轮内部的合成消息裁剪，跨轮用 id
   * 更准（详见 `TemplateInjection` 的裁剪注释）。
   */
  private def commitNativeStart(host: WebChatHost, userId: String, tabId: Int): Future[Unit] =
    if (userId.isEmpty) Future.successful(())
    else
      WebChatHosts
        .upsertExclusive(
          host.copy(seedToMessageId = userId, seedToBodyCount = 0, lastTabId = tabId, updatedAt = js.Date.now())
        )
        .map(_ => ())

  /** 给插件页发一条消息并等它 ack（语义由 payload 自己定：原生 run / 原生失败重试）。 */
  private def sendToPageWithRetry(pageTabId: Int, payload: js.Object, attempts: Int): Future[js.Any] = {
    // 只重试"页面还没挂监听"这类发送失败；页面明确回了 ok=false 说明它收到了但拒了，重试没意义。
    def attempt(left: Int): Future[js.Any] =
      ChromeAPI.tabs
        .sendMessage(pageTabId, payload)
        .toFuture
        .map(JsBridge.asObject(_))
        .recoverWith { case e =>
          if (left > 0) delay(300).flatMap(_ => attempt(left - 1)) else Future.failed(e)
        }
    attempt(attempts)
  }

  /** 原生 agent 写了进度：广播给所有插件页，让正显示这条会话的页面刷新（不重拉会话树时只是列表刷新）。 */
  def nativeRunProgress(sessionId: String): Unit =
    if (sessionId.nonEmpty) {
      notifyPage(sessionId).foreach(_ => ())
      nativeRuns.findForSession(sessionId).foreach(run => pushState(run.tabId).foreach(_ => ()))
    }

  /**
   * 原生 agent 跑完（成功 / 失败 / 取消）：落最终水位线、重新武装观察、刷新胶囊。
   *
   * `protocolFailure` = 这次失败是 marker 协议类：把这一轮交回插件页自动重试（预算在页面侧）。
   */
  def nativeRunDone(runId: String, ok: Boolean, error: String, protocolFailure: Boolean = false): Future[Unit] =
    nativeRuns.remove(runId) match {
      case None      => Future.successful(())
      case Some(run) =>
        runtimes.get(run.tabId).foreach { runtime =>
          if (ok) clearError(runtime)
          else if (error.nonEmpty && !WebChatErrorPresentation.isCancellation(error))
            recordError(runtime, runId, error)
        }
        if (!ok && error.nonEmpty)
          TraceStore.noteEvidence(runId, s"native run failed: ${PopCanLog.short(error, 160)}")
        ChatStore
          .tipMessageId(run.sessionId)
          .flatMap { tip =>
            WebChatHosts
              .find(run.convKey)
              // provider 跑的过程中可能给 URL 换了形：退回按会话 × provider 找当前那段对话。
              .flatMap {
                case Some(host) => Future.successful(Some(host))
                case None       => WebChatHosts.findBySession(run.sessionId).map(_.find(_.provider == run.provider))
              }
              .flatMap {
                case Some(current) =>
                  WebChatHosts
                    .upsertExclusive(
                      current.copy(
                        seedToMessageId = tip.getOrElse(current.seedToMessageId),
                        seedToBodyCount = 0,
                        lastTabId = run.tabId,
                        lastTurnAt = js.Date.now(),
                        updatedAt = js.Date.now()
                      )
                    )
                    .map(_ => ())
                case None          => Future.successful(())
              }
          }
          .flatMap(_ => notifyPage(run.sessionId))
          .flatMap(_ => refreshTab(run.tabId))
          .flatMap(_ => pushState(run.tabId))
          .flatMap { _ =>
            if (!ok && protocolFailure && run.assistantId.nonEmpty)
              requestNativeRetry(
                run.sessionId,
                run.provider,
                run.convKey,
                run.tabId,
                run.assistantId,
                error
              ).recover { case e =>
                PopCanLog.warn("webchat", s"native retry request failed: ${e.getMessage}")
                ()
              }
            else Future.successful(())
          }
          .recover { case e => PopCanLog.warn("webchat", s"native run finish failed: ${e.getMessage}") }
    }

  /** 胶囊的 Stop：把取消转给正在跑这条会话的插件页。 */
  def stopNativeRun(sessionId: String): Future[js.Object] =
    nativeRuns.findForSession(sessionId) match {
      case None      =>
        Future.successful(
          JsBridge.obj("ok" -> (false), "error" -> ("No native agent run is active"))
        )
      case Some(run) =>
        ChromeAPI.tabs
          .sendMessage(
            run.pageTabId,
            JsBridge.obj("type" -> ("NATIVE_RUN_CANCEL"), "runId" -> (run.runId))
          )
          .toFuture
          .map(_ => JsBridge.obj("ok" -> (true)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
    }

  /**
   * host 页 / provider tab 被关掉：对应的原生 run 收口 —— 把还挂着的 running assistant 标成 error，别让胶囊和会话永远停在 running。
   *
   * 页面被关 = 跑在页面里的 agent 循环已经没了；这里不再发取消，只把状态对齐。
   */
  def onTabRemoved(tabId: Int): Unit          =
    nativeRuns.forTab(tabId).foreach { run =>
      nativeRuns.remove(run.runId)
      PopCanLog.warn("webchat", s"native run host gone run=${run.runId} closedTab=$tabId")
      if (run.assistantId.nonEmpty)
        ChatStore
          .listMessages(run.sessionId)
          .map(_.find(_.id == run.assistantId))
          .flatMap {
            case Some(message) =>
              ChatStore.updateMessage(
                message.copy(
                  content = "Native agent run stopped: its host page or tab was closed",
                  status = "error",
                  updatedAt = js.Date.now()
                )
              )
            case None          => Future.successful(())
          }
          .recover { case _ => () }
          .foreach(_ => ())
      notifyPage(run.sessionId).foreach(_ => ())
      pushState(run.tabId).foreach(_ => ())
    }
  private def delay(ms: Double): Future[Unit] = {
    val promise = Promise[Unit]()
    js.timers.setTimeout(ms)(promise.success(()))
    promise.future
  }
  private def record(
      runtime: Runtime,
      host: WebChatHost,
      userText: String,
      normalized: String,
      forceRaw: Boolean = false
  ): Future[Unit] = {
    val provider                               = host.provider
    val structurallyParsed                     =
      try new FinalSseProducer(runtime.turnGen, provider).parseMarkersText(normalized)
      catch { case _: Throwable => Left("marker parse threw") }
    val parsed                                 =
      structurallyParsed.flatMap { result =>
        val violations = DeclaredToolPolicy.validate(result.content, result.toolCalls, runtime.turnAllowedTools)
        if (violations.isEmpty) Right(result)
        else Left(DeclaredToolPolicy.messages(violations).mkString(" | "))
      }
    // marker 里的 reason 也要落库：网页原生这条链路没有 steps，插件页靠 `thinking` 渲染折叠的 "Thinking"。
    val (assistantText, assistantThinking, ok) = parsed match {
      case Right(result) if !forceRaw => (result.content, capWebTurnThinking(result.thinking), true)
      case _                          => (normalized, "", false)
    }
    if (!ok)
      PopCanLog.warn(
        "webchat",
        if (forceRaw) s"native agent handoff failed gen=${runtime.turnGen}; storing raw text"
        else s"marker parse failed gen=${runtime.turnGen}; storing raw text"
      )
    val markerReason                           = parsed match {
      case Left(error) => error
      case Right(_)    => ""
    }
    val protocolRepair                         =
      Option(markerReason).filter(_.nonEmpty).map(reason =>
        ProtocolRepair.directive(List(reason), runtime.turnAllowedTools)
      )
    val regenerate                             = isRegenerate(userText, host)
    val origin                                 = s"web:${host.convKey}"
    ChatStore
      .appendWebTurn(
        host.sessionId,
        provider,
        origin,
        userText,
        assistantText,
        treatAsRegenerate = regenerate,
        thinking = assistantThinking,
        // marker / 交接失败：这条 assistant 落 error（气泡上因此有 Retry 入口），不再伪装成成功。
        failed = !ok,
        browserTools = sessionBrowserTools.getOrElse(host.sessionId, false)
      )
      .flatMap(_ => ChatStore.tipMessageId(host.sessionId))
      .flatMap { tip =>
        val assistantId = tip.getOrElse("")
        (if (ok && assistantId.nonEmpty) notifyMemoryTurn(host.sessionId, provider, origin, userText, assistantId)
         else Future.successful(()))
          .map(_ => tip)
      }
      // 会话 id 常常是"第一条消息发出去之后"才出现在地址里（`/chat` → `/chat/<id>`）：记录按**结束时的 URL** 落，
      // 否则下一轮会按新 URL 查不到记录、退回全量；旧 URL 的记录顺手删掉，避免留下查不到的别名。
      .flatMap(tip => currentUrl(runtime.tabId).map(url => (tip, url)))
      .flatMap { case (tip, url) =>
        val convKey = if (url.nonEmpty) url else host.convKey
        val next    = host.copy(
          convKey = convKey,
          seedToMessageId = tip.getOrElse(host.seedToMessageId),
          lastUserText = if (userText.nonEmpty) userText else host.lastUserText,
          toolProfileId = runtime.turnToolProfileId,
          lastTabId = runtime.tabId,
          // 这一轮真的记完了：这才是"最后一次对话"的时间戳（绑定类写入不会碰它）。
          lastTurnAt = js.Date.now(),
          updatedAt = js.Date.now()
        )
        runtime.assoc = Some(next)
        // 一个 (session, provider) 只留一个当前 URL：写新对话时同 provider 的旧 URL 一并删掉。
        WebChatHosts.upsertExclusive(next)
      }
      .flatMap(_ => notifyPage(host.sessionId))
      .flatMap(_ => refreshTab(runtime.tabId))
      .flatMap(_ => pushState(runtime.tabId))
      // marker 类失败：把这一轮交回插件页自动重试（换新对话 + 完整模版；预算在页面侧）。
      .flatMap(_ =>
        if (markerReason.nonEmpty) requestNativeRetryForTurn(runtime, host, markerReason, protocolRepair)
        else Future.successful(())
      )
      .recover { case e =>
        PopCanLog.warn("webchat", s"record turn failed: ${e.getMessage}")
        ()
      }
  }
  private def notifyMemoryTurn(
      sessionId: String,
      provider: String,
      origin: String,
      userText: String,
      assistantMessageId: String
  ): Future[Unit] =
    ServerTabManager.ensurePluginPageTab().flatMap { pageTabId =>
      ChromeAPI.tabs
        .sendMessage(
          pageTabId,
          JsBridge.obj(
            "type"               -> ("MEMORY_INGEST_TURN"),
            "sessionId"          -> (sessionId),
            "provider"           -> (provider),
            "origin"             -> (origin),
            "userText"           -> (userText),
            "assistantMessageId" -> (assistantMessageId)
          )
        )
        .toFuture
        .map(_ => ())
        .recover { case _ => () }
    }

  /** 原生首轮 marker 解析失败：拿这条会话的 tip（刚写下的 error assistant）去请插件页自动重试。 */
  private def requestNativeRetryForTurn(
      runtime: Runtime,
      host: WebChatHost,
      reason: String,
      protocolRepair: Option[String]
  ): Future[Unit] =
    ChatStore
      .tipMessageId(host.sessionId)
      .flatMap { tip =>
        val assistantId = tip.getOrElse("")
        if (assistantId.isEmpty) Future.successful(())
        else
          requestNativeRetry(
            host.sessionId,
            host.provider,
            host.convKey,
            runtime.tabId,
            assistantId,
            reason,
            protocolRepair
          ).map { _ =>
            TraceStore.noteEvidence(
              runtime.turnGen,
              s"marker parse failed -> plugin page auto-retry requested (assistant=$assistantId)"
            )
          }
      }
      .recover { case e =>
        PopCanLog.warn("webchat", s"native retry request failed: ${e.getMessage}")
        ()
      }

  /**
   * 请插件页按预算自动重试某一轮（页面侧决定是否还在预算内；预算用尽 / 找不到父 user 时它会自己 no-op）。
   *
   * 页面不可达 → 什么都不做：失败已经按 error 落库，用户还能在气泡上手动 Retry。
   */
  private def requestNativeRetry(
      sessionId: String,
      provider: String,
      convKey: String,
      tabId: Int,
      assistantId: String,
      reason: String,
      protocolRepair: Option[String] = None
  ): Future[Unit] =
    if (sessionId.isEmpty || assistantId.isEmpty) Future.successful(())
    else
      ServerTabManager.ensurePluginPageTab().flatMap { pageTabId =>
        val payload = JsBridge.obj(
          "type"           -> ("NATIVE_RETRY_TURN"),
          "sessionId"      -> (sessionId),
          "provider"       -> (provider),
          "convKey"        -> (convKey),
          "tabId"          -> (tabId),
          "assistantId"    -> (assistantId),
          "reason"         -> (reason),
          "protocolRepair" -> protocolRepair.getOrElse("")
        )
        sendToPageWithRetry(pageTabId, payload, attempts = 40).map { _ =>
          PopCanLog.info(
            "webchat",
            s"marker failure -> plugin page auto-retry requested session=$sessionId assistant=$assistantId"
          )
          ()
        }
      }

  /**
   * 通知插件页"这条会话变了"（网页原生多了几轮 / 被重命名）。
   *
   * 用 `runtime.sendMessage` **广播**给所有扩展页，而不是只发给 `tabs.query` 出来的第一个 —— 同时开着多个插件页时， 只通知第一个会让其余的会话名/列表一直是旧的（实测踩到过）。
   */
  private def notifyPage(sessionId: String): Future[Unit] =
    ChromeAPI.runtime
      .sendMessage(
        JsBridge.obj("type" -> ("WEBCHAT_SESSION_UPDATED"), "sessionId" -> (sessionId))
      )
      .toFuture
      .map(_ => ())
      .recover { case _ => () }

  /** 把最近一次看到的聊天请求体按 provider 落一份，供"写 request.userText 路径"时对照。 */
  private def rememberRequest(provider: String, url: String, postData: String): Unit =
    if (provider.isEmpty || postData.isEmpty) ()
    else
      ChromeAPI.storage.local
        .set(js.Dictionary[js.Any](
          s"thWebChatLastRequest:$provider" -> JsBridge.obj(
            "url"      -> (url),
            "postData" -> (if (postData.length > 20000) postData.take(20000) else postData),
            "at"       -> (js.Date.now())
          )
        ))
        .toFuture
        .recover { case _ => () }
        .foreach(_ => ())
  // ---------------------------------------------------------------- 上下文 / 劫持窗口
  private def refreshArm(runtime: Runtime): Future[Unit] =
    // 任务正在驱动这个 tab：这一轮的上下文注入归任务的 `task:<gen>` 计划，网页原生别抢（两边 arm 后到者会顶掉先到的）。
    if (taskOwnsTab(runtime.tabId)) {
      runtime.pendingDelta = Nil
      runtime.pendingDeltaReady = false
      if (runtime.hijacking) disarm(runtime) else Future.successful(())
    } else
      runtime.assoc match {
        case None       => disarm(runtime)
        case Some(host) =>
          needsInject().flatMap { needed =>
            if (needed && !runtime.hijacking) {
              // 抢先把"该补的上下文"算好放内存：劫持发生在请求已经暂停的时刻，那里每多读一次 storage 都会拉长暂停。
              runtime.pendingDeltaReady = false
              deltaFor(host).flatMap { delta =>
                runtime.pendingDelta = delta
                runtime.pendingDeltaReady = true
                enableHijack(runtime)
              }
            } else if (!needed && runtime.hijacking) {
              runtime.pendingDelta = Nil
              runtime.pendingDeltaReady = false
              disarm(runtime)
            } else Future.successful(())
          }
      }

  /** 插件页任务收工（租约已释放）后回调：这个 tab 的劫持窗口按当前归属重新武装。 */
  def onTaskReleased(tabId: Int): Future[Unit] =
    if (tabId <= 0 || !runtimes.contains(tabId)) Future.successful(())
    else refreshTab(tabId).recover { case _ => () }

  /** 这个 tab 现在是不是正被插件页任务驱动（任务持有租约，或改写计划是任务挂的）；`helper#…` 不算。 */
  private def taskOwnsTab(tabId: Int): Boolean =
    CdpSession.owners(tabId).exists(_.startsWith("task:")) ||
      RequestRewrite.ownerOf(tabId).exists(_.startsWith("task:"))

  /**
   * 这一轮要不要注入（要不要武装劫持窗口）。
   *
   * 每次已绑定 Session 的请求都使用本轮 `AgentToolSurface` 生成工具声明；工具集合变化由对话 revision 隔离。
   *
   * 形态另算（`decideHijack`）：**首投 ⇒ 整份模版**，其余（含欠账）⇒ **只投增量**；网页原生不做"每轮重投整份"的开关。
   */
  private def needsInject(): Future[Boolean]                         =
    Future.successful(true)
  private def deltaFor(host: WebChatHost): Future[List[ChatMessage]] =
    ChatStore.sessionPathMessages(host.sessionId).map(path => unseen(host, path))

  /**
   * 这段对话**还没见过**的会话消息（要补的上下文）。
   *
   * 水位线命中时：先丢掉紧随水位线的那条 assistant —— 它就是本对话对上次投递产出的回答，provider 页自己已经有 （插件页跑完之后切过来续聊时，这一步能避免把答案又投一遍）；再按 `origin`
   * 过滤掉本对话产生的消息， 别的 provider / 插件页产生的照旧带上（跨 provider 接力靠它）。水位线为空或找不到（首投 / fork / undo）→ 全量。
   */
  private def unseen(host: WebChatHost, path: List[ChatMessage]): List[ChatMessage] = {
    val origin = s"web:${host.convKey}"
    val after  =
      if (host.seedToMessageId.isEmpty) path
      else {
        val index = path.indexWhere(_.id == host.seedToMessageId)
        if (index < 0) path else path.drop(index + 1).dropWhile(_.role == "assistant")
      }
    after.filter(message => message.origin != origin && (message.role == "user" || message.role == "assistant"))
  }

  /**
   * 欠账窗口打开：把"补上下文"的决策挂到共用的 `RequestRewrite` 上。
   *
   * 真正改写发生在请求暂停那一刻，所以这里先把增量算好放内存（暂停期间每多读一次 storage 都会拉长页面的等待）。
   */
  private def enableHijack(runtime: Runtime): Future[Unit] =
    runtime.config match {
      case Some(config) if config.apiPatterns.nonEmpty && ProviderRequestRule.hasRule(ruleOf(config)) =>
        val provider = runtime.assoc.map(_.provider).getOrElse("")
        val rule     = ruleOf(config)
        val plan     = RewritePlan(
          owner = hijackOwner(runtime),
          provider = provider,
          patterns = config.apiPatterns,
          decide = (postData, headers) => decideHijack(runtime, rule, postData, headers),
          onDisarm = _ => runtime.hijacking = false,
          onError = text => recordError(runtime, s"rewrite:${runtime.turnGen}", text),
          decisionTimeoutMs = 1200
        )
        RequestRewrite
          .arm(runtime.tabId, plan)
          .map { _ =>
            runtime.hijacking = true
            PopCanLog.debug("webchat", s"hijack armed tab=${runtime.tabId}")
            ()
          }
          .recover { case e =>
            recordError(
              runtime,
              s"rewrite:${runtime.turnGen}:enable",
              s"Fetch.enable failed: ${Option(e.getMessage).getOrElse(e.toString)}"
            )
            ()
          }
      case _ => Future.successful(())
    }
  private def disarm(runtime: Runtime): Future[Unit]       =
    RequestRewrite.disarm(runtime.tabId, hijackOwner(runtime), "disarm").map { _ =>
      runtime.hijacking = false
      ()
    }
  private def hijackOwner(runtime: Runtime): String        = s"webchat:${runtime.tabId}"

  /** 请求暂停时的决策：认不出用户原话就原样放行（窗口按情况保留），认出来才把模版写回请求体。 */
  private def decideHijack(
      runtime: Runtime,
      rule: String,
      body: Array[Byte],
      headers: js.Dictionary[String]
  ): Future[RewriteDecision] = {
    // 跳过注入的原因一并写进 trace evidence：trace 索引只留 5 条，事后光看 thWebChatHijackLog 很难对上具体哪一轮。
    def skip(reason: String, message: String): RewriteDecision = {
      TraceStore.noteEvidence(runtime.turnGen, s"hijack skipped ($reason): $message")
      RewriteDecision.Fail(reason, message)
    }
    if (runtime.assoc.isEmpty) Future.successful(RewriteDecision.Pass("skip-unassigned", keepArmed = true))
    else if (body.isEmpty) Future.successful(RewriteDecision.Pass("skip-bodyless", keepArmed = true))
    else if (!ProviderRequestRule.hasRule(rule))
      Future.successful(
        skip("skip-no-rule", "This provider has no request.userText rule - observe-only (no context injection).")
      )
    else
      ProviderRequestRule.readUserText(rule, body) match {
        case None                                  =>
          Future.successful(
            skip("skip-no-text", s"Could not rewrite request.userText: ${ProviderRequestRule.diagnose(rule, body)}")
          )
        case Some(_) if !runtime.pendingDeltaReady =>
          Future.successful(RewriteDecision.Pass("skip-not-ready", keepArmed = true))
        case Some(rawUserText)                     =>
          val userText  = PromptArtifact.userTextOrOriginal(rawUserText)
          if (userText != rawUserText) {
            TraceStore.noteEvidence(
              runtime.turnGen,
              s"prompt artifact unwrapped rawChars=${rawUserText.length} userChars=${userText.length}"
            )
          }
          runtime.hijackUserText = userText
          // 改写时手里这份就是**改写前**的原话：权威值，覆盖刚才"第一次看到请求"时的快照。
          runtime.turnUserText = userText
          val provider  = runtime.assoc.map(_.provider).getOrElse("")
          // 形态只看水位线：首投（这段对话还没有水位线）⇒ 完整模版；其余（含"欠账"那一档）⇒ 只投消息增量。
          // 网页原生不做"每轮重投整份"这种开关（那是插件页 `/context full` 的语义，且它每轮 New Chat）。
          val form      = TemplateInjection.formOf(
            TemplateInjection.ModeDelta,
            hasWatermark = runtime.assoc.exists(_.seedToMessageId.nonEmpty)
          )
          val recent    =
            runtime.pendingDelta.takeRight(4).map(message => s"${message.role}: ${message.content.take(1200)}").mkString(
              "\n"
            )
          val sessionId = runtime.assoc.map(_.sessionId).getOrElse("")
          SessionSettingsStore
            .load(sessionId)
            .recover { case _ => com.just3bowls.model.chat.SessionSettings.Default }
            .flatMap { settings =>
              val toolSurface     = AgentToolSurface(AgentToolRegistry.chatToolsFor(settings.browserTools))
              runtime.turnBrowserTools = settings.browserTools
              runtime.turnAllowedTools = toolSurface.toolNames
              runtime.turnToolProfileId = toolSurface.revision
              val profileMismatch =
                runtime.assoc.exists(host =>
                  host.sessionId.nonEmpty && host.toolProfileId != toolSurface.revision
                )
              if (profileMismatch)
                Future.successful(
                  skip(
                    "skip-tool-profile",
                    "The tool surface changed for this session. Start a new Provider chat before sending."
                  )
                )
              else
                MemoryRecallBridge
                  .recall(
                    MemoryQuery(
                      sessionId = sessionId,
                      query = userText,
                      recentContext = recent
                    )
                  )
                  .map { memory =>
                    // 原生首轮和插件页 agent 首轮同构：只带本轮真实工具声明，不额外注入 capability system role。
                    val template =
                      TemplateInjection.buildNativePayload(
                        form,
                        provider,
                        runtime.pendingDelta,
                        userText,
                        surface = toolSurface,
                        memoryContext = memory.text
                      )
                    TraceStore.noteInject(runtime.turnGen, s"native/$form", Some(template))
                    ProviderRequestRule.writeUserText(rule, body, template) match {
                      case Some(rewritten) =>
                        // 只有规则里声明了"改请求头"才整份覆盖头。
                        val headerPatch =
                          if (!ProviderRequestRule.hasHeaderDerives(rule)) Right(headers)
                          else ProviderRequestRule.rewriteHeaders(rule, headers, template)
                        headerPatch match {
                          case Right(patched) =>
                            val headerNote =
                              deriveHeaders(rule).map(name => s" headers=${name.mkString(",")}").getOrElse("")
                            PopCanLog.info(
                              "webchat",
                              s"hijack rewrite tab=${runtime.tabId} form=$form delta=${runtime.pendingDelta.size} " +
                                s"memory=${memory.hits.size} chars=${rewritten.length} " +
                                s"toolProfile=${PopCanLog.short(toolSurface.revision, 32)}$headerNote"
                            )
                            TraceStore.noteEvidence(
                              runtime.turnGen,
                              s"native/$form messages=${runtime.pendingDelta.size} memoryHits=${memory.hits.size} " +
                                s"rewrittenChars=${rewritten.length} " +
                                s"toolProfile=${PopCanLog.short(toolSurface.revision, 32)}$headerNote"
                            )
                            if (ProviderRequestRule.hasHeaderDerives(rule)) RewriteDecision.Write(rewritten, patched)
                            else RewriteDecision.Write(rewritten)
                          case Left(reason)   =>
                            skip("skip-header-derive", s"Could not rewrite request headers: $reason")
                        }
                      case None            =>
                        skip(
                          "skip-rewrite-failed",
                          s"Could not rewrite request.userText: ${ProviderRequestRule.diagnose(rule, body)}"
                        )
                    }
                  }
            }
            .recover { case error =>
              PopCanLog.warn("memory", s"native recall degraded: ${Option(error.getMessage).getOrElse(error.toString)}")
              RewriteDecision.Pass("skip-memory-failed", keepArmed = true)
            }
      }
  }

  /** 规则里声明要改的请求头名字（只用于日志/evidence）。 */
  private def deriveHeaders(rule: String): Option[List[String]] =
    Option(ProviderRequestRule.deriveOps(rule).map(_.header).filter(_.nonEmpty).distinct).filter(_.nonEmpty)
  // ---------------------------------------------------------------- 小工具
  private def sleep(ms: Int): Future[Unit]                      = {
    val promise = Promise[Unit]()
    js.timers.setTimeout(ms)(promise.success(()))
    promise.future
  }
  private def isActiveTab(tabId: Int): Future[Boolean]          =
    ChromeAPI.tabs.get(tabId).toFuture.map(tab => tab.active.getOrElse(false)).recover { case _ => false }
  private def currentUrl(tabId: Int): Future[String]            =
    ChromeAPI.tabs.get(tabId).toFuture.map(tab => tab.url.toOption.getOrElse("")).recover { case _ => "" }
  private def ruleOf(config: ParserProviderInfo): String        = config.parserRule.getOrElse("")
  private def resolveProvider(tabId: Int): Future[Option[(String, ParserProviderInfo)]]                   =
    currentUrl(tabId).flatMap(resolveProviderByUrl)
  private[background] def resolveProviderByUrl(url: String): Future[Option[(String, ParserProviderInfo)]] =
    com.just3bowls.model.provider.ParserProviderLocal.loadAll().map { configs =>
      configs.toList
        .flatMap { case (id, local) =>
          val pageUrl = Option(local.pageUrl).getOrElse("")
          if (pageUrl.trim.nonEmpty && urlMatches(pageUrl, url))
            ServerTabManager.configFrom(local).map(info => (id, info))
          else None
        }
        .sortBy { case (_, info) => -Option(info.pageUrl).getOrElse("").length }
        .headOption
    }

  /** 与 `ManagedTabManager` 同口径：委托共享的 `PageIdentity`。 */
  private def urlMatches(configured: String, current: String): Boolean =
    com.just3bowls.api.provider.PageIdentity.sameProviderPage(configured, current)
}
