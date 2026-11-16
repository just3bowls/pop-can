package com.just3bowls.background.messaging

import com.just3bowls.facade.JsBridge

import com.just3bowls.background.bootstrap.BackgroundState
import com.just3bowls.background.webchat.WebChatService
import com.just3bowls.facade.{
  LocationMarkersReadyMessage,
  MessageSender,
  OkResponse,
  WebChatBrowserToolsMessage,
  WebChatFocusUrlMessage,
  WebChatNativeRunMessage,
  WebChatRenameSessionMessage,
  WebChatSessionMessage
}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[background] object WebChatMessageHandler {
  val types: Set[String] = Set(
    "WEBCHAT_QUERY_STATE",
    "WEBCHAT_LIST_SESSIONS",
    "WEBCHAT_FOCUS_URL",
    "WEBCHAT_SET_BROWSER_TOOLS",
    "NATIVE_RUN_PROGRESS",
    "NATIVE_RUN_DONE",
    "WEBCHAT_STOP_RUN",
    "WEBCHAT_NEW_SESSION",
    "WEBCHAT_SESSION_CLICK",
    "WEBCHAT_DELETE_SESSION",
    "WEBCHAT_RENAME_SESSION",
    "WEBCHAT_ASSIGN",
    "LOCATION_MARKERS_READY"
  )

  def handles(msgType: String): Boolean = types.contains(msgType)

  def handle(msgType: String, message: js.Any, sender: MessageSender): js.Any =
    msgType match {
      case "WEBCHAT_QUERY_STATE" =>
        val tabId = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        WebChatService
          .stateFor(tabId)
          .map(state => JsBridge.obj("ok" -> (true), "state" -> (state)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_LIST_SESSIONS" =>
        val listTabId = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        WebChatService
          .sessionOptions(listTabId)
          .map(sessions => JsBridge.obj("ok" -> (true), "sessions" -> (sessions)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage), "sessions" -> (js.Array[js.Any]())).asInstanceOf[
              js.Object
            ]
          }
          .toJSPromise

      case "WEBCHAT_FOCUS_URL" =>
        val url     = message.asInstanceOf[WebChatFocusUrlMessage].url.getOrElse("")
        val fromTab = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        WebChatService
          .focusConversation(url, fromTab)
          .map(_ => JsBridge.obj("ok" -> (true)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_SET_BROWSER_TOOLS" =>
        val tabId = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        val on    = message.asInstanceOf[WebChatBrowserToolsMessage].on.getOrElse(true)
        WebChatService
          .setBrowserTools(tabId, on)
          .map(state => JsBridge.obj("ok" -> (true), "state" -> (state)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "NATIVE_RUN_PROGRESS" =>
        val sessionId = message.asInstanceOf[WebChatSessionMessage].sessionId.getOrElse("")
        WebChatService.nativeRunProgress(sessionId)
        js.undefined
      case "NATIVE_RUN_DONE"     =>
        val done            = message.asInstanceOf[WebChatNativeRunMessage]
        val runId           = done.runId.getOrElse("")
        val ok              = done.ok.getOrElse(false)
        val error           = done.error.getOrElse("")
        val protocolFailure = done.protocolFailure.getOrElse(false)
        WebChatService
          .nativeRunDone(runId, ok, error, protocolFailure)
          .map(_ => JsBridge.obj("ok" -> (true)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_STOP_RUN" =>
        val tabId     = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        val sessionId = message.asInstanceOf[WebChatSessionMessage].sessionId.getOrElse("")
        WebChatService
          .stopNativeRun(sessionId)
          .flatMap { result =>
            val ok = result.asInstanceOf[OkResponse].ok.getOrElse(false)
            WebChatService
              .stateFor(tabId)
              .map(state => JsBridge.obj("ok" -> (ok), "state" -> (state)))
              .recover { case _ => JsBridge.obj("ok" -> (ok), "state" -> (js.undefined)) }
          }
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_NEW_SESSION" =>
        val tabId = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        WebChatService
          .newSession(tabId)
          .map(state => JsBridge.obj("ok" -> (true), "state" -> (state)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_SESSION_CLICK" =>
        val tabId     = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        val sessionId = message.asInstanceOf[WebChatSessionMessage].sessionId.getOrElse("")
        WebChatService
          .clickSession(tabId, sessionId)
          .map(state => JsBridge.obj("ok" -> (true), "state" -> (state)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_DELETE_SESSION" =>
        val tabId     = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        val sessionId = message.asInstanceOf[WebChatSessionMessage].sessionId.getOrElse("")
        WebChatService
          .deleteSession(tabId, sessionId)
          .map(state => JsBridge.obj("ok" -> (true), "state" -> (state)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_RENAME_SESSION" =>
        val tabId     = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        val rename    = message.asInstanceOf[WebChatRenameSessionMessage]
        val sessionId = rename.sessionId.getOrElse("")
        val title     = rename.title.getOrElse("")
        WebChatService
          .renameSession(tabId, sessionId, title)
          .map(state => JsBridge.obj("ok" -> (true), "state" -> (state)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "WEBCHAT_ASSIGN" =>
        val tabId     = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        val sessionId = message.asInstanceOf[WebChatSessionMessage].sessionId.getOrElse("")
        WebChatService
          .assign(tabId, sessionId)
          .map(state => JsBridge.obj("ok" -> (true), "state" -> (state)))
          .recover { case e =>
            JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
          }
          .toJSPromise

      case "LOCATION_MARKERS_READY" =>
        val tabId = sender.tab.toOption.flatMap(_.id.toOption).getOrElse(-1)
        val roles = message
          .asInstanceOf[LocationMarkersReadyMessage]
          .roles
          .toOption
          .map(_.toMap)
          .getOrElse(Map.empty)
        BackgroundState.reportMarkerRoles(tabId, roles)
        PopCanLog.debug("location", s"content markers ready tab=$tabId roles=${roles.mkString(",")}")
        js.undefined
      case _                        => js.undefined
    }
}
