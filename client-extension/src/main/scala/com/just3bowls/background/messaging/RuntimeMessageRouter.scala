package com.just3bowls.background.messaging

import com.just3bowls.facade.JsBridge

import com.just3bowls.background.automation.provider.*
import com.just3bowls.background.runtime.*
import com.just3bowls.facade.*
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[background] object RuntimeMessageRouter {
  def install(registerDisposer: (() => Unit) => Unit): Unit = {
    val onRuntimeMessage: js.Function3[js.Any, MessageSender, js.Function1[js.Any, Unit], js.Any] =
      (message, sender, _) => {
        val msg       = message.asInstanceOf[RuntimeMessage]
        val msgType   = msg.`type`.getOrElse("")
        // 网页原生 host tab 的轮次：直接驱动已经存在的那条 tab，不走 provider slot 分配（也绝不 New Chat）。
        val hostTabId =
          if (msgType == "INPUT_PROMPT") message.asInstanceOf[InputPromptMessage].hostTabId.toOption.getOrElse(0)
          else 0
        if (msgType == "INPUT_PROMPT" && hostTabId > 0)
          HostPrompt
            .run(message.asInstanceOf[InputPromptMessage], hostTabId)
            .toJSPromise
        else
          msgType match {
            case "PROVIDER_CONTENT_REFRESH" =>
              ProviderContentRefresh.run("provider status changed")
                .map(_ => JsBridge.obj("ok" -> (true)))
                .toJSPromise

            // 存储健康度：config → Log 面板显示（分片后的文档数 / 字节 / 脏标记 / 迁移残留）。
            case "STATE_STATS" =>
              TabStore
                .load()
                .map(_ => JsBridge.obj("ok" -> (true), "stats" -> (TabStore.stats())))
                .recover { case e =>
                  JsBridge.obj("ok" -> (false), "error" -> (e.getMessage))
                }
                .toJSPromise

            case msgType if WebChatMessageHandler.handles(msgType) =>
              WebChatMessageHandler.handle(msgType, message, sender)
            case t if ProviderTaskMessageHandler.handles(t)        =>
              ProviderTaskMessageHandler.handle(t, message, sender)
            case t if ProviderPackMessageHandler.handles(t)        =>
              ProviderPackMessageHandler.handle(message)
            case t if ServerControlMessageHandler.handles(t)       =>
              ServerControlMessageHandler.handle(t, message)
            case t if BrowserToolMessageHandler.handles(t)         =>
              BrowserToolMessageHandler.handle(t, message)
            case _                                                 =>
              js.undefined
          }
      }
    ChromeAPI.runtime.onMessage.addListener(onRuntimeMessage)
    registerDisposer(() => ChromeAPI.runtime.onMessage.removeListener(onRuntimeMessage))
  }

}
