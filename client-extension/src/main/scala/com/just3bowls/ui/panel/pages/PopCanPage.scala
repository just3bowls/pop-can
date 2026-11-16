package com.just3bowls.ui.panel.pages

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.chat.*
import com.just3bowls.model.provider.*
import com.just3bowls.model.memory.*
import com.just3bowls.model.localapi.*
import PopCanPageState.*

import com.just3bowls.ui.components.*

import com.just3bowls.api.chat.AgentModePolicy
import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.api.chat.ChatSendPolicy
import com.just3bowls.api.memory.MemoryTurn
import com.just3bowls.api.provider.ProviderChatArgs
import com.just3bowls.api.provider.ProviderCycle
import com.just3bowls.model.chat.AgentPlanApproval
import com.just3bowls.model.chat.AgentModeSettings
import com.just3bowls.model.localapi.ApiKeyConfig
import com.just3bowls.model.localapi.ApiKeyRoute
import com.just3bowls.model.localapi.ApiKeyStore
import com.just3bowls.model.chat.MarkerParseFailed
import com.just3bowls.model.provider.ProviderChatSettings
import com.just3bowls.model.provider.ProviderHostAccess
import com.just3bowls.model.chat.TabAiEvent
import com.just3bowls.model.chat.TabAiExecutor
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.facade.{
  BrowserEvents,
  ChromeAPI,
  MessageSender,
  NativeRetryTurnMessage,
  RuntimeMessage,
  SessionUrlRowMessage,
  SessionUrlsMessage,
  SessionUrlsResponse,
  StorageChange,
  Tab,
  TabGroup,
  WebChatSessionMessage
}
import com.raquo.laminar.api.L._
import scala.scalajs.js
import scala.collection.mutable
import scala.concurrent.Future
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import org.scalajs.dom

