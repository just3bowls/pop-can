package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.DebuggerSession

import scala.scalajs.js

object CdpTargets {
  def tabTarget(tabId: Int): DebuggerSession =
    JsBridge.obj("tabId" -> (tabId)).asInstanceOf[DebuggerSession]
}
