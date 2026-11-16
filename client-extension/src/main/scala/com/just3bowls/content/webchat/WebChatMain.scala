package com.just3bowls.content.webchat

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.{
  StorageChange,
  WebChatActionResponse,
  WebChatBadgePosition,
  WebChatCapsuleState,
  WebChatListResponse,
  WebChatSessionSummary,
  WebChatStateMessage
}
import com.just3bowls.ui.content.webchat.{
  WebChatSessionView,
  WebChatPanelPlacement,
  WebChatToastView,
  WebChatUrlView,
  WebChatView,
  WebChatViewCallbacks,
  WebChatViewState
}
import com.raquo.laminar.api.L.{render => laminarRender, _}

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** 网页原生会话在 Provider 页面中的控制层；视图由 ui/content/webchat 的 Laminar 组件负责。 */
object WebChatMain {
  private val BadgePosKey            = "thWebChatBadgePos"
  private val SessionBindDelay       = 250.0
  private val SessionTitleClickDelay = 220.0
  private val SessionsRefresh        = 1500.0
  private val ToastMs                = 8000.0

  @JSExportTopLevel(name = "webChatMain", moduleID = "webchat")
  def webChatMain(): Unit = {
    val document       = JsBridge.document
    val globalObj      = JsBridge.globalThis
    val alreadyMounted = JsBridge.asBoolean(globalObj.hasOwnProperty("__thWebChatMounted"))
    if (!alreadyMounted) {
      JsBridge.update(globalObj, "__thWebChatMounted", JsBridge.asAny(true))
      val chrome = JsBridge.chrome

      var root: js.Any                          = null
      var state: WebChatCapsuleState            =
        JsBridge
          .obj("supported" -> (false), "capsuleEnabled" -> (false), "collapsed" -> (true))
          .asInstanceOf[WebChatCapsuleState]
      var sessions: List[WebChatSessionSummary] = Nil
      var sessionsLoaded                        = false
      var listError                             = ""
      var actionError                           = ""
      var confirmingDelete                      = ""
      var renamingId                            = ""
      var renameDraft                           = ""
      var collapsed                             = true
      var lastSessionsFetch                     = 0.0
      val expandedSessionIds                    = mutable.Set.empty[String]
      val seenErrorIds                          = mutable.LinkedHashSet.empty[String]
      val toastTimers                           = mutable.Map.empty[String, js.timers.SetTimeoutHandle]

      var posLeft                = -1.0
      var posTop                 = -1.0
      var dragOffsetX            = 0.0
      var dragOffsetY            = 0.0
      var dragging               = false
      var dragged                = false
      var panelOpenLeft          = true
      var panelOpenAbove         = true
      var sessionBindTimer       = Option.empty[js.timers.SetTimeoutHandle]
      var sessionBindId          = ""
      var sessionTitleClickTimer = Option.empty[js.timers.SetTimeoutHandle]
      var sessionTitleClickId    = ""

      val stateVar  = Var(WebChatViewState(
        supported = false,
        capsuleEnabled = false,
        collapsed = true,
        associated = false,
        behind = false,
        error = "",
        title = "",
        provider = "",
        sessionId = "",
        canTakeover = false,
        agentRunning = false,
        browserTools = false,
        sessionsLoaded = false,
        sessions = Nil,
        listError = "",
        actionError = "",
        confirmingDelete = "",
        renamingId = "",
        renameDraft = "",
        expandedSessionIds = Set.empty,
        panelPlacement = WebChatPanelPlacement(openLeft = true, openAbove = true)
      ))
      val toastsVar = Var(List.empty[WebChatToastView])

      def text(value: js.Any): String =
        if (value == null || js.isUndefined(value)) "" else value.toString

      def urlsOf(session: WebChatSessionSummary): List[WebChatUrlView] =
        session.urls
          .toOption
          .toList
          .flatMap(_.toList)
          .map(item =>
            WebChatUrlView(
              url = item.url.getOrElse(""),
              provider = item.provider.getOrElse(""),
              current = item.isCurrent.getOrElse(false),
              last = item.isLast.getOrElse(false)
            )
          )

      def snapshot(): WebChatViewState =
        WebChatViewState(
          supported = state.supported.getOrElse(false),
          capsuleEnabled = state.capsuleEnabled.getOrElse(false),
          collapsed = collapsed,
          associated = state.associated.getOrElse(false),
          behind = state.behind.getOrElse(false),
          error = state.error.getOrElse(""),
          title = state.title.getOrElse(""),
          provider = state.provider.getOrElse(""),
          sessionId = state.sessionId.getOrElse(""),
          canTakeover = state.canTakeover.getOrElse(false),
          agentRunning = state.agentRunning.getOrElse(false),
          browserTools = state.browserTools.getOrElse(false),
          sessionsLoaded = sessionsLoaded,
          sessions = sessions.map(session =>
            WebChatSessionView(
              id = session.id.getOrElse(""),
              title = session.title.getOrElse(""),
              urls = urlsOf(session)
            )
          ),
          listError = listError,
          actionError = actionError,
          confirmingDelete = confirmingDelete,
          renamingId = renamingId,
          renameDraft = renameDraft,
          expandedSessionIds = expandedSessionIds.toSet,
          panelPlacement = WebChatPanelPlacement(panelOpenLeft, panelOpenAbove)
        )

      def render(): Unit = stateVar.set(snapshot())

      def send(payload: js.Any): js.Promise[js.Any] =
        JsBridge.asPromiseAny(chrome.runtime.sendMessage(payload))

      def cancelSessionBind(): Unit = {
        sessionBindTimer.foreach(js.timers.clearTimeout)
        sessionBindTimer = None
        sessionBindId = ""
      }

      def cancelSessionTitleClick(): Unit = {
        sessionTitleClickTimer.foreach(js.timers.clearTimeout)
        sessionTitleClickTimer = None
        sessionTitleClickId = ""
      }

      def toggleSessionUrls(id: String): Unit = {
        if (expandedSessionIds.contains(id)) expandedSessionIds.remove(id)
        else expandedSessionIds.add(id)
        render()
      }

      def runAction(payload: js.Any): Unit = {
        cancelSessionTitleClick()
        confirmingDelete = ""
        renamingId = ""
        val onDone: js.Function1[js.Any, js.Any]      = js.Any.fromFunction1 { (result: js.Any) =>
          val data = result.asInstanceOf[WebChatActionResponse]
          val ok   = data.ok.getOrElse(false)
          actionError = if (ok) "" else data.error.getOrElse("")
          refreshSessions(force = true)
          data.state.toOption match {
            case Some(next) => applyState(next)
            case None       => render()
          }
          js.undefined
        }
        val onFailed: js.Function1[scala.Any, js.Any] = js.Any.fromFunction1 { (_: scala.Any) =>
          actionError = "Action failed - background unreachable"
          render()
          js.undefined
        }
        send(payload).`then`(onDone, onFailed)
      }

      def refreshSessions(force: Boolean = false): Unit = {
        val now = js.Date.now()
        if (!force && now - lastSessionsFetch < SessionsRefresh) ()
        else {
          lastSessionsFetch = now
          sessionsLoaded = false
          val onListed: js.Function1[js.Any, js.Any]    = js.Any.fromFunction1 { (result: js.Any) =>
            val data = result.asInstanceOf[WebChatListResponse]
            val ok   = data.ok.getOrElse(false)
            listError = if (ok) "" else data.error.getOrElse("")
            if (ok) {
              sessions =
                data.sessions.getOrElse(js.Array[WebChatSessionSummary]()).toList
              val expandable = sessions.iterator.filter(session => urlsOf(session).nonEmpty).map(s =>
                s.id.getOrElse("")
              ).toSet
              expandedSessionIds.filterInPlace(expandable.contains)
            }
            sessionsLoaded = true
            render()
            js.undefined
          }
          val onFailed: js.Function1[scala.Any, js.Any] = js.Any.fromFunction1 { (_: scala.Any) =>
            listError = "Background unreachable - sessions not refreshed"
            sessionsLoaded = true
            render()
            js.undefined
          }
          send(JsBridge.obj("type" -> ("WEBCHAT_LIST_SESSIONS"))).`then`(onListed, onFailed)
        }
      }

      def saveRename(): Unit = {
        val id    = renamingId
        val title = renameDraft.trim
        renamingId = ""
        if (id.isEmpty || title.isEmpty) render()
        else runAction(JsBridge.obj("type" -> ("WEBCHAT_RENAME_SESSION"), "sessionId" -> (id), "title" -> (title)))
      }

      def showErrorToast(id: String, summary: String, detail: String): Unit = {
        val errorId = id.trim
        val body    = summary.trim
        if (errorId.nonEmpty && body.nonEmpty && !seenErrorIds.contains(errorId)) {
          seenErrorIds += errorId
          while (seenErrorIds.size > 64) seenErrorIds.remove(seenErrorIds.head)
          val fullDetail = Option(detail).map(_.trim).filter(_.nonEmpty).getOrElse(body)
          val toast      = WebChatToastView(errorId, body, fullDetail)
          toastsVar.update(items => (toast :: items).take(3))
          toastTimers.get(errorId).foreach(js.timers.clearTimeout)
          toastTimers.update(errorId, js.timers.setTimeout(ToastMs)(dismissToast(errorId)))
        }
      }

      def dismissToast(id: String): Unit = {
        toastTimers.remove(id).foreach(js.timers.clearTimeout)
        toastsVar.update(_.filterNot(_.id == id))
      }

      def copyToast(toast: WebChatToastView): Unit =
        try {
          JsBridge.navigator.clipboard
            .writeText(toast.detail)
            .`then`(js.Any.fromFunction1((_: js.Any) => js.undefined))
        } catch { case _: Throwable => () }

      def applyState(next: WebChatCapsuleState): Unit = {
        state = next
        if (state.supported.getOrElse(false) && state.capsuleEnabled.getOrElse(false))
          showErrorToast(
            state.errorId.getOrElse(""),
            state.error.getOrElse(""),
            state.errorDetail.getOrElse("")
          )
        render()
        if (!collapsed) refreshSessions()
      }

      def queryState(): Unit =
        send(JsBridge.obj("type" -> ("WEBCHAT_QUERY_STATE"))).`then`(
          js.Any.fromFunction1 { (result: js.Any) =>
            val data = result.asInstanceOf[WebChatActionResponse]
            data.state.foreach(applyState)
            js.undefined
          }
        )

      def applyPosition(left: Double, top: Double): Unit = {
        val width       = JsBridge.asDouble(JsBridge.window.innerWidth)
        val height      = JsBridge.asDouble(JsBridge.window.innerHeight)
        val rect        =
          if (root == null) null
          else JsBridge.asObject(JsBridge.call(root, "getBoundingClientRect"))
        val badgeWidth  =
          if (rect == null) 120.0
          else JsBridge.asDouble(rect.width)
        val badgeHeight =
          if (rect == null) 30.0
          else JsBridge.asDouble(rect.height)
        posLeft = math.max(8.0, math.min(left, math.max(8.0, width - badgeWidth - 8.0)))
        posTop = math.max(8.0, math.min(top, math.max(8.0, height - badgeHeight - 8.0)))
        if (root != null) {
          val style = JsBridge.field(root, "style")
          JsBridge.update(style, "left", s"${posLeft}px")
          JsBridge.update(style, "top", s"${posTop}px")
          JsBridge.update(style, "right", "auto")
          JsBridge.update(style, "bottom", "auto")
        }
      }

      def updatePanelPlacement(): Unit =
        if (root != null) {
          val viewportWidth  = JsBridge.asDouble(JsBridge.window.innerWidth)
          val viewportHeight = JsBridge.asDouble(JsBridge.window.innerHeight)
          val rect           = JsBridge.asObject(JsBridge.call(root, "getBoundingClientRect"))
          val left           = JsBridge.asDouble(rect.left)
          val right          = JsBridge.asDouble(rect.right)
          val roomLeft       = left
          val roomRight      = viewportWidth - right
          val panelWidth     = math.min(300.0, math.max(0.0, viewportWidth - 24.0))
          panelOpenLeft =
            if (roomRight >= panelWidth + 6.0) false
            else if (roomLeft >= panelWidth + 6.0) true
            else roomLeft >= roomRight
          panelOpenAbove = JsBridge.asDouble(rect.top) > viewportHeight / 2.0
        }

      def applyBadgePlacement(left: Double, top: Double): Unit = {
        applyPosition(left, top)
        updatePanelPlacement()
      }

      def persistBadge(): Unit =
        chrome.storage.local
          .set(JsBridge.obj("thWebChatBadgePos" -> (JsBridge.obj(
            "left"      -> (posLeft),
            "top"       -> (posTop),
            "collapsed" -> (collapsed)
          ))))
          .`then`(js.Any.fromFunction1((_: js.Any) => js.undefined))

      def loadBadge(): Unit =
        chrome.storage.local.get(BadgePosKey).`then`(
          js.Any.fromFunction1 { (value: js.Any) =>
            val raw = JsBridge.field(value, BadgePosKey)
            if (raw != null && !js.isUndefined(raw)) {
              val stored = raw.asInstanceOf[WebChatBadgePosition]
              stored.collapsed.toOption.foreach(collapsed = _)
              val left   = stored.left.getOrElse(-1.0)
              val top    = stored.top.getOrElse(-1.0)
              if (left >= 0 && top >= 0) applyBadgePlacement(left, top)
              else updatePanelPlacement()
            }
            render()
            js.undefined
          }
        )

      val callbacks = WebChatViewCallbacks(
        toggleCapsule = () =>
          if (!dragged) {
            cancelSessionBind()
            cancelSessionTitleClick()
            collapsed = !collapsed
            confirmingDelete = ""
            renamingId = ""
            renameDraft = ""
            actionError = ""
            if (!collapsed) refreshSessions(force = true)
            updatePanelPlacement()
            render()
            persistBadge()
          },
        startDrag = event => {
          if (root != null) {
            val rect = JsBridge.asObject(JsBridge.call(root, "getBoundingClientRect"))
            val left = JsBridge.asDouble(rect.left)
            val top  = JsBridge.asDouble(rect.top)
            applyPosition(left, top)
            dragOffsetX = JsBridge.asDouble(JsBridge.field(event, "clientX")) - left
            dragOffsetY = JsBridge.asDouble(JsBridge.field(event, "clientY")) - top
            dragging = true
            dragged = false
          }
        },
        bindSession = id => {
          cancelSessionBind()
          cancelSessionTitleClick()
          sessionBindId = id
          sessionBindTimer = Some(js.timers.setTimeout(SessionBindDelay) {
            val target = sessionBindId
            cancelSessionBind()
            if (target.nonEmpty) runAction(JsBridge.obj("type" -> ("WEBCHAT_ASSIGN"), "sessionId" -> (target)))
          })
        },
        clickSessionTitle = id => {
          cancelSessionTitleClick()
          sessionTitleClickId = id
          sessionTitleClickTimer = Some(js.timers.setTimeout(SessionTitleClickDelay) {
            val target = sessionTitleClickId
            cancelSessionTitleClick()
            if (target.nonEmpty && sessions.exists(session => text(JsBridge.field(session, "id")) == target))
              toggleSessionUrls(target)
          })
        },
        renameSession = id => {
          cancelSessionBind()
          cancelSessionTitleClick()
          sessions.find(session => text(JsBridge.field(session, "id")) == id).foreach { session =>
            renamingId = id
            renameDraft = text(JsBridge.field(session, "title"))
            render()
          }
        },
        changeRenameDraft = value => { renameDraft = value },
        saveRename = () => saveRename(),
        cancelRename = () => {
          renamingId = ""
          render()
        },
        requestDelete = id => {
          cancelSessionBind()
          cancelSessionTitleClick()
          confirmingDelete = id
          render()
        },
        cancelDelete = () => {
          confirmingDelete = ""
          render()
        },
        deleteSession = id => runAction(JsBridge.obj("type" -> ("WEBCHAT_DELETE_SESSION"), "sessionId" -> (id))),
        newSession = () => runAction(JsBridge.obj("type" -> ("WEBCHAT_NEW_SESSION"))),
        toggleUrls = id => {
          cancelSessionTitleClick()
          toggleSessionUrls(id)
        },
        openUrl = url => {
          cancelSessionBind()
          cancelSessionTitleClick()
          runAction(JsBridge.obj("type" -> ("WEBCHAT_FOCUS_URL"), "url" -> (url)))
        },
        stopAgent = () =>
          runAction(JsBridge.obj(
            "type"      -> ("WEBCHAT_STOP_RUN"),
            "sessionId" -> (state.sessionId.getOrElse(""))
          )),
        toggleBrowserTools = () =>
          runAction(JsBridge.obj(
            "type" -> ("WEBCHAT_SET_BROWSER_TOOLS"),
            "on"   -> (!state.browserTools.getOrElse(false))
          )),
        copyToast = copyToast,
        dismissToast = dismissToast
      )

      val listener: js.Function3[js.Any, js.Any, js.Any, js.Any] =
        (message: js.Any, _, _) => {
          val typed = message.asInstanceOf[WebChatStateMessage]
          if (typed.`type`.contains("WEBCHAT_STATE")) typed.state.foreach(applyState)
          js.undefined
        }
      chrome.runtime.onMessage.addListener(listener)

      val onStorageChanged: js.Function2[js.Dictionary[js.Any], String, Unit] = (changes, area) => {
        if (area == "local" && changes.contains(BadgePosKey)) {
          val raw = changes(BadgePosKey).asInstanceOf[StorageChange].newValue
          if (raw != null && !js.isUndefined(raw)) {
            val stored        = raw.asInstanceOf[WebChatBadgePosition]
            val nextCollapsed = stored.collapsed.toOption.getOrElse(collapsed)
            var needsRender   = false
            var positioned    = false
            if (nextCollapsed != collapsed) {
              cancelSessionBind()
              cancelSessionTitleClick()
              collapsed = nextCollapsed
              confirmingDelete = ""
              renamingId = ""
              renameDraft = ""
              actionError = ""
              if (!collapsed) refreshSessions(force = true)
              needsRender = true
            }
            if (!dragging) {
              val left = JsBridge.undefinedDouble(stored.left).getOrElse(posLeft)
              val top  = JsBridge.undefinedDouble(stored.top).getOrElse(posTop)
              if (left >= 0 && top >= 0) {
                applyBadgePlacement(left, top)
                positioned = true
                if (!collapsed) needsRender = true
              }
            }
            if (!positioned) updatePanelPlacement()
            if (needsRender) render()
          }
        }
      }
      chrome.storage.onChanged.addListener(onStorageChanged)

      val onPointerMove: js.Function1[js.Any, Unit] = (event: js.Any) => {
        if (dragging) {
          val mouse = JsBridge.asObject(event)
          applyPosition(
            JsBridge.asDouble(mouse.clientX) - dragOffsetX,
            JsBridge.asDouble(mouse.clientY) - dragOffsetY
          )
          dragged = true
        }
      }
      val onPointerUp: js.Function1[js.Any, Unit]   = (_: js.Any) => {
        if (dragging) {
          dragging = false
          if (dragged) {
            applyBadgePlacement(posLeft, posTop)
            if (!collapsed) render()
            persistBadge()
          }
        }
      }
      document.addEventListener("pointermove", onPointerMove)
      document.addEventListener("pointerup", onPointerUp)

      val onResize: js.Function1[js.Any, Unit] = (_: js.Any) => {
        if (posLeft >= 0 && posTop >= 0) {
          val beforeLeft = posLeft
          val beforeTop  = posTop
          applyBadgePlacement(beforeLeft, beforeTop)
          if (!collapsed) render()
          if (posLeft != beforeLeft || posTop != beforeTop) persistBadge()
        }
      }
      JsBridge.window.addEventListener("resize", onResize)

      def removeStaleRoots(): Unit = {
        val stale = document.querySelectorAll("[data-th-webchat-root], [data-th-webchat-toasts]")
        val total = JsBridge.asInt(stale.length)
        var i     = 0
        while (i < total) {
          val node = stale.item(i)
          if (node != null && node.parentNode != null) node.parentNode.removeChild(node)
          i += 1
        }
      }

      def mount(): Unit = {
        removeStaleRoots()
        root = document.createElement("div")
        JsBridge.update(
          JsBridge.field(root, "style"),
          "cssText",
          "all:initial;box-sizing:border-box;position:fixed;right:14px;bottom:14px;z-index:2147483647;" +
            "user-select:none;display:block;"
        )
        JsBridge.call(root, "setAttribute", "data-th-webchat-root", "1")
        document.body.appendChild(root)
        val shadow   = JsBridge.call(root, "attachShadow", JsBridge.obj("mode" -> ("open")))
        val style    = document.createElement("style")
        style.textContent =
          ":host{all:initial;box-sizing:border-box;text-align:left;}" +
            "*,*::before,*::after{box-sizing:border-box;}" +
            "button,input,textarea,select{font:inherit;}"
        JsBridge.call(shadow, "appendChild", style)
        val host     = document.createElement("div")
        host.style.cssText = "display:block;"
        JsBridge.call(shadow, "appendChild", host)
        laminarRender(
          host.asInstanceOf[org.scalajs.dom.Element],
          div(
            child <-- stateVar.signal.combineWith(toastsVar.signal).map { case (viewState, toasts) =>
              WebChatView.render(viewState, toasts, callbacks)
            }
          )
        )
        if (posLeft >= 0 && posTop >= 0) applyPosition(posLeft, posTop)
        else loadBadge()
        updatePanelPlacement()
        queryState()
        var lastHref = JsBridge.asString(JsBridge.location.href)
        js.timers.setInterval(1200) {
          val href = JsBridge.asString(JsBridge.location.href)
          if (href != lastHref) {
            lastHref = href
            queryState()
          }
        }
      }

      val readyState = JsBridge.undefinedString(document.readyState).getOrElse("")
      if (readyState == "loading")
        document.addEventListener("DOMContentLoaded", js.Any.fromFunction1((_: js.Any) => mount()))
      else mount()
    }
  }
}