object PopCanPage {
  final private case class ProviderXPathConfig(
      input: String,
      url: String = "",
      newChatXPath: String = "",
      apiUrl: String = ""
  )
  private val genericXPathConfig                                         = ProviderXPathConfig(
    input = "//textarea",
    url = "",
    newChatXPath = "",
    apiUrl = ""
  )
  private def getProviderConfig(provider: String): ProviderXPathConfig   =
    parserProvidersVar.now().find(_.id == provider).map { config =>
      ProviderXPathConfig(
        input = config.inputSelector,
        url = config.pageUrl,
        newChatXPath = config.newChatSelector,
        apiUrl = config.apiPatterns.mkString("\n")
      )
    }.getOrElse(genericXPathConfig)
  private def refreshParserProviders(): Future[List[ParserProviderInfo]] =
    ParserApiClient.list()
      .map { list =>
        parserProvidersVar.set(list)
        list
      }
      .recover { case e =>
        PopCanLog.warn("page", s"parser providers refresh failed: ${Option(e.getMessage).getOrElse(e.toString)}")
        Nil
      }
  def render(): Unit                                                     = {
    renderDisposers.foreach(_())
    renderDisposers.clear()
    PopCanLog.init()
    PopCanLog.info("page", "Pop Can tab started")
    val markdownStyle                 = dom.document.createElement("style")
    markdownStyle.textContent =
      """
        .popcan-markdown { line-height: 1.6; overflow-wrap: break-word; }
        .popcan-markdown p { margin: 0.3em 0; }
        .popcan-markdown p:first-child { margin-top: 0; }
        .popcan-markdown p:last-child { margin-bottom: 0; }
        .popcan-markdown h1, .popcan-markdown h2, .popcan-markdown h3 { margin: 10px 0 6px; }
        .popcan-markdown pre {
          background: #f3f4f6; padding: 10px; border-radius: 6px;
          overflow-x: auto; font-size: 12px; line-height: 1.5;
        }
        .popcan-markdown code { background: #f3f4f6; padding: 1px 4px; border-radius: 4px; }
        .popcan-markdown pre code { background: transparent; padding: 0; }
        .popcan-markdown ul, .popcan-markdown ol { padding-left: 20px; margin: 6px 0; }
        .popcan-markdown blockquote {
          margin: 8px 0; padding-left: 10px; border-left: 3px solid #cbd5e1;
          color: #475569;
        }
        .popcan-markdown a { color: #2563eb; }
        .th-config-focus:focus { outline: none; }
        .th-config-focus:focus-visible {
          outline: none;
          box-shadow: inset 0 0 0 2px rgba(37, 99, 235, 0.38);
        }
        @keyframes popcan-api-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.35; }
        }
      """
    dom.document.head.appendChild(markdownStyle)
    val taskVar                       = Var("")
    val providerVar                   = Var("")
    val manualProviderOverridesVar    = Var(Map.empty[String, String])
    var sessionUrlsLoadSerial         = 0
    val inputSelVar                   = Var("""//*[@id="root"]/div/div[2]/div[3]/div/div[2]""")
    val taskInputId                   = "popcan-task-input"
    val currentSessionIdVar           = Var("")
    val currentSessionTitleEditingVar = Var(false)
    val currentSessionTitleDraftVar   = Var("")
    val currentSessionTitleBusyVar    = Var(false)
    var currentSessionTitleClickTimer = Option.empty[js.timers.SetTimeoutHandle]

    def clearCurrentSessionTitleClick(): Unit =
      currentSessionTitleClickTimer.foreach(js.timers.clearTimeout)
      currentSessionTitleClickTimer = None

    def cancelCurrentSessionTitleEdit(): Unit = {
      clearCurrentSessionTitleClick()
      currentSessionTitleEditingVar.set(false)
      currentSessionTitleDraftVar.set("")
      currentSessionTitleBusyVar.set(false)
    }

    /** 当前 Session 的 Agent Mode；没有 Session 时作为首条消息的待落库选择。 */
    val agentModeVar       = Var(AgentModePolicy.React)
    val activeLeafIdVar    = Var(Option.empty[String])
    val sessionMessagesVar = Var(List.empty[ChatMessage])

    /** 只在屏幕上显示、尚未落库的占位消息（研究的回复占位、chat 的 pending 提问/回复）。 */
    val pendingMessagesVar      = Var(List.empty[ChatMessage])
    val regenerableMessageIdVar = Var(Option.empty[String])
    val undoDepthVar            = Var(0)

    /** footer 的 Undo 提示只服务普通 undo；clear 结果只走 toast。 */
    val showUndoHintVar = Var(false)

    /** Undo 后仅当前 session 的下一次 Chat 提交使用 new；实际提交时消费。 */
    val undoForcesNewSessions = Var(Set.empty[String])
    var abortCurrentChat      = Option.empty[() => Unit]
    var pendingNewSession     = Option.empty[Future[String]]
    val commandStageVar       = Var[ChatCommandStage](ChatCommandStage.Closed)
    val commandItemsVar       = Var(List.empty[ChatCommandItem])
    val commandIndexVar       = Var(0)
    val footerNoticeVar       = Var(FooterNotice("", "info"))
    val rememberDraftScopeVar = Var(Option.empty[String])

    /** Current session's browser-tools policy; before the first send it is the pending value for the next session. */
    val browserToolsVar = Var(false)

    /**
     * 固定值（pin）的内存缓存：`{ providerId: { thread, prompt } }`（用户词汇 `new|continue` / `inline|file`）。
     *
     * 常态不在这里 —— 默认值是"这条会话在这个 provider 上用过没有"推导出来的（见 `sendSettingsFor`）；用户明确"这家以后都这样"（`@gml … pin`）才落到这儿。
     */
    val pinnedSettingsVar = Var(Map.empty[String, ChatSendPolicy.Settings])

    /**
     * 只作用于**下一次发送**的行内参数（内存，不落库）：`@gml new file` 收工后放这儿，这家 provider 的下一次发送用掉即清。
     *
     * 只保留最新提交的那一家（用户再 `@kimi …` 收工就替换），切会话时清空。
     */
    val pendingSendVar                                       = Var(Option.empty[PendingSend])
    val pendingTabRefsVar                                    = Var(List.empty[BrowserTabRef])
    val tabCandidatesVar                                     = Var(List.empty[BrowserTabRef])
    val pendingAgentPlanVar                                  = Var(Option.empty[PendingAgentPlan])
    var closeMemoryLibrary                                   = () => ()
    var refreshMemoryLibrary                                 = () => ()
    var closeSessionFolderPanel                              = () => ()
    var openSessionFolderPanel                               = () => ()
    var closeCommandsForSessionFolder                        = () => ()
    val newSessionFolderIdVar                                = Var("")
    def presentAgentPlan(planId: String, plan: String): Unit = {
      pendingAgentPlanVar.set(Some(PendingAgentPlan(planId, plan)))
      js.timers.setTimeout(0) {
        Option(dom.document.getElementById(taskInputId).asInstanceOf[dom.HTMLTextAreaElement])
          .foreach(_.focus())
      }
    }

    /**
     * 底部左槽只装不属于 composer 输入区的持久提示（重命名、自动重试、pin 确认等）。
     *
     * `Generating/Routing` 已迁到输入区左上角，`Undone` 已迁到 placeholder；失败、用法和面板结果继续走 [[Toast]]。
     */
    /** 提示/失败：右上角 toast（成功类就地反馈的地方不在这里）。 */
    def showInfo(text: String): Unit  = Toast.info(text)
    def showError(text: String): Unit = Toast.error(text)
    def clearNotice(): Unit           = footerNoticeVar.set(FooterNotice("", "info"))
    // ---- 会话 URL 列表：这条 session 在用的 provider URL（插件页 chat 用过的 provider tab + 归属本会话的原生对话 URL）----
    val sessionUrlsVar                = Var(List.empty[SessionUrlInfo])
    val sessionUrlsOpenVar            = Var(false)

    /**
     * "最后一次对话的 provider"到位时的回调（会话 URL 快照里带回来）。
     *
     * 跟随逻辑 `followLatestProvider` 定义在 `applyChatProvider` 之后（它要用到那之后的 val），这里先用一个回调占位，避免前向引用。
     */
    var followProviderHook: String => Unit = _ => ()

    /** `QUERY_SESSION_URLS` 的响应与 SW 主动推送的 `SESSION_URLS_UPDATED` 共用这一份解析。 */
    def applySessionUrls(rawUrls: js.Array[SessionUrlRowMessage]): Unit =
      sessionUrlsVar.set(rawUrls.toList.map { t =>
        SessionUrlInfo(
          tabId = t.tabId.getOrElse(0),
          url = t.url.getOrElse(""),
          provider = t.provider.getOrElse(""),
          toolProfileId = t.toolProfileId.getOrElse("")
        )
      })

    /** 拉一次「本会话在用哪些 provider URL」；同一次调用也让 SW 订阅这条会话（之后有变化会主动推）。 */
    def loadSessionUrls(): Future[Unit] =
      val requestedSessionId = currentSessionIdVar.now()
      sessionUrlsLoadSerial += 1
      val serial             = sessionUrlsLoadSerial
      ChromeAPI.runtime.sendMessage(
        JsBridge.obj("type" -> ("QUERY_SESSION_URLS"), "sessionId" -> (requestedSessionId))
      )
        .toFuture
        .map { response =>
          if (currentSessionIdVar.now() == requestedSessionId && serial == sessionUrlsLoadSerial) {
            val result = response.asInstanceOf[SessionUrlsResponse]
            followProviderHook(result.latestProvider.getOrElse(""))
            applySessionUrls(result.urls.getOrElse(js.Array[SessionUrlRowMessage]()))
          }
        }
        .recover { case e =>
          PopCanLog.error("page", "refresh session urls failed", e)
        }
    def refreshSessionUrls(): Unit      =
      loadSessionUrls().foreach(_ => ())

    /** 点某一行 = 切到那个 tab（同时把它的窗口拉到前台）。 */
    def activateSessionTab(tabId: Int): Unit                                                          =
      if (tabId > 0) {
        ChromeAPI.runtime.sendMessage(
          JsBridge.obj("type" -> ("ACTIVATE_SERVER_TAB"), "tabId" -> (tabId))
        )
          .toFuture
          .foreach(_ => ())
      }
    def activateReferencedTab(tab: BrowserTabRef): Unit                                               =
      if (tab.tabId > 0) {
        val focusWindow =
          if (tab.windowId > 0)
            ChromeAPI.windows
              .update(tab.windowId, js.Dictionary[js.Any]("focused" -> true))
              .toFuture
              .map(_ => ())
              .recover { case _ => () }
          else Future.successful(())
        focusWindow
          .flatMap(_ =>
            ChromeAPI.tabs
              .update(tab.tabId, js.Dictionary[js.Any]("active" -> true))
              .toFuture
              .map(_ => ())
          )
          .recover { case _ =>
            Toast.info(s"${tab.ref} is no longer open; Undo or re-send to restore it")
          }
          .foreach(_ => ())
      }
    def restoreReferencedTabs(refs: List[BrowserTabRef]): Future[(List[BrowserTabRef], List[String])] =
      ReferencedTabRestorer.restore(refs)

    /** 绑定行没有活 tab（tab 被关 / 还没打开）：交给 SW 新建一个 tab 打开这段 URL 并切过去。 */
    def focusSessionUrl(url: String): Unit =
      if (url.nonEmpty) {
        ChromeAPI.runtime.sendMessage(
          JsBridge.obj("type" -> ("WEBCHAT_FOCUS_URL"), "url" -> (url))
        )
          .toFuture
          .foreach(_ => ())
      }

    /**
     * 点某一行右侧的 × = 结束这段 URL 在本会话里的使命：tab 留着，下次用这个 provider 会去开新对话。
     */
    def endSessionUrl(info: SessionUrlInfo): Unit =
      if (info.provider.nonEmpty) {
        ChromeAPI.runtime.sendMessage(
          JsBridge.obj(
            "type"      -> ("SESSION_URL_END"),
            "sessionId" -> (currentSessionIdVar.now()),
            "provider"  -> (info.provider),
            "url"       -> (info.url)
          )
        )
          .toFuture
          .recover { case _ => JsBridge.obj() }
          .foreach(_ => refreshSessionUrls())
      }

    /**
     * 结束这条会话在某个 provider 上的当前 URL（regenerate 之前先用）：tab 留着，下次该 provider 会开新对话。
     *
     * URL 从会话 URL 列表里取（没有就留空 = 只标"下次换新对话"）。
     */
    def endSessionProviderUrl(sessionId: String, provider: String): Unit =
      if (sessionId.nonEmpty && provider.nonEmpty) {
        val url = sessionUrlsVar.now().find(_.provider == provider).map(_.url).getOrElse("")
        ChromeAPI.runtime.sendMessage(
          JsBridge.obj(
            "type"      -> ("SESSION_URL_END"),
            "sessionId" -> (sessionId),
            "provider"  -> (provider),
            "url"       -> (url)
          )
        )
          .toFuture
          .recover { case _ => JsBridge.obj() }
          .foreach(_ => refreshSessionUrls())
      }
    def agentModeLabel(mode: String): String                             =
      AgentModePolicy.Options
        .find(_.id == AgentModePolicy.normalize(mode))
        .map(_.label)
        .getOrElse("ReAct")

    /**
     * `/agent` 与 Tab 共用：选择立即成为当前 Session 的模式。
     *
     * 还没有 Session 时先保留在 `agentModeVar`，首条消息创建 Session 后再写入 AgentModeSettings。
     */
    def setAgentMode(mode: String, announce: Boolean = true): Unit = {
      val normalized = AgentModePolicy.normalize(mode)
      val sessionId  = currentSessionIdVar.now()
      agentModeVar.set(normalized)
      if (sessionId.nonEmpty) AgentModeSettings.save(sessionId, normalized).foreach(_ => ())
      if (announce) {
        val scope = if (sessionId.nonEmpty) "this session" else "next chat"
        Toast.info(s"Agent: ${agentModeLabel(normalized)} — $scope")
      }
    }
    def cycleAgentMode(): Unit                                     =
      setAgentMode(
        if (AgentModePolicy.normalize(agentModeVar.now()) == AgentModePolicy.Plan)
          AgentModePolicy.React
        else AgentModePolicy.Plan
      )

    final case class MarkerPart(
        text: Signal[String],
        normal: Signal[Boolean],
        tooltip: Signal[String],
        onSwitch: () => Unit
    )

    /** Provider 与它的两项本次设置共用一枚 marker：`name[thread|prompt]`。 */
    def providerSettingsMarker(
        providerText: Signal[String],
        providerNormal: Signal[Boolean],
        providerTooltip: Signal[String],
        onProviderSwitch: () => Unit,
        thread: MarkerPart,
        prompt: MarkerPart,
        clickable: Signal[Boolean],
        visible: Signal[Boolean]
    ): HtmlElement = {
      val normal =
        Signal
          .combine(
            Signal.combine(providerNormal, thread.normal),
            prompt.normal
          )
          .map { case ((provider, threadNormal), promptNormal) =>
            provider && threadNormal && promptNormal
          }
      def clickableValue(
          text: Signal[String],
          isNormal: Signal[Boolean],
          tooltip: Signal[String],
          action: () => Unit,
          strikeWhenChanged: Boolean
      ) =
        span(
          role := "button",
          tabIndex <-- clickable.map(if (_) 0 else -1),
          child.text <-- text,
          color <-- isNormal.map(if (_) "#6b7280" else "#b45309"),
          textDecoration <--
            isNormal.map(normal => if (!normal && strikeWhenChanged) "line-through" else "none"),
          cursor <-- clickable.map(if (_) "pointer" else "default"),
          title <-- tooltip,
          aria.disabled <-- clickable.map(!_),
          onClick --> { _ => action() },
          onKeyDown --> { event =>
            if (event.key == "Enter" || event.key == " ") {
              event.preventDefault()
              action()
            }
          }
        )
      span(
        display <-- visible.map(if (_) "inline-flex" else "none"),
        alignItems.center,
        gap := "3px",
        span(
          width         := "7px",
          height        := "7px",
          borderRadius  := "50%",
          flexShrink    := "0",
          backgroundColor <-- normal.map(if (_) "#9ca3af" else "#b45309")
        ),
        clickableValue(providerText, providerNormal, providerTooltip, onProviderSwitch, strikeWhenChanged = false),
        span("[", color := "#9ca3af"),
        clickableValue(thread.text, thread.normal, thread.tooltip, thread.onSwitch, strikeWhenChanged = false),
        span("|", color := "#cbd5e1"),
        clickableValue(prompt.text, prompt.normal, prompt.tooltip, prompt.onSwitch, strikeWhenChanged = false),
        span("]", color := "#9ca3af")
      )
    }

    /** Empty-provider entry point: action text, not a status dot, and opens Config -> Providers. */
    def providerSetupMarker(
        onClickAction: () => Unit,
        visible: Signal[Boolean]
    ): HtmlElement = {
      val label       =
        parserProvidersVar.signal.map { providers =>
          val hasActive = providers.exists(_.status == "active")
          if (providers.isEmpty) "+ Add provider"
          else if (!hasActive) "+ Enable provider"
          else "+ Choose provider"
        }
      val tooltip     =
        parserProvidersVar.signal.map { providers =>
          val hasActive = providers.exists(_.status == "active")
          if (providers.isEmpty)
            "No provider rules installed. Open Config -> Providers to download or import rules."
          else if (!hasActive)
            "No provider is enabled. Open Config -> Providers to enable one."
          else
            "No provider is selected. Open Config -> Providers to choose one."
        }
      span(
        role     := "button",
        tabIndex := 0,
        display <-- visible.map(if (_) "inline-flex" else "none"),
        alignItems.center,
        gap      := "4px",
        color    := "#1d4ed8",
        fontSize := "11px",
        cursor.pointer,
        child.text <-- label,
        title <-- tooltip,
        onClick --> { _ => onClickAction() },
        onKeyDown --> { event =>
          if (event.key == "Enter" || event.key == " ") {
            event.preventDefault()
            onClickAction()
          }
        }
      )
    }

    /** 切换会话时同步来源标记（`source` 只用于列表展示，不再限制读写）。 */
    def syncSessionSource(): Unit                                   = {
      pendingTabRefsVar.set(Nil)
      tabCandidatesVar.set(Nil)
      // 会话 URL 列表跟着切会话重拉（并让 SW 把订阅切到这条会话）。
      sessionUrlsOpenVar.set(false)
      // 行内参数只在"提交它的那条会话"里有效：换会话一律作废（固定值不在此列，它是这家 provider 的长期属性）。
      pendingSendVar.set(None)
      refreshSessionUrls()
    }
    def applyBrowserTools(enabled: Boolean, persist: Boolean): Unit =
      if (chatRunningVar.now())
        showError("Browser tools cannot change while this chat is running. Stop it first.")
      else {
        val previous  = browserToolsVar.now()
        val sessionId = currentSessionIdVar.now()
        browserToolsVar.set(enabled)
        if (persist && sessionId.nonEmpty)
          SessionSettingsStore
            .saveBrowserTools(sessionId, enabled)
            .recover { case e =>
              if (currentSessionIdVar.now() == sessionId && browserToolsVar.now() == enabled)
                browserToolsVar.set(previous)
              showError(s"Save browser tools failed: ${Option(e.getMessage).getOrElse("unknown error")}")
            }
            .foreach(_ => ())
      }

    /** 固定值全表读进内存缓存（启动一次 + `thProviderPinnedSettings` 变更时刷新）。 */
    def refreshPinnedSettings(): Unit =
      ProviderChatSettings.load().foreach(settings => pinnedSettingsVar.set(settings))
    refreshPinnedSettings()

    /** 这家 provider 的固定值（用户明确"以后都这样"）；没固定过 = None。 */
    def pinnedOf(provider: String): Option[ChatSendPolicy.Settings] =
      pinnedSettingsVar.now().get(provider)

    def toolProfileForRun(browserTools: Boolean, referencedTabCount: Int): String =
      AgentToolSurface(
        AgentToolRegistry.chatToolsFor(BrowserToolsPolicy.effectiveForRun(browserTools, referencedTabCount))
      ).revision

    def providerConversationCompatible(
        provider: String,
        urls: List[SessionUrlInfo],
        browserTools: Boolean,
        referencedTabCount: Int
    ): Boolean = {
      val expected = toolProfileForRun(browserTools, referencedTabCount)
      provider.nonEmpty && urls.exists(url => url.provider == provider && url.toolProfileId == expected)
    }

    /** A provider is reusable only when its bound Host uses the current turn's exact tool profile. */
    def sessionUsedProvider(provider: String): Boolean =
      providerConversationCompatible(
        provider,
        sessionUrlsVar.now(),
        browserToolsVar.now(),
        pendingTabRefsVar.now().size
      )

    /** 会话推导默认：用过 → `continue + inline`；没用过 → `new + inline`。 */
    def baselineSendSettings(provider: String): ChatSendPolicy.Settings =
      ChatSendPolicy.baseline(sessionUsedProvider(provider))
    def undoForcesNew(sessionId: String): Boolean                       =
      sessionId.nonEmpty && undoForcesNewSessions.now().contains(sessionId)

    /** 当前实际 baseline：正常推导，叠加 Undo 后仅下一轮的 new。 */
    def effectiveBaselineSendSettings(provider: String): ChatSendPolicy.Settings =
      if (undoForcesNew(currentSessionIdVar.now())) ChatSendPolicy.afterUndo(sessionUsedProvider(provider))
      else baselineSendSettings(provider)

    /** 等着被这条 provider 的下一次发送用掉的行内覆盖。 */
    def pendingSendOf(provider: String): Option[ChatSendPolicy.Settings] =
      pendingSendVar.now().filter(_.provider == provider).map(_.settings)

    /**
     * 这一次发送真正生效的设置：**一次性覆盖 > 固定值 > 会话推导默认**。
     *
     * `typed` = `/provider …` 命令参数；右下角按钮和真正发送都传 None，读取已提交的同一份 `PendingSend`。
     */
    def sendSettingsFor(provider: String, typed: Option[ProviderChatArgs.Parsed] = None): ChatSendPolicy.Settings = {
      val committed = ChatSendPolicy.resolve(
        effectiveBaselineSendSettings(provider),
        pinnedOf(provider),
        pendingSendOf(provider)
      )
      val resolved  = typed.map(parsed => ChatSendPolicy.withArgs(committed, parsed)).getOrElse(committed)
      if (undoForcesNew(currentSessionIdVar.now()))
        ChatSendPolicy.forceThread(resolved, ProviderChatArgs.ThreadNew)
      else resolved
    }

    /** 发送用掉那份一次性覆盖（只有命中这家 provider 才清；别家等着自己的下一次发送）。 */
    def consumePendingSend(provider: String): Unit  =
      if (pendingSendVar.now().exists(_.provider == provider)) pendingSendVar.set(None)
    def consumeUndoNew(sessionId: String): Unit     =
      if (undoForcesNew(sessionId)) undoForcesNewSessions.update(_ - sessionId)
    def effectiveBaseline(
        provider: String,
        urls: List[SessionUrlInfo],
        sessionId: String,
        undoSessions: Set[String],
        browserTools: Boolean,
        referencedTabCount: Int
    ): ChatSendPolicy.Settings = {
      val used = providerConversationCompatible(provider, urls, browserTools, referencedTabCount)
      if (sessionId.nonEmpty && undoSessions.contains(sessionId)) ChatSendPolicy.afterUndo(used)
      else ChatSendPolicy.baseline(used)
    }
    val undoForcesNewCurrentSignal: Signal[Boolean] =
      Signal
        .combine(currentSessionIdVar.signal, undoForcesNewSessions.signal)
        .map { case (sessionId, sessions) => sessionId.nonEmpty && sessions.contains(sessionId) }

    /** 右下角 thread / prompt 标记的当前生效值和会话推导默认值，保持与真正发送链路同源。 */
    val currentSendSettingsSignal: Signal[Option[ChatSendPolicy.Settings]] =
      Signal
        .combine(
          providerVar.signal,
          sessionUrlsVar.signal,
          pinnedSettingsVar.signal,
          pendingSendVar.signal,
          currentSessionIdVar.signal,
          undoForcesNewSessions.signal,
          browserToolsVar.signal,
          pendingTabRefsVar.signal
        )
        .map { case (provider, urls, pinned, pending, sessionId, undoSessions, browserTools, tabRefs) =>
          if (provider.isEmpty) None
          else {
            val settings = ChatSendPolicy.resolve(
              effectiveBaseline(provider, urls, sessionId, undoSessions, browserTools, tabRefs.size),
              pinned.get(provider),
              pending.filter(_.provider == provider).map(_.settings)
            )
            Some(
              if (sessionId.nonEmpty && undoSessions.contains(sessionId))
                ChatSendPolicy.forceThread(settings, ProviderChatArgs.ThreadNew)
              else settings
            )
          }
        }
    val currentBaselineSettingsSignal: Signal[ChatSendPolicy.Settings]     =
      Signal
        .combine(providerVar.signal, sessionUrlsVar.signal, browserToolsVar.signal, pendingTabRefsVar.signal)
        .map { case (provider, urls, browserTools, tabRefs) =>
          ChatSendPolicy.baseline(
            providerConversationCompatible(provider, urls, browserTools, tabRefs.size)
          )
        }
    val chatSettingsVisible: Signal[Boolean]                               =
      providerVar.signal.map(_.nonEmpty)

    /** 右下角点击只改当前 provider 的下一轮覆盖；固定值仍由隐藏的 `pin/unpin` 语法维护。 */
    def updateCurrentPending(transform: ChatSendPolicy.Settings => ChatSendPolicy.Settings): Unit = {
      val provider = providerVar.now()
      if (provider.nonEmpty) {
        val next = transform(sendSettingsFor(provider)).normalized
        pendingSendVar.set(Some(PendingSend(provider, next)))
      }
    }

    /** 提交新分支内容（新消息 / 新研究）即作废 redo：清掉该会话的重做栈并把提示归零。 */
    def clearRedoStack(sessionId: String): Unit                        =
      if (sessionId.nonEmpty) {
        ChatStore.clearUndoStack(sessionId).foreach { _ =>
          undoDepthVar.set(0)
          showUndoHintVar.set(false)
        }
      }
    val apiKeysVar                                                     = Var(List.empty[ApiKeyConfig])
    val selectedApiKeyIdVar                                            = Var("")
    def applyProviderXPaths(provider: String): Unit                    = {
      val config = getProviderConfig(provider)
      inputSelVar.set(config.input)
    }
    def applyChatProvider(provider: String): Unit                      =
      applyProviderXPaths(provider)
    def activeChatProvider(provider: String): Boolean                  =
      provider.nonEmpty && parserProvidersVar.now().exists(p => p.id == provider && p.status == "active")
    def preferredProviderForSession(sessionId: String): Option[String] =
      manualProviderOverridesVar.now().get(sessionId).filter(activeChatProvider)
    def setChatProvider(provider: String, manual: Boolean): Unit       =
      if (activeChatProvider(provider)) {
        providerVar.set(provider)
        applyChatProvider(provider)
        if (manual) {
          val sessionId = currentSessionIdVar.now()
          manualProviderOverridesVar.update(_.updated(sessionId, provider))
        }
      }

    /**
     * 插件页 provider 自动跟随：没有手动偏好的 Session 才跟随"最后一次对话"的 provider。 用户手动选择后，该 Session 的偏好优先于之后到达的 URL 快照。
     *
     * 有 run 正在跑时先不动 —— 免得 footer 的 `provider · Chat` 行和正在流的这一轮不是一个 provider；下一次推送会补上。
     */
    def followLatestProvider(provider: String): Unit                 = {
      val manual   = preferredProviderForSession(currentSessionIdVar.now())
      val followed = manual.orElse(Option(provider).filter(activeChatProvider))
      if (followed.exists(_ != providerVar.now())) {
        val busy = pendingMessagesVar.now().nonEmpty
        if (!busy) {
          followed.foreach { next =>
            providerVar.set(next)
            applyChatProvider(next)
          }
        }
      }
    }
    // 回调在这里接上（必须在 `followLatestProvider` 之后）：会话 URL 快照一到就把 provider 选择器跟过去。
    followProviderHook = provider => followLatestProvider(provider)
    def ensureProviderSelected(list: List[ParserProviderInfo]): Unit = {
      val current = providerVar.now()
      if (current.isEmpty || !list.exists(provider => provider.id == current && provider.status == "active")) {
        list.find(_.status == "active").foreach { provider =>
          setChatProvider(provider.id, manual = false)
        }
      }
    }

    /**
     * 状态行 provider 那一枚：点一下切到**下一个已启用**的 provider。
     *
     * 顺序取 `parserProvidersVar` 的原序（= 配置面板那份，按 id 排序），禁用的跳过、到尾回绕；走的是和 `@` 选家、会话跟随同一条路径 （`providerVar` +
     * `applyChatProvider`），不落库。
     */
    def cycleProvider(): Unit                                                      = {
      val active = parserProvidersVar.now().filter(_.status == "active").map(_.id)
      ProviderCycle.next(active, providerVar.now()).foreach { next =>
        setChatProvider(next, manual = true)
      }
    }
    applyChatProvider(providerVar.now())
    refreshSessionUrls()
    TabAiExecutor.initialize(fn => renderDisposers += fn)
    // 网页原生会话的 agent host：SW 把原生第 1 轮交过来，这里用同一套 agent 跑完工具环。
    NativeRunHost.initialize(fn => renderDisposers += fn)
    val onStorageChanged: js.Function2[js.Dictionary[StorageChange], String, Unit] = (changes, areaName) => {
      // 窗口状态不再由页面订阅：SW 的 `SessionUrls` 从内存态直接 push 快照（单一真相 + 单一实现）。
      // provider 的 rule / apiPatterns 现在也可能被外部改（网页原生会话的配置流程）：面板跟着刷新，避免显示旧规则。
      if (areaName == "local" && changes.keys.exists(_.startsWith("parserProvider:"))) {
        refreshParserProviders().foreach(ensureProviderSelected)
      }
      // A provider capsule or another plugin page may update the settings of the session shown here.
      if (areaName == "local") {
        changes.keys
          .flatMap(SessionSettingsStore.sessionIdFromKey)
          .find(_ == currentSessionIdVar.now())
          .foreach { sessionId =>
            SessionSettingsStore.get(sessionId).foreach { stored =>
              if (currentSessionIdVar.now() == sessionId) {
                val settings = stored.getOrElse(SessionSettings.Default)
                agentModeVar.set(AgentModePolicy.normalize(settings.agentMode))
                browserToolsVar.set(settings.browserTools)
              }
            }
          }
      }
      // 每个 provider 的聊天设置（`/provider` 里改）：别的插件页改了这里也跟着刷。
      if (areaName == "local" && changes.contains(ProviderChatSettings.Key)) {
        refreshPinnedSettings()
      }
      // Session 元数据是跨页面 / SW 的共同真相：创建、首条消息自动命名和重命名都靠这一条同步。
      val changedSessionKeys =
        if (areaName == "local") changes.keys.filter(_.startsWith(ChatStore.SessionKeyPrefix)).toList
        else Nil
      if (changedSessionKeys.nonEmpty) {
        chatSessionsVar.update { sessions =>
          val byId = mutable.Map.from(sessions.map(session => session.id -> session))
          changedSessionKeys.foreach { key =>
            val sessionId = key.stripPrefix(ChatStore.SessionKeyPrefix)
            val decoded   = ChatStore.sessionFromStorageValue(JsBridge.asAny(changes(key).newValue))
            decoded match {
              case Some(session) => byId.update(sessionId, session)
              case None          => byId.remove(sessionId)
            }
          }
          byId.values.toList.sortBy(-_.createdAt)
        }
      }
      val changedFolderKeys  =
        if (areaName == "local") changes.keys.filter(_.startsWith(ChatStore.FolderKeyPrefix)).toList
        else Nil
      if (changedFolderKeys.nonEmpty) {
        chatFoldersVar.update { folders =>
          val byId = mutable.Map.from(folders.map(folder => folder.id -> folder))
          changedFolderKeys.foreach { key =>
            val folderId = key.stripPrefix(ChatStore.FolderKeyPrefix)
            val decoded  = ChatStore.sessionFolderFromStorageValue(JsBridge.asAny(changes(key).newValue))
            decoded match {
              case Some(folder) => byId.update(folderId, folder)
              case None         => byId.remove(folderId)
            }
          }
          byId.values.toList.sortBy(folder => (folder.name, folder.id))
        }
      }
    }
    ChromeAPI.storage.onChanged.addListener(onStorageChanged)
    renderDisposers += (() => ChromeAPI.storage.onChanged.removeListener(onStorageChanged))

    /** 网页原生会话更新回调：真正的实现在 loadChatSessions 定义之后绑定（避免前向引用）。 */
    var onWebChatUpdated: String => Unit = _ => ()
    var onWebChatDeleted: String => Unit = _ => ()

    /** 原生轮 marker 解析失败后的自动重试入口：实现在 `retryTurn` 定义之后绑定。 */
    var onNativeRetryTurn: NativeRetryTurnMessage => Unit                                       = _ => ()
    val onTestProgress: js.Function3[js.Any, MessageSender, js.Function1[js.Any, Unit], js.Any] =
      (message, _, _) => {
        val msg = message.asInstanceOf[RuntimeMessage]
        if (msg.`type`.getOrElse("") == "WEBCHAT_SESSION_UPDATED") {
          // 网页原生会话在 provider 页上多了几轮：刷新会话列表；如果用户正看着这条，连消息树一起刷新。
          val updated = message.asInstanceOf[WebChatSessionMessage].sessionId.getOrElse("")
          onWebChatUpdated(updated)
        } else if (msg.`type`.getOrElse("") == "WEBCHAT_SESSION_DELETED") {
          // 在 provider 页的胶囊里删了会话：插件页跟着刷新列表；删的正好是当前会话就清空视图。
          val deleted = message.asInstanceOf[WebChatSessionMessage].sessionId.getOrElse("")
          onWebChatDeleted(deleted)
        } else if (msg.`type`.getOrElse("") == "NATIVE_RETRY_TURN") {
          // 原生轮 marker 解析失败：SW 把这一轮交过来，预算内自动重试（响应里带 assistantId / reason）。
          onNativeRetryTurn(message.asInstanceOf[NativeRetryTurnMessage])
          js.Promise.resolve[js.Any](JsBridge.obj("ok" -> (true)))
        } else if (msg.`type`.getOrElse("") == "SESSION_URLS_UPDATED") {
          // SW 侧 tab / 组 / slot / 归属有任何变化都会推最新快照：会话 URL 列表实时刷新，不用点刷新。
          val payload   = message.asInstanceOf[SessionUrlsMessage]
          val sessionId = payload.sessionId.getOrElse("")
          if (sessionId.nonEmpty && sessionId == currentSessionIdVar.now()) {
            followProviderHook(payload.latestProvider.getOrElse(""))
            payload.urls
              .toOption
              .foreach(raw => applySessionUrls(raw))
          }
        }
        js.undefined
      }
    ChromeAPI.runtime.onMessage.addListener(onTestProgress)
    renderDisposers += (() => ChromeAPI.runtime.onMessage.removeListener(onTestProgress))
    def resetTaskInputHeight(): Unit                                                            =
      Option(dom.document.getElementById(taskInputId).asInstanceOf[dom.HTMLTextAreaElement]).foreach { ta =>
        ta.style.height = "auto"
        ta.style.height = s"${ta.scrollHeight}px"
      }
    def persistCurrentSessionId(id: String): Unit                                               =
      ChromeAPI.storage.local.set(
        js.Dictionary("currentChatSessionId" -> JsBridge.asObject(id))
      ).toFuture.foreach(_ => ())
    def clearCurrentSessionId(): Unit                                                           =
      ChromeAPI.storage.local.remove("currentChatSessionId").toFuture.foreach(_ => ())

    /**
     * 存储 + pending 占位的合并视图（同 id 以已落库的为准）。 这就是"用户屏幕上能看到的消息集合"，也是 undo 的目标域： 屏幕上有的就一定撤得掉。
     */
    def visibleMessages(): List[ChatMessage]              = {
      val stored = sessionMessagesVar.now()
      val ids    = stored.map(_.id).toSet
      stored ++ pendingMessagesVar.now().filterNot(message => ids.contains(message.id))
    }
    def sessionById(): Map[String, ChatMessage]           =
      ChatMessageTree.byId(visibleMessages())
    def pathTo(leafId: Option[String]): List[ChatMessage] =
      ChatMessageTree.pathTo(visibleMessages(), leafId)
    def refreshChatDerivedState(): Unit                   = {
      val all           = visibleMessages()
      val grouped       = all.filter(_.parentId.isDefined).groupBy(_.parentId.get)
      val lastAssistant = pathTo(activeLeafIdVar.now()).filter(_.role == "assistant").lastOption
      regenerableMessageIdVar.set(
        lastAssistant
          .map(_.id)
          .filter(id => grouped.getOrElse(id, Nil).isEmpty)
      )
    }

    /** 只负责更新当前可见路径。 */
    def setActiveLeaf(leafId: Option[String]): List[ChatMessage] = {
      val path = pathTo(leafId)
      activeLeafIdVar.set(leafId)
      chatMessagesVar.set(path)
      refreshChatDerivedState()
      path
    }

    /**
     * 从存储重读会话消息并重算可见链。
     *
     * `dropPendingIds` 用于"落库侧自己生成了新 id（user / error assistant）"的失败收尾：这些占位既不在存储里、
     * 又永远匹配不掉，留着会变成幽灵消息；当存储确实有内容时把它们撤掉，叶子回落到存储里的最后一条。
     */
    def refreshSessionMessages(
        sessionId: String,
        preferredLeaf: Option[String] = None,
        dropPendingIds: List[String] = Nil
    ): Unit =
      ChatStore
        .listMessages(sessionId)
        .map { all =>
          sessionMessagesVar.set(all)
          // 已落库的占位从 pending 里清掉：同一个 id 不该同时躺在"存储"和"占位"两层里。
          val storedIds  = all.map(_.id).toSet
          val extraDrops = if (all.nonEmpty) dropPendingIds.toSet else Set.empty[String]
          pendingMessagesVar.update(_.filterNot(message =>
            storedIds.contains(message.id) || extraDrops.contains(message.id)
          ))
          val known      = sessionById()
          val leaf       = preferredLeaf
            .filter(known.contains)
            // 叶子取"会话树的树梢"（没有任何消息以它为 parent），不是"createdAt 最后一条"：
            // 同毫秒并列时后者可能选到父消息，路径就会在提问处停住、回答气泡看不见。
            .orElse(ChatStore.tipOf(all).map(_.id))
            .orElse(activeLeafIdVar.now().filter(known.contains))
          setActiveLeaf(leaf)
        }
        .recover { case e =>
          PopCanLog.error("chat", "refresh session messages failed", e)
        }
        .foreach(_ => ())

    /**
     * 会话消息的统一出口：落库（有同 id 就 update，否则 add）→ 维护 pending 占位 → 刷新渲染与 active leaf。
     *
     * 所有"改消息"的路径（提交提问、落回复、取消清占位、undo/redo）都必须走这里， 否则存储快照、屏幕上渲染的列表、active leaf 会分叉。
     */
    def commitMessages(
        sessionId: String,
        upsert: Option[ChatMessage] = None,
        pending: List[ChatMessage],
        dropPendingIds: List[String] = Nil,
        leaf: Option[String]
    ): Unit = {
      if (dropPendingIds.nonEmpty)
        pendingMessagesVar.update(_.filterNot(message => dropPendingIds.contains(message.id)))
      if (pending.nonEmpty) {
        val pendingIds = pending.map(_.id).toSet
        pendingMessagesVar.update(list => list.filterNot(message => pendingIds.contains(message.id)) ++ pending)
      }
      val writeF =
        if (sessionId.isEmpty) Future.successful(())
        else
          upsert match {
            case None          => Future.successful(())
            case Some(message) =>
              ChatStore
                .listMessages(sessionId)
                .flatMap { all =>
                  if (all.exists(_.id == message.id)) ChatStore.updateMessage(message)
                  else ChatStore.addMessage(message)
                }
          }
      writeF
        .recover { case e => showError(s"Save message failed: ${e.getMessage}") }
        .foreach { _ =>
          upsert.foreach(message => pendingMessagesVar.update(_.filterNot(_.id == message.id)))
          refreshSessionMessages(sessionId, preferredLeaf = leaf.orElse(upsert.map(_.id)))
        }
    }

    /** 把从根到指定消息的这段对话复制成一个新的 session，并切换过去继续。 */
    def forkAt(messageId: String): Unit =
      if (chatRunningVar.now()) ()
      else {
        val byId = sessionById()
        val path = if (messageId.nonEmpty) pathTo(Some(messageId)) else pathTo(activeLeafIdVar.now())
        if (path.isEmpty || byId.isEmpty) {
          showInfo("Nothing to fork yet")
        } else {
          val source   = chatSessionsVar.now().find(_.id == currentSessionIdVar.now())
          val title    = source
            .map(session => if (session.title.trim.nonEmpty) s"${session.title.trim} (fork)" else "Fork")
            .getOrElse("Fork")
          val provider = path.lastOption.map(_.provider).filter(_.nonEmpty).getOrElse(providerVar.now())
          val src      = source.getOrElse(ChatSession("", title, "default", provider, 0.0, 0.0))
          clearNotice()
          ChatStore
            .forkSession(src, path, title)
            .map { session =>
              openChatSession(session.id)
              if (parserProvidersVar.now().exists(p => p.id == session.provider && p.status == "active")) {
                providerVar.set(session.provider)
                applyChatProvider(session.provider)
              }
              chatSessionsVar.update(list =>
                if (list.exists(_.id == session.id)) list else session :: list
              )
              js.timers.setTimeout(0) {
                resetTaskInputHeight()
                Option(dom.document.getElementById(taskInputId).asInstanceOf[dom.HTMLTextAreaElement])
                  .foreach(_.focus())
              }
            }
            .recover { case e => showError(s"Fork failed: ${e.getMessage}") }
            .foreach(_ => ())
        }
      }

    /** 收集某条消息及其整棵子树（包含自身），按创建时间排序。 */
    def subtreeOf(messageId: String): List[ChatMessage] =
      ChatMessageTree.subtreeOf(visibleMessages(), messageId)

    /**
     * undo 与 clear 共用的删除/入栈逻辑。
     *
     * 唯一区别由 `refill` 决定：普通 undo 把被撤销的问题带回输入框；clear 只清空。
     */
    def removeAsUndoStep(
        sessionId: String,
        removed: List[ChatMessage],
        preferredLeaf: Option[String],
        kind: String,
        refill: Option[String],
        sessionTitle: Option[String] = None,
        refillTabs: List[BrowserTabRef] = Nil
    ): Future[Int] = {
      clearNotice()
      for {
        _            <- ChatStore.deleteMessages(removed.map(_.id))
        _            <- ChatStore.pushUndoStep(sessionId, removed, kind, sessionTitle)
        _            <- if (kind == ChatStore.ClearKind)
          ChatStore.setSessionTitle(sessionId, ChatStore.NewSessionTitle)
        else Future.successful(())
        _            <- if (kind == ChatStore.ClearKind) loadChatSessions() else Future.successful(())
        restoredTabs <- if (kind == ChatStore.UndoKind)
          restoreReferencedTabs(refillTabs)
            .map { case (tabs, failures) =>
              if (failures.nonEmpty)
                Toast.info(s"Restored ${tabs.size} tab reference(s); failed: ${failures.mkString(", ")}")
              tabs
            }
        else Future.successful(Nil)
      } yield {
        if (kind == ChatStore.ClearKind || removed.exists(_.mode == "chat"))
          undoForcesNewSessions.update(_ + sessionId)
        val removedIds = removed.map(_.id).toSet
        pendingMessagesVar.update(_.filterNot(message => removedIds.contains(message.id)))
        commandStageVar.set(ChatCommandStage.Closed)
        commandItemsVar.set(Nil)
        pendingTabRefsVar.set(restoredTabs)
        tabCandidatesVar.set(Nil)
        refill match {
          case Some(text) =>
            taskVar.set(text)
            js.timers.setTimeout(0) {
              resetTaskInputHeight()
              Option(dom.document.getElementById(taskInputId).asInstanceOf[dom.HTMLTextAreaElement])
                .foreach(_.focus())
            }
          case None       =>
            taskVar.set("")
            resetTaskInputHeight()
        }
        refreshUndoDepth()
        refreshSessionMessages(sessionId, preferredLeaf = preferredLeaf)
        removed.size
      }
    }

    /** 撤销到某条提问：摘掉该提问及其之后的所有消息，把文本回填到输入框。 */
    def undoAt(messageId: String): Unit =
      if (chatRunningVar.now()) {
        // 静默失败会让人以为"已经撤掉了、可以重发"，于是同一句问题被提交两次、出现两个相同气泡。
        Toast.info("An answer is running — press Stop or wait for it before undoing")
      } else if (currentSessionIdVar.now().isEmpty) {
        showInfo("Nothing to undo")
      } else {
        val sessionId = currentSessionIdVar.now()
        val all       = visibleMessages()
        val target    = all.find(message => message.id == messageId && message.role == "user")
        target match {
          case None       =>
            showInfo("Nothing to undo")
          case Some(user) =>
            val removed = subtreeOf(user.id)
            removeAsUndoStep(
              sessionId,
              removed,
              user.parentId,
              ChatStore.UndoKind,
              Some(user.content),
              refillTabs = user.tabRefs
            )
              .recover { case e => showError(s"Undo failed: ${e.getMessage}") }
              .foreach(_ => ())
        }
      }

    /** /undo：只撤销当前路径里最后一条提问。 */
    def undoLast(): Unit =
      pathTo(activeLeafIdVar.now()).filter(_.role == "user").lastOption match {
        case Some(user) => undoAt(user.id)
        case None       => showInfo("Nothing to undo")
      }

    /** `/clear`：等价于把整条会话作为一次 undo；不回填输入框，provider URL 保持不动。 */
    def clearCurrentChat(): Unit = {
      pendingTabRefsVar.set(Nil)
      tabCandidatesVar.set(Nil)
      val sessionId = currentSessionIdVar.now()
      if (sessionId.isEmpty) showError("No chat to clear")
      else if (chatRunningVar.now()) showError("Stop the running answer before /clear")
      else
        (for {
          session  <- ChatStore.getSession(sessionId)
          messages <- ChatStore.listMessages(sessionId)
          currentTitle = session.map(_.title).getOrElse("")
          customTitle  = currentTitle.nonEmpty && !ChatStore.isPlaceholderTitle(currentTitle)
          _ <-
            if (messages.isEmpty && !customTitle) {
              Toast.info("Nothing to clear")
              Future.successful(())
            } else
              removeAsUndoStep(
                sessionId,
                messages,
                preferredLeaf = None,
                kind = ChatStore.ClearKind,
                refill = None,
                sessionTitle = if (customTitle) Some(currentTitle) else None
              ).map(removed =>
                Toast.success(
                  if (removed > 0) s"Cleared $removed message(s). Use /redo to restore"
                  else "Session title cleared. Use /redo to restore"
                )
              )
        } yield ())
          .recover { case e => Toast.error(s"Clear failed: ${Option(e.getMessage).getOrElse("unknown error")}") }
          .foreach(_ => ())
    }

    /** /redo：恢复最近一个 undo 或 clear step。 */
    def redoLastUndo(): Unit     =
      if (chatRunningVar.now() || currentSessionIdVar.now().isEmpty) ()
      else {
        val sessionId = currentSessionIdVar.now()
        ChatStore
          .popUndoStep(sessionId)
          .flatMap {
            case None       =>
              showInfo("Nothing to redo")
              Future.successful(())
            case Some(step) =>
              clearNotice()
              undoForcesNewSessions.update(_ - sessionId)
              for {
                _ <- step.sessionTitle match {
                  case Some(title) =>
                    ChatStore.getSession(sessionId).flatMap {
                      case Some(session) if ChatStore.isPlaceholderTitle(session.title) =>
                        ChatStore.setSessionTitle(sessionId, title)
                      case _                                                            => Future.successful(())
                    }
                  case None        => Future.successful(())
                }
                _ <- ChatStore.addMessages(step.messages)
                _ <- loadChatSessions()
              } yield {
                val restoredIds = step.messages.map(_.id).toSet
                pendingMessagesVar.update(_.filterNot(message => restoredIds.contains(message.id)))
                refreshUndoDepth()
                refreshSessionMessages(sessionId, preferredLeaf = step.messages.lastOption.map(_.id))
              }
          }
          .recover { case e => showError(s"Redo failed: ${e.getMessage}") }
          .foreach(_ => ())
      }
    def refreshUndoDepth(): Unit =
      if (currentSessionIdVar.now().isEmpty) {
        undoDepthVar.set(0)
        showUndoHintVar.set(false)
      } else
        ChatStore.loadUndoStack(currentSessionIdVar.now()).foreach { steps =>
          undoDepthVar.set(steps.size)
          showUndoHintVar.set(steps.lastOption.exists(_.kind == ChatStore.UndoKind))
        }

    /** Chat agent 的工具轮数预算。 */
    def maxStepsFor(mode: String): Int                            = 8
    def applyTabEvent(messageId: String)(event: TabAiEvent): Unit =
      event match {
        case TabAiEvent.StepStarted                =>
          ()
        case TabAiEvent.StepFinished(_)            =>
          ()
        case TabAiEvent.InternalRecoveryStarted(_) =>
          updateChatMessage(messageId)(message =>
            message.copy(content = "", thinking = "", toolCalls = "", status = "running")
          )
        case TabAiEvent.Thinking(delta)            =>
          updateChatMessage(messageId)(message => message.copy(thinking = message.thinking + delta))
        case TabAiEvent.Content(delta)             =>
          updateChatMessage(messageId)(message => message.copy(content = message.content + delta))
        case TabAiEvent.ContentReset               =>
          updateChatMessage(messageId)(message => message.copy(content = ""))
        case TabAiEvent.ToolCall(name, _)          =>
          val call = js.JSON.stringify(JsBridge.obj("name" -> (name), "arguments" -> ("")))
          updateChatMessage(messageId)(message =>
            message.copy(
              toolCalls = if (message.toolCalls.isEmpty) call else message.toolCalls + "," + call
            )
          )
        case TabAiEvent.ToolFinished(_, _, _, _)   =>
          ()
        case TabAiEvent.StepFailed(_)              =>
          ()
        case TabAiEvent.TurnFailed(_, _, _, _, _)  =>
          ()
        case TabAiEvent.HumanInput(_, _)           =>
          ()
        case TabAiEvent.PlanProposed(planId, plan) =>
          presentAgentPlan(planId, plan)
        case TabAiEvent.Notice(text)               =>
          Toast.info(text)
        case TabAiEvent.ProcessUpdated(stepsJson)  =>
          updateChatMessage(messageId)(message => message.copy(steps = stepsJson))
      }
    def completedAttempt(
        base: ChatMessage,
        attemptId: String,
        index: Int,
        trigger: String,
        actual: ChatMessage,
        auto: Boolean,
        reason: String
    ): ChatAttempt =
      AgentAttemptProjection.completedAttempt(base, attemptId, index, trigger, actual, auto, reason)

    /** 气泡上的 Regenerate / Retry：手动触发一次重试（次数不限，计数落在回答消息的 `retries` 上）。 */
    def regenerate(message: ChatMessage): Unit =
      if (regenerableMessageIdVar.now().contains(message.id))
        retryTurn(message, reason = "manual retry", auto = false)

    /**
     * 手动 / 自动共用的重试：把这一轮**换一段 provider 对话**（New Chat + 完整模版）重跑一遍，并在同一条消息位置上替换回答。
     *
     *   - `auto = true`（marker 类失败后的自动重试）：provider 固定用这条消息当时的 provider、不抢焦点；
     *   - `auto = false`（气泡上的 Regenerate / Retry）：沿用"当前选中优先"，focus 按 session 偏好。
     *
     * 一律以 `message.sessionId` 为准（后台会话也能重试），user 从存储里回溯（不依赖页面当前显示的是哪条会话）。
     */
    def retryTurn(
        message: ChatMessage,
        reason: String,
        auto: Boolean,
        protocolRepair: Option[String] = None
    ): Unit =
      if (chatRunningVar.now() || message.role != "assistant" || message.sessionId.isEmpty) ()
      else
        ChatStore
          .getMessagePath(message.id)
          .flatMap { path =>
            path.reverse.find(_.role == "user") match {
              case None       => Future.successful(())
              case Some(user) => startRetryTurn(message, user, reason, auto, protocolRepair)
            }
          }
          .recover { case e => PopCanLog.warn("agent", s"retry turn failed: ${Option(e.getMessage).getOrElse("")}") }
          .foreach(_ => ())
    def startRetryTurn(
        message: ChatMessage,
        user: ChatMessage,
        reason: String,
        auto: Boolean,
        protocolRepair: Option[String]
    ): Future[Unit] = {
      val sessionId = message.sessionId
      val base      = ChatMessage.withAttempts(message)
      val previous  = base.attempts.find(_.id == base.activeAttemptIdOrLegacy).getOrElse(ChatAttempt.fromMessage(base))
      val attemptIndex = if (auto) previous.index else base.attempts.map(_.index).maxOption.getOrElse(0) + 1
      val providers    = parserProvidersVar.now()
      val current      = providerVar.now()
      val providerF    =
        if (auto && message.provider.nonEmpty) Future.successful(message.provider)
        else if (providers.exists(p => p.id == current && p.status == "active")) Future.successful(current)
        else if (providers.exists(p => p.id == message.provider && p.status == "active"))
          Future.successful(message.provider)
        else Future.successful(current)
      providerF.flatMap { provider =>
        if (provider.isEmpty) Future.successful(())
        else
          for {
            _ <- Future.successful(())
            generationId   = s"regen-${js.Date.now()}-${Math.round(js.Math.random() * 1000000)}"
            attemptId      = if (auto) previous.id else generationId
            runningAttempt = ChatAttempt(
              id = attemptId,
              index = attemptIndex,
              trigger = if (auto) previous.trigger else ChatAttempt.Regenerate,
              status = "running",
              provider = provider,
              content = "",
              thinking = "",
              steps = "",
              error = "",
              internalRecoveries = previous.internalRecoveries,
              createdAt = if (auto) previous.createdAt else js.Date.now(),
              updatedAt = js.Date.now(),
              browserTools = previous.browserTools
            )
            runningMessage = base.copy(
              provider = provider,
              content = "",
              thinking = "",
              steps = "",
              status = "running",
              attempts = base.attempts.filterNot(_.id == attemptId) :+ runningAttempt,
              activeAttemptId = attemptId,
              browserTools = previous.browserTools,
              updatedAt = js.Date.now()
            )
            _ <- Future.successful {
              consumeUndoNew(sessionId)
              // 重试 = 换一段新对话重新问一遍：先结束这个 provider 的当前 URL（tab 不动，这一轮 New Chat + 不受水位线影响 ⇒ 投完整模版）。
              // 所以它固定用 `new + inline`，不读固定值也不读行内参数。
              endSessionProviderUrl(sessionId, provider)
              if (!auto && message.provider.nonEmpty && message.provider != provider)
                Toast.info(s"Retrying on $provider (this answer was from ${message.provider})")
            }
            _ <- Future.successful {
              commitMessages(
                sessionId = sessionId,
                pending = List(runningMessage),
                dropPendingIds = List(message.id),
                leaf = Some(message.id)
              )
              chatRunningVar.set(true)
            }
            _ <- ChatRunner
              .runAssistantVariant(
                sessionId,
                user,
                provider,
                applyTabEvent(message.id),
                registerCancel = cancel => abortCurrentChat = Some(cancel),
                maxSteps = maxStepsFor(user.mode),
                useBrowserTools = previous.browserTools,
                // 重试/重新生成 = 新开一段对话 + 整份模版（`file` 不参与：重问的那一轮正文一律走注入）。
                contextMode = ProviderChatSettings.New,
                promptMode = ProviderChatSettings.Inline,
                conversation = "fresh",
                assistantId = Some(message.id),
                retries = previous.internalRecoveries + (if (auto) 1 else 0),
                agentMode =
                  Option(message.effectiveMode).filter(_.nonEmpty).getOrElse(AgentModePolicy.React),
                requestedMode =
                  Option(message.requestedMode).filter(_.nonEmpty).getOrElse(AgentModePolicy.React),
                modeReason = reason,
                initialProtocolRepair = protocolRepair
              )
              .map { assistant =>
                abortCurrentChat = None
                chatRunningVar.set(false)
                val attempt =
                  completedAttempt(base, attemptId, attemptIndex, ChatAttempt.Regenerate, assistant, auto, reason)
                ChatStore
                  .saveAttempt(base, attempt)
                  .map(_ => refreshSessionMessages(sessionId, preferredLeaf = Some(assistant.id)))
                  .recover { case e => showError(s"Save attempt failed: ${e.getMessage}") }
              }
              .recover { case e =>
                abortCurrentChat = None
                chatRunningVar.set(false)
                ChatStore
                  .listMessages(sessionId)
                  .map(_.find(_.id == message.id))
                  .flatMap {
                    case Some(failed) =>
                      val attempt =
                        completedAttempt(base, attemptId, attemptIndex, ChatAttempt.Regenerate, failed, auto, reason)
                      ChatStore.saveAttempt(base, attempt).map(_ => ())
                    case None         => Future.successful(())
                  }
                  .map(_ => refreshSessionMessages(sessionId, dropPendingIds = List(message.id)))
                  .flatMap(_ => maybeAutoRetry(sessionId, message.id, e))
              }
          } yield ()
      }
    }

    /**
     * 失败收口后的统一判定：只有 marker/协议类失败、且这条回答的自动预算没用完（`retries < 2`）时才再自动重试一次。
     *
     * 传输 / 超时 / 工具 / 取消失败都不自动重试（气泡上的 Retry 兜底）。
     */
    def maybeAutoRetry(sessionId: String, assistantId: String, failure: Throwable): Future[Unit] =
      if (sessionId.isEmpty || assistantId.isEmpty || !MarkerParseFailed.isMarkerFailure(failure))
        Future.successful(())
      else
        ChatStore
          .listMessages(sessionId)
          .flatMap(messages =>
            messages.find(_.id == assistantId) match {
              case Some(assistant) if assistant.role == "assistant" && assistant.retries < 2 =>
                PopCanLog.info(
                  "agent",
                  s"auto-retry #${assistant.retries + 1}: ${Option(failure.getMessage).getOrElse("marker failure")}"
                )
                retryTurn(assistant, reason = Option(failure.getMessage).getOrElse("marker failure"), auto = true)
                Future.successful(())
              case _                                                                         =>
                Future.successful(())
            }
          )
    // SW 在原生轮 marker 解析失败后把这一轮交过来：按预算自动重试（后台会话也能跑，一切都以 message.sessionId 为准）。
    onNativeRetryTurn = (payload: NativeRetryTurnMessage) => {
      val sessionId      = payload.sessionId.getOrElse("")
      val assistantId    = payload.assistantId.getOrElse("")
      val reason         = payload.reason.filter(_.nonEmpty).getOrElse(
        "marker parse failed"
      )
      val protocolRepair = payload.protocolRepair.toOption.map(_.trim).filter(_.nonEmpty)
      if (sessionId.nonEmpty && assistantId.nonEmpty)
        ChatStore
          .listMessages(sessionId)
          .map(_.find(_.id == assistantId))
          .foreach {
            case Some(assistant) if assistant.role == "assistant" && assistant.retries < 2 =>
              PopCanLog.info("agent", s"native turn marker failure -> auto retry: $reason")
              retryTurn(assistant, reason, auto = true, protocolRepair = protocolRepair)
            case _                                                                         => ()
          }
    }
    def loadCurrentSession(): Unit                                                               =
      ChromeAPI.storage.local.get("currentChatSessionId").toFuture.foreach { result =>
        result.get("currentChatSessionId").foreach { raw =>
          JsBridge.undefinedString(raw).toOption.filter(_.nonEmpty).foreach { id =>
            cancelCurrentSessionTitleEdit()
            currentSessionIdVar.set(id)
            chatRunningVar.set(false)
            syncAgentModeForSession(id)
            syncSessionSource()
            refreshSessionMessages(id)
            refreshUndoDepth()
          }
        }
      }
    def loadChatSessions(): Future[Unit]                                                         =
      ChatStore
        .loadSessionTree()
        .flatMap { tree =>
          chatSessionsVar.set(tree.sessions)
          chatFoldersVar.set(tree.folders)
          if (newSessionFolderIdVar.now().nonEmpty && !tree.folders.exists(_.id == newSessionFolderIdVar.now()))
            newSessionFolderIdVar.set("")
          syncSessionSource()
          val repaired = tree.repairedSessionFolders + tree.repairedFolderParents
          if (repaired > 0)
            Toast.info(
              s"Repaired $repaired missing folder reference${if (repaired == 1) "" else "s"} in the session tree"
            )
          SessionSettingsStore.migrate(tree.sessions.map(_.id))
        }
        .recover { case e =>
          PopCanLog.error("chat", "load chat sessions failed", e)
          ()
        }
    def renameChatSession(sessionId: String, title: String): Future[Unit]                        =
      ChatStore.renameSession(sessionId, title).flatMap(_ => loadChatSessions())
    def syncAgentModeForSession(sessionId: String): Unit                                         =
      if (sessionId.trim.isEmpty) {
        agentModeVar.set(AgentModePolicy.React)
        browserToolsVar.set(false)
      } else
        SessionSettingsStore.load(sessionId).foreach { settings =>
          if (currentSessionIdVar.now() == sessionId) {
            agentModeVar.set(AgentModePolicy.normalize(settings.agentMode))
            browserToolsVar.set(settings.browserTools)
          }
        }
    def openChatSession(id: String): Unit                                                        = {
      pendingAgentPlanVar.set(None)
      AgentPlanApproval.cancelAll("Session changed")
      closeSessionFolderPanel()
      cancelCurrentSessionTitleEdit()
      currentSessionIdVar.set(id)
      chatRunningVar.set(false)
      syncAgentModeForSession(id)
      syncSessionSource()
      persistCurrentSessionId(id)
      refreshSessionMessages(id)
      refreshUndoDepth()
    }
    onWebChatUpdated = (updated: String) => {
      loadChatSessions()
      if (updated.nonEmpty && updated == currentSessionIdVar.now()) {
        syncSessionSource()
        refreshSessionMessages(updated)
      }
    }
    onWebChatDeleted = (deleted: String) => {
      // 存储侧已经删掉了（SW 删的），页面只需要把列表与当前视图对齐。
      manualProviderOverridesVar.update(_ - deleted)
      chatSessionsVar.update(_.filterNot(_.id == deleted))
      undoForcesNewSessions.update(_ - deleted)
      pendingMessagesVar.update(_.filterNot(_.sessionId == deleted))
      if (currentSessionIdVar.now() == deleted) {
        cancelCurrentSessionTitleEdit()
        agentModeVar.set(AgentModePolicy.React)
        browserToolsVar.set(false)
        pendingTabRefsVar.set(Nil)
        tabCandidatesVar.set(Nil)
        currentSessionIdVar.set("")
        sessionMessagesVar.set(Nil)
        chatMessagesVar.set(Nil)
        undoDepthVar.set(0)
        showUndoHintVar.set(false)
        activeLeafIdVar.set(None)
        refreshChatDerivedState()
        abortCurrentChat = None
        chatRunningVar.set(false)
        clearCurrentSessionId()
      }
      loadChatSessions()
    }
    def deleteChatSession(id: String): Future[Unit]                                              =
      ChatStore.deleteSession(id)
        .flatMap { _ =>
          activeMemoryClient
            .map(_.forgetSession(id, forgetSources = false).map(_ => ()))
            .getOrElse(Future.successful(()))
            .recover { case e =>
              PopCanLog.warn("memory", s"session memory cleanup failed: ${Option(e.getMessage).getOrElse("")}")
              ()
            }
        }
        .map { _ =>
          manualProviderOverridesVar.update(_ - id)
          chatSessionsVar.update(_.filterNot(_.id == id))
          undoForcesNewSessions.update(_ - id)
          pendingMessagesVar.update(_.filterNot(_.sessionId == id))
          if (currentSessionIdVar.now() == id) {
            cancelCurrentSessionTitleEdit()
            agentModeVar.set(AgentModePolicy.React)
            browserToolsVar.set(false)
            pendingAgentPlanVar.set(None)
            AgentPlanApproval.cancelAll("Session deleted")
            pendingTabRefsVar.set(Nil)
            tabCandidatesVar.set(Nil)
            currentSessionIdVar.set("")
            sessionMessagesVar.set(Nil)
            chatMessagesVar.set(Nil)
            undoDepthVar.set(0)
            showUndoHintVar.set(false)
            activeLeafIdVar.set(None)
            refreshChatDerivedState()
            abortCurrentChat = None
            chatRunningVar.set(false)
            clearCurrentSessionId()
          }
        }
        .recover { case e =>
          PopCanLog.error("chat", "delete chat session failed", e)
          ()
        }
    def updateChatMessage(id: String)(update: ChatMessage => ChatMessage): Unit                  =
      chatMessagesVar.update(_.map(message => if (message.id == id) update(message) else message))
    def abortRunningChat(): Unit                                                                 = {
      abortCurrentChat.foreach(_())
      abortCurrentChat = None
      chatRunningVar.set(false)
      pendingAgentPlanVar.set(None)
      AgentPlanApproval.cancelAll("Task canceled")
    }

    /** 立即建 user + pending assistant 消息并开始规划；不再有开跑前配置阶段。 */
    /** 当前 gate 的 Continue / Skip：先记录 HITL 与配置，再从同一 checkpoint 继续。 */
    def startNewChat(
        folderIdOverride: Option[String] = None,
        keepSessionPanelOpen: Boolean = false,
        reuseEmptySession: Boolean = true
    ): Future[String] = {
      val inheritedFolderId                           =
        chatSessionsVar
          .now()
          .find(_.id == currentSessionIdVar.now())
          .map(_.folderId)
          .filter(id => id.nonEmpty && chatFoldersVar.now().exists(_.id == id))
          .getOrElse("")
      val folderId                                    = folderIdOverride.getOrElse(inheritedFolderId)
      val provider                                    =
        Option(providerVar.now()).filter(_.nonEmpty)
          .orElse(parserProvidersVar.now().find(_.status == "active").map(_.id))
          .getOrElse("")
      val reusableSession                             =
        if (!reuseEmptySession) None
        else
          chatSessionsVar.now()
            .find(_.id == currentSessionIdVar.now())
            .filter { session =>
              ChatStore.isPlaceholderTitle(session.title) &&
              session.folderId == folderId &&
              sessionMessagesVar.now().isEmpty &&
              chatMessagesVar.now().isEmpty &&
              pendingMessagesVar.now().forall(_.sessionId != session.id) &&
              !chatRunningVar.now()
            }
      newSessionFolderIdVar.set(folderId)
      if (!keepSessionPanelOpen) closeSessionFolderPanel()
      pendingTabRefsVar.set(Nil)
      tabCandidatesVar.set(Nil)
      abortCurrentChat.foreach(_())
      abortCurrentChat = None
      chatRunningVar.set(false)
      cancelCurrentSessionTitleEdit()
      currentSessionIdVar.set("")
      activeLeafIdVar.set(None)
      sessionMessagesVar.set(Nil)
      chatMessagesVar.set(Nil)
      agentModeVar.set(AgentModePolicy.React)
      browserToolsVar.set(false)
      pendingAgentPlanVar.set(None)
      AgentPlanApproval.cancelAll("New session")
      undoDepthVar.set(0)
      showUndoHintVar.set(false)
      def activate(sessionId: String): Future[String] = {
        manualProviderOverridesVar.update { overrides =>
          overrides.get("").fold(overrides)(provider => (overrides - "") + (sessionId -> provider))
        }
        cancelCurrentSessionTitleEdit()
        currentSessionIdVar.set(sessionId)
        persistCurrentSessionId(sessionId)
        agentModeVar.set(AgentModePolicy.React)
        SessionSettingsStore.reset(sessionId, browserToolsVar.now(), AgentModePolicy.React).foreach(_ => ())
        syncSessionSource()
        refreshSessionMessages(sessionId)
        loadChatSessions().map(_ => sessionId)
      }
      val operation                                   = reusableSession match {
        case Some(session) =>
          activate(session.id)
        case None          =>
          ChatStore
            .createSession(ChatStore.NewSessionTitle, provider, folderId)
            .flatMap(session => activate(session.id))
      }
      pendingNewSession = Some(operation)
      operation.foreach(_ => if (pendingNewSession.contains(operation)) pendingNewSession = None)
      operation.failed.foreach(_ => if (pendingNewSession.contains(operation)) pendingNewSession = None)
      operation
    }
    def closeCommandPalette(clearInput: Boolean = true): Unit = {
      commandStageVar.set(ChatCommandStage.Closed)
      commandItemsVar.set(Nil)
      commandIndexVar.set(0)
      clearNotice()
      if (clearInput) taskVar.set("")
    }
    closeCommandsForSessionFolder = () =>
      if (commandStageVar.now() != ChatCommandStage.Closed) closeCommandPalette(clearInput = false)
    def parseCommandInput(): (String, String)                 = {
      val text = taskVar.now()
      if (!text.startsWith("/")) ("", "")
      else {
        val body = text.drop(1)
        val idx  = body.indexWhere(_.isWhitespace)
        if (idx < 0) (body, "")
        else (body.take(idx), body.drop(idx + 1).trim)
      }
    }
    def atRootItems(query: String): List[ChatCommandItem]     = {
      val q    = query.trim.toLowerCase
      val rows = List(
        ChatCommandItem(
          id = "at:tabs",
          label = "tabs",
          description = "Reference browser tabs for this run",
          run = () => openTabsStage()
        )
      )
      rows.filter(item => q.isEmpty || item.label.startsWith(q))
    }
    def openTabsStage(): Unit                                 = {
      taskVar.set("@tabs ")
      commandStageVar.set(ChatCommandStage.Tabs)
      loadTabCandidates().foreach { _ =>
        refreshCommandItems(ChatCommandStage.Tabs, "")
      }
    }
    def loadTabCandidates(): Future[Unit]                     =
      ChromeAPI.tabs.getCurrent().toFuture
        .flatMap { current =>
          current.windowId.toOption match {
            case Some(windowId) =>
              ChromeAPI.tabs.query(js.Dictionary[js.Any]("windowId" -> windowId)).toFuture
            case None           => Future.successful(js.Array[Tab]())
          }
        }
        .flatMap { tabs =>
          val windowIds = tabs.toList.flatMap(_.windowId.toOption).distinct
          val groupsF   =
            windowIds.headOption
              .map(windowId =>
                ChromeAPI.tabGroups
                  .query(js.Dictionary[js.Any]("windowId" -> windowId))
                  .toFuture
              )
              .getOrElse(Future.successful(js.Array[TabGroup]()))
          groupsF.recover { case _ => js.Array[TabGroup]() }.map(groups => (tabs, groups))
        }
        .map { case (tabs, groups) =>
          val selected   = pendingTabRefsVar.now().map(tab => tab.tabId -> tab).toMap
          val groupsById = groups.toList.map(group => group.id -> group).toMap
          val rows       = tabs.toList
            .sortBy(_.index.toOption.getOrElse(Int.MaxValue))
            .flatMap(tab =>
              tab.id.toOption
                .filter(_ > 0)
                .zip(tab.url.toOption.filter(url => url.startsWith("http")))
                .map { case (tabId, url) =>
                  val previous = selected.get(tabId)
                  val groupId  = tab.groupId.toOption.getOrElse(-1)
                  val group    = groupsById.get(groupId)
                  BrowserTabRef(
                    ref = previous.map(_.ref).getOrElse(""),
                    tabId = tabId,
                    title = tab.title.toOption.filter(_.nonEmpty).getOrElse(url),
                    url = url,
                    windowId = tab.windowId.toOption.getOrElse(0),
                    index = tab.index.toOption.getOrElse(0),
                    pinned = tab.pinned.toOption.getOrElse(false),
                    groupId = groupId,
                    groupTitle = group.flatMap(_.title.toOption).getOrElse(""),
                    groupColor = group.flatMap(_.color.toOption).getOrElse("")
                  )
                }
            )
          tabCandidatesVar.set(rows)
          pendingTabRefsVar.set(
            pendingTabRefsVar
              .now()
              .flatMap(old => rows.find(_.tabId == old.tabId).map(row => row.copy(ref = old.ref)))
          )
        }
        .recover { case e =>
          tabCandidatesVar.set(Nil)
          showError(s"Could not list browser tabs: ${Option(e.getMessage).getOrElse("unknown error")}")
        }
    def toggleTabReference(tab: BrowserTabRef): Unit          = {
      val selected = pendingTabRefsVar.now()
      selected.find(_.tabId == tab.tabId) match {
        case Some(_) =>
          pendingTabRefsVar.set(selected.filterNot(_.tabId == tab.tabId))
          tabCandidatesVar.update(_.map(row =>
            if (row.tabId == tab.tabId) row.copy(ref = "") else row
          ))
        case None    =>
          if (selected.size >= 8) showError("At most 8 tabs can be referenced")
          else {
            val nextNumber =
              selected.flatMap(tabRef => tabRef.ref.stripPrefix("T").toIntOption).maxOption.getOrElse(0) + 1
            val next       = tab.copy(ref = s"T$nextNumber")
            pendingTabRefsVar.set(selected :+ next)
            tabCandidatesVar.update(_.map(row => if (row.tabId == tab.tabId) next else row))
          }
      }
      refreshCommandItems(ChatCommandStage.Tabs, tabsStageQuery())
    }
    def tabsStageQuery(): String                              = {
      val body  = taskVar.now().stripPrefix("@").trim
      val lower = body.toLowerCase
      if (lower == "tabs") "" else body.drop(4).trim
    }
    def tabItems(query: String): List[ChatCommandItem]        =
      PopCanCommandItems.tabs(
        query,
        tabCandidatesVar.now(),
        pendingTabRefsVar.now().map(tab => tab.tabId -> tab.ref).toMap,
        toggleTabReference
      )

    /** `/agent` 面板选择当前 Session 的 Agent Mode。 */
    def agentItems(query: String): List[ChatCommandItem] =
      PopCanCommandItems.agents(
        query,
        AgentModePolicy.normalize(agentModeVar.now()),
        mode => {
          setAgentMode(mode)
          closeCommandPalette()
        }
      )

    /** `/provider` 面板只负责选择 provider。 */
    def providerItems(query: String): List[ChatCommandItem] = {
      val parsed = ProviderChatArgs.parse(query)
      val q      = parsed.query.trim.toLowerCase
      parserProvidersVar
        .now()
        .filter(_.status == "active")
        .filter(p => q.isEmpty || p.name.toLowerCase.contains(q) || p.id.toLowerCase.contains(q))
        .sortBy { p =>
          // 前缀命中的排前面（`@ki` 时 kimi 在含 "ki" 的其它名字之前），其余按名字排序保证确定性。
          val prefixHit = q.nonEmpty && (p.name.toLowerCase.startsWith(q) || p.id.toLowerCase.startsWith(q))
          (if (prefixHit) 0 else 1, p.name)
        }
        .map { provider =>
          ChatCommandItem(
            id = s"provider:${provider.id}",
            label = provider.name,
            description = provider.id,
            selected = provider.id == providerVar.now(),
            title = s"Select ${provider.name}. " +
              s"Usage: /provider ${provider.id} new|continue inline|file pin|unpin.",
            run = () =>
              if (isCompleteProviderToken(parsed.query)) commitProviderRow(provider.id, parsed)
              else pickProvider(provider.id, parsed)
          )
        }
    }

    /** 首 token 是否已经是某家 active provider 的 id / 名字（决定 Enter 是"选中"还是"收工"）。 */
    def isCompleteProviderToken(token: String): Boolean = {
      val value = token.trim.toLowerCase
      value.nonEmpty && parserProvidersVar
        .now()
        .exists(p => p.status == "active" && (p.id.toLowerCase == value || p.name.toLowerCase == value))
    }

    /** 把已打的参数拼回一行（回填用）：`new file focus pin`。 */
    def providerArgsText(parsed: ProviderChatArgs.Parsed): String =
      (parsed.thread.toList ++ parsed.prompt.toList ++
        (if (parsed.unpin) List(ProviderChatArgs.UnpinFlag)
         else if (parsed.pin) List(ProviderChatArgs.PinFlag)
         else Nil)).mkString(" ")

    /**
     * 选中一家：输入框回填成 `/provider <id> <已打参数>`（光标在末尾），把当前 provider 切过去，面板继续开着 —— 什么设置都还没落地，用户可以接着打参数。
     *
     * 选完就是"这家了"，所以切 provider 立刻发生；Esc 只丢弃这段输入，不回退这次选择。
     */
    def pickProvider(providerId: String, parsed: ProviderChatArgs.Parsed): Unit = {
      val args = providerArgsText(parsed)
      val text = if (args.isEmpty) s"/provider $providerId " else s"/provider $providerId $args"
      setChatProvider(providerId, manual = true)
      taskVar.set(text)
      Option(dom.document.getElementById(taskInputId).asInstanceOf[dom.HTMLTextAreaElement]).foreach { ta =>
        ta.value = text
        ta.focus()
        ta.setSelectionRange(ta.value.length, ta.value.length)
      }
      resetTaskInputHeight()
      commandStageVar.set(ChatCommandStage.Providers)
      clearNotice()
      refreshCommandItems(ChatCommandStage.Providers, text.stripPrefix("/provider").trim)
    }

    /**
     * 收工：`pin` / `unpin` 落地到这家 provider 的固定值，行内打的参数落成"只作用于下一次发送"的覆盖，然后关面板 + 清空输入。
     *
     * 没打值的收工就是"选好这家、关面板、去提问"，什么设置都不改。
     */
    def commitProviderRow(providerId: String, parsed: ProviderChatArgs.Parsed): Unit = {
      val effective = sendSettingsFor(providerId, Some(parsed))
      if (parsed.pin) {
        // 固定 = 冻结"此刻生效的这一组"（含这行刚打的值）。
        pinnedSettingsVar.update(all => all.updated(providerId, effective))
        ProviderChatSettings.setPinned(providerId, effective).foreach { _ =>
          refreshPinnedSettings()
          Toast.success(s"Pinned $providerId: thread ${effective.thread} · prompt ${effective.prompt}")
        }
      } else if (parsed.unpin) {
        pinnedSettingsVar.update(all => all - providerId)
        ProviderChatSettings.clearPinned(providerId).foreach { _ =>
          refreshPinnedSettings()
          Toast.info(s"Unpinned $providerId — back to the session default")
        }
      }
      if (parsed.thread.nonEmpty || parsed.prompt.nonEmpty) {
        // 只留最新一家：这次提交把上一次的覆盖替换掉。
        pendingSendVar.set(Some(PendingSend(providerId, effective)))
      }
      setChatProvider(providerId, manual = true)
      closeCommandPalette()
    }
    def beginRememberScope(): Unit                                                   = {
      taskVar.set("/remember ")
      commandStageVar.set(ChatCommandStage.RememberScope)
      clearNotice()
      refreshCommandItems(ChatCommandStage.RememberScope, "")
      resetTaskInputHeight()
      Option(dom.document.getElementById(taskInputId).asInstanceOf[dom.HTMLTextAreaElement]).foreach { ta =>
        ta.focus()
        ta.setSelectionRange(ta.value.length, ta.value.length)
      }
    }
    def chooseRememberScope(scope: String): Unit                                     =
      if (scope == "session" && currentSessionIdVar.now().isEmpty)
        showError("Open a chat before saving session Memory")
      else {
        val prefix = s"/remember $scope "
        closeCommandPalette()
        rememberDraftScopeVar.set(Some(scope))
        taskVar.set(prefix)
        footerNoticeVar.set(FooterNotice(
          s"$scope Memory · type the memory text, then press Enter · Esc cancels",
          "info"
        ))
        resetTaskInputHeight()
        js.timers.setTimeout(0) {
          Option(dom.document.getElementById(taskInputId).asInstanceOf[dom.HTMLTextAreaElement]).foreach { ta =>
            ta.focus()
            ta.setSelectionRange(ta.value.length, ta.value.length)
          }
        }
      }
    def rememberScopeItems(query: String): List[ChatCommandItem]                     =
      PopCanCommandItems.rememberScopes(query, currentSessionIdVar.now().nonEmpty, chooseRememberScope)
    def commandSpecItems(query: String): List[ChatCommandItem]                       =
      PopCanCommandItems.commands(
        query,
        () => beginRememberScope(),
        (name, _) => executeCommand(name, parseCommandInput()._2)
      )
    def refreshCommandItems(stage: ChatCommandStage, query: String): Unit            = {
      val items         = stage match {
        case ChatCommandStage.Commands      => commandSpecItems(query)
        case ChatCommandStage.AtRoot        => atRootItems(query)
        case ChatCommandStage.Providers     => providerItems(query)
        case ChatCommandStage.Agents        => agentItems(query)
        case ChatCommandStage.Tabs          => tabItems(query)
        case ChatCommandStage.RememberScope => rememberScopeItems(query)
        case ChatCommandStage.Closed        => Nil
      }
      commandItemsVar.set(items)
      val selectedIndex = items.indexWhere(_.selected)
      commandIndexVar.set(if (selectedIndex >= 0) selectedIndex else 0)
      // 过滤后列表可能变短、原来滚到的位置不再有效：把当前键盘焦点滚进视口。
      scrollCommandSelectionIntoView()
    }
    def executeCommand(name: String, args: String): Unit                             =
      name.toLowerCase match {
        case "new"           =>
          startNewChat().failed.foreach(error =>
            showError(s"Create session failed: ${Option(error.getMessage).getOrElse("unknown error")}")
          )
          closeCommandPalette()
        case "remember"      =>
          rememberDraftScopeVar.set(None)
          closeCommandPalette()
          val trimmed       = args.trim
          val (scope, text) =
            if (trimmed.toLowerCase.startsWith("global "))
              "global" -> trimmed.drop("global".length).trim
            else if (trimmed.toLowerCase.startsWith("session "))
              "session"   -> trimmed.drop("session".length).trim
            else "global" -> trimmed
          if (text.isEmpty) showError("Usage: /remember [global|session] <text>")
          else if (scope == "session" && currentSessionIdVar.now().isEmpty)
            showError("Open a chat before saving session Memory")
          else
            activeMemoryClient match {
              case None         => showError("Memory runtime is not available")
              case Some(client) =>
                client
                  .remember(
                    scope,
                    "fact",
                    text,
                    importance = 8,
                    pinned = true,
                    sourceSessionId = if (scope == "session") currentSessionIdVar.now() else ""
                  )
                  .map { _ =>
                    refreshMemoryLibrary()
                    Toast.success(s"Remembered in $scope memory")
                  }
                  .recover { case e =>
                    showError(s"Remember failed: ${Option(e.getMessage).getOrElse("unknown error")}")
                  }
                ()
            }
        case "clear"         =>
          closeCommandPalette()
          clearCurrentChat()
        case "fork"          =>
          forkAt(activeLeafIdVar.now().getOrElse(""))
          closeCommandPalette()
        case "undo"          =>
          undoLast()
          closeCommandPalette()
        case "redo"          =>
          redoLastUndo()
          closeCommandPalette()
        case "agent"         =>
          val query = args.trim
          commandStageVar.set(ChatCommandStage.Agents)
          taskVar.set(if (query.isEmpty) "/agent " else s"/agent $query")
          clearNotice()
          refreshCommandItems(ChatCommandStage.Agents, query)
        case "provider"      =>
          val query = args.trim
          commandStageVar.set(ChatCommandStage.Providers)
          taskVar.set(if (query.isEmpty) "/provider " else s"/provider $query")
          clearNotice()
          refreshCommandItems(ChatCommandStage.Providers, query)
        case "browser-tools" =>
          val toolsArg = args.trim.toLowerCase
          // 先关面板：closeCommandPalette 会清提示，提示必须在它之后发。
          closeCommandPalette()
          toolsArg match {
            case ""    => applyBrowserTools(!browserToolsVar.now(), persist = true)
            case "on"  => applyBrowserTools(enabled = true, persist = true)
            case "off" => applyBrowserTools(enabled = false, persist = true)
            case _     => showError("Usage: /browser-tools [on|off]")
          }
        case "sessions"      =>
          closeCommandPalette()
          loadChatSessions().foreach(_ => openSessionFolderPanel())
        case other           =>
          showError(s"Unknown command: /$other")
      }
    def refreshCommandPalette(): Unit                                                = {
      val text              = taskVar.now()
      val keepRememberDraft = rememberDraftScopeVar.now().exists { scope =>
        val prefix = s"/remember $scope"
        text == prefix || text.startsWith(s"$prefix ")
      }
      if (keepRememberDraft) return
      rememberDraftScopeVar.set(None)
      if (text.startsWith("@") || text.startsWith("/")) {
        closeMemoryLibrary()
        closeSessionFolderPanel()
        sessionUrlsOpenVar.set(false)
      }
      if (text.startsWith("@")) {
        val body  = text.drop(1).dropWhile(_.isWhitespace)
        val lower = body.toLowerCase
        if (lower == "tabs" || lower.startsWith("tabs ")) {
          val entering = commandStageVar.now() != ChatCommandStage.Tabs
          commandStageVar.set(ChatCommandStage.Tabs)
          clearNotice()
          if (entering || tabCandidatesVar.now().isEmpty)
            loadTabCandidates().foreach(_ => refreshCommandItems(ChatCommandStage.Tabs, tabsStageQuery()))
          else refreshCommandItems(ChatCommandStage.Tabs, tabsStageQuery())
        } else {
          commandStageVar.set(ChatCommandStage.AtRoot)
          clearNotice()
          refreshCommandItems(ChatCommandStage.AtRoot, body)
        }
      } else if (!text.startsWith("/")) {
        if (commandStageVar.now() != ChatCommandStage.Closed) closeCommandPalette(clearInput = false)
        clearNotice()
      } else {
        val (name, args) = parseCommandInput()
        if (name == "provider") {
          commandStageVar.set(ChatCommandStage.Providers)
          clearNotice()
          refreshCommandItems(ChatCommandStage.Providers, args)
        } else if (name == "agent") {
          commandStageVar.set(ChatCommandStage.Agents)
          clearNotice()
          refreshCommandItems(ChatCommandStage.Agents, args)
        } else
          commandStageVar.now() match {
            case ChatCommandStage.Closed                              =>
              commandStageVar.set(ChatCommandStage.Commands)
              refreshCommandItems(ChatCommandStage.Commands, name)
            case ChatCommandStage.Commands                            =>
              refreshCommandItems(ChatCommandStage.Commands, name)
            case ChatCommandStage.AtRoot | ChatCommandStage.Providers | ChatCommandStage.Agents |
                ChatCommandStage.Tabs =>
              commandStageVar.set(ChatCommandStage.Commands)
              refreshCommandItems(ChatCommandStage.Commands, name)
            case ChatCommandStage.RememberScope if name == "remember" =>
              refreshCommandItems(ChatCommandStage.RememberScope, args)
            case ChatCommandStage.RememberScope                       =>
              commandStageVar.set(ChatCommandStage.Commands)
              refreshCommandItems(ChatCommandStage.Commands, name)
          }
      }
    }
    def handleCommandEnter(): Boolean                                                =
      if (commandStageVar.now() == ChatCommandStage.Closed) false
      else if (commandStageVar.now() == ChatCommandStage.Providers && commandItemsVar.now().isEmpty) {
        val (_, args) = parseCommandInput()
        val query     = args.trim
        showError(if (query.isEmpty) "No active providers" else s"No matching provider: $query")
        true
      } else if (commandStageVar.now() == ChatCommandStage.Agents && commandItemsVar.now().isEmpty) {
        val (_, args) = parseCommandInput()
        val query     = args.trim
        showError(if (query.isEmpty) "No agent modes available" else s"No matching agent mode: $query")
        true
      } else if (
        (commandStageVar.now() == ChatCommandStage.AtRoot ||
          commandStageVar.now() == ChatCommandStage.Tabs ||
          commandStageVar.now() == ChatCommandStage.RememberScope) && commandItemsVar.now().isEmpty
      ) {
        // `@` 后面没有可匹配项时按普通文本提交。
        closeCommandPalette(clearInput = false)
        false
      } else {
        val items = commandItemsVar.now()
        if (items.nonEmpty) {
          val index = math.max(0, math.min(commandIndexVar.now(), items.size - 1))
          val item  = items(index)
          clearNotice()
          item.run()
        } else {
          val (name, args) = parseCommandInput()
          if (name.nonEmpty) executeCommand(name, args)
        }
        true
      }
    def moveCommandSelection(delta: Int): Unit                                       = {
      val size = commandItemsVar.now().size
      if (size > 0) {
        val next = (commandIndexVar.now() + delta + size) % size
        commandIndexVar.set(next)
        scrollCommandSelectionIntoView()
      }
    }

    /**
     * 把当前选中行滚进可视区 —— opencode TUI 的做法（`moveTo` 里按视口算滚动）。
     *
     * 不做这个的话，连按 ↓ 到底部时选中项会跑出 280px 的列表视口，只能靠鼠标滚轮才发现它。
     */
    def scrollCommandSelectionIntoView(): Unit =
      js.timers.setTimeout(0) {
        Option(dom.document.querySelector(".th-cmd-selected")).foreach { node =>
          JsBridge.call(
            node,
            "scrollIntoView",
            JsBridge.obj("block" -> ("nearest"), "inline" -> ("nearest"))
          )
          ()
        }
      }

    /**
     * Esc：纯取消 —— Enter 已经是一次收工，Esc 只在用户想放弃这段输入时用。
     *
     * 收尾口径按 opencode TUI 的 `prompt.autocomplete.hide`：
     *
     *   - 面板处于 Providers 阶段：输入是没打完的 `/provider …` → 一并清掉；
     *   - Commands 阶段且输入形如 `/…` 不含空格：命令还没定下 → 整段删掉（等价它的 `deleteRange(0,0 → cursor)`）；
     *   - 其余（已定下命令并带参数，如手打的 `/context full`）→ 只关面板，文字留着继续编辑。
     */
    def escapeCommandPalette(): Unit                                                                 = {
      val text             = taskVar.now()
      val scaffoldOnly     =
        commandStageVar.now() == ChatCommandStage.AtRoot ||
          commandStageVar.now() == ChatCommandStage.Providers ||
          commandStageVar.now() == ChatCommandStage.Agents ||
          commandStageVar.now() == ChatCommandStage.Tabs ||
          commandStageVar.now() == ChatCommandStage.RememberScope
      val halfTypedCommand = text.startsWith("/") && !text.contains(" ")
      if (scaffoldOnly || halfTypedCommand) taskVar.set("")
      closeCommandPalette(clearInput = false)
      resetTaskInputHeight()
    }
    def resolvePendingAgentPlan(proposal: PendingAgentPlan, action: String, note: String = ""): Unit = {
      pendingAgentPlanVar.set(None)
      taskVar.set("")
      resetTaskInputHeight()
      AgentPlanApproval.resolve(proposal.planId, action, note)
    }

    /** 审批态下主输入框只用于修改计划；空输入不触发任何操作。 */
    def submitPendingPlanModification(): Boolean     =
      pendingAgentPlanVar.now() match {
        case Some(proposal) =>
          val note = taskVar.now().trim
          if (note.nonEmpty) resolvePendingAgentPlan(proposal, "modify", note)
          true
        case None           => false
      }
    def submitTask(): Unit                           = {
      if (submitPendingPlanModification()) return
      if (currentSessionIdVar.now().isEmpty) {
        pendingNewSession match {
          case Some(operation) =>
            operation.foreach(_ => js.timers.setTimeout(0)(submitTask()))
            operation.failed.foreach(error =>
              showError(s"Create session failed: ${Option(error.getMessage).getOrElse("unknown error")}")
            )
            return
          case None            =>
            ()
        }
      }
      clearNotice()
      def runAfterPermission(): Unit = {
        val task               = taskVar.now()
        val chosenProvider     =
          if (providerVar.now().nonEmpty) providerVar.now()
          else parserProvidersVar.now().find(_.status == "active").map(_.id).getOrElse("")
        val requestedAgentMode = AgentModePolicy.normalize(agentModeVar.now())
        val routed             = AgentModePolicy.route(requestedAgentMode)
        val routeDecision      = Some(routed)
        routeDecision.foreach { decision =>
          val runMode             = decision.mode
          val sessionBrowserTools = browserToolsVar.now()
          val referencedTabs      = pendingTabRefsVar.now()
          val runBrowserTools     = BrowserToolsPolicy.effectiveForRun(sessionBrowserTools, referencedTabs.size)
          if (BrowserToolsPolicy.isOneTurnOverride(sessionBrowserTools, referencedTabs.size))
            Toast.info("Browser tools enabled for this run because a tab was referenced")
          if (chosenProvider.nonEmpty && chosenProvider != providerVar.now()) {
            providerVar.set(chosenProvider)
            applyChatProvider(chosenProvider)
          }
          if (task.trim.startsWith("/")) {
            val (commandName, commandArgs) = parseCommandInput()
            if (commandName == "remember" && rememberDraftScopeVar.now().nonEmpty)
              executeCommand(commandName, commandArgs)
            else {
              commandStageVar.set(ChatCommandStage.Commands)
              refreshCommandPalette()
            }
          } else if (task.trim.nonEmpty && chosenProvider.nonEmpty) {
            pendingTabRefsVar.set(Nil)
            tabCandidatesVar.set(Nil)
            val generationId     = js.Date.now().toString + "-" + js.Math.random().toString.replace(".", "").take(8)
            val now              = js.Date.now()
            val parentId         = activeLeafIdVar.now()
            val mode             = "chat"
            val maxSteps         = maxStepsFor(mode)
            val pendingUser      = ChatMessage(
              id = generationId,
              sessionId = currentSessionIdVar.now(),
              parentId = parentId,
              role = "user",
              content = task,
              thinking = "",
              status = "running",
              toolCalls = "",
              toolCallId = "",
              toolResults = "",
              provider = chosenProvider,
              mergedFrom = Nil,
              createdAt = now,
              updatedAt = now,
              mode = mode,
              requestedMode = requestedAgentMode,
              effectiveMode = runMode,
              modePath = js.JSON.stringify(js.Array(JsBridge.obj(
                "mode"      -> (runMode),
                "requested" -> (requestedAgentMode),
                "reason"    -> (decision.reason),
                "at"        -> (now)
              ))),
              browserTools = runBrowserTools,
              tabRefs = referencedTabs
            )
            val pendingAssistant = ChatMessage(
              id = generationId + "-assistant",
              sessionId = currentSessionIdVar.now(),
              parentId = Some(generationId),
              role = "assistant",
              content = "",
              thinking = "",
              status = "running",
              toolCalls = "",
              toolCallId = "",
              toolResults = "",
              provider = chosenProvider,
              mergedFrom = Nil,
              createdAt = now,
              updatedAt = now,
              mode = mode,
              requestedMode = requestedAgentMode,
              effectiveMode = runMode,
              modePath = js.JSON.stringify(js.Array(JsBridge.obj(
                "mode"      -> (runMode),
                "requested" -> (requestedAgentMode),
                "reason"    -> (decision.reason),
                "at"        -> (now)
              ))),
              browserTools = runBrowserTools
            )
            // 这两条只是屏幕上的占位（提问由 runChat 落库、回复在结束时落库）；统一进 pending 层并刷新。
            commitMessages(
              sessionId = currentSessionIdVar.now(),
              pending = List(pendingUser, pendingAssistant),
              leaf = Some(pendingAssistant.id)
            )
            val sessionFuture: Future[String] =
              if (currentSessionIdVar.now().isEmpty) {
                val folderId = Option(newSessionFolderIdVar.now())
                  .filter(id => id.isEmpty || chatFoldersVar.now().exists(_.id == id))
                  .getOrElse("")
                ChatStore.createSession(
                  task.take(40),
                  chosenProvider,
                  folderId = folderId
                )
                  .flatMap { session =>
                    cancelCurrentSessionTitleEdit()
                    currentSessionIdVar.set(session.id)
                    persistCurrentSessionId(session.id)
                    SessionSettingsStore
                      .ensure(session.id, sessionBrowserTools, requestedAgentMode)
                      .foreach(_ => ())
                    Future.successful {
                      // 新会话在这家还没有任何对话：先把上一会话的 URL 快照清掉，免得这一轮把"用过"算成继承来的。
                      sessionUrlsVar.set(Nil)
                      refreshSessionUrls()
                      session.id
                    }
                  }
              } else {
                Future.successful(currentSessionIdVar.now())
              }
            sessionFuture
              .flatMap { sessionId =>
                chatRunningVar.set(true)
                // 先按清除/Undo 的强制状态算出本轮设置，再作废 redo；否则 clear 的 new 会被提前消费掉。
                val sendSettings                = sendSettingsFor(chosenProvider)
                // 重新发送新消息后，之前 undo 掉的内容不再可 redo。
                clearRedoStack(sessionId)
                val onEvent: TabAiEvent => Unit = {
                  case TabAiEvent.StepStarted                =>
                    ()
                  case TabAiEvent.StepFinished(_)            =>
                    ()
                  case TabAiEvent.InternalRecoveryStarted(_) =>
                    updateChatMessage(generationId + "-assistant")(message =>
                      message.copy(content = "", thinking = "", toolCalls = "", status = "running")
                    )
                  case TabAiEvent.Thinking(delta)            =>
                    updateChatMessage(generationId + "-assistant")(message =>
                      message.copy(thinking = message.thinking + delta)
                    )
                  case TabAiEvent.Content(delta)             =>
                    updateChatMessage(generationId + "-assistant")(message =>
                      message.copy(content = message.content + delta)
                    )
                  case TabAiEvent.ContentReset               =>
                    updateChatMessage(generationId + "-assistant")(message => message.copy(content = ""))
                  case TabAiEvent.ToolCall(name, _)          =>
                    val call = js.JSON.stringify(JsBridge.obj("name" -> (name), "arguments" -> ("")))
                    updateChatMessage(generationId + "-assistant")(message =>
                      message.copy(
                        toolCalls = if (message.toolCalls.isEmpty) call else message.toolCalls + "," + call
                      )
                    )
                  case TabAiEvent.ToolFinished(_, _, _, _)   =>
                    ()
                  case TabAiEvent.StepFailed(_)              =>
                    ()
                  case TabAiEvent.TurnFailed(_, _, _, _, _)  =>
                    ()
                  case TabAiEvent.HumanInput(_, _)           =>
                    ()
                  case TabAiEvent.PlanProposed(planId, plan) =>
                    presentAgentPlan(planId, plan)
                  case TabAiEvent.Notice(text)               =>
                    Toast.info(text)
                  case TabAiEvent.ProcessUpdated(stepsJson)  =>
                    updateChatMessage(generationId + "-assistant")(message => message.copy(steps = stepsJson))
                }
                // 这一次发送的设置 = 行内参数 > 固定值 > 会话推导默认（与 `@` 面板显示同源）；用掉就作废这份一次性覆盖。
                consumePendingSend(chosenProvider)
                consumeUndoNew(sessionId)
                val recentMemoryContext         =
                  visibleMessages().takeRight(4).map(message => s"${message.role}: ${message.content.take(1200)}")
                    .mkString("\n")
                val memoryContextF              =
                  activeMemoryClient
                    .map(_.recall(sessionId, task, recentMemoryContext))
                    .getOrElse(Future.successful(com.just3bowls.api.memory.MemoryContext.Empty))
                val runF                        =
                  memoryContextF.flatMap(memory =>
                    ChatRunner.runChat(
                      sessionId,
                      task,
                      chosenProvider,
                      parentId = parentId,
                      onEvent = onEvent,
                      registerCancel = cancel => abortCurrentChat = Some(cancel),
                      mode = mode,
                      maxSteps = maxSteps,
                      useBrowserTools = runBrowserTools,
                      contextMode = ChatSendPolicy.wireThread(sendSettings.thread),
                      promptMode = sendSettings.prompt,
                      // 屏幕上的两条占位就是落库时的两条消息（同 id）：undo 栈因此不会混进"还没落库的占位"。
                      userId = Some(generationId),
                      assistantId = Some(generationId + "-assistant"),
                      tabRefs = referencedTabs,
                      memoryContext = memory.text,
                      agentMode = runMode,
                      requestedMode = requestedAgentMode,
                      modeReason = decision.reason
                    )
                  )
                runF.map { assistant =>
                  pendingAgentPlanVar.set(None)
                  abortCurrentChat = None
                  chatRunningVar.set(false)
                  refreshSessionMessages(sessionId, preferredLeaf = Some(assistant.id))
                  activeMemoryClient.foreach(
                    _.ingest(
                      MemoryTurn(
                        turnId = assistant.id,
                        sessionId = sessionId,
                        userMessageId = generationId,
                        assistantMessageId = assistant.id,
                        attemptId = assistant.activeAttemptIdOrLegacy,
                        provider = chosenProvider,
                        mode = mode,
                        origin = "plugin",
                        userText = task,
                        assistantText = assistant.content,
                        untrustedEvidence = runBrowserTools,
                        completedAt = js.Date.now().toLong
                      )
                    )
                  )
                  ()
                }.recover { case e =>
                  pendingAgentPlanVar.set(None)
                  abortCurrentChat = None
                  chatRunningVar.set(false)
                  updateChatMessage(generationId + "-assistant")(assistant =>
                    assistant.copy(
                      status = "error",
                      content = if (assistant.content.nonEmpty) assistant.content else e.getMessage
                    )
                  )
                  // 失败时落库侧会写它自己的 user / error assistant（id 与这里的占位不同）：撤掉这一轮的占位，
                  // 叶子回落到存储里的 error assistant；否则叶子会停在"用户占位"上，向上回溯的渲染把回答气泡整条截掉。
                  refreshSessionMessages(
                    sessionId,
                    preferredLeaf = Some(generationId + "-assistant"),
                    dropPendingIds = List(generationId, generationId + "-assistant")
                  )
                  // marker/协议类失败：预算内自动再重试一次（换新对话 + 完整模版）；其余失败留给气泡上的 Retry。
                  maybeAutoRetry(sessionId, generationId + "-assistant", e)
                }
              }
              .recover { case e =>
                pendingAgentPlanVar.set(None)
                abortCurrentChat = None
                chatRunningVar.set(false)
                updateChatMessage(generationId)(_.copy(status = "error"))
                updateChatMessage(generationId + "-assistant")(_.copy(status = "error", content = e.getMessage))
                if (currentSessionIdVar.now().nonEmpty) {
                  // 这条路径连落库都没走到：保留占位并让叶子指向助手占位，至少把失败显示出来。
                  refreshSessionMessages(
                    currentSessionIdVar.now(),
                    preferredLeaf = Some(generationId + "-assistant")
                  )
                }
              }
              .foreach(_ => ())
            taskVar.set("")
            js.timers.setTimeout(0)(resetTaskInputHeight())
          } else if (task.trim.nonEmpty) {
            showInfo("No active provider — choose one with the provider command")
          }
        }
      }
      val providerUrl                = parserProvidersVar.now()
        .find(_.id == providerVar.now())
        .map(_.pageUrl)
        .filter(_.nonEmpty)
        .getOrElse("")
      if (providerUrl.nonEmpty) {
        ProviderHostAccess.request(providerUrl).foreach { granted =>
          if (granted) {
            runAfterPermission()
          } else {
            chatRunningVar.set(false)
            Toast.error(
              s"Host access was not granted for ${ProviderHostAccess.hostOf(providerUrl)}. " +
                "Enable it in Config → Providers and retry."
            )
            PopCanLog.warn("page", s"permission denied for ${PopCanLog.short(providerUrl)}")
          }
        }
      } else {
        runAfterPermission()
      }
    }
    loadCurrentSession()
    // 会话列表在页面加载时就拉一次：聊天区那行显示的是"当前会话名"，/sessions 与重命名后的标题也靠这份数据。
    loadChatSessions().foreach(_ => ())
    refreshParserProviders().foreach(ensureProviderSelected)
    val serverWorkerClient                           = new ServerWorkerClient(
      () => serverRunningVar.now(),
      serverRunningVar.set,
      serverStatusVar.set,
      apiKeysVar.set,
      applyProviderXPaths,
      fn => renderDisposers += fn,
      Toast.error
    )
    val memoryClient                                 = new MemoryClient(serverWorkerClient)
    val memoryLibrary                                = new MemoryLibraryPanel(
      memoryClient,
      currentSessionIdVar.signal,
      () => {
        closeSessionFolderPanel()
        sessionUrlsOpenVar.set(false)
        configDrawerOpenVar.set(false)
        if (commandStageVar.now() != ChatCommandStage.Closed) closeCommandPalette(clearInput = false)
      }
    )
    closeMemoryLibrary = () => memoryLibrary.close()
    refreshMemoryLibrary = () => memoryLibrary.externalChange()
    val sessionFolderPanel                           = new SessionFolderPanel(
      currentSessionIdVar,
      chatSessionsVar,
      chatFoldersVar,
      newSessionFolderIdVar,
      onMove = (sessionId, folderId) =>
        ChatStore.moveSessionToFolder(sessionId, folderId).flatMap(_ => loadChatSessions()),
      onMoveFolder = (folderId, parentId) =>
        ChatStore.moveSessionFolder(folderId, parentId).flatMap(_ => loadChatSessions()),
      onCreate = (parentId, name) =>
        ChatStore
          .createSessionFolder(name, parentId)
          .flatMap(folder => loadChatSessions().map(_ => folder)),
      onRename = (folderId, name) =>
        ChatStore.renameSessionFolder(folderId, name).flatMap(_ => loadChatSessions()),
      onDelete = folderId =>
        ChatStore.deleteSessionFolder(folderId).flatMap(_ => loadChatSessions()),
      onNewSession = folderId =>
        startNewChat(Some(folderId), keepSessionPanelOpen = true, reuseEmptySession = false),
      onOpenSession = sessionId => {
        openChatSession(sessionId)
        chatSessionsVar.now().find(_.id == sessionId).foreach { session =>
          val provider = preferredProviderForSession(session.id).getOrElse(session.provider)
          setChatProvider(provider, manual = false)
        }
      },
      onRenameSession = renameChatSession,
      onDeleteSession = deleteChatSession,
      onOpen = () => {
        closeMemoryLibrary()
        sessionUrlsOpenVar.set(false)
        configDrawerOpenVar.set(false)
        closeCommandsForSessionFolder()
      }
    )
    closeSessionFolderPanel = () => sessionFolderPanel.close()
    openSessionFolderPanel = () => {
      cancelCurrentSessionTitleEdit()
      sessionFolderPanel.openPanel()
    }
    def prepareConfigOpen(): Unit                    = {
      memoryLibrary.close()
      sessionFolderPanel.close()
      sessionUrlsOpenVar.set(false)
      if (commandStageVar.now() != ChatCommandStage.Closed) closeCommandPalette(clearInput = false)
    }
    def openProviderSettings(): Unit                 = {
      prepareConfigOpen()
      configSectionVar.set("providers")
      refreshParserProviders().foreach(ensureProviderSelected)
      configDrawerMountedVar.set(true)
      configDrawerOpenVar.set(true)
    }
    val currentSessionTitleEditorId                  = "popcan-current-session-title-editor"
    def currentSessionTitleNow(): String             =
      chatSessionsVar
        .now()
        .find(_.id == currentSessionIdVar.now())
        .map(_.title.trim)
        .filter(_.nonEmpty)
        .getOrElse(ChatStore.NewSessionTitle)
    def scheduleOpenSessionFolder(): Unit            = {
      clearCurrentSessionTitleClick()
      currentSessionTitleClickTimer = Some(js.timers.setTimeout(220) {
        currentSessionTitleClickTimer = None
        openSessionFolderPanel()
      })
    }
    def handleCurrentSessionTitleClick(): Unit       =
      if (!currentSessionTitleEditingVar.now()) {
        if (currentSessionTitleClickTimer.nonEmpty) clearCurrentSessionTitleClick()
        else scheduleOpenSessionFolder()
      }
    def beginCurrentSessionTitleRename(): Unit       =
      if (currentSessionIdVar.now().nonEmpty && !currentSessionTitleBusyVar.now()) {
        clearCurrentSessionTitleClick()
        sessionFolderPanel.close()
        currentSessionTitleDraftVar.set(currentSessionTitleNow())
        currentSessionTitleBusyVar.set(false)
        currentSessionTitleEditingVar.set(true)
      }
    def saveCurrentSessionTitle(): Unit              = {
      val sessionId = currentSessionIdVar.now()
      val title     = currentSessionTitleDraftVar.now().trim
      val current   = currentSessionTitleNow()
      if (sessionId.nonEmpty && !currentSessionTitleBusyVar.now()) {
        if (title.isEmpty) Toast.info("Session name cannot be empty")
        else if (title == current) cancelCurrentSessionTitleEdit()
        else {
          currentSessionTitleBusyVar.set(true)
          renameChatSession(sessionId, title)
            .map { _ =>
              Toast.success(s"Renamed to: $title")
              currentSessionTitleEditingVar.set(false)
              currentSessionTitleDraftVar.set("")
            }
            .recover { case error =>
              Toast.error(s"Rename failed: ${Option(error.getMessage).getOrElse("unknown error")}")
            }
            .foreach(_ => currentSessionTitleBusyVar.set(false))
        }
      }
    }
    renderDisposers += (() => clearCurrentSessionTitleClick())
    val currentChatSessionTitle                      = currentSessionIdVar.signal
      .combineWith(chatSessionsVar.signal)
      .map { case (id, sessions) =>
        if (id.isEmpty) "No chat yet"
        else
          sessions
            .find(_.id == id)
            .map(_.title.trim)
            .filter(_.nonEmpty)
            .getOrElse(ChatStore.NewSessionTitle)
      }
    val currentChatSessionPathTitle                  = sessionFolderPanel.pathPrefix
      .combineWith(currentChatSessionTitle, currentSessionIdVar.signal)
      .map { case (prefix, title, id) => if (id.isEmpty) title else s"$prefix$title" }
    memoryClient.installExtractionHandler(MemoryExtractionRunner.run)
    memoryClient.startExtractionLoop()
    activeMemoryClient = Some(memoryClient)
    NativeRunHost.setMemoryIngestHandler(turn => memoryClient.ingest(turn).foreach(_ => ()))
    NativeRunHost.setMemoryRecallHandler(query =>
      memoryClient.recall(query.sessionId, query.query, query.recentContext, query.budgetTokens)
    )
    renderDisposers += (() => {
      if (activeMemoryClient.contains(memoryClient)) activeMemoryClient = None
    })
    ApiKeyStore.load().foreach { keys =>
      apiKeysVar.set(keys)
      selectedApiKeyIdVar.set(keys.find(!_.revoked).map(_.id).getOrElse(""))
      serverWorkerClient.loadServerWorkerPreference()
    }
    def generateApiKey(name: String): Unit           = {
      val key = ApiKeyStore.generateKey(name)
      apiKeysVar.update(_ :+ key)
      selectedApiKeyIdVar.set(key.id)
      ApiKeyStore.save(apiKeysVar.now()).foreach(_ => ())
    }
    def renameApiKey(id: String, name: String): Unit = {
      apiKeysVar.update(ApiKeyStore.rename(_, id, name))
      ApiKeyStore.save(apiKeysVar.now()).foreach(_ => ())
    }
    def revokeApiKey(id: String): Unit               =
      if (id != ApiKeyStore.SystemApiKeyId) {
        ChromeAPI.runtime.sendMessage(
          JsBridge.obj("type" -> ("RELEASE_KEY_TABS"), "keyId" -> (id))
        )
          .toFuture
          .recover { case _ => JsBridge.obj() }
          .foreach { _ =>
            apiKeysVar.update(_.filterNot(_.id == id))
            ApiKeyStore.save(apiKeysVar.now()).foreach { _ =>
              if (selectedApiKeyIdVar.now() == id) selectedApiKeyIdVar.set("")
              refreshSessionUrls()
            }
          }
      }
    def copyApiKey(id: String): Future[Unit]         =
      apiKeysVar.now().find(_.id == id) match {
        case None      => Future.failed(new RuntimeException("API key not found"))
        case Some(key) =>
          val clipboard = JsBridge.navigator.clipboard
          if (js.isUndefined(clipboard) || clipboard == null)
            Future.failed(new RuntimeException("clipboard unavailable"))
          else JsBridge.asPromiseAny(clipboard.writeText(key.secret)).toFuture.map(_ => ())
      }

    /** `Config → API keys` 每个 key 的 Routing：每条候选 `provider + weight + promptMode`，外加策略 / failover。 */
    def setApiKeyRouting(id: String, routes: List[ApiKeyRoute], strategy: String, failover: String): Unit = {
      apiKeysVar.update(ApiKeyStore.setRouting(_, id, routes, strategy, failover))
      ApiKeyStore.save(apiKeysVar.now()).foreach(_ => ())
    }
    val configPanelContext = new ConfigPanelContext(
      serverStatusVar,
      configSectionVar,
      serverWorkerClient,
      memoryClient,
      apiKeysVar,
      parserProvidersVar,
      () => {
        refreshParserProviders().foreach(ensureProviderSelected)
        ()
      },
      generateApiKey,
      renameApiKey,
      revokeApiKey,
      copyApiKey,
      setApiKeyRouting
    )

    /**
     * 「回到最新」：聊天区（整页）离底部超过一屏的 120px 时，在输入框右上角出现一个一键到底的按钮。
     *
     * 只有真的滚上去了才显示 —— 贴在底部时它没有意义，常驻反而挡视线。
     */
    val atBottomVar                                   = Var(true)
    val onWindowScroll: js.Function1[dom.Event, Unit] = (_: dom.Event) => {
      val doc = dom.document.documentElement
      val gap = doc.scrollHeight - doc.scrollTop - doc.clientHeight
      atBottomVar.set(gap <= 120)
    }
    dom.window.addEventListener("scroll", onWindowScroll)
    renderDisposers += (() => dom.window.removeEventListener("scroll", onWindowScroll))
    def scrollToLatest(): Unit                        = {
      atBottomVar.set(true)
      JsBridge.window
        .scrollTo(
          JsBridge.obj("top" -> (dom.document.documentElement.scrollHeight), "behavior" -> ("smooth")).asInstanceOf[
            js.Object
          ]
        )
      ()
    }
    val chatRunActive                                 = chatRunningVar.signal
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      div(
        cls        := "popcan-container",
        padding    := "0 24px",
        maxWidth   := "800px",
        margin     := "0 auto",
        fontFamily := "system-ui, sans-serif",
        minHeight  := "100vh",
        display.flex,
        flexDirection.column,
        boxSizing  := "border-box",
        // Main display
        div(
          flexGrow        := "1",
          marginTop       := "0",
          width           := "100%",
          minWidth        := "0",
          overflowX       := "hidden",
          paddingBottom   := "180px",
          ChatPanel.render(
            chatMessagesVar,
            regenerableMessageIdVar,
            forkAt,
            undoAt,
            regenerate,
            chatRunActive,
            abortRunningChat,
            activateReferencedTab
          ),
          new CommentNode("")
        ),
        div(
          position        := "fixed",
          top             := "12px",
          right           := "12px",
          zIndex          := 60,
          LocalApiStatusIndicator.render(
            statusSignal = serverStatusVar.signal,
            activeSignal = serverRunningVar.signal
              .combineWith(serverStatusVar.signal)
              .map { case (running, status) => LocalApiStatus.isActive(status, running) },
            onToggle = () => {
              if (serverWorkerClient.isServerActive(serverStatusVar.now())) serverWorkerClient.stopServerWorker()
              else serverWorkerClient.startServerWorker()
            }
          )
        ),
        // Sticky bottom controls: provider + tab list + task input
        div(
          cls             := "popcan-composer",
          position        := "sticky",
          bottom          := "0",
          zIndex          := "50",
          backgroundColor := "#ffffff",
          borderTop       := "1px solid #e5e7eb",
          boxShadow       := "0 -4px 12px rgba(0,0,0,0.04)",
          padding         := "8px 10px 12px",
          // 输入框右上角的一键到底（只在滚上去时出现）
          button(
            "↓",
            position        := "absolute",
            right           := "10px",
            top             := "-32px",
            width           := "28px",
            height          := "28px",
            alignItems      := "center",
            justifyContent  := "center",
            fontSize        := "13px",
            lineHeight      := "1",
            color           := "#374151",
            backgroundColor := "#ffffff",
            border          := "1px solid #d1d5db",
            borderRadius    := "999px",
            boxShadow       := "0 2px 6px rgba(0,0,0,0.08)",
            cursor.pointer,
            title           := "Jump to the latest message",
            display <-- atBottomVar.signal.map(atBottom => if (atBottom) "none" else "flex"),
            onClick --> { _ => scrollToLatest() }
          ),
          // Task input
          div(
            position        := "relative",
            display.flex,
            flexDirection.column,
            backgroundColor := "#ffffff",
            border          := "1px solid #d1d5db",
            borderRadius    := "8px",
            boxShadow       := "0 -4px 12px rgba(0,0,0,0.04)",
            // Session URL 列表从 composer 顶栏向上浮出，不参与输入框布局。
            div(
              position        := "absolute",
              left            := "0",
              right           := "0",
              bottom          := "100%",
              marginBottom    := "6px",
              maxHeight       := "240px",
              overflowY.auto,
              border          := "1px solid #d1d5db",
              borderRadius    := "8px",
              backgroundColor := "#ffffff",
              boxShadow       := "0 8px 24px rgba(0,0,0,0.12)",
              zIndex          := 180,
              display <-- sessionUrlsOpenVar.signal
                .combineWith(currentSessionIdVar.signal)
                .map { case (open, sessionId) => if (open && sessionId.nonEmpty) "block" else "none" },
              children <-- sessionUrlsVar.signal.map { urls =>
                if (urls.isEmpty)
                  List(div(
                    padding  := "8px 10px",
                    fontSize := "11px",
                    color    := "#9ca3af",
                    "No provider URLs for this session yet"
                  ))
                else
                  urls.map { info =>
                    div(
                      display.flex,
                      alignItems.center,
                      gap          := "6px",
                      padding      := "5px 8px",
                      fontSize     := "11px",
                      borderBottom := "1px solid #f1f5f9",
                      title        := info.url,
                      div(
                        flexGrow        := "1",
                        minWidth        := "0",
                        cursor.pointer,
                        onClick --> { _ =>
                          if (info.tabId > 0) activateSessionTab(info.tabId)
                          else focusSessionUrl(info.url)
                        },
                        span(
                          info.url,
                          display.flex,
                          color := "#374151",
                          overflow.hidden,
                          textOverflow.ellipsis,
                          whiteSpace.nowrap
                        )
                      ),
                      button(
                        "×",
                        fontSize        := "11px",
                        lineHeight      := "1",
                        padding         := "1px 5px",
                        cursor.pointer,
                        backgroundColor := "transparent",
                        color           := "#9ca3af",
                        border          := "1px solid #e5e7eb",
                        borderRadius    := "4px",
                        title           :=
                          "Remove this URL from the session — the tab stays open; next time this provider is used a new chat will start.",
                        onClick --> { ev =>
                          ev.stopPropagation()
                          endSessionUrl(info)
                        }
                      )
                    )
                  }
              }
            ),
            // Composer 顶栏：Session 与 Agent Mode 始终在同一行。
            div(
              display.flex,
              alignItems.center,
              gap             := "8px",
              minWidth        := "0",
              padding         := "6px 10px",
              borderBottom    := "1px solid #f1f5f9",
              div(
                display.flex,
                alignItems.center,
                gap        := "6px",
                minWidth   := "0",
                flexGrow   := "1",
                flexShrink := "1",
                div(
                  display.flex,
                  alignItems.center,
                  gap        := "4px",
                  minWidth   := "0",
                  flexGrow   := "1",
                  flexShrink := "1",
                  div(
                    display.contents,
                    child <-- currentSessionTitleEditingVar.signal.map {
                      case false =>
                        button(
                          idAttr          := sessionFolderPanel.TriggerId,
                          `type`          := "button",
                          display.flex,
                          alignItems.center,
                          gap             := "5px",
                          minWidth        := "0",
                          flexShrink      := "1",
                          overflow.hidden,
                          padding         := "0",
                          backgroundColor := "transparent",
                          border          := "none",
                          cursor.pointer,
                          aria.expanded <-- sessionFolderPanel.isOpen,
                          aria.controls   := sessionFolderPanel.PanelId,
                          title <-- currentChatSessionPathTitle.map(path =>
                            s"$path · click to browse · double-click to rename"
                          ),
                          span(
                            flexShrink := "1",
                            minWidth   := "0",
                            overflow.hidden,
                            textOverflow.ellipsis,
                            whiteSpace.nowrap,
                            color      := "#374151",
                            fontWeight.bold,
                            child.text <-- currentChatSessionTitle
                          ),
                          onClick --> { _ => handleCurrentSessionTitleClick() },
                          onDblClick --> { event =>
                            event.preventDefault()
                            event.stopPropagation()
                            beginCurrentSessionTitleRename()
                          }
                        )
                      case true  =>
                        div(
                          idAttr   := currentSessionTitleEditorId,
                          display.flex,
                          alignItems.center,
                          gap      := "4px",
                          minWidth := "0",
                          maxWidth := "100%",
                          onClick --> { event => event.stopPropagation() },
                          onDblClick --> { event =>
                            event.preventDefault()
                            event.stopPropagation()
                          },
                          documentEvents(_.onMouseDown).filter { event =>
                            currentSessionTitleEditingVar.now() &&
                            !currentSessionTitleBusyVar.now() && {
                              val editor = dom.document.getElementById(currentSessionTitleEditorId)
                              val target = event.target.asInstanceOf[dom.Node]
                              editor == null || !editor.contains(target)
                            }
                          } --> { _ => cancelCurrentSessionTitleEdit() },
                          input(
                            `type`       := "text",
                            maxLength    := 120,
                            onMountFocus,
                            onMountCallback { context =>
                              val input = context.thisNode.ref
                              js.timers.setTimeout(0) {
                                input.focus()
                                input.select()
                              }
                            },
                            minWidth     := "0",
                            width        := "120px",
                            maxWidth     := "120px",
                            padding      := "2px 6px",
                            fontSize     := "12px",
                            border       := "1px solid #93c5fd",
                            borderRadius := "4px",
                            outline      := "none",
                            disabled <-- currentSessionTitleBusyVar.signal,
                            controlled(
                              value <-- currentSessionTitleDraftVar,
                              onInput.mapToValue --> currentSessionTitleDraftVar.set
                            ),
                            onKeyDown --> { event =>
                              event.stopPropagation()
                              event.key match {
                                case "Enter"  =>
                                  event.preventDefault()
                                  saveCurrentSessionTitle()
                                case "Escape" =>
                                  event.preventDefault()
                                  cancelCurrentSessionTitleEdit()
                                case _        => ()
                              }
                            }
                          ),
                          NeutralIconButton.render(
                            "✓",
                            "Save session name",
                            saveCurrentSessionTitle,
                            disabledSignal = currentSessionTitleBusyVar.signal
                          ),
                          NeutralIconButton.render(
                            "×",
                            "Cancel session rename",
                            cancelCurrentSessionTitleEdit,
                            disabledSignal = currentSessionTitleBusyVar.signal
                          )
                        )
                    }
                  ),
                  button(
                    display <-- currentSessionIdVar.signal.map(id => if (id.nonEmpty) "inline-flex" else "none"),
                    alignItems.center,
                    justifyContent.center,
                    flexShrink   := "0",
                    width        := "22px",
                    height       := "20px",
                    padding      := "0",
                    fontSize     := "12px",
                    lineHeight   := "1",
                    cursor.pointer,
                    backgroundColor <-- sessionUrlsOpenVar.signal.map(open =>
                      if (open) "#eff6ff" else "transparent"
                    ),
                    color        := "#6b7280",
                    border       := "none",
                    borderRadius := "4px",
                    "🔗",
                    aria.expanded <-- sessionUrlsOpenVar.signal,
                    title <-- sessionUrlsOpenVar.signal
                      .combineWith(sessionUrlsVar.signal)
                      .map { case (open, urls) =>
                        val action = if (open) "Hide" else "Show"
                        s"$action provider URLs for this session (${urls.size})"
                      },
                    onClick --> { _ =>
                      cancelCurrentSessionTitleEdit()
                      memoryLibrary.close()
                      sessionFolderPanel.close()
                      sessionUrlsOpenVar.update(!_)
                    }
                  )
                )
              ),
              ConfigDrawer.renderTrigger(
                configDrawerOpenVar,
                configDrawerMountedVar,
                onBeforeOpen = prepareConfigOpen
              )
            ),
            // 文本输入区
            div(
              display.flex,
              alignItems.flexEnd,
              gap             := "8px",
              padding         := "10px",
              div(
                position := "relative",
                flexGrow := "1",
                minWidth := "0",
                child <-- pendingAgentPlanVar.signal.map {
                  case None           => emptyNode
                  case Some(proposal) =>
                    div(
                      marginBottom := "8px",
                      PlanCard.render(
                        title = "Plan",
                        status = "awaiting-plan",
                        summary = "",
                        body = List(
                          pre(
                            proposal.plan,
                            margin          := "0 0 8px",
                            padding         := "8px",
                            maxHeight       := "240px",
                            overflow        := "auto",
                            whiteSpace      := "pre-wrap",
                            fontFamily      := "ui-monospace, SFMono-Regular, Menlo, monospace",
                            fontSize        := "10px",
                            backgroundColor := "#ffffff",
                            border          := "1px solid #e2e8f0",
                            borderRadius    := "4px"
                          )
                        ),
                        actions = List(
                          PlanCard.Action(
                            "Cancel",
                            () => {
                              resolvePendingAgentPlan(proposal, "cancel")
                            },
                            danger = true
                          ),
                          PlanCard.Action(
                            "Execute",
                            () => {
                              resolvePendingAgentPlan(proposal, "execute")
                            },
                            primary = true
                          )
                        )
                      )
                    )
                },
                div(
                  display <-- pendingTabRefsVar.signal.map(refs => if (refs.isEmpty) "none" else "flex"),
                  flexWrap        := "wrap",
                  gap             := "4px",
                  padding         := "0 0 6px",
                  children <-- pendingTabRefsVar.signal.map(_.map { tab =>
                    div(
                      display         := "inline-flex",
                      alignItems.center,
                      gap             := "4px",
                      maxWidth        := "100%",
                      padding         := "2px 6px",
                      backgroundColor := "#eff6ff",
                      color           := "#1d4ed8",
                      border          := "1px solid #bfdbfe",
                      borderRadius    := "999px",
                      fontSize        := "10px",
                      title           := tab.url,
                      span(
                        overflow.hidden,
                        textOverflow.ellipsis,
                        whiteSpace.nowrap,
                        s"${tab.ref} · ${tab.title}"
                      ),
                      button(
                        "×",
                        padding         := "0",
                        cursor.pointer,
                        backgroundColor := "transparent",
                        color           := "#1d4ed8",
                        border          := "none",
                        fontSize        := "11px",
                        title           := s"Remove ${tab.ref} reference",
                        onClick --> { _ =>
                          pendingTabRefsVar.update(_.filterNot(_.tabId == tab.tabId))
                          tabCandidatesVar.update(_.map(row =>
                            if (row.tabId == tab.tabId) row.copy(ref = "") else row
                          ))
                          if (commandStageVar.now() == ChatCommandStage.Tabs)
                            refreshCommandItems(ChatCommandStage.Tabs, tabsStageQuery())
                        }
                      )
                    )
                  })
                ),
                textArea(
                  idAttr          := taskInputId,
                  placeholder <-- Signal
                    .combine(
                      pendingAgentPlanVar.signal,
                      commandStageVar.signal,
                      showUndoHintVar.signal
                    )
                    .map {
                      case (Some(_), _, _)                        =>
                        "Describe changes to the plan, then press Enter to replan"
                      case (_, ChatCommandStage.Agents, _)        =>
                        "Choose ReAct or Plan · Enter selects"
                      case (_, ChatCommandStage.AtRoot, _)        =>
                        "Choose @tabs"
                      case (_, ChatCommandStage.Providers, _)     =>
                        "Choose a provider, then thread / prompt settings"
                      case (_, ChatCommandStage.Tabs, _)          =>
                        "Enter toggles tabs · Esc finishes selection"
                      case (_, ChatCommandStage.RememberScope, _) =>
                        "Choose where to save this memory"
                      case (_, ChatCommandStage.Commands, _)      =>
                        "Filter commands · Enter selects"
                      case (_, _, true)                           =>
                        "Undone — type /redo to restore"
                      case _                                      =>
                        "Enter your task, or type / or @"
                    },
                  width           := "100%",
                  minHeight       := "36px",
                  maxHeight       := "120px",
                  padding         := "6px 8px",
                  boxSizing.borderBox,
                  fontSize        := "13px",
                  lineHeight      := "20px",
                  border          := "none",
                  outline         := "none",
                  resize          := "none",
                  overflowY.auto,
                  controlled(
                    value <-- taskVar,
                    onInput --> { ev =>
                      val ta = ev.target.asInstanceOf[dom.HTMLTextAreaElement]
                      taskVar.set(ta.value)
                      ta.style.height = "auto"
                      ta.style.height = s"${ta.scrollHeight}px"
                      if (pendingAgentPlanVar.now().isEmpty) refreshCommandPalette()
                    }
                  ),
                  onKeyDown --> { e =>
                    val composing = BrowserEvents.isComposing(e)
                    if (!composing && pendingAgentPlanVar.now().isDefined) {
                      e.key match {
                        case "Enter" if !e.shiftKey =>
                          e.preventDefault()
                          submitPendingPlanModification()
                        case "Escape"               =>
                          e.preventDefault()
                          taskVar.set("")
                          resetTaskInputHeight()
                        case _                      =>
                          ()
                      }
                    } else if (!composing && rememberDraftScopeVar.now().isDefined && e.key == "Escape") {
                      e.preventDefault()
                      rememberDraftScopeVar.set(None)
                      clearNotice()
                      taskVar.set("")
                      resetTaskInputHeight()
                    } else if (!composing && commandStageVar.now() != ChatCommandStage.Closed) {
                      e.key match {
                        case "ArrowDown"            =>
                          e.preventDefault()
                          moveCommandSelection(1)
                        case "ArrowUp"              =>
                          e.preventDefault()
                          moveCommandSelection(-1)
                        case "Tab"                  =>
                          e.preventDefault()
                          commandItemsVar.now().lift(commandIndexVar.now()).foreach { item =>
                            if (item.id == "command:remember") {
                              beginRememberScope()
                            } else if (item.id.startsWith("command:")) {
                              val name = item.label.takeWhile(_ != ' ')
                              taskVar.set(s"$name ")
                              refreshCommandPalette()
                            }
                          }
                        case "Escape"               =>
                          e.preventDefault()
                          escapeCommandPalette()
                        case "Enter" if !e.shiftKey =>
                          e.preventDefault()
                          handleCommandEnter()
                        case _                      =>
                          ()
                      }
                    } else if (
                      e.key == "Tab" &&
                      !e.shiftKey &&
                      !e.ctrlKey &&
                      !e.altKey &&
                      !e.metaKey &&
                      rememberDraftScopeVar.now().isEmpty
                    ) {
                      e.preventDefault()
                      cycleAgentMode()
                    } else if (e.key == "Enter" && !e.shiftKey) {
                      e.preventDefault()
                      // 生成中不提交新问题，停止统一由输入框左下角的 Stop 控制。
                      if (!chatRunningVar.now()) submitTask()
                    }
                  }
                ),
                div(
                  position        := "absolute",
                  left            := "0",
                  right           := "0",
                  bottom          := "100%",
                  marginBottom    := "6px",
                  backgroundColor := "#ffffff",
                  border          := "1px solid #d1d5db",
                  borderRadius    := "8px",
                  boxShadow       := "0 8px 24px rgba(0,0,0,0.12)",
                  overflow        := "hidden",
                  maxHeight       := "280px",
                  overflowY       := "auto",
                  zIndex          := 200,
                  display <-- commandStageVar.signal.map(stage =>
                    if (stage == ChatCommandStage.Closed) "none" else "block"
                  ),
                  children <-- commandItemsVar.signal.combineWith(commandIndexVar.signal).map {
                    case (items, selectedIndex) =>
                      if (items.isEmpty) {
                        List(div(padding := "8px 10px", fontSize := "12px", color := "#6b7280", "No matches"))
                      } else {
                        items.zipWithIndex.map { case (item, index) =>
                          div(
                            // 键盘选择靠这个类定位"当前行"再滚进视口（见 scrollCommandSelectionIntoView）。
                            className := (if (index == selectedIndex) "th-cmd-row th-cmd-selected" else "th-cmd-row"),
                            display.flex,
                            alignItems.center,
                            justifyContent.spaceBetween,
                            gap       := "8px",
                            padding   := "7px 10px",
                            cursor.pointer,
                            title     := item.title,
                            backgroundColor := (if (index == selectedIndex) "#eff6ff" else "#ffffff"),
                            borderBottom    := "1px solid #f1f5f9",
                            onClick --> { _ => item.run() },
                            div(
                              display.flex,
                              alignItems.center,
                              gap      := "6px",
                              minWidth := "0",
                              span(
                                width      := "12px",
                                flexShrink := "0",
                                color      := "#2563eb",
                                fontSize   := "11px",
                                fontWeight.bold,
                                if (item.selected) "✓" else ""
                              ),
                              div(
                                minWidth   := "0",
                                div(fontSize := "12px", color := "#111827", fontWeight.bold, item.label),
                                if (item.description.nonEmpty)
                                  div(fontSize := "11px", color := "#6b7280", item.description)
                                else div()
                              )
                            )
                          )
                        }
                      }
                  }
                )
              )
            ),
            div(
              display.flex,
              alignItems.center,
              justifyContent.spaceBetween,
              gap             := "8px",
              marginTop       := "4px",
              padding         := "0 10px 6px",
              fontSize        := "11px",
              color           := "#6b7280",
              // 左槽位只放不属于 composer 的持久提示；运行状态和 Undo 已迁到输入区。
              div(
                flexGrow       := "1",
                flexShrink     := "1",
                minWidth       := "0",
                display.flex,
                alignItems.center,
                gap            := "6px",
                overflow.hidden,
                whiteSpace.nowrap,
                BrowserToolsIndicator.render(
                  enabledSignal = browserToolsVar.signal,
                  onToggle = () => applyBrowserTools(!browserToolsVar.now(), persist = true)
                ),
                memoryLibrary.renderTrigger,
                span(
                  display <-- footerNoticeVar.signal.map(notice =>
                    if (notice.text.nonEmpty) "inline" else "none"
                  ),
                  color      := "#d1d5db",
                  fontSize   := "10px",
                  lineHeight := "1",
                  flexShrink := "0",
                  "|"
                ),
                span(
                  minWidth   := "0",
                  overflow.hidden,
                  textOverflow.ellipsis,
                  whiteSpace.nowrap,
                  color <-- footerNoticeVar.signal.map(notice =>
                    if (notice.kind == "error") "#dc2626" else "#6b7280"
                  ),
                  title <-- footerNoticeVar.signal.map(_.text),
                  child.text <-- footerNoticeVar.signal.map(_.text)
                )
              ),
              div(
                flexGrow       := "0",
                flexShrink     := "0",
                display.flex,
                alignItems     := "center",
                justifyContent := "flex-end",
                gap            := "8px",
                // 有 provider 时使用状态圆点；空状态使用无圆点的蓝色配置动作。
                providerSetupMarker(
                  onClickAction = openProviderSettings,
                  visible = providerVar.signal.map(_.isEmpty)
                ),
                providerSettingsMarker(
                  providerText = Signal
                    .combine(parserProvidersVar.signal, providerVar.signal)
                    .map { case (providers, provider) =>
                      providers
                        .find(_.id == provider)
                        .map(_.name)
                        .getOrElse(if (provider.isEmpty) "no provider" else provider)
                    },
                  providerNormal = Signal
                    .combine(sessionUrlsVar.signal, providerVar.signal)
                    .map { case (urls, provider) => provider.nonEmpty && !urls.exists(_.provider == provider) },
                  providerTooltip = Signal
                    .combine(parserProvidersVar.signal, providerVar.signal, sessionUrlsVar.signal)
                    .map { case (providers, provider, urls) =>
                      val active = providers.filter(_.status == "active").map(_.id)
                      val next   = ProviderCycle.next(active, provider).filter(_ != provider)
                      val here   =
                        if (provider.nonEmpty && urls.exists(_.provider == provider))
                          "This chat already has a conversation here — the next send continues it."
                        else
                          "This chat has not used it yet — the next send starts a new chat."
                      s"$provider — click for the next enabled provider${next.map(id => s" ($id)").getOrElse("")}.\n$here\n" +
                        s"Order: ${active.mkString(" → ")}"
                    },
                  onProviderSwitch = () => cycleProvider(),
                  thread = MarkerPart(
                    text = currentSendSettingsSignal.map(_.map(_.thread).getOrElse("—")),
                    normal = Signal.combine(currentSendSettingsSignal, currentBaselineSettingsSignal).map {
                      case (value, base) => value.forall(_.thread == base.thread)
                    },
                    tooltip = Signal.combine(currentSendSettingsSignal, undoForcesNewCurrentSignal).map {
                      case (current, undoNew) =>
                        current
                          .map { settings =>
                            val next     = if (settings.thread == ProviderChatArgs.ThreadNew) {
                              ProviderChatArgs.ThreadContinue
                            } else ProviderChatArgs.ThreadNew
                            val undoNote =
                              if (undoNew)
                                "\nUndo: this session's next Chat send starts a new provider conversation, then returns to the normal default."
                              else ""
                            s"Thread: ${settings.thread}. Click for $next on the next send only. " +
                              s"Usage: /provider <provider> new|continue.$undoNote"
                          }
                          .getOrElse("Choose a provider first.")
                    },
                    onSwitch = () =>
                      updateCurrentPending(settings =>
                        settings.copy(
                          thread =
                            if (settings.thread == ProviderChatArgs.ThreadNew) ProviderChatArgs.ThreadContinue
                            else ProviderChatArgs.ThreadNew
                        )
                      )
                  ),
                  prompt = MarkerPart(
                    text = currentSendSettingsSignal.map(_.map(_.prompt).getOrElse("—")),
                    normal = Signal.combine(currentSendSettingsSignal, currentBaselineSettingsSignal).map {
                      case (value, base) => value.forall(_.prompt == base.prompt)
                    },
                    tooltip = currentSendSettingsSignal.map { current =>
                      current
                        .map { settings =>
                          val next =
                            if (settings.prompt == ProviderChatArgs.PromptFile) ProviderChatArgs.PromptInline
                            else ProviderChatArgs.PromptFile
                          s"Prompt: ${settings.prompt}. Click for $next on the next send only. " +
                            "Usage: /provider <provider> inline|file."
                        }
                        .getOrElse("Choose a provider first.")
                    },
                    onSwitch = () =>
                      updateCurrentPending(settings =>
                        settings.copy(
                          prompt =
                            if (settings.prompt == ProviderChatArgs.PromptFile) ProviderChatArgs.PromptInline
                            else ProviderChatArgs.PromptFile
                        )
                      )
                  ),
                  clickable = chatSettingsVisible,
                  visible = chatSettingsVisible
                )
              )
            ),
            memoryLibrary.renderPanel,
            sessionFolderPanel.renderPanel
          )
        ),
        Toast.renderLayer(),
        ConfigDrawer.renderOverlay(configPanelContext, configDrawerOpenVar, configDrawerMountedVar)
      )
    )
  }
}
