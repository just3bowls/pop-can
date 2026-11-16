package com.just3bowls.background.bootstrap

import com.just3bowls.background.runtime.{
  ManagedTabManager,
  ProviderContentRefresh,
  TabKeepAlive,
  TabOwnership,
  TabTasks
}
import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.automation.provider.*
import com.just3bowls.background.provider.ProviderPackManager
import com.just3bowls.background.webchat.*
import com.just3bowls.facade.*
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.scalajs.js

private[background] object BackgroundEvents {
  def install(registerDisposer: (() => Unit) => Unit): Unit = {
    val onDebuggerEvent: js.Function3[DebuggerSession, String, js.Object, Unit] = (source, method, params) => {
      val tabId = source.tabId.getOrElse(-1)
      if (method.startsWith("Fetch.")) RequestRewrite.onEvent(tabId, method, params)
      else CdpSession.dispatch(tabId, method, params)
    }
    ChromeAPI.debugger.onEvent.addListener(onDebuggerEvent)
    registerDisposer(() => ChromeAPI.debugger.onEvent.removeListener(onDebuggerEvent))
    val onDebuggerDetach: js.Function2[Debuggee, String, Unit]                  = (source, _reason) => {
      val tabId = source.tabId.getOrElse(-1)
      PopCanLog.info("cdp", s"CDP detached from tab $tabId (reason=$_reason)")
      CdpSession.onDetached(tabId, Option(_reason).filter(_.nonEmpty).getOrElse("detached"))
    }
    ChromeAPI.debugger.onDetach.addListener(onDebuggerDetach)
    registerDisposer(() => ChromeAPI.debugger.onDetach.removeListener(onDebuggerDetach))
// 点击扩展图标 → 在**当前窗口**里开/聚焦面板 tab（不再开新窗口）
    val onActionClicked: js.Function1[Tab, Unit]                                = (tab: Tab) => {
      ServerTabManager.openPanel(tab).foreach(_ => ())
    }
    ChromeAPI.action.onClicked.addListener(onActionClicked)
    registerDisposer(() => ChromeAPI.action.onClicked.removeListener(onActionClicked))
    ChromeAPI.alarms.create(
      "pop-can-keepalive",
      js.Dictionary("periodInMinutes" -> 1)
    )
    ChromeAPI.alarms.create(
      "pop-can-provider-pack",
      js.Dictionary("periodInMinutes" -> 24 * 60)
    )
    val onAlarm: js.Function1[Alarm, Unit]                                      = alarm => {
      if (alarm.name.getOrElse("").contains("pop-can-keepalive")) {
        PopCanLog.debug("tabs", s"keepalive alarm fired: ${alarm.name.getOrElse("")}")
        TabKeepAlive.sweepManagedTabs()
      } else if (alarm.name.getOrElse("") == "pop-can-provider-pack") {
        ProviderPackManager.startup()
      }
    }
    ChromeAPI.alarms.onAlarm.addListener(onAlarm)
    registerDisposer(() => ChromeAPI.alarms.onAlarm.removeListener(onAlarm))
    val onTabUpdated: js.Function3[Int, TabChangeInfo, Tab, Unit]               = (tabId, changeInfo, tab) => {
      if (changeInfo.status.contains("loading")) {
        ContentInjection.invalidate(tabId)
      } else if (changeInfo.status.contains("complete")) {
        tab.url.toOption.foreach { url =>
          ManagedTabManager.updateCurrentUrlByTabId(tabId, url).foreach(_ => ())
        }
      }
      // URL 决定 Provider，实际 group 决定 owner。完整加载、SPA URL 变化、拖入/拖出 group 都走同一个对账入口。
      if (changeInfo.groupId.isDefined || changeInfo.url.isDefined || changeInfo.status.contains("complete")) {
        TabOwnership.reconcileTab(tabId, "tabs.onUpdated").foreach(_ => ())
      }
    }
    ChromeAPI.tabs.onUpdated.addListener(onTabUpdated)
    registerDisposer(() => ChromeAPI.tabs.onUpdated.removeListener(onTabUpdated))
    val onTabRemoved: js.Function2[Int, TabRemoveInfo, Unit]                    =
      (tabId, _) => {
        ContentInjection.invalidate(tabId)
        TabTasks.cancelByTab(tabId, "tab removed")
        ManagedTabManager.removeTabByTabId(tabId).foreach(_ => ())
        CdpSession.releaseTab(tabId, "tab removed").foreach(_ => ())
        WebChatService.onTabClosed(tabId).foreach(_ => ())
        // host 页或 provider tab 被关：正在跑的原生 agent run 收口（把 running 的 assistant 标成 error）。
        WebChatService.onTabRemoved(tabId)
        BackgroundState.markerRoleStates.remove(tabId)
      }
    ChromeAPI.tabs.onRemoved.addListener(onTabRemoved)
    registerDisposer(() => ChromeAPI.tabs.onRemoved.removeListener(onTabRemoved))
// 网页原生会话：tab 激活变化时重算"谁该开接管窗口"；SW 启动时按绑定表恢复观察。
    val onTabActivated: js.Function1[js.Any, Unit] = (_: js.Any) => WebChatService.refreshWindows()
    ChromeAPI.tabs.onActivated.addListener(onTabActivated)
    registerDisposer(() => ChromeAPI.tabs.onActivated.removeListener(onTabActivated))
// 归属 / 会话 / 上下文设置变了（写入方可能是插件页、胶囊或 SW 自己）：重算归属并把状态推给胶囊。
    val onWebChatStorageChanged: js.Function2[js.Dictionary[StorageChange], String, Unit] =
      (changes, area) => WebChatService.onStorageChanged(changes, area)
    ChromeAPI.storage.onChanged.addListener(onWebChatStorageChanged)
    registerDisposer(() => ChromeAPI.storage.onChanged.removeListener(onWebChatStorageChanged))
    val onPermissionAdded: js.Function1[js.Dictionary[js.Any], Unit]                      = _ =>
      ProviderContentRefresh.run("origin added").foreach(_ => ())
    val onPermissionRemoved: js.Function1[js.Dictionary[js.Any], Unit]                    = _ =>
      ProviderContentRefresh.run("origin removed").foreach(_ => ())
    ChromeAPI.permissions.onAdded.addListener(onPermissionAdded)
    ChromeAPI.permissions.onRemoved.addListener(onPermissionRemoved)
    registerDisposer(() => ChromeAPI.permissions.onAdded.removeListener(onPermissionAdded))
    registerDisposer(() => ChromeAPI.permissions.onRemoved.removeListener(onPermissionRemoved))
  }
}
