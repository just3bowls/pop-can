package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.diagnostics.*

import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.{BrowserTabsResponse, BrowserToolCallResponse, ChromeAPI}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** 主页面向 background 发起 CDP 浏览器工具调用。 */
object BrowserToolRunner {

  /** 不绑定会话的工具调用（没有会话归属的临时调用）。 */
  def call(name: String, argsText: String): Future[js.Any] =
    call(sessionId = "", sessionTitle = "", name, argsText)

  /**
   * 关掉插件窗口里 `tools` 组的全部 tab：新的一轮开跑前清掉上一轮留下的页面。
   *
   * provider tab 在独立的 provider 组，不受影响；组空掉之后下次工具调用会按需重建。
   */
  def clearToolTabs(preserveTabIds: Set[Int] = Set.empty): Future[Unit] =
    ChromeAPI.runtime
      .sendMessage(JsBridge.obj(
        "type"           -> ("TOOL_TABS_CLEAR"),
        "preserveTabIds" -> (js.Array(preserveTabIds.toSeq.sorted.map(JsBridge.asObject(_))*))
      ))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }

  /** 新一轮起点复用同一个清理口径；3s 超时兜底，清不掉也不能挡住本轮运行。 */
  def clearPreviousToolTabs(preserveTabIds: Set[Int] = Set.empty): Future[Unit] = {
    val p      = Promise[Unit]()
    val handle = js.timers.setTimeout(3000)(p.trySuccess(()))
    clearToolTabs(preserveTabIds).onComplete { result =>
      js.timers.clearTimeout(handle)
      result.failed.foreach(e => PopCanLog.warn("agent", s"clear previous tool tabs failed: ${e.getMessage}"))
      p.trySuccess(())
    }
    p.future
  }
  def bindReferencedTabs(
      sessionId: String,
      sessionTitle: String,
      refs: List[BrowserTabRef]
  ): Future[(List[BrowserTabRef], Int)] =
    if (refs.isEmpty) Future.successful((Nil, 0))
    else
      ChromeAPI.runtime
        .sendMessage(JsBridge.obj(
          "type"         -> ("BROWSER_TABS_BIND"),
          "sessionId"    -> (sessionId),
          "sessionTitle" -> (sessionTitle),
          "tabs"         -> (js.Array(refs.map(tab =>
            JsBridge.obj(
              "ref"   -> (tab.ref),
              "tabId" -> (tab.tabId),
              "title" -> (tab.title),
              "url"   -> (tab.url)
            )
          )*))
        ))
        .toFuture
        .flatMap { response =>
          val result = response.asInstanceOf[BrowserTabsResponse]
          if (result.ok.getOrElse(false))
            Future.successful(
              result.tabs
                .toOption
                .map(_.toList.flatMap { tab =>
                  val ref   = tab.ref.getOrElse("")
                  val tabId = tab.tabId.getOrElse(0)
                  if (ref.isEmpty || tabId <= 0) None
                  else Some(BrowserTabRef(
                    ref = ref,
                    tabId = tabId,
                    title = tab.title.getOrElse(""),
                    url = tab.url.getOrElse("")
                  ))
                })
                .getOrElse(Nil) -> result.previousTabId.getOrElse(0)
            )
          else Future.failed(
            new RuntimeException(result.error.getOrElse("bind tabs failed"))
          )
        }
  def releaseReferencedTabs(sessionId: String): Future[Unit]                    =
    if (sessionId.isEmpty) Future.successful(())
    else
      ChromeAPI.runtime
        .sendMessage(
          JsBridge.obj("type" -> ("BROWSER_TABS_RELEASE"), "sessionId" -> (sessionId))
        )
        .toFuture
        .map(_ => ())
        .recover { case _ => () }

  /** 带 chat session 上下文的调用；browser_* 工具据此定位本会话的常驻 tab。 */
  def call(sessionId: String, sessionTitle: String, name: String, argsText: String): Future[js.Any] = {
    val callId = s"tool-${js.Date.now()}-${Math.round(Math.random() * 1e6)}"
    ChromeAPI.runtime
      .sendMessage(
        JsBridge.obj(
          "type"         -> ("BROWSER_TOOL_CALL"),
          "toolCallId"   -> (callId),
          "sessionId"    -> (sessionId),
          "sessionTitle" -> (sessionTitle),
          "name"         -> (name),
          "args"         -> (argsText)
        )
      )
      .toFuture
      .flatMap { response =>
        val result = response.asInstanceOf[BrowserToolCallResponse]
        if (result.ok.getOrElse(false)) Future.successful(result.result.getOrElse(JsBridge.obj()))
        else Future.failed(new RuntimeException(result.error.getOrElse(s"$name failed")))
      }
  }
}
