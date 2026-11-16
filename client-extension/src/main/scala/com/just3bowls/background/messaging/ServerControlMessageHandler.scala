package com.just3bowls.background.messaging

import com.just3bowls.facade.JsBridge

import com.just3bowls.background.runtime.*
import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.automation.provider.ServerTabManager
import com.just3bowls.background.bootstrap.BackgroundState
import com.just3bowls.facade.*
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[background] object ServerControlMessageHandler {
  val types: Set[String]                =
    Set("QUERY_SESSION_URLS", "SESSION_URL_END", "RELEASE_KEY_TABS", "ACTIVATE_SERVER_TAB", "CANCEL_TASK")
  def handles(msgType: String): Boolean = types.contains(msgType)

  def handle(msgType: String, message: js.Any): js.Any =
    msgType match {
      case "QUERY_SESSION_URLS" =>
        val sessionId = message.asInstanceOf[QuerySessionUrlsMessage].sessionId.getOrElse("")
        SessionUrls
          .watch(sessionId)
          .recover { case e =>
            JsBridge.obj(
              "ok"             -> (false),
              "sessionId"      -> (sessionId),
              "latestProvider" -> (""),
              "urls"           -> (js.Array[js.Object]()),
              "error"          -> (e.getMessage)
            )
          }
          .toJSPromise

      case "SESSION_URL_END" =>
        // 列表里按 ×：结束这段 URL（tab 留着），并记下「下次这个 provider 开新对话」。
        val endMsg    = message.asInstanceOf[SessionUrlEndMessage]
        val sessionId = endMsg.sessionId.getOrElse("")
        val provider  = endMsg.provider.getOrElse("")
        val url       = endMsg.url.getOrElse("")
        SessionUrls
          .endUrl(sessionId, provider, url)
          .map(_ => BackgroundResponseFactory.simpleResult(true))
          .recover { case e => BackgroundResponseFactory.simpleResult(false, e.getMessage) }
          .toJSPromise

      case "RELEASE_KEY_TABS"    =>
        val keyId = message.asInstanceOf[ReleaseKeyTabsMessage].keyId.getOrElse("")
        ManagedTabManager.releaseKeyTabs(keyId).foreach(_ => ())
        BackgroundResponseFactory.simpleResult(true)
      case "ACTIVATE_SERVER_TAB" =>
        val tabId = message.asInstanceOf[ActivateServerTabMessage].tabId.getOrElse(-1)
        ServerTabManager.focusServerTab(tabId)
          .map(_ => BackgroundResponseFactory.simpleResult(true))
          .recover { case e => BackgroundResponseFactory.simpleResult(false, e.getMessage) }
          .toJSPromise

      case "CANCEL_TASK" =>
        val generationId = message.asInstanceOf[CancelTaskMessage].generationId.getOrElse("")
        if (generationId.nonEmpty) {
          BackgroundState.cancelledCdpTasks += generationId
          BackgroundState.activeCdpTasks.remove(generationId)
          BackgroundState.clearChunkParsingState(generationId)
          CdpSession.cancelOwner(s"task:$generationId", "Task canceled")
          ManagedTabManager.cancelTaskById(generationId, "Task canceled").foreach(_ => ())
          PopCanLog.info("background", s"canceled task gen=$generationId")
        }
        js.Promise.resolve[js.Any](JsBridge.obj("ok" -> (true)))
      case _             => js.undefined
    }
}
