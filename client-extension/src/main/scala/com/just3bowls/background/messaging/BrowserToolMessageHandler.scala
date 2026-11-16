package com.just3bowls.background.messaging

import com.just3bowls.facade.JsBridge

import com.just3bowls.background.automation.browser.*
import com.just3bowls.background.automation.provider.ServerTabManager
import com.just3bowls.background.runtime.*
import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.*
import com.just3bowls.model.chat.*
import com.just3bowls.model.diagnostics.*
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[background] object BrowserToolMessageHandler {
  val types: Set[String] = Set("TOOL_TABS_CLEAR", "BROWSER_TABS_BIND", "BROWSER_TABS_RELEASE", "BROWSER_TOOL_CALL")
  def handles(msgType: String): Boolean = types.contains(msgType)

  def handle(msgType: String, message: js.Any): js.Any =
    msgType match {
      case "TOOL_TABS_CLEAR"   =>
        // 新一轮 run 起跑前清掉上一轮的 tool tab（provider tab 在别的组，不受影响）。
        new js.Promise[js.Any]((resolve, _) => {
          val preserve = message
            .asInstanceOf[ToolTabsClearMessage]
            .preserveTabIds
            .toOption
            .map(_.toList.toSet)
            .getOrElse(Set.empty[Int])
          ServerTabManager
            .hostWindow()
            .flatMap(windowId => ToolTabGroup.closeAll(windowId, preserve))
            .recover { case e =>
              PopCanLog.warn("tool", s"TOOL_TABS_CLEAR failed: ${e.getMessage}")
              ()
            }
            .foreach(_ => resolve(JsBridge.obj("ok" -> (true))))
        })
      case "BROWSER_TABS_BIND" =>
        val bind = message.asInstanceOf[BrowserTabsBindMessage]
        val refs = bind.tabs
          .toOption
          .map(_.toList.flatMap { item =>
            val ref   = item.ref.getOrElse("")
            val tabId = item.tabId.getOrElse(0)
            if (ref.isEmpty || tabId <= 0) None
            else Some(BrowserTabRef(
              ref = ref,
              tabId = tabId,
              title = item.title.getOrElse(""),
              url = item.url.getOrElse("")
            ))
          })
          .getOrElse(Nil)
        BrowserUseExecutor
          .bindReferencedTabs(
            bind.sessionId.getOrElse(""),
            bind.sessionTitle.getOrElse(""),
            refs
          )
          .map { case (tabs, previousTabId) =>
            JsBridge.obj(
              "ok"            -> (true),
              "previousTabId" -> (previousTabId),
              "tabs"          -> (js.Array(tabs.map(tab =>
                JsBridge.obj(
                  "ref"   -> (tab.ref),
                  "tabId" -> (tab.tabId),
                  "title" -> (tab.title),
                  "url"   -> (tab.url)
                )
              )*))
            )
          }
          .recover { case e => JsBridge.asObject(BackgroundResponseFactory.simpleResult(false, e.getMessage)) }
          .toJSPromise

      case "BROWSER_TABS_RELEASE" =>
        val sessionId = message.asInstanceOf[BrowserTabsReleaseMessage].sessionId.getOrElse("")
        BrowserUseExecutor
          .releaseReferencedTabs(sessionId)
          .map(_ => JsBridge.asObject(BackgroundResponseFactory.simpleResult(true)))
          .recover { case e => JsBridge.asObject(BackgroundResponseFactory.simpleResult(false, e.getMessage)) }
          .toJSPromise

      case "BROWSER_TOOL_CALL" =>
        val toolMsg    = message.asInstanceOf[BrowserToolCallMessage]
        val toolCallId = toolMsg.toolCallId.getOrElse("")
        val name       = toolMsg.name.getOrElse("")
        val argsText   = toolMsg.args.getOrElse("{}")
        PopCanLog.info("tool", s"BROWSER_TOOL_CALL id=$toolCallId name=$name")
        new js.Promise[js.Any]((resolve, _) => {
          val args                   = if (argsText.trim.isEmpty) JsBridge.obj() else js.JSON.parse(argsText)
          // 注册表是工具声明的单一事实来源；这里只决定每种 kind 用哪个执行器。
          val future: Future[js.Any] = AgentToolRegistry.find(name) match {
            case Some(spec) =>
              spec.kind match {
                case ToolKind.BrowserUse =>
                  BrowserUseExecutor.invoke(
                    toolMsg.sessionId.getOrElse(""),
                    toolMsg.sessionTitle.getOrElse(""),
                    name,
                    args
                  )
                case ToolKind.Local      =>
                  // Local 工具由发起方 agent 自己执行，不走 background 分发。
                  Future.failed(new Exception(s"Tool '$name' is executed by its own agent, not by background"))
              }
            case None       =>
              Future.failed(new Exception(s"Unknown browser tool: $name"))
          }
          future.foreach { result =>
            resolve(JsBridge.obj("toolCallId" -> (toolCallId), "ok" -> (true), "result" -> (result)))
          }
          future.recover { case e =>
            resolve(JsBridge.obj(
              "toolCallId" -> (toolCallId),
              "ok"         -> (false),
              "error"      -> (e.getMessage)
            ))
          }
        })
      case _                   => js.undefined
    }
}
