package com.just3bowls.background.bootstrap

import com.just3bowls.background.runtime.*
import com.just3bowls.background.webchat.*
import com.just3bowls.facade.ChromeAPI
import com.just3bowls.background.provider.ProviderPackManager
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.provider.ProviderPresets
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.scalajs.js

private[background] object BackgroundBootstrap {
  def run(): Unit = {
    PopCanLog.init()
    PopCanLog.info("background", "Pop Can background ready")
// 会话 URL 列表：浏览器 tab / 组变化 + slot 写入都推最新快照。
    SessionUrls.start()
    WebChatService.restore()
// SW 启动打点：SW 挂掉时"有没有跑到这里"是第一个要回答的问题（storage 里能直接读）。
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any]("thSwStartedAt" -> js.Date.now()))
      .toFuture
      .recover { case _ => () }
      .foreach(_ => ())
// provider 目录来自用户安装的 Provider Pack：启动时应用已安装 payload，再让内容脚本注入面跟上。
    ProviderPresets
      .ensureApplied()
      .map { report =>
        if (report.changed) PopCanLog.info("presets", s"provider presets ${report.summary}")
        ()
      }
      .recover { case e => PopCanLog.warn("presets", s"apply failed: ${e.getMessage}") }
      .foreach { _ =>
        ProviderPackManager.startup()
        WebChatContentScripts.startWatch()
        WebChatContentScripts.ensureRegistered().foreach(_ => ())
      }
// 任务只在内存（`TabTasks`），SW 重启即视为没有任务在跑 —— 与旧版 restoreAfterReload 的效果一致，不需要额外收口。
    ManagedTabManager
      .reconcileManagedTabs()
      .foreach(_ => TabKeepAlive.sweepManagedTabs())
  }
}
