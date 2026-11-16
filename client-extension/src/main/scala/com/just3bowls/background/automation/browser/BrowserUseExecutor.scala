package com.just3bowls.background.automation.browser

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.JsObject
import com.just3bowls.background.runtime.*
import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.automation.provider.*

import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.{
  AxNode,
  AxProperty,
  AxTreeResult,
  BrowserPageInfo,
  BrowserScrollResult,
  CdpCommandResults,
  ChromeAPI,
  DebuggerSession,
  DomBoxModelResult,
  DomResolveNodeResult,
  ElementBox,
  RuntimeCallFunctionOnResult,
  Tab,
  TabChangeInfo
}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.{Failure, Success}

/**
 * Chat agent 的通用浏览器操作执行器。
 *
 * 每个 chat session 一个常驻 tab（放在插件页所在窗口、`active = false`，不抢用户焦点），tab 统一归入 `tools` tab group （见 `ToolTabGroup`）；v1
 * 不做自动清理，tab 由用户手动关闭。 观察用浏览器自己的 a11y 树（CDP `Accessibility`），动作复用 CDP `Input` 派发，不做截图与视觉。 状态落
 * `chrome.storage.session`：Service Worker 重启不丢，浏览器重启即清；内存里再缓存一份加速。
 */
private[background] object BrowserUseExecutor {

  /** AX 树转 RawNode 的无损采集上限（只防爆页面）；快照与抽取共用同一预算。 */
  private val MaxRawNodes = 10000

  /** 快照预算：打印全部可交互节点 + 标题 + 地标，超出则截断并显式说明。 */
  private val MaxSnapshotNodes = 200
  private val MaxSnapshotChars = 20000
  private val MaxSnapshotUrl   = 400

  /** browser_extract(urls=[...]) 的并行读预算：条数 / 单页 / 总量 / 并发 / 单页加载超时。 */
  private val MaxReadUrls         = 3
  private val MaxReadCharsPerPage = 10000
  private val MaxReadCharsTotal   = 32000
  private val ReadConcurrency     = 3
  private val ReadTabTimeoutMs    = 15000

  /** 快照里保留的地标行：给模型提供"这块是内容/导航/表单"的上下文。 */
  private val LandmarkRoles = Set("main", "navigation", "form", "dialog", "alertdialog", "alert")
  private val SnapshotHint  =
    "[page body text is omitted; call browser_extract for the readable text]\n"

  /**
   * 动作类工具（navigate / click / scroll / type）的默认回执。
   *
   * 动作只报"做了什么"，观察交给显式的 `browser_snapshot` / `browser_extract`；需要动作连带快照时传 `snapshot=true`（与 Chrome DevTools MCP 的
   * includeSnapshot 同形，只是默认关闭）。
   */
  private val ActionHint =
    "[call browser_snapshot for the interactive list (refs), browser_extract for the page text; " +
      "pass snapshot=true to include the list with this result]\n"

  private val ToolTimeoutMs = 60000

  /** 结构/文档容器：即使带 focusable 也不算可操作节点，不给 ref 也不标状态。 */
  private val NonInteractiveRoles = Set(
    "RootWebArea",
    "WebArea",
    "document",
    "banner",
    "main",
    "navigation",
    "form",
    "region",
    "article",
    "complementary",
    "contentinfo",
    "search",
    "list",
    "table",
    "row",
    "cell",
    "toolbar",
    "menubar",
    "tablist",
    "menu",
    "tree",
    "grid",
    "group",
    "status",
    "alert",
    "log",
    "dialog",
    "alertdialog",
    // 文本叶子即使继承 editable（contenteditable 内部）也不该拿到 ref。
    "StaticText",
    "InlineTextBox",
    "LineBreak",
    "ListMarker"
  )

  /** 无 action 属性也能点击/输入的 role。 */
  private val InteractiveRoles   = Set(
    "link",
    "button",
    "checkbox",
    "radio",
    "switch",
    "textbox",
    "searchbox",
    "combobox",
    "listbox",
    "option",
    "menuitem",
    "menuitemcheckbox",
    "menuitemradio",
    "tab",
    "slider",
    "spinbutton",
    "treeitem",
    "disclosureTriangle",
    "PopUpButton",
    "TextField"
  )
  private val CheckableRoles     = Set("checkbox", "radio", "switch", "menuitemcheckbox", "menuitemradio")
  private val MaxRefsPerDocument = 4000
  private val sessionStore       = new BrowserUseSessionStore
  private val extractor          =
    new BrowserExtractor((target, expression) => evaluate(target, expression), fullAxTree, collectRawNodes)

  /** 同一 session 的工具调用串行化，避免两次调用同时创建 tab / 覆盖 ref 映射。 */
  private val sessionLocks = mutable.Map.empty[String, Promise[Unit]]

  /** 每个会话最近一次的搜索词：只给 browser_extract 的区域兜底当目标词用。 */
  private val lastQueries = mutable.Map.empty[String, String]
  // ---------------------------------------------------------------- 入口
  def invoke(sessionId: String, sessionTitle: String, name: String, args: js.Any): Future[JsObject] =
    if (sessionId.trim.isEmpty) {
      Future.failed(new Exception(s"$name needs a browser session id"))
    } else {
      val params = JsBridge.asDictionary(args)
      withSessionLock(sessionId) {
        withTimeout(name, ToolTimeoutMs) {
          val prepared =
            if (name == "browser_use_tab") ensureSessionRecord(sessionId, sessionTitle)
            else ensureSession(sessionId, sessionTitle)
          prepared.flatMap { handle =>
            val action =
              name match {
                case "browser_search"   =>
                  runSearch(handle, stringArg(params, "query").getOrElse(""))
                case "browser_navigate" =>
                  runNavigate(
                    handle,
                    stringArg(params, "url").getOrElse(""),
                    boolArg(params, "new_tab"),
                    boolArg(params, "snapshot")
                  )
                case "browser_snapshot" =>
                  runSnapshot(handle)
                case "browser_click"    =>
                  runClick(handle, stringArg(params, "ref").getOrElse(""), boolArg(params, "snapshot"))
                case "browser_type"     =>
                  runType(
                    handle,
                    stringArg(params, "ref").getOrElse(""),
                    stringArg(params, "text").getOrElse(""),
                    boolArg(params, "submit"),
                    boolArg(params, "snapshot")
                  )
                case "browser_scroll"   =>
                  runScroll(
                    handle,
                    stringArg(params, "direction").getOrElse(""),
                    numberArg(params, "amount"),
                    boolArg(params, "snapshot")
                  )
                case "browser_extract"  =>
                  runExtract(handle, stringListArg(params, "urls"))
                case "browser_use_tab"  =>
                  runUseReferencedTab(handle, stringArg(params, "ref").getOrElse(""))
                case other              =>
                  Future.failed(new Exception(s"Unknown browser tool: $other"))
              }
            action.transformWith {
              case Success(value) =>
                workspaceState(handle).map(state => JsBridge.asObject(s"$state\n\n${resultText(value)}"))
              case Failure(error) =>
                workspaceState(handle).flatMap { state =>
                  val message = Option(error.getMessage).getOrElse(error.toString)
                  Future.failed(new RuntimeException(s"$state\n\nTool error: $message", error))
                }
            }
          }
        }
      }
    }

  /** Bind user-selected real tabs to one chat run without moving them into the tools group. */
  def bindReferencedTabs(
      sessionId: String,
      sessionTitle: String,
      refs: List[BrowserTabRef]
  ): Future[(List[BrowserTabRef], Int)] =
    if (sessionId.trim.isEmpty) Future.successful((Nil, 0))
    else
      withSessionLock(sessionId) {
        ensureSessionRecord(sessionId, sessionTitle).flatMap { handle =>
          val live = Future.sequence(refs.map { tab =>
            ChromeAPI.tabs
              .get(tab.tabId)
              .toFuture
              .map(actual =>
                tab.copy(
                  title = actual.title.toOption.filter(_.nonEmpty).getOrElse(tab.title),
                  url = actual.url.toOption.filter(_.nonEmpty).getOrElse(tab.url)
                )
              )
              .map(Some(_))
              .recover { case _ => None }
          })
          live.flatMap { values =>
            val valid = values.flatten
            discardUnusedToolPlaceholder(handle).flatMap { _ =>
              handle.update { session =>
                val previous =
                  if (session.attachedTabs.nonEmpty && session.previousTabId > 0) session.previousTabId
                  else if (session.tabId > 0) session.tabId
                  else 0
                session.copy(attachedTabs = valid, previousTabId = previous)
              }
              persist(handle).map(_ => (valid, handle.get.previousTabId))
            }
          }
        }
      }

  /** Release run-scoped references and restore the page that was current before @tabs binding. */
  def releaseReferencedTabs(sessionId: String): Future[Unit]                                          =
    if (sessionId.trim.isEmpty) Future.successful(())
    else
      withSessionLock(sessionId) {
        loadSession(sessionId).flatMap {
          case None          => Future.successful(())
          case Some(session) =>
            val handle        = new SessionHandle(session)
            val restoreId     = handle.get.previousTabId
            val currentTabId  = handle.get.tabId
            val currentIsRef  = handle.get.attachedTabs.exists(_.tabId == currentTabId)
            val restoredTabId = if (restoreId > 0) restoreId else if (currentIsRef) 0 else currentTabId
            handle.update(_.copy(
              attachedTabs = Nil,
              previousTabId = 0,
              tabId = restoredTabId,
              groupId = if (restoredTabId == currentTabId) handle.get.groupId else -1,
              refs = Map.empty,
              nodeRefs = Map.empty,
              url = "",
              pageTitle = "",
              scrollX = 0,
              scrollY = 0,
              scrollContainer = "",
              documentToken = ""
            ))
            if (restoreId > 0) ensureSessionTab(handle).flatMap(_ => persist(handle))
            else persist(handle)
        }
      }
  private def resultText(result: js.Any): String                                                      =
    if (js.isUndefined(result) || result == null) ""
    else if (js.typeOf(result) == "string") JsBridge.asString(result)
    else
      try {
        val text = js.JSON.stringify(result)
        if (js.isUndefined(text) || text == null) result.toString else JsBridge.asString(text)
      } catch { case _: Throwable => result.toString }
  private def workspaceTabs(handle: SessionHandle): Future[List[Tab]]                                 =
    ChromeAPI.tabs
      .query(js.Dictionary[js.Any]("windowId" -> handle.get.windowId))
      .toFuture
      .map(
        _.toList
          .filter { tab =>
            val id = tab.id.toOption.getOrElse(-1)
            id == handle.get.tabId ||
            tab.groupId.toOption.contains(handle.get.groupId) ||
            handle.get.attachedTabs.exists(_.tabId == id)
          }
          .sortBy(tab => (tab.index.toOption.getOrElse(Int.MaxValue), tab.id.toOption.getOrElse(Int.MaxValue)))
      )
      .recover { case _ => Nil }
  private def workspaceState(handle: SessionHandle): Future[String]                                   =
    workspaceTabs(handle).map { tabs =>
      val current      = tabs.find(tab => tab.id.toOption.contains(handle.get.tabId))
      val currentUrl   =
        current.flatMap(_.url.toOption).filter(_.nonEmpty).orElse(Option(handle.get.url).filter(_.nonEmpty))
      val currentTitle = current.flatMap(_.title.toOption).filter(_.nonEmpty).getOrElse(handle.get.pageTitle)
      def label(value: String, max: Int): String =
        value.replaceAll("\\s+", " ").trim.take(max)
      val currentLine                            =
        if (currentUrl.isEmpty) "(blank)"
        else s"${label(currentTitle, 80)} | ${label(currentUrl.getOrElse(""), 180)}"
      val shown                                  = tabs.take(8)
      val lines                                  = shown.map { tab =>
        val tabId  = tab.id.toOption.getOrElse(-1)
        val ref    = handle.get.attachedTabs.find(_.tabId == tabId).map(tabRef => s"[${tabRef.ref}] ").getOrElse("")
        val marker = (if (tabId == handle.get.tabId) "[current] " else "") + ref
        val url    = tab.url.toOption.filter(_.nonEmpty).getOrElse("(blank)")
        val title  = tab.title.toOption.filter(_.nonEmpty).getOrElse("(untitled)")
        s"- $marker${label(title, 80)} | ${label(url, 180)}"
      }
      val omitted                                = math.max(0, tabs.size - shown.size)
      val suffix                                 = if (omitted > 0) s"\n- … $omitted more tab(s)" else ""
      s"[browser workspace]\ncurrent: $currentLine\ntabs:\n${lines.mkString("\n")}$suffix"
    }
  private def runUseReferencedTab(handle: SessionHandle, rawRef: String): Future[JsObject]            = {
    val ref = rawRef.trim.toUpperCase
    handle.get.attachedTabs.find(_.ref.toUpperCase == ref) match {
      case None      =>
        Future.failed(new Exception(s"browser_use_tab needs an attached tab ref such as T1, got '$rawRef'"))
      case Some(tab) =>
        discardUnusedToolPlaceholder(handle).flatMap { _ =>
          ChromeAPI.tabs
            .get(tab.tabId)
            .toFuture
            .flatMap { actual =>
              handle.update(_.copy(
                tabId = tab.tabId,
                refs = Map.empty,
                nodeRefs = Map.empty,
                url = actual.url.toOption.getOrElse(tab.url),
                pageTitle = actual.title.toOption.getOrElse(tab.title),
                scrollX = 0,
                scrollY = 0,
                scrollContainer = "",
                documentToken = ""
              ))
              persist(handle).map(_ =>
                JsBridge.asObject(s"Using referenced tab $ref: ${actual.title.toOption.getOrElse(tab.title)}")
              )
            }
            .recoverWith { case _ =>
              Future.failed(new Exception(s"Referenced tab $ref is no longer open"))
            }
        }
    }
  }
  // ---------------------------------------------------------------- 会话状态
  private def ensureSession(sessionId: String, sessionTitle: String): Future[SessionHandle]           =
    ensureSessionRecord(sessionId, sessionTitle).flatMap(ensureSessionTab)
  private def ensureSessionRecord(sessionId: String, sessionTitle: String): Future[SessionHandle]     =
    loadSession(sessionId).flatMap {
      case Some(session) =>
        val title  = if (sessionTitle.trim.isEmpty) session.title else sessionTitle.trim
        val handle = sessionStore.handle(session.copy(title = title))
        if (title == session.title) Future.successful(handle)
        else persist(handle).map(_ => handle)
      case None          =>
        createSessionRecord(sessionId, sessionTitle.trim).map(sessionStore.handle)
    }
  private def loadSession(sessionId: String): Future[Option[BrowserUseSession]]                       =
    sessionStore.load(sessionId)
  private def createSessionRecord(sessionId: String, sessionTitle: String): Future[BrowserUseSession] =
    ServerTabManager.hostWindow().flatMap { windowId =>
      windowTabIds(windowId).map { existing =>
        BrowserUseSession(
          sessionId = sessionId,
          title = sessionTitle,
          tabId = 0,
          windowId = windowId,
          groupId = -1,
          nextRef = 1,
          refs = Map.empty,
          nodeRefs = Map.empty,
          knownTabs = existing,
          attachedTabs = Nil,
          previousTabId = 0,
          url = "",
          pageTitle = "",
          scrollX = 0,
          scrollY = 0,
          scrollContainer = "",
          documentToken = ""
        )
      }.flatMap { session =>
        PopCanLog.info("tool", s"browser reference session created session=$sessionId tab=0")
        persist(sessionStore.handle(session)).map(_ => session)
      }
    }

  /** tab 被用户关掉后，下次调用按需新建一个（旧 ref 全部作废）。 */
  private def ensureSessionTab(handle: SessionHandle): Future[SessionHandle] =
    ChromeAPI.tabs.get(handle.get.tabId).toFuture
      .map(_ => handle)
      .recoverWith { case _ =>
        ServerTabManager.hostWindow().flatMap { windowId =>
          ChromeAPI.tabs.create(
            js.Dictionary[js.Any]("url" -> "about:blank", "windowId" -> windowId, "active" -> false)
          ).toFuture.flatMap { created =>
            created.id.toOption match {
              case Some(tabId) =>
                ToolTabGroup.attach(tabId, windowId).flatMap { groupId =>
                  windowTabIds(windowId).map { existing =>
                    handle.update(_.copy(
                      tabId = tabId,
                      windowId = windowId,
                      groupId = groupId,
                      refs = Map.empty,
                      nodeRefs = Map.empty,
                      knownTabs = existing + tabId,
                      url = "",
                      pageTitle = "",
                      scrollX = 0,
                      scrollY = 0,
                      scrollContainer = "",
                      documentToken = ""
                    ))
                  }.flatMap(_ => persist(handle).map(_ => handle))
                }
              case None        =>
                Future.failed(new Exception("browser session tab recreation failed: no tab id"))
            }
          }
        }
      }
  private def persist(handle: SessionHandle): Future[Unit]                   = sessionStore.save(handle)
  // ---------------------------------------------------------------- tab 与分组
  private def windowTabIds(windowId: Int): Future[Set[Int]]                  =
    ChromeAPI.tabs.query(js.Dictionary[js.Any]("windowId" -> windowId)).toFuture
      .map(tabs => tabs.toList.flatMap(_.id.toOption).toSet)
      .recover { case _ => Set.empty[Int] }

  /**
   * 当前 tab 是不是"本会话刚建、一次都没用过"的 `about:blank` 占位。
   *
   * 是的话 `browser_search` / `browser_navigate` 直接复用它，省掉"关掉空 tab 再开新 tab"这一轮往返； 不是的话一律另开新 tab（当前页不被顶掉）。
   */
  private def isUnusedPlaceholder(handle: SessionHandle): Future[Boolean]       =
    if (handle.get.url.nonEmpty) Future.successful(false)
    else
      ChromeAPI.tabs
        .get(handle.get.tabId)
        .toFuture
        .map(tab => tab.url.toOption.forall(url => url.isEmpty || url == "about:blank"))
        .recover { case _ => false }
  private def discardUnusedToolPlaceholder(handle: SessionHandle): Future[Unit] = {
    val tabId = handle.get.tabId
    if (tabId <= 0 || handle.get.attachedTabs.exists(_.tabId == tabId)) Future.successful(())
    else
      isUnusedPlaceholder(handle).flatMap { unused =>
        if (!unused) Future.successful(())
        else
          ChromeAPI.tabs
            .remove(tabId)
            .toFuture
            .recover { case _ => () }
            .map { _ =>
              handle.update(session =>
                session.copy(
                  tabId = 0,
                  groupId = -1,
                  knownTabs = session.knownTabs - tabId,
                  previousTabId = if (session.previousTabId == tabId) 0 else session.previousTabId,
                  refs = Map.empty,
                  nodeRefs = Map.empty,
                  url = "",
                  pageTitle = "",
                  scrollX = 0,
                  scrollY = 0,
                  scrollContainer = "",
                  documentToken = ""
                )
              )
              ()
            }
      }
  }

  /** 在当前 tab 上打开 url（会话状态不变，文档相关状态交给调用方清理）。 */
  private def openInCurrentTab(handle: SessionHandle, url: String): Future[Int] =
    ChromeAPI.tabs
      .update(handle.get.tabId, js.Dictionary[js.Any]("url" -> url))
      .toFuture
      .map(_ => handle.get.tabId)

  /** 另开一个工具 tab（同窗口、tools 组、不抢焦点）并把它设为会话当前操作对象。 */
  private def openInNewTab(handle: SessionHandle, url: String): Future[Int] =
    ChromeAPI.tabs
      .create(js.Dictionary[js.Any](
        "url"      -> url,
        "windowId" -> handle.get.windowId,
        "active"   -> false
      ))
      .toFuture
      .flatMap { created =>
        created.id.toOption match {
          case Some(tabId) =>
            ToolTabGroup.attach(tabId, handle.get.windowId).flatMap { groupId =>
              handle.update(session =>
                session.copy(
                  tabId = tabId,
                  groupId = groupId,
                  knownTabs = session.knownTabs + tabId,
                  refs = Map.empty,
                  nodeRefs = Map.empty,
                  url = "",
                  pageTitle = "",
                  scrollX = 0,
                  scrollY = 0,
                  scrollContainer = "",
                  documentToken = ""
                )
              )
              persist(handle).map(_ => tabId)
            }
          case None        =>
            Future.failed(new Exception("browser tab creation failed: no tab id"))
        }
      }

  /** 页面自己开的新 tab（target=_blank 等）收进同组并切成当前操作对象。 */
  private def adoptNewTabs(handle: SessionHandle): Future[Unit]                        =
    ChromeAPI.tabs.query(js.Dictionary[js.Any]("windowId" -> handle.get.windowId)).toFuture
      .flatMap { tabs =>
        val list    = tabs.toList
        val known   = handle.get.knownTabs
        val current = handle.get.tabId
        val newTabs = list
          .flatMap(tab => tab.id.toOption.map(id => (id, tab)))
          .filter { case (id, tab) =>
            id != current && !known.contains(id) && !isExtensionTab(tab)
          }
        val allIds  = list.flatMap(_.id.toOption).toSet
        if (newTabs.isEmpty) {
          handle.update(_.copy(knownTabs = known ++ allIds))
          persist(handle)
        } else {
          // 优先收「由本会话某个 tab 打开」的（openerTabId 命中），避免把用户自己新开的 tab 当成操作对象；
          // 没有 opener 信息时（window.open + noopener）退回取最新的一个。
          val opened        = newTabs.filter { case (_, tab) => tab.openerTabId.toOption.exists(known.contains) }
          val (newTabId, _) = (if (opened.nonEmpty) opened else newTabs).maxBy(_._1)
          ToolTabGroup.attach(newTabId, handle.get.windowId).flatMap { groupId =>
            // 新 tab 往往还在加载，先等它到 complete（超时就算了），后续快照才有内容。
            waitForSettled(newTabId, 15000).flatMap { _ =>
              settle(300).flatMap { _ =>
                handle.update(_.copy(
                  tabId = newTabId,
                  groupId = groupId,
                  refs = Map.empty,
                  nodeRefs = Map.empty,
                  knownTabs = known ++ allIds + newTabId,
                  url = "",
                  pageTitle = "",
                  scrollX = 0,
                  scrollY = 0,
                  scrollContainer = "",
                  documentToken = ""
                ))
                PopCanLog.info("tool", s"browser session adopted tab session=${handle.get.sessionId} tab=$newTabId")
                persist(handle)
              }
            }
          }
        }
      }
      .recover { case e =>
        PopCanLog.warn("tool", s"adopt new tabs failed: ${e.getMessage}")
      }
  private def isExtensionTab(tab: Tab): Boolean                                        = {
    val extensionOrigin =
      try ChromeAPI.runtime.getURL("")
      catch { case _: Throwable => "chrome-extension://" }
    tab.url.toOption.exists(_.startsWith(extensionOrigin))
  }
  // ---------------------------------------------------------------- CDP 基础设施
  /**
   * 每次工具调用持一份短 CDP 租约：真实 attach/detach 交给 `CdpSession`（同 tab 有别的 owner 时只是合流）。
   *
   * 工具 tab 一律在后台：调用期间额外持一份页面活动租约，避免 AX / 懒加载 / 点击操作遇到 hidden 或 frozen renderer。
   */
  private def withAttach[A](tabId: Int)(body: DebuggerSession => Future[A]): Future[A] = {
    val target = CdpTargets.tabTarget(tabId)
    val owner  = CdpSession.nextOwner("tool")
    CdpSession.acquire(tabId, owner).flatMap { lease =>
      CdpSession
        .acquirePageActivity(tabId, owner)
        .map(Some(_))
        .recover { case e =>
          PopCanLog.warn("cdp", s"tool page activity unavailable tab=$tabId: ${e.getMessage}")
          Option.empty[CdpPageActivityLease]
        }
        .flatMap { activity =>
          body(target).transformWith { result =>
            activity
              .map(_.release("tool done"))
              .getOrElse(Future.successful(()))
              .recover { case e =>
                PopCanLog.warn("cdp", s"tool page activity restore failed tab=$tabId: ${e.getMessage}")
                ()
              }
              .flatMap(_ => lease.release("tool done"))
              .transform(_ => result)
          }
        }
    }
  }
  private def send(
      target: DebuggerSession,
      method: String,
      params: js.UndefOr[js.Object] = js.undefined
  ): Future[JsObject] =
    ChromeAPI.debugger.sendCommand(target, method, params).toFuture
      .map(raw => CdpCommandResults.commandResult(raw.toOption.getOrElse(JsBridge.obj())))

  /** `Accessibility` 域需要先 enable 才能稳定拿到完整树；失败不致命，继续尝试 getFullAXTree。 */
  private def enableAccessibility(target: DebuggerSession): Future[Unit]                                       =
    send(target, "Accessibility.enable").map(_ => ()).recover { case _ => () }
  private def evaluate(target: DebuggerSession, expression: String): Future[js.Any]                            =
    send(
      target,
      "Runtime.evaluate",
      JsBridge.obj("expression" -> (expression), "returnByValue" -> (true), "awaitPromise" -> (false)).asInstanceOf[
        js.Object
      ]
    ).map(raw => CdpCommandResults.evaluateValue(raw))
  // ---------------------------------------------------------------- 动作
  /** 动作收尾：默认只回一行状态，`withSnapshot` 时改用与 `browser_snapshot` 完全相同的输出。 */
  private def actionResult(handle: SessionHandle, withSnapshot: Boolean, statusLine: String): Future[JsObject] =
    if (withSnapshot) runSnapshot(handle)
    else Future.successful(JsBridge.asObject(s"$statusLine\n$ActionHint"))
  private def runNavigate(
      handle: SessionHandle,
      rawUrl: String,
      newTab: Boolean,
      withSnapshot: Boolean
  ): Future[JsObject] =
    normalizeUrl(rawUrl) match {
      case None      =>
        Future.failed(new Exception(s"browser_navigate needs an http(s) URL, got '$rawUrl'"))
      case Some(url) =>
        val opened =
          if (newTab || handle.get.tabId <= 0)
            openInNewTab(handle, url).map(_ => true)
          else
            openInCurrentTab(handle, url)
              .map(_ => false)
              .recoverWith(_ => openInNewTab(handle, url).map(_ => true))
        opened.flatMap { isNewTab =>
          val tabId = handle.get.tabId
          // 文档换了，backendDOMNodeId 全部失效。
          handle.update(session =>
            session.copy(
              refs = Map.empty,
              nodeRefs = Map.empty,
              url = url,
              pageTitle = "",
              scrollX = 0,
              scrollY = 0,
              scrollContainer = "",
              documentToken = ""
            )
          )
          persist(handle)
            .flatMap(_ => waitForTabLoaded(tabId, 30000).recover { case _ => () })
            .flatMap(_ => settle(400))
            .flatMap(_ => adoptNewTabs(handle))
            .flatMap(_ =>
              actionResult(
                handle,
                withSnapshot,
                if (isNewTab) s"Opened $url in a new tab." else s"Navigated to $url."
              )
            )
        }
    }

  /**
   * 用浏览器默认搜索引擎搜索，返回结构化结果列表（见 `detectSearchResults`）。
   *
   * 引擎由用户自己的浏览器设置决定，插件里没有任何引擎知识；结果页开在**新 tab** 里并成为当前操作对象（当前页不被顶掉）， 方便紧跟的 browser_navigate / browser_extract
   * 操作。只有在从未用过的空白占位上才就地搜索。
   */
  private def runSearch(handle: SessionHandle, rawQuery: String): Future[JsObject] = {
    val query = rawQuery.trim
    if (query.isEmpty) Future.failed(new Exception("browser_search needs a non-empty query"))
    else {
      val previousTab = handle.get.tabId
      isUnusedPlaceholder(handle).flatMap { reuse =>
        val prepared: Future[Int] =
          if (reuse) Future.successful(handle.get.tabId)
          else openInNewTab(handle, "about:blank").map(_ => handle.get.tabId)
        prepared.flatMap { tabId =>
          val runQuery: Future[Unit] =
            ChromeAPI.search.query(js.Dictionary[js.Any]("text" -> query, "tabId" -> tabId)).toFuture.map(_ =>
              ()
            )
          runQuery
            .recoverWith { case e =>
              // 新 tab 上查不动时退回旧行为：在原 tab 里查一次。
              if (reuse) {
                Future.failed(new Exception(
                  s"chrome.search.query failed (is the 'search' permission granted?): ${e.getMessage}"
                ))
              } else {
                handle.update(_.copy(
                  tabId = previousTab,
                  refs = Map.empty,
                  nodeRefs = Map.empty,
                  url = "",
                  pageTitle = "",
                  scrollX = 0,
                  scrollY = 0,
                  scrollContainer = "",
                  documentToken = ""
                ))
                ChromeAPI.search
                  .query(js.Dictionary[js.Any]("text" -> query, "tabId" -> previousTab))
                  .toFuture
                  .map(_ => ())
                  .recoverWith { case e2 =>
                    Future.failed(new Exception(
                      s"chrome.search.query failed (is the 'search' permission granted?): ${e2.getMessage}"
                    ))
                  }
              }
            }
            .flatMap { _ =>
              val activeTab = handle.get.tabId
              // 文档换了，backendDOMNodeId 全部失效。
              handle.update(_.copy(
                refs = Map.empty,
                nodeRefs = Map.empty,
                url = "",
                pageTitle = "",
                scrollX = 0,
                scrollY = 0,
                scrollContainer = "",
                documentToken = ""
              ))
              persist(handle)
                .flatMap(_ => waitForTabLoaded(activeTab, 30000).recover { case _ => () })
                .flatMap(_ => settle(400))
            }
            // tabId 若未被采纳（浏览器另开了结果页），这里会把新 tab 接管为当前操作对象。
            .flatMap(_ => adoptNewTabs(handle))
            .flatMap(_ => detectSearchResults(handle, query))
        }
      }
    }
  }

  /**
   * 搜索结果记录检测（替换掉"返回页面快照"的旧契约）：
   *
   * 页面侧跑 MDR 式检测（同签名兄弟重复 → 记录 → 字段对齐 → 指标），Scala 侧打分选最高分列表并校验。 校验通过 → 返回结构化列表（标题/URL/摘要/日期/广告标记），同时按 host 固化容器档案；
   * 没有合格列表 → **直接失败**（不兜底快照），让 agent 自己决定换查询词还是用 snapshot/extract。
   */
  private def detectSearchResults(handle: SessionHandle, query: String): Future[JsObject] = {
    val captured = withAttach(handle.get.tabId) { target =>
      for {
        page <- enableAccessibility(target).flatMap(_ => pageInfo(target))
        host = hostOf(if (page.url.nonEmpty) page.url else handle.get.url)
        profile <- SearchResults.loadProfile(host)
        raw     <- evaluate(target, SearchResults.detectJs(query, profile))
      } yield (page, host, profile, raw)
    }
    captured.flatMap { case (page, host, profile, raw) =>
      val detected  = SearchResults.parse(raw)
      val scored    = detected.lists.map(candidate => candidate -> SearchResults.score(candidate, query)).sortBy(-_._2)
      // 记住这次搜索词，browser_extract 的区域兜底会用它当相关性目标（快照本身不再过滤）。
      val sessionId = handle.get.sessionId
      lastQueries.update(sessionId, query)
      scored.headOption match {
        case Some((candidate, score)) if SearchResults.valid(candidate) =>
          val text  = SearchResults.render(detected, candidate, score, math.max(0, scored.size - 1))
          val saveF = SearchResults.saveProfile(host, candidate.path, candidate.sig)
          saveF.flatMap(_ => persistPageState(handle, page).map(_ => JsBridge.asObject(text)))
        case _                                                          =>
          SearchResults.logNoResult(host, detected)
          val failF = profile match {
            case Some(used) => SearchResults.failProfile(host, used.containerPath)
            case None       => Future.successful(())
          }
          failF.flatMap(_ => persistPageState(handle, page)).flatMap { _ =>
            Future.failed(new Exception(
              s"browser_search found no result list on $host (page title: ${detected.title.take(80)}; " +
                s"${detected.diagnostic}) — " +
                "use browser_snapshot to inspect the page, or browser_extract for its text"
            ))
          }
      }
    }
  }

  /** 观察类调用共用：把当前页面状态写回会话并落库。 */
  private def persistPageState(handle: SessionHandle, page: PageInfoValue): Future[Unit] = {
    handle.update(session =>
      session.copy(
        url = page.url,
        pageTitle = page.title,
        scrollX = page.scrollX,
        scrollY = page.scrollY,
        documentToken = if (page.token.nonEmpty) page.token else session.documentToken
      )
    )
    persist(handle)
  }

  /**
   * 观察：采集整棵 AX 树 → 渲染"可交互节点 + 标题 + 地标"的交互清单。
   *
   * 不带任何相关性过滤：同一页面同一文档的输出是确定的（除 ref 递增），与 Playwright MCP / browser-use 的形态一致。 正文不在这里给（用
   * browser_extract），所以文章页的快照只有导航/页脚/标题这几行。
   */
  private def runSnapshot(handle: SessionHandle): Future[JsObject] = {
    val captured = withAttach(handle.get.tabId) { target =>
      for {
        page <- enableAccessibility(target).flatMap(_ => pageInfo(target))
        tree <- fullAxTree(target)
      } yield (page, tree)
    }
    captured.flatMap { case (page, tree) =>
      // 文档换了（点击导致同 tab 导航等）→ 先清空旧 ref，再按新文档分配，旧 ref 不会被复用。
      if (page.token.nonEmpty && page.token != handle.get.documentToken) {
        handle.update(_.copy(refs = Map.empty, nodeRefs = Map.empty, scrollContainer = ""))
      }
      val header = snapshotHeader(handle, page)
      val text   = renderPageSnapshot(header, collectRawNodes(tree, MaxRawNodes), backend => refFor(handle, backend))
      persistPageState(handle, page).map(_ => JsBridge.asObject(text))
    }
  }

  /**
   * 交互清单：按文档顺序打印"可交互节点（带 ref + 状态）+ 标题 + 地标"，扁平行、不做缩进、不按目标词筛。 预算内全部给出；超出时截断并显式说明（让模型知道可以滚动或再快照一次）。
   */
  private def renderPageSnapshot(
      header: String,
      roots: List[AxFilter.RawNode],
      refFor: Option[Int] => Option[String]
  ): String = {
    val printable                             = mutable.ArrayBuffer.empty[AxFilter.RawNode]
    def collect(node: AxFilter.RawNode): Unit = {
      if (node.interactive || node.role == "heading" || LandmarkRoles.contains(node.role) || node.role == "RootWebArea")
        printable += node
      node.children.foreach(collect)
    }
    roots.foreach(collect)
    val out                                   = new StringBuilder(header)
    var emitted                               = 0
    var cut                                   = false
    printable.foreach { node =>
      if (!cut && (emitted >= MaxSnapshotNodes || out.length >= MaxSnapshotChars)) cut = true
      if (!cut) {
        emitted += 1
        val ref       = if (node.interactive) refFor(node.backendNodeId) else None
        val refText   = ref.map(value => s" [ref=$value]").getOrElse("")
        val stateText = node.states.map(state => s" [$state]").mkString
        val nameText  = if (node.name.isEmpty) "" else s" \"${node.name.take(200)}\""
        val urlText   =
          if (node.url.isEmpty) ""
          else if (node.url.length <= MaxSnapshotUrl) s" url=${node.url}"
          else s" url=${node.url.take(MaxSnapshotUrl)}…"
        out.append(s"- ${node.role}$nameText$refText$urlText$stateText\n")
      }
    }
    if (cut)
      out.append(
        s"[snapshot truncated: printed $emitted of ${printable.size} nodes — scroll or call browser_snapshot again]\n"
      )
    else if (emitted == 0) out.append("[no interactive nodes found on this page]\n")
    out.append(SnapshotHint)
    out.toString
  }
  private def hostOf(url: String): String =
    // 不用正则：内联 flag 只能出现在模式开头（"^(?i)…" 会在运行时抛 PatternSyntaxException）
    {
      val lower = url.trim.toLowerCase
      val rest  =
        if (lower.startsWith("https://")) lower.drop("https://".length)
        else if (lower.startsWith("http://")) lower.drop("http://".length)
        else lower
      rest.takeWhile(ch => ch != '/' && ch != '?' && ch != '#')
    }
  private def runClick(handle: SessionHandle, ref: String, withSnapshot: Boolean): Future[JsObject] =
    resolveRef(handle, ref) match {
      case None            =>
        Future.failed(unknownRefError(ref))
      case Some(backendId) =>
        withAttach(handle.get.tabId) { target =>
          requireSameDocument(target, handle)
            .flatMap(_ => elementCenter(target, backendId))
            .flatMap { case (x, y) =>
              for {
                _ <- dispatchMouse(target, "mouseMoved", x, y, buttons = 0)
                _ <- dispatchMouse(target, "mousePressed", x, y, buttons = 1)
                _ <- dispatchMouse(target, "mouseReleased", x, y, buttons = 0)
              } yield ()
            }
        }.flatMap(_ => settle(200))
          .flatMap(_ => waitForSettled(handle.get.tabId, 15000))
          .flatMap(_ => settle(300))
          .flatMap(_ => adoptNewTabs(handle))
          .flatMap(_ => actionResult(handle, withSnapshot, s"Clicked [$ref]."))
    }
  private def runType(
      handle: SessionHandle,
      ref: String,
      text: String,
      submit: Boolean,
      withSnapshot: Boolean
  ): Future[JsObject] =
    if (text.isEmpty) {
      Future.failed(new Exception("browser_type needs non-empty text"))
    } else {
      resolveRef(handle, ref) match {
        case None            =>
          Future.failed(unknownRefError(ref))
        case Some(backendId) =>
          withAttach(handle.get.tabId) { target =>
            requireSameDocument(target, handle)
              .flatMap(_ => focusElement(target, backendId))
              .flatMap { _ =>
                for {
                  _ <- selectAll(target)
                  _ <- send(
                    target,
                    "Input.insertText",
                    JsBridge.obj("text" -> (text))
                  )
                  _ <- if (submit) pressEnter(target) else Future.successful(())
                } yield ()
              }
          }.flatMap(_ => settle(if (submit) 600 else 200))
            .flatMap(_ => if (submit) waitForSettled(handle.get.tabId, 15000) else Future.successful(()))
            .flatMap(_ => adoptNewTabs(handle))
            .flatMap(_ => persist(handle))
            .flatMap(_ =>
              actionResult(
                handle,
                withSnapshot,
                s"Typed ${text.length} characters into [$ref]" +
                  (if (submit) " and pressed Enter to submit." else ".")
              )
            )
      }
    }
  private def runScroll(
      handle: SessionHandle,
      rawDirection: String,
      amount: Option[Double],
      withSnapshot: Boolean
  ): Future[JsObject] = {
    val direction = normalizeDirection(rawDirection)
    direction match {
      case None      =>
        Future.failed(new Exception(s"browser_scroll needs direction up/down/left/right, got '$rawDirection'"))
      case Some(dir) =>
        withAttach(handle.get.tabId) { target =>
          pageInfo(target).flatMap { page =>
            val step   = amount.filter(_ > 0).getOrElse {
              if (dir == "up" || dir == "down") math.max(200.0, page.viewportHeight * 0.9)
              else math.max(200.0, page.viewportWidth * 0.9)
            }
            val deltaX = dir match {
              case "left"  => -step
              case "right" => step
              case _       => 0.0
            }
            val deltaY = dir match {
              case "up"   => -step
              case "down" => step
              case _      => 0.0
            }
            // 不用 Input.dispatchMouseEvent(mouseWheel)：那条命令要等 compositor ack，后台 tab / headless
            // 下实测不会返回（Chromium 153 直接挂住）。改成等价 JS 滚动：先滚视口中心下最内层的可滚动容器，
            // 没有就滚 window。返回值告诉我们实际滚的是谁，快照头部据此标注。
            evaluate(target, BrowserPageScripts.scroll(deltaX, deltaY)).map { raw =>
              val container =
                if (js.isUndefined(raw) || raw == null) ""
                else {
                  val scrolled = raw.asInstanceOf[BrowserScrollResult]
                  val kind     = scrolled.target.getOrElse("window")
                  if (kind == "window") ""
                  else s"$kind(${scrolled.targetX.getOrElse(0.0).toInt}, ${scrolled.targetY.getOrElse(0.0).toInt})"
                }
              (container, step)
            }
          }
        }.flatMap { case (container, step) =>
          handle.update(_.copy(scrollContainer = container))
          settle(250).map(_ => step)
        }
          .flatMap(step => actionResult(handle, withSnapshot, s"Scrolled $dir by ${step.toInt}px."))
    }
  }

  /**
   * 正文抽取：元数据 → readability（本地注入的库）→ 区域打分（AxFilter）→ 全文兜底。
   *
   * 最后一级"全文"只作兜底，不对外暴露模式参数。所有解析都在 `document.cloneNode(true)` 上做， 不修改活页面（旧实现会真的删掉 nav/footer，破坏后续快照）。
   */
  private def runExtract(handle: SessionHandle, urls: List[String]): Future[JsObject] =
    if (urls.isEmpty) {
      extractFromTab(
        handle.get.tabId,
        urlHint = handle.get.url,
        titleHint = handle.get.pageTitle,
        goal = lastQueries.getOrElse(handle.get.sessionId, "")
      ).map(text => JsBridge.asObject(text))
    } else readUrls(handle, urls)

  /** 单页抽取：注入库 → 页面侧候选 → 选择策略。返回与 browser_extract 同形的文本。 */
  private def extractFromTab(tabId: Int, urlHint: String, titleHint: String, goal: String): Future[String] =
    withAttach(tabId)(target => extractor.extract(target, urlHint, titleHint, goal))

  /**
   * `browser_extract(urls=[...])`：优先复用当前页和 tools 组中同 URL 的页面，只为缺失 URL 新建 read tab。
   *
   * 只读——不改变会话的当前操作对象、不产生 refs；单页失败只占一行，全部失败才抛错。
   */
  private def readUrls(handle: SessionHandle, rawUrls: List[String]): Future[JsObject] = {
    val seen    = mutable.Set.empty[String]
    val targets = rawUrls.map(_.trim).filter(_.nonEmpty).flatMap { raw =>
      canonicalHttpUrl(raw) match {
        case Some((key, _)) if seen.add("url:" + key) => Some((raw, Some(key)))
        case Some(_)                                  => None
        case None if seen.add("raw:" + raw)           => Some((raw, None))
        case None                                     => None
      }
    }.take(MaxReadUrls)
    if (targets.isEmpty) {
      Future.failed(new Exception("browser_extract got urls but none of them is a usable URL"))
    } else {
      workspaceTabs(handle).flatMap { availableTabs =>
        val results = inParallel(targets, ReadConcurrency) { case (raw, keyOpt) =>
          keyOpt match {
            case None      => Future.successful((raw, s"[$raw] read failed: not an http(s) URL"))
            case Some(key) =>
              val current  = availableTabs.find(tab =>
                tab.id.toOption.contains(handle.get.tabId) &&
                  tab.url.toOption.flatMap(canonicalHttpUrl).exists(_._1 == key)
              )
              val reused   = current.orElse(availableTabs.find(tab =>
                tab.url.toOption.flatMap(canonicalHttpUrl).exists(_._1 == key)
              ))
              val selected =
                reused match {
                  case Some(tab) =>
                    tab.id.toOption match {
                      case Some(tabId) =>
                        val status = if (tabId == handle.get.tabId) "current" else "reused"
                        if (status == "reused") {
                          handle.update(session => session.copy(knownTabs = session.knownTabs + tabId))
                          persist(handle)
                        }
                        Future.successful((tabId, status, tab.title.toOption.getOrElse("")))
                      case None        =>
                        openReadTab(handle, canonicalHttpUrl(raw).map(_._2).getOrElse(raw))
                          .map(tabId => (tabId, "opened", ""))
                    }
                  case None      =>
                    openReadTab(handle, canonicalHttpUrl(raw).map(_._2).getOrElse(raw))
                      .map(tabId => (tabId, "opened", ""))
                }
              selected
                .flatMap { case (tabId, status, titleHint) =>
                  waitForTabLoaded(tabId, ReadTabTimeoutMs)
                    .recover { case _ => () }
                    .flatMap { _ =>
                      tabUrlOf(tabId).flatMap {
                        // 加载失败（DNS / 连接被拒 / 超时）时 tab 还停在 about:blank：明说这页没读到，别返回空正文。
                        case Some(_) => Future.successful(())
                        case None    => Future.failed(new Exception("page did not load"))
                      }
                    }
                    .flatMap(_ =>
                      extractFromTab(
                        tabId,
                        urlHint = raw,
                        titleHint = titleHint,
                        goal = lastQueries.getOrElse(handle.get.sessionId, "")
                      )
                    )
                    .map(text => (raw, s"[tab=$status] " + capRead(text, MaxReadCharsPerPage)))
                }
                .recover { case e =>
                  (raw, s"[$raw] read failed: ${Option(e.getMessage).getOrElse(e.toString)}")
                }
          }
        }
        results.map { pages =>
          val joined = pages.map(_._2).mkString("\n\n---\n\n")
          val text   =
            if (joined.length <= MaxReadCharsTotal) joined
            else joined.take(MaxReadCharsTotal) + s"\n[read truncated at $MaxReadCharsTotal chars]\n"
          val failed = pages.count(page => page._2.contains("] read failed:"))
          if (failed == pages.size && pages.nonEmpty) {
            throw new Exception(s"browser_extract could not read any of the ${pages.size} URL(s):\n${text.take(600)}")
          }
          JsBridge.asObject(text)
        }
      }
    }
  }

  /** HTTP(S) URL 的匹配键；忽略 fragment、默认端口和末尾 `/`，保留 query。 */
  private def canonicalHttpUrl(rawUrl: String): Option[(String, String)] =
    normalizeUrl(rawUrl).flatMap { normalized =>
      try {
        val parsed = JsBridge.asObject(JsBridge.newInstance(JsBridge.urlConstructor)(normalized))
        val scheme = JsBridge.asString(parsed.protocol).toLowerCase
        if (scheme != "http:" && scheme != "https:") None
        else {
          parsed.hash = ""
          val path = JsBridge.asString(parsed.pathname)
          if (path.length > 1 && path.endsWith("/")) parsed.pathname = path.dropRight(1)
          val href = JsBridge.asString(parsed.href)
          Some(href -> href)
        }
      } catch {
        case _: Throwable => None
      }
    }

  /** tab 当前的真实地址；还在 about:blank 或取不到（tab 没了）时给 None。 */
  private def tabUrlOf(tabId: Int): Future[Option[String]] =
    ChromeAPI.tabs
      .get(tabId)
      .toFuture
      .map(tab => tab.url.toOption.filter(url => url.startsWith("http")))
      .recover { case _ => None }

  /** 读页专用 tab：与 navigate 的页面一样进 tools 组、后台打开，但**不改会话当前操作对象**。 */
  private def openReadTab(handle: SessionHandle, url: String): Future[Int] =
    ChromeAPI.tabs
      .create(js.Dictionary[js.Any](
        "url"      -> url,
        "windowId" -> handle.get.windowId,
        "active"   -> false
      ))
      .toFuture
      .flatMap { created =>
        created.id.toOption match {
          case Some(tabId) =>
            ToolTabGroup.attach(tabId, handle.get.windowId).flatMap { _ =>
              // 登记进 knownTabs：否则 adoptNewTabs 会把读页 tab 误当"页面自己开的新 tab"抢成当前操作对象。
              handle.update(session => session.copy(knownTabs = session.knownTabs + tabId))
              persist(handle).map(_ => tabId)
            }
          case None        =>
            Future.failed(new Exception("read tab creation failed: no tab id"))
        }
      }
  private def capRead(text: String, maxChars: Int): String                 =
    if (text.length <= maxChars) text else text.take(maxChars) + s"\n[page truncated at $maxChars chars]"

  /** 受限并发跑一批任务（保持输入顺序），避免一次开太多 tab / CDP attach。 */
  private def inParallel[A, B](items: List[A], limit: Int)(f: A => Future[B]): Future[List[B]] =
    if (items.isEmpty) Future.successful(Nil)
    else {
      val indexed               = items.zipWithIndex
      val results               = mutable.Map.empty[Int, B]
      val workers               = math.max(1, math.min(limit, indexed.size))
      val queue                 = mutable.Queue(indexed*)
      def drain(): Future[Unit] =
        if (queue.isEmpty) Future.successful(())
        else {
          val (item, index) = queue.dequeue()
          f(item)
            .map { value =>
              results.update(index, value)
              ()
            }
            .flatMap(_ => drain())
        }
      Future.sequence(List.fill(workers)(drain())).map { _ =>
        indexed.map { case (_, index) => results.getOrElse(index, null.asInstanceOf[B]) }
      }
    }

  // ---------------------------------------------------------------- 元素定位与输入
  private def resolveRef(handle: SessionHandle, rawRef: String): Option[Int] = {
    val ref = normalizeRef(rawRef)
    handle.get.refs.get(ref)
  }
  private def normalizeRef(rawRef: String): String                           = {
    val trimmed = rawRef.trim
    if (trimmed.toLowerCase.startsWith("ref=")) trimmed.drop(4).trim else trimmed
  }
  private def unknownRefError(rawRef: String): Exception                     =
    new Exception(
      s"Unknown ref '$rawRef': it is not part of this tab's current document " +
        "(the page navigated, a new tab took over, or the ref was never snapshotted). Call browser_snapshot first."
    )

  /** 用文档标记兜底：ref 是上一个文档的（点击导致同 tab 导航）时立刻作废并给出可读错误。 */
  private def requireSameDocument(target: DebuggerSession, handle: SessionHandle): Future[Unit] =
    pageInfo(target).flatMap { page =>
      if (page.token.isEmpty || page.token == handle.get.documentToken) Future.successful(())
      else {
        handle.update(_.copy(refs = Map.empty, nodeRefs = Map.empty, scrollContainer = ""))
        Future.failed(new Exception(
          "That ref belongs to a previous page (the tab navigated). Call browser_snapshot for fresh refs."
        ))
      }
    }

  /** 先把元素滚进视口，再用 getBoundingClientRect 换视口内中心点；getBoxModel 仅作兜底。 */
  private def elementCenter(target: DebuggerSession, backendNodeId: Int): Future[(Double, Double)]          =
    ensureDom(target)
      .flatMap(_ => scrollIntoView(target, backendNodeId))
      .flatMap(_ => rectCenter(target, backendNodeId))
      .recoverWith { case e =>
        PopCanLog.warn(
          "tool",
          s"rect center failed backend=$backendNodeId (${e.getMessage}), falling back to box model"
        )
        boxModelCenter(target, backendNodeId)
      }
      .flatMap {
        case Some(point) if point._1 > 0 && point._2 > 0 => Future.successful(point)
        case _                                           =>
          Future.failed(new Exception(
            "Could not locate a clickable point for that ref (element may be hidden, off-screen or disabled). " +
              "Call browser_snapshot again."
          ))
      }
  private def scrollIntoView(target: DebuggerSession, backendNodeId: Int): Future[Unit]                     =
    send(
      target,
      "DOM.scrollIntoViewIfNeeded",
      JsBridge.obj("backendNodeId" -> (backendNodeId))
    ).map(_ => ()).recover { case _ => () }
  private def rectCenter(target: DebuggerSession, backendNodeId: Int): Future[Option[(Double, Double)]]     =
    send(
      target,
      "DOM.resolveNode",
      JsBridge.obj("backendNodeId" -> (backendNodeId))
    ).flatMap { raw =>
      raw.asInstanceOf[DomResolveNodeResult].`object`.toOption.flatMap(_.objectId.toOption) match {
        case None           => Future.failed(new Exception("ref node could not be resolved"))
        case Some(objectId) =>
          send(
            target,
            "Runtime.callFunctionOn",
            JsBridge.obj(
              "objectId"            -> (objectId),
              "functionDeclaration" -> ("""function(){var r=this.getBoundingClientRect();
                  |if (!r || (r.width <= 0 && r.height <= 0)) return null;
                  |return {x: r.left + r.width / 2, y: r.top + r.height / 2, width: r.width, height: r.height};}"""
                .stripMargin),
              "returnByValue"       -> (true)
            )
          ).map { result =>
            result.asInstanceOf[RuntimeCallFunctionOnResult].result.toOption
              .flatMap(_.value.toOption)
              .map(_.asInstanceOf[ElementBox])
              .flatMap(box =>
                for {
                  x <- box.x.toOption
                  y <- box.y.toOption
                } yield (x, y)
              )
          }
      }
    }
  private def boxModelCenter(target: DebuggerSession, backendNodeId: Int): Future[Option[(Double, Double)]] =
    send(
      target,
      "DOM.getBoxModel",
      JsBridge.obj("backendNodeId" -> (backendNodeId))
    ).flatMap { raw =>
      // 实测 getBoxModel 的 quad 与 getBoundingClientRect 同口径（视口坐标），不需要再减滚动量。
      pageInfo(target).map { page =>
        val quad = raw.asInstanceOf[DomBoxModelResult].model.content
        if (quad.length < 8) None
        else {
          val xs         = List(quad(0), quad(2), quad(4), quad(6))
          val ys         = List(quad(1), quad(3), quad(5), quad(7))
          val x          = xs.sum / xs.size
          val y          = ys.sum / ys.size
          val inViewport =
            page.viewportWidth <= 0 || page.viewportHeight <= 0 ||
              (x > 0 && y > 0 && x <= page.viewportWidth && y <= page.viewportHeight)
          if (inViewport) Some((x, y)) else None
        }
      }
    }.recover { case _ => None }
  private def focusElement(target: DebuggerSession, backendNodeId: Int): Future[Unit]                       =
    ensureDom(target)
      .flatMap(_ =>
        send(
          target,
          "DOM.focus",
          JsBridge.obj("backendNodeId" -> (backendNodeId))
        )
      )
      .map(_ => ())
      .recoverWith { case _ =>
        // 不是可聚焦元素（例如 contenteditable 之外的容器）时退化成点一下。
        elementCenter(target, backendNodeId).flatMap { case (x, y) =>
          dispatchMouse(target, "mousePressed", x, y, buttons = 1)
            .flatMap(_ => dispatchMouse(target, "mouseReleased", x, y, buttons = 0))
        }
      }
  private def selectAll(target: DebuggerSession): Future[Unit]                                              =
    send(
      target,
      "Input.dispatchKeyEvent",
      JsBridge.obj(
        "type"                  -> ("rawKeyDown"),
        "modifiers"             -> (2),
        "key"                   -> ("a"),
        "code"                  -> ("KeyA"),
        "windowsVirtualKeyCode" -> (65),
        "nativeVirtualKeyCode"  -> (65),
        "commands"              -> (js.Array("selectAll"))
      )
    ).flatMap(_ =>
      send(
        target,
        "Input.dispatchKeyEvent",
        JsBridge.obj(
          "type"                  -> ("keyUp"),
          "modifiers"             -> (2),
          "key"                   -> ("a"),
          "code"                  -> ("KeyA"),
          "windowsVirtualKeyCode" -> (65),
          "nativeVirtualKeyCode"  -> (65)
        )
      )
    ).map(_ => ())
  private def pressEnter(target: DebuggerSession): Future[Unit]                                             =
    send(
      target,
      "Input.dispatchKeyEvent",
      JsBridge.obj(
        "type"                  -> ("keyDown"),
        "key"                   -> ("Enter"),
        "code"                  -> ("Enter"),
        "windowsVirtualKeyCode" -> (13),
        "nativeVirtualKeyCode"  -> (13),
        "text"                  -> ("\r"),
        "unmodifiedText"        -> ("\r")
      )
    ).flatMap(_ =>
      send(
        target,
        "Input.dispatchKeyEvent",
        JsBridge.obj(
          "type"                  -> ("keyUp"),
          "key"                   -> ("Enter"),
          "code"                  -> ("Enter"),
          "windowsVirtualKeyCode" -> (13),
          "nativeVirtualKeyCode"  -> (13)
        )
      )
    ).map(_ => ())
  private def dispatchMouse(
      target: DebuggerSession,
      eventType: String,
      x: Double,
      y: Double,
      buttons: Int
  ): Future[Unit] =
    send(
      target,
      "Input.dispatchMouseEvent",
      JsBridge.obj(
        "type"       -> (eventType),
        "x"          -> (x),
        "y"          -> (y),
        "button"     -> (if (eventType == "mouseMoved") "none" else "left"),
        "buttons"    -> (buttons),
        "clickCount" -> (if (eventType == "mouseMoved") 0 else 1)
      )
    ).map(_ => ())
  // ---------------------------------------------------------------- 观察
  /** DOM 域命令（resolveNode / getBoxModel / focus）在部分 Chrome 版本需要先取一次文档。 */
  private def ensureDom(target: DebuggerSession): Future[Unit]                                              =
    send(target, "DOM.getDocument", JsBridge.obj("depth" -> (1)))
      .map(_ => ())
      .recover { case _ => () }

  /** 点击/提交后页面可能在导航，等它到 complete 再取快照（超时就按现状快照）。 */
  private def waitForSettled(tabId: Int, timeoutMs: Int): Future[Unit]  =
    ChromeAPI.tabs.get(tabId).toFuture
      .flatMap { tab =>
        if (tab.status.contains("loading")) waitForTabLoaded(tabId, timeoutMs).recover { case _ => () }
        else Future.successful(())
      }
      .recover { case _ => () }
  private def pageInfo(target: DebuggerSession): Future[PageInfoValue]  =
    evaluate(target, BrowserPageScripts.PageInfo).map {
      case value if js.isUndefined(value) || value == null => PageInfoValue("", "", "", 0, 0, 0, 0, 0)
      case value                                           =>
        val info = value.asInstanceOf[BrowserPageInfo]
        PageInfoValue(
          url = info.url.getOrElse(""),
          title = info.title.getOrElse(""),
          token = info.token.getOrElse(""),
          scrollX = info.scrollX.getOrElse(0.0),
          scrollY = info.scrollY.getOrElse(0.0),
          viewportWidth = info.viewportWidth.getOrElse(0.0),
          viewportHeight = info.viewportHeight.getOrElse(0.0),
          documentHeight = info.documentHeight.getOrElse(0.0)
        )
    }
  final private case class PageInfoValue(
      url: String,
      title: String,
      token: String,
      scrollX: Double,
      scrollY: Double,
      viewportWidth: Double,
      viewportHeight: Double,
      documentHeight: Double
  )
  private def fullAxTree(target: DebuggerSession): Future[List[AxNode]] =
    send(target, "Accessibility.getFullAXTree")
      .map(raw => raw.asInstanceOf[AxTreeResult].nodes.toList)

  /**
   * 无损采集：把整棵 AX 树转成带统计字段的 RawNode 树（不做白名单、不做路径压缩、不做 URL 精简）。 统计字段供本地相关性过滤使用，只在本地流转，不进 prompt。
   */
  private def collectRawNodes(nodes: List[AxNode], budget: Int): List[AxFilter.RawNode] = {
    val byId      = nodes.map(node => node.nodeId -> node).toMap
    var remaining = budget
    var nextUid   = 0
    def build(nodeId: String, path: List[String], parentUid: Option[Int]): Option[AxFilter.RawNode] =
      if (remaining <= 0) None
      else
        byId.get(nodeId).map { node =>
          remaining -= 1
          nextUid += 1
          val uid         = nextUid
          val role        = roleOf(node)
          val name        = displayName(node)
          val url         = linkUrlOf(node, role)
          val interactive = isInteractive(node, role)
          // 只把"正文叶子"和"可交互标签"算进区域文本：容器 name 是子树文本的重复。
          val text        =
            if (role == "StaticText" || role == "LabelText") name else ""
          val kids        = node.childIds.toOption
            .getOrElse(js.Array[String]())
            .toList
            .flatMap(child => build(child, path :+ role, Some(uid)))
          val repeat      = kids.groupBy(_.role).map { case (kidRole, list) => kidRole -> list.size }
          val withRepeat  = kids.map(kid => kid.copy(siblingRepeat = repeat.getOrElse(kid.role, 0)))
          val contentLen  = text.length + (if (interactive) name.length else 0)
          AxFilter.RawNode(
            uid = uid,
            parentUid = parentUid,
            role = role,
            name = name,
            url = url,
            text = text,
            backendNodeId = node.backendDOMNodeId.toOption,
            interactive = interactive,
            states = if (interactive) statesOf(node, role) else Nil,
            children = withRepeat,
            subtreeTextLen = contentLen + withRepeat.map(_.subtreeTextLen).sum,
            interactiveCount = (if (interactive) 1 else 0) + withRepeat.map(_.interactiveCount).sum,
            siblingRepeat = 0,
            pathRoles = path
          )
        }
    nodes.headOption.flatMap(root => build(root.nodeId, Nil, None)).toList
  }

  /** 快照头部三行；紧凑与过滤两条渲染路径共用。 */
  private def snapshotHeader(handle: SessionHandle, page: PageInfoValue): String = {
    val container =
      if (handle.get.scrollContainer.nonEmpty) s" inner=${handle.get.scrollContainer}" else ""
    s"URL: ${if (page.url.nonEmpty) page.url else handle.get.url}\n" +
      s"Title: ${if (page.title.nonEmpty) page.title else handle.get.pageTitle}\n" +
      s"Viewport: ${page.viewportWidth.toInt}x${page.viewportHeight.toInt} " +
      s"scroll=(${page.scrollX.toInt}, ${page.scrollY.toInt}) pageHeight=${page.documentHeight.toInt}$container\n\n"
  }

  /** ref 在同一个文档内按 backendDOMNodeId 复用，并且只追加不复用，保证旧 ref 不会指向新元素。 */
  private def refFor(handle: SessionHandle, backendNodeId: Option[Int]): Option[String] =
    backendNodeId.flatMap { backend =>
      handle.get.nodeRefs.get(backend) match {
        case Some(existing) => Some(existing)
        case None           =>
          if (handle.get.refs.size >= MaxRefsPerDocument) None
          else {
            val ref = s"e${handle.get.nextRef}"
            handle.update(session =>
              session.copy(
                nextRef = session.nextRef + 1,
                refs = session.refs.updated(ref, backend),
                nodeRefs = session.nodeRefs.updated(backend, ref)
              )
            )
            Some(ref)
          }
      }
    }
  private def roleOf(node: AxNode): String                                              =
    node.role.toOption.flatMap(_.value.toOption).map(_.toString).filter(_.nonEmpty).getOrElse("generic")
  private def displayName(node: AxNode): String                                         =
    node.name.toOption
      .flatMap(_.value.toOption)
      .map(_.toString)
      .getOrElse("")
      .replaceAll("\\s+", " ")
      .trim
      .take(120)
  private def propertiesOf(node: AxNode): List[AxProperty]                              =
    node.properties.toOption.getOrElse(js.Array[AxProperty]()).toList

  /** 链接节点的真实地址（AX 树自带 `url` 属性），仅 link 且是 http(s) 时使用。 */
  private def linkUrlOf(node: AxNode, role: String): String                       =
    if (role != "link") ""
    else
      propertyOf(node, "url")
        .map(_.toString.trim)
        .filter(url => url.startsWith("http"))
        .getOrElse("")
  private def propertyOf(node: AxNode, name: String): Option[js.Any]              =
    propertiesOf(node).find(_.name == name).flatMap(_.value.value.toOption)
  private def booleanProperty(node: AxNode, name: String): Option[Boolean] =
    // js.Any 与 scala.Boolean 在 Scala.js 里没有子类型关系，只能按 js.typeOf 判定后再转换。
    // Chrome 对部分属性给 boolean，对部分属性（如 checked）给 "true"/"false" 字符串，两种都认。
    propertyOf(node, name).flatMap { value =>
      if (js.typeOf(value) == "boolean") Some(JsBridge.asBoolean(value))
      else if (js.typeOf(value) == "string") {
        value.toString.trim match {
          case "true"  => Some(true)
          case "mixed" => Some(true)
          case _       => Some(false)
        }
      } else None
    }
  private def isInteractive(node: AxNode, role: String): Boolean                  =
    !NonInteractiveRoles.contains(role) &&
      (booleanProperty(node, "focusable").exists(identity) ||
        propertyOf(node, "editable").isDefined ||
        InteractiveRoles.contains(role))
  private def statesOf(node: AxNode, role: String): List[String]                  = {
    val states = mutable.ListBuffer.empty[String]
    if (booleanProperty(node, "disabled").exists(identity)) states += "disabled"
    if (booleanProperty(node, "focused").exists(identity)) states += "focused"
    // 无名可编辑容器（contenteditable 富文本）在快照里没有别的提示，标一下让模型知道能输入。
    if (!InteractiveRoles.contains(role) && propertyOf(node, "editable").isDefined) states += "editable"
    if (CheckableRoles.contains(role)) {
      propertyOf(node, "checked") match {
        case None        => ()
        case Some(value) =>
          stateOfChecked(value).foreach(states += _)
      }
    }
    states.toList
  }
  private def stateOfChecked(value: js.Any): Option[String]                       =
    if (js.typeOf(value) == "boolean") Some(if (JsBridge.asBoolean(value)) "checked" else "unchecked")
    else
      value.toString.trim match {
        case "true"  => Some("checked")
        case "false" => Some("unchecked")
        case other   => Some(s"checked=$other")
      }
  // ---------------------------------------------------------------- 工具函数
  private def normalizeUrl(rawUrl: String): Option[String]                        = {
    val trimmed = rawUrl.trim
    if (trimmed.isEmpty) None
    else {
      val withScheme =
        if (trimmed.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) trimmed
        else if (trimmed.startsWith("about:") || trimmed.startsWith("data:")) trimmed
        else s"https://$trimmed"
      if (withScheme.matches("(?i)^https?://.+")) Some(withScheme)
      else if (withScheme.startsWith("about:") || withScheme.startsWith("data:")) Some(withScheme)
      else None
    }
  }
  private def normalizeDirection(rawDirection: String): Option[String]            =
    rawDirection.trim.toLowerCase.replace("page_", "").replace("scroll_", "") match {
      case "up" | "upwards"     => Some("up")
      case "down" | "downwards" => Some("down")
      case "left"               => Some("left")
      case "right"              => Some("right")
      case _                    => None
    }
  private def stringArg(args: js.Dictionary[js.Any], key: String): Option[String] =
    args.get(key).map(_.toString.trim).filter(_.nonEmpty)
  private def numberArg(args: js.Dictionary[js.Any], key: String): Option[Double] =
    args.get(key).flatMap { value =>
      if (js.typeOf(value) == "number") Some(JsBridge.asDouble(value))
      else value.toString.trim.toDoubleOption
    }
  private def boolArg(args: js.Dictionary[js.Any], key: String): Boolean          =
    args.get(key).exists { value =>
      if (js.typeOf(value) == "boolean") JsBridge.asBoolean(value)
      else value.toString.trim.equalsIgnoreCase("true")
    }

  /** 数组参数：接受 ["a","b"]、单个字符串，以及 JSON 数组字符串（模型偶尔会把数组写成字符串）。 */
  private def stringListArg(args: js.Dictionary[js.Any], key: String): List[String] =
    args.get(key).toList.flatMap { value =>
      if (js.isUndefined(value) || value == null) Nil
      else if (js.Array.isArray(value)) {
        JsBridge.asArray(value).toList.flatMap(item =>
          Option(item).map(_.toString.trim).filter(_.nonEmpty)
        )
      } else {
        val text = value.toString.trim
        if (text.isEmpty) Nil
        else if (text.startsWith("[")) {
          try {
            JsBridge.asArray(js.JSON.parse(text)).toList
              .flatMap(item => Option(item).map(_.toString.trim).filter(_.nonEmpty))
          } catch { case _: Throwable => List(text) }
        } else List(text)
      }
    }

  /**
   * 等 tab 加载完。先查一次当前状态：页面可能在我们挂监听之前就已经 complete（搜索/导航后很常见）， 只有确实还在加载时才挂 onUpdated 监听，避免白等到超时。
   */
  private def waitForTabLoaded(tabId: Int, timeoutMs: Int): Future[Unit]            =
    ChromeAPI.tabs.get(tabId).toFuture
      .map(tab => tab.status.toOption.contains("complete") && tab.url.toOption.exists(_.startsWith("http")))
      .recover { case _ => false }
      .flatMap {
        case true  => Future.successful(())
        case false => waitForTabLoadedEvent(tabId, timeoutMs)
      }
  private def waitForTabLoadedEvent(tabId: Int, timeoutMs: Int): Future[Unit]       = {
    val p                                                          = Promise[Unit]()
    var done                                                       = false
    var timeoutHandle: js.UndefOr[js.timers.SetTimeoutHandle]      = js.undefined
    lazy val listener: js.Function3[Int, TabChangeInfo, Tab, Unit] = (id, changeInfo, _) => {
      if (id == tabId && changeInfo.status.contains("complete") && !done) {
        done = true
        timeoutHandle.toOption.foreach(js.timers.clearTimeout)
        timeoutHandle = js.undefined
        ChromeAPI.tabs.onUpdated.removeListener(listener)
        p.success(())
      }
    }
    ChromeAPI.tabs.onUpdated.addListener(listener)
    timeoutHandle = js.timers.setTimeout(timeoutMs) {
      if (!done) {
        done = true
        ChromeAPI.tabs.onUpdated.removeListener(listener)
        p.failure(new Exception(s"Page load timeout: $tabId"))
      }
    }
    p.future
  }
  private def settle(ms: Int): Future[Unit]                                         = {
    val p = Promise[Unit]()
    js.timers.setTimeout(ms)(p.success(()))
    p.future
  }
  private def withSessionLock[A](sessionId: String)(body: => Future[A]): Future[A]  = {
    val previous = sessionLocks.get(sessionId).map(_.future).getOrElse(Future.successful(()))
    val release  = Promise[Unit]()
    sessionLocks.update(sessionId, release)
    previous.flatMap { _ =>
      val task =
        try body
        catch { case e: Throwable => Future.failed(e) }
      task.transform { result =>
        release.trySuccess(())
        if (sessionLocks.get(sessionId).contains(release)) sessionLocks.remove(sessionId)
        result
      }
    }
  }
  private def withTimeout[A](label: String, ms: Int)(body: => Future[A]): Future[A] = {
    val p      = Promise[A]()
    var done   = false
    val handle = js.timers.setTimeout(ms) {
      if (!done) {
        done = true
        p.tryFailure(new Exception(s"$label timed out after ${ms}ms; the page may be unreachable"))
      }
    }
    (try body
    catch { case e: Throwable => Future.failed(e) }).onComplete { result =>
      if (!done) {
        done = true
        js.timers.clearTimeout(handle)
        p.tryComplete(result)
      }
    }
    p.future
  }
}
