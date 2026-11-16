package com.just3bowls.content.location

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.JsonValue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import com.just3bowls.model.localapi.PageScriptProtocol
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.ui.content.markers.{MarkerBoxView, MarkerOverlayState, MarkerOverlayView, MarkerRingView}
import com.raquo.laminar.api.L.{Var, render => laminarRender, _}
object LocationMain {
  @JSExportTopLevel(name = "locationMain", moduleID = "location")
  def locationMain(): Unit = {
    PopCanLog.init()
    val chrome                  = JsBridge.chrome
    val document                = JsBridge.document
    // 页面上下文级单例：background 现在会按存活状态 ensure/inject，重复注入必须是幂等的；
    // 否则每注入一次都会多一套 observer、message listener 和 marker box。
    val globalObj               = JsBridge.globalThis
    if (JsBridge.asBoolean(globalObj.hasOwnProperty("__thLocationMounted"))) return
    JsBridge.update(globalObj, "__thLocationMounted", JsBridge.asAny(true))
    JsBridge.update(globalObj, "__thLocationProtocol", JsBridge.asAny(PageScriptProtocol.LocationVersion))
    var markerObserver: js.Any  = null
    var markerRefreshGeneration = 0
    val markerHealCooldowns     = scala.collection.mutable.Map.empty[String, Double]
    val markerBoxStates         = scala.collection.mutable.Map.empty[String, MarkerBoxView]
    var markerRings             = List.empty[MarkerRingView]
    val markerViewVar           = Var(MarkerOverlayState(Nil, Nil))
    var silentMarkers           = false
    var markerBoxSyncStarted    = false
    var markerHealthInterval: js.UndefOr[js.timers.SetIntervalHandle] = js.undefined
    var lastMarkerRecoveryAt                                          = 0.0
    var lastLoggedMissingRoles                                        = Set.empty[String]
    val markerRegistry                        = scala.collection.mutable.Map.empty[String, js.Any]
    var requiredMarkerRoles                   = List("new-chat", "input")
    mountMarkerOverlay()
    def originOf(url: String): String         =
      try JsBridge.asString(JsBridge.newInstance(JsBridge.urlConstructor)(url).origin)
      catch { case _: Throwable => "" }
    def logFlow(message: String): Unit        = {
      PopCanLog.debug("location", message)
      try {
        chrome.runtime.sendMessage(JsBridge.obj("type" -> ("LOCATION_FLOW_LOG"), "message" -> (message)))
      } catch {
        case _: Throwable => ()
      }
    }
    def markRole(selector: String, role: String, fingerprint: js.Any, force: Boolean = false): Unit = {
      if (selector.isEmpty || (!force && markerExists(role))) return
      ensureMarkerStyles()
      if (force) {
        val stale = document.querySelectorAll(s"""[data-th-role="$role"]""")
        val count = JsBridge.asInt(stale.length)
        var i     = 0
        while (i < count) {
          stale.item(i).removeAttribute("data-th-role")
          i += 1
        }
        markerRegistry.remove(role)
        removeMarkerBox(role)
      }
      try {
        val el     = ElementFingerprint.findRole(selector, role, fingerprint)
        val found  = el != null && !js.isUndefined(el)
        val detail =
          if (found) {
            val tag       = JsBridge.asString(JsBridge.field(el, "tagName"))
            val rawClass  = JsBridge.field(el, "className")
            val className =
              if (rawClass == null || js.isUndefined(rawClass)) ""
              else rawClass.toString
            s" tag=$tag class=$className"
          } else {
            ""
          }
        PopCanLog.debug("location", s"mark role=$role selector=${PopCanLog.short(selector)} found=$found$detail")
        logFlow(s"mark role=$role selector=$selector found=$found$detail")
        if (found) {
          JsBridge.call(el, "setAttribute", "data-th-role", role)
          markerRegistry.update(role, el)
          syncMarkerBoxes()
        } else if (fingerprint != null && !js.isUndefined(fingerprint)) {
          val now         = js.Date.now()
          val cooldownKey = s"$role:$selector"
          val canHeal     = markerHealCooldowns.get(cooldownKey).forall(_ + 3000 < now)
          if (canHeal) {
            markerHealCooldowns.update(cooldownKey, now)
            PopCanLog.warn("location", s"heal failed role=$role selector=${PopCanLog.short(selector)}")
            logFlow(s"heal failed role=$role selector=$selector")
          }
        }
      } catch {
        case e: Throwable =>
          PopCanLog.error("location", s"mark failed role=$role selector=${PopCanLog.short(selector)}", e)
          logFlow(s"mark failed role=$role selector=$selector error=${e.getMessage}")
      }
    }
    def ensureMarkerStyles(): Unit            = {
      val oldStyle = document.querySelector("#popcan-marker-styles")
      if (
        oldStyle != null && !js.isUndefined(oldStyle) &&
        oldStyle.parentNode != null && !js.isUndefined(oldStyle.parentNode)
      ) {
        oldStyle.parentNode.removeChild(oldStyle)
      }
      if (document.querySelector("#popcan-location-marker-styles") != null) return
      val style    = document.createElement("style")
      style.id = "popcan-location-marker-styles"
      style.textContent =
        """
          |.popcan-marker-box {
          |  position: fixed;
          |  z-index: 2147483645;
          |  pointer-events: none;
          |  border: 1px solid transparent;
          |  box-sizing: border-box;
          |}
          |@keyframes popcan-role-flash {
          |  from { opacity: 1; box-shadow: 0 0 0 3px currentColor; }
          |  to   { opacity: 0; box-shadow: 0 0 0 8px transparent; }
          |}
          |.popcan-marker-flash-ring {
          |  position: fixed;
          |  z-index: 2147483646;
          |  pointer-events: none;
          |  box-sizing: border-box;
          |  border: 2px solid transparent;
          |  border-radius: 4px;
          |  animation: popcan-role-flash 800ms ease-out forwards;
          |}
          |.popcan-marker-flash {
          |  animation: popcan-label-flash 600ms ease-out;
          |}
          |@keyframes popcan-label-flash {
          |  0%   { transform: scale(1);   filter: brightness(1); }
          |  40%  { transform: scale(1.15); filter: brightness(1.35); }
          |  100% { transform: scale(1);   filter: brightness(1); }
          |}
          |.popcan-marker-label {
          |  position: absolute;
          |  top: -19px;
          |  left: -1px;
          |  padding: 1px 6px;
          |  border-radius: 3px;
          |  color: #fff;
          |  font: 10px/14px system-ui, sans-serif;
          |  letter-spacing: 0;
          |  white-space: nowrap;
          |}
          |""".stripMargin
      document.head.appendChild(style)
    }
    def markerRoleColor(role: String): String =
      role match {
        case "new-chat" => "#16a34a"
        case "input"    => "#2563eb"
        case _          => "#9333ea"
      }
    def markerRoleLabel(role: String): String =
      role match {
        case "new-chat" => "New Chat"
        case "input"    => "Input"
        case _          => "Marker"
      }

    /** 这几个角色只显示标签、不画常驻边框（边框只在 CDP 触发时短暂闪一下）。 */
    def markerRoleIsLabelOnly(role: String): Boolean =
      role == "new-chat" || role == "input"
    def publishMarkerOverlay(): Unit                 =
      markerViewVar.set(
        MarkerOverlayState(
          boxes = markerBoxStates.values.toList.sortBy(_.role),
          rings = markerRings
        )
      )

    def mountMarkerOverlay(): Unit =
      if (js.isUndefined(JsBridge.field(globalObj, "__thMarkerOverlayMounted"))) {
        JsBridge.update(globalObj, "__thMarkerOverlayMounted", JsBridge.asAny(true))
        val host = document.createElement("div")
        host.setAttribute("data-th-marker-overlay", "1")
        host.style.cssText = "position:fixed;inset:0;pointer-events:none;z-index:2147483646;"
        document.documentElement.appendChild(host)
        laminarRender(
          host.asInstanceOf[org.scalajs.dom.Element],
          div(child <-- markerViewVar.signal.map(MarkerOverlayView.render))
        )
      }

    def removeMarkerBox(role: String): Unit = {
      markerBoxStates.remove(role)
      publishMarkerOverlay()
    }

    def removeAllMarkerBoxes(): Unit = {
      markerBoxStates.clear()
      markerRings = Nil
      publishMarkerOverlay()
    }

    /**
     * CDP 真的点到 New Chat / 聚焦到 Input 时的一次性反馈：元素上一圈描边渐隐 + 标签闪一下。
     *
     * 只做临时元素，不等同于"给这些角色画常驻边框"。
     */
    def flashRole(role: String): Unit                          = {
      val el     = document.querySelector(s"""[data-th-role="$role"]""")
      if (el == null || js.isUndefined(el)) return
      val rect   = JsBridge.asObject(el.getBoundingClientRect())
      val width  = JsBridge.asDouble(rect.width)
      val height = JsBridge.asDouble(rect.height)
      if (width <= 0 || height <= 0) return
      val id     = s"$role-${js.Date.now().toLong}"
      markerRings = markerRings :+ MarkerRingView(
        id = id,
        color = markerRoleColor(role),
        left = JsBridge.asDouble(rect.left),
        top = JsBridge.asDouble(rect.top),
        width = width,
        height = height
      )
      markerBoxStates.get(role).foreach(box => markerBoxStates.update(role, box.copy(flash = true)))
      publishMarkerOverlay()
      js.timers.setTimeout(900) {
        markerRings = markerRings.filterNot(_.id == id)
        publishMarkerOverlay()
      }
      js.timers.setTimeout(700) {
        markerBoxStates.get(role).foreach(box => markerBoxStates.update(role, box.copy(flash = false)))
        publishMarkerOverlay()
      }
    }
    def syncMarkerBoxes(): Unit                                = {
      var changed = false
      requiredMarkerRoles.foreach { role =>
        val el = document.querySelector(s"""[data-th-role="$role"]""")
        if (el != null && !js.isUndefined(el)) {
          markerRegistry.update(role, el)
          val rect   = el.getBoundingClientRect()
          val width  = JsBridge.asDouble(rect.width)
          val height = JsBridge.asDouble(rect.height)
          if (!silentMarkers && width > 0 && height > 0) {
            val previous = markerBoxStates.get(role)
            val next     = MarkerBoxView(
              role = role,
              label = markerRoleLabel(role),
              color = markerRoleColor(role),
              labelOnly = markerRoleIsLabelOnly(role),
              left = JsBridge.asDouble(rect.left) - 1,
              top = JsBridge.asDouble(rect.top) - 1,
              width = width + 2,
              height = height + 2,
              flash = previous.exists(_.flash)
            )
            if (!previous.contains(next)) {
              markerBoxStates.update(role, next)
              changed = true
            }
          } else if (markerBoxStates.remove(role).nonEmpty) {
            changed = true
          }
        } else if (markerRegistry.remove(role).nonEmpty || markerBoxStates.remove(role).nonEmpty) {
          markerRegistry.remove(role)
          changed = true
        }
      }
      if (changed) publishMarkerOverlay()
    }
    def ensureMarkerBoxSyncing(): Unit                         = {
      if (!markerBoxSyncStarted) {
        markerBoxSyncStarted = true
        js.timers.setInterval(400)(syncMarkerBoxes())
        JsBridge.window.addEventListener(
          "resize",
          js.Any.fromFunction1(_ => syncMarkerBoxes())
        )
        document.addEventListener(
          "scroll",
          js.Any.fromFunction1(_ => syncMarkerBoxes()),
          true
        )
      }
      if (js.isUndefined(markerHealthInterval)) {
        // MutationObserver 是主路径；这个低频兜底覆盖 observer 丢失 / 静默 DOM 替换的情况。
        markerHealthInterval = js.timers.setInterval(1000) {
          val now = js.Date.now()
          if (requiredMarkersMissing() && now - lastMarkerRecoveryAt >= 1000) {
            lastMarkerRecoveryAt = now
            logMissingMarkers()
            refreshMarkers(quiet = true)
          }
        }
      }
    }
    def markerExists(role: String): Boolean                    = {
      val el = document.querySelector(s"""[data-th-role="$role"]""")
      el != null && !js.isUndefined(el)
    }
    def requiredMarkersMissing(): Boolean                      =
      requiredMarkerRoles.exists(role => !markerExists(role))
    def missingMarkerRoles(): List[String]                     =
      requiredMarkerRoles.filter(role => !markerExists(role))
    def logMissingMarkers(): Unit                              = {
      val missing = missingMarkerRoles().toSet
      if (missing != lastLoggedMissingRoles) {
        if (missing.nonEmpty) {
          val detail = s"roles=${missing.toList.sorted.mkString(",")}"
          PopCanLog.warn("location", s"marker missing detected $detail")
          logFlow(s"marker missing detected $detail")
        }
        lastLoggedMissingRoles = missing
      }
    }
    def markerRoleStates(): js.Dictionary[Boolean]             = {
      val dict = js.Dictionary.empty[Boolean]
      requiredMarkerRoles.foreach(role => dict.update(role, markerExists(role)))
      dict
    }
    def sendMarkerStatus(): Unit                               =
      chrome.runtime.sendMessage(JsBridge.obj(
        "type"     -> ("LOCATION_MARKERS_READY"),
        "protocol" -> (PageScriptProtocol.LocationVersion),
        "url"      -> (JsBridge.asString(JsBridge.location.href)),
        "roles"    -> (JsBridge.asObject(markerRoleStates()))
      ))
    def providerFingerprint(cfg: js.Any, role: String): js.Any = {
      val rawFingerprints = JsBridge.field(JsBridge.field(cfg, "page"), "fingerprints")
      if (rawFingerprints == null || js.isUndefined(rawFingerprints)) null
      else {
        val fp = JsBridge.field(rawFingerprints, role)
        if (fp == null || js.isUndefined(fp)) null else fp
      }
    }
    def matchesProviderOrigin(pageUrl: String): Boolean        = {
      val configuredOrigin = originOf(pageUrl)
      val currentOrigin    = originOf(JsBridge.asString(JsBridge.location.href))
      configuredOrigin.nonEmpty && configuredOrigin == currentOrigin
    }
    def markProviderRoles(data: js.Any): Unit                  = {
      val rawProviders = JsBridge.field(data, "parserProviders")
      if (rawProviders != null && !js.isUndefined(rawProviders)) {
        val keys             = JsBridge.asStringArray(JsBridge.objectConstructor.keys(rawProviders))
        var provider: js.Any = null
        keys.foreach { id =>
          val cfg     = JsonValue.field(rawProviders, id).getOrElse(js.undefined)
          val pageUrl =
            JsBridge.stringField(JsBridge.field(cfg, "page"), "url").getOrElse("")
          if (provider == null && matchesProviderOrigin(pageUrl)) {
            provider = cfg
          }
        }
        if (provider != null) {
          val page      = JsBridge.field(provider, "page")
          val selectors = JsBridge.field(page, "selectors")
          requiredMarkerRoles.foreach {
            case "new-chat" =>
              markRole(
                JsonValue.stringField(selectors, "new-chat").getOrElse(""),
                "new-chat",
                providerFingerprint(provider, "new-chat")
              )
            case "input"    =>
              markRole(
                JsBridge.stringField(selectors, "input").getOrElse(""),
                "input",
                providerFingerprint(provider, "input")
              )
            case _          => ()
          }
        }
      }
    }
    def normalizeProviderData(raw: js.Any): Unit               = {
      val existing = JsBridge.field(raw, "parserProviders")
      if (existing == null || js.isUndefined(existing)) {
        val dict = js.Dictionary.empty[js.Any]
        JsBridge.asStringArray(JsBridge.objectConstructor.keys(raw)).foreach { key =>
          if (key.startsWith("parserProvider:")) {
            val id = key.stripPrefix("parserProvider:")
            dict.update(id, JsBridge.asAny(JsBridge.field(raw, key)))
          }
        }
        JsBridge.update(raw, "parserProviders", JsBridge.asAny(dict))
      }
    }

    /**
     * 只读"标记渲染需要的 key"（provider 运行时配置 + 两个开关）。
     *
     * 以前这里是 `chrome.storage.local.get(null, …)` —— 每个 provider 页加载都会把整个扩展存储（曾 21.5MB、含状态表与全部 trace） 反序列化一遍。现在先用
     * `getKeys()` 定位，再只读这几条。
     */
    def loadConfigSnapshot(onReady: js.Any => Unit): Unit                                  = {
      val area                                             = JsBridge.chrome.storage.local
      def wanted(keys: js.Array[String]): js.Array[String] =
        keys.filter(key =>
          key.startsWith("parserProvider:")
        )
      def read(keys: js.Array[String]): Unit               =
        chrome.storage.local.get(
          js.Array(wanted(keys).toList*),
          js.Any.fromFunction1(raw => onReady(JsBridge.asObject(JsBridge.asAny(raw))))
        )
      if (js.isUndefined(area.getKeys)) {
        // 老 Chrome 没有 getKeys（很少见）：退回整份读取，行为与旧版一致。
        chrome.storage.local.get(null, js.Any.fromFunction1(raw => onReady(JsBridge.asObject(JsBridge.asAny(raw)))))
      } else {
        val handle: js.Function1[js.Array[String], Unit] = keys => read(keys)
        area.getKeys().`then`(handle)
        ()
      }
    }
    def refreshMarkers(quiet: Boolean = false): Unit                                       =
      loadConfigSnapshot { raw =>
        val data         = JsBridge.asObject(raw)
        normalizeProviderData(data)
        silentMarkers =
          JsBridge.undefinedBoolean(data.popCanCaptureSilent).getOrElse(false)
        markProviderRoles(data)
        syncMarkerBoxes()
        val stillMissing = missingMarkerRoles()
        if (stillMissing.nonEmpty) {
          val detail = s"roles=${stillMissing.mkString(",")}"
          if (!quiet) {
            PopCanLog.warn("location", s"markers still missing after refresh $detail")
            logFlow(s"markers still missing after refresh $detail")
          }
        } else if (!quiet) {
          PopCanLog.debug("location", "all required markers present")
          logFlow("all required markers present")
        }
        sendMarkerStatus()
      }
    def scheduleMarkerRefresh(): Unit                                                      = {
      markerRefreshGeneration += 1
      val generation = markerRefreshGeneration
      js.timers.setTimeout(150) {
        if (generation == markerRefreshGeneration && requiredMarkersMissing()) {
          logMissingMarkers()
          refreshMarkers()
        }
      }
    }
    def ensureMarkerObserver(): Unit                                                       = {
      if (markerObserver != null || js.isUndefined(JsBridge.mutationObserverConstructor)) return
      markerObserver = JsBridge.newInstance(JsBridge.mutationObserverConstructor)(
        js.Any.fromFunction1 { (mutations: js.Any) =>
          val records = JsBridge.asArray(mutations).toList
          if (
            records.exists(record =>
              JsBridge.stringField(record, "type").getOrElse("") == "childList"
            )
          ) {
            scheduleMarkerRefresh()
          }
        }
      )
      JsBridge.call(
        markerObserver,
        "observe",
        JsBridge.document.documentElement,
        JsBridge.obj("childList" -> (true), "subtree" -> (true))
      )
    }
    def applyMarkers(onDone: () => Unit = () => ()): Unit                                  =
      loadConfigSnapshot { raw =>
        val data = JsBridge.asObject(raw)
        normalizeProviderData(data)
        removeAllMarkerBoxes()
        markProviderRoles(data)
        syncMarkerBoxes()
        ensureMarkerBoxSyncing()
        sendMarkerStatus()
        onDone()
      }
    val onRuntimeMessage: js.Function3[js.Any, js.Any, js.Function1[js.Any, Unit], js.Any] =
      (message: js.Any, _, sendResponse: js.Function1[js.Any, Unit]) => {
        val msg     = JsBridge.asObject(message)
        val msgType = JsBridge.undefinedString(msg.`type`).getOrElse("")
        if (msgType == "LOCATION_PING") {
          sendResponse(JsBridge.obj(
            "ok"       -> (true),
            "protocol" -> (PageScriptProtocol.LocationVersion),
            "url"      -> (JsBridge.asString(JsBridge.location.href)),
            "roles"    -> (JsBridge.asObject(markerRoleStates()))
          ))
        }
        if (msgType == "LOCATION_SET_REQUIRED_ROLES") {
          val roles = JsBridge.asStringArray(msg.roles).toList
          if (roles.nonEmpty) {
            requiredMarkerRoles = roles.distinct
            refreshMarkers()
          }
        }
        if (msgType == "LOCATION_ENSURE") {
          val roles   =
            JsBridge.undefinedStringArray(msg.roles).toOption.map(_.toList).getOrElse(requiredMarkerRoles)
          val force   = JsBridge.undefinedBoolean(msg.force).getOrElse(false)
          if (roles.nonEmpty) requiredMarkerRoles = roles.distinct
          val targets = JsBridge.undefinedArrayAny(msg.targets).toOption.map(_.toList).getOrElse(Nil)
          targets.foreach { target =>
            val role        = JsBridge.stringField(target, "role").getOrElse("")
            val selector    = JsBridge.stringField(target, "selector").getOrElse("")
            val fingerprint =
              JsBridge.undefinedAny(JsBridge.field(target, "fingerprint")).getOrElse(null)
            if (role.nonEmpty && selector.nonEmpty)
              markRole(selector, role, fingerprint, force = force || !markerExists(role))
          }
          syncMarkerBoxes()
          ensureMarkerBoxSyncing()
          sendMarkerStatus()
          val missing = missingMarkerRoles()
          sendResponse(JsBridge.obj(
            "ok"       -> (missing.isEmpty),
            "protocol" -> (PageScriptProtocol.LocationVersion),
            "roles"    -> (JsBridge.asObject(markerRoleStates())),
            "missing"  -> (js.Array(missing*))
          ))
        }
        if (msgType == "LOCATION_SET_MARKER_VISIBILITY") {
          val visible = JsBridge.undefinedBoolean(msg.visible).getOrElse(true)
          silentMarkers = !visible
          if (!visible) {
            removeAllMarkerBoxes()
          } else {
            syncMarkerBoxes()
            ensureMarkerBoxSyncing()
          }
        }
        if (msgType == "LOCATION_APPLY") {
          val role        = JsBridge.undefinedString(msg.role).getOrElse("")
          val selector    = JsBridge.undefinedString(msg.selector).getOrElse("")
          val fingerprint =
            JsBridge.undefinedAny(msg.fingerprint).getOrElse(null)
          if (role.nonEmpty && selector.nonEmpty) {
            markRole(selector, role, fingerprint)
            syncMarkerBoxes()
            ensureMarkerBoxSyncing()
          } else applyMarkers()
        }
        if (msgType == "LOCATION_ROLE_TRIGGERED") {
          // SW 那边真的点了 New Chat / 聚焦了 Input：给对方一次"触发了"的反馈。
          val role = JsBridge.undefinedString(msg.role).getOrElse("")
          if (role.nonEmpty) flashRole(role)
        }
        js.undefined
      }
    chrome.runtime.onMessage.addListener(onRuntimeMessage)
    if (JsBridge.asString(document.readyState) == "loading") {
      document.addEventListener(
        "DOMContentLoaded",
        js.Any.fromFunction1 { _ =>
          applyMarkers { () =>
            sendMarkerStatus()
            ensureMarkerObserver()
          }
        }
      )
    } else {
      applyMarkers { () =>
        sendMarkerStatus()
        ensureMarkerObserver()
      }
    }
  }
}
