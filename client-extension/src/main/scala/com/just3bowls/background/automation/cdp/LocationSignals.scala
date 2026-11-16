package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.scalajs.js

/**
 * 给页面上的标记发"CDP 真的触发了这一块"的信号，让内容脚本闪一下。
 *
 * 页面没注入内容脚本、或者对方已经关机，都只是静默失败——这只是反馈，不该影响主流程。
 */
private[background] object LocationSignals {
  def setVisible(tabId: Int, visible: Boolean): Unit =
    if (tabId <= 0) ()
    else
      ChromeAPI.tabs
        .sendMessage(
          tabId,
          JsBridge.obj("type" -> ("LOCATION_SET_MARKER_VISIBILITY"), "visible" -> (visible))
        )
        .toFuture
        .recover { case _ => () }
        .foreach(_ => ())
  def trigger(tabId: Int, role: String): Unit        =
    if (tabId <= 0 || role.isEmpty) ()
    else {
      try {
        ChromeAPI.tabs
          .sendMessage(
            tabId,
            JsBridge.obj("type" -> ("LOCATION_ROLE_TRIGGERED"), "role" -> (role))
          )
          .toFuture
          .recover { case _ => () }
          .foreach(_ => ())
      } catch {
        case e: Throwable =>
          PopCanLog.debug("location", s"role trigger signal failed tab=$tabId role=$role: ${e.getMessage}")
      }
    }
}
