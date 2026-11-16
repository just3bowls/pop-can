package com.just3bowls.background.webchat

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.{ChromeAPI, RegisteredContentScript, StorageChange}
import com.just3bowls.model.provider.ParserProviderLocal
import com.just3bowls.model.provider.ProviderHostAccess
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 网页原生会话的胶囊 UI：按已授权的 provider origin 用 `registerContentScripts` 注册（而不是写进 manifest）， 这样权限面仍然是"配了哪个 provider
 * 才注入哪个站点"；对已经打开的 tab 再补一次 `executeScript`。
 */
private[background] object WebChatContentScripts {
  private val ScriptId          = "th-webchat"
  private var watchAttached     = false
  private var reRegisterPending = false
  private def scriptPath: String =
    // 读一个不存在的全局变量在模块作用域里会抛 ReferenceError，进而把整个 SW 的启动打断：这里必须兜住。
    try {
      JsBridge.undefinedString(JsBridge.webChatScript).toOption.getOrElse("")
    } catch {
      case _: Throwable => ""
    }

  /**
   * 跟随 provider 配置变化重注册：启用/停用某家、面板 import 新预设之后，注入面要跟着变。
   *
   * 一次应用会连写 9 条 `parserProvider:*`，所以这里合并成一次（400ms 去抖），不然每个 key 都触发一轮注册。
   */
  def startWatch(): Unit                 =
    if (!watchAttached) {
      watchAttached = true
      ChromeAPI.storage.onChanged.addListener {
        (changes: js.Dictionary[StorageChange], area: String) =>
          if (area == "local" && changes.keys.exists(_.startsWith("parserProvider:"))) scheduleReRegister()
      }
    }
  private def scheduleReRegister(): Unit =
    if (!reRegisterPending) {
      reRegisterPending = true
      js.timers.setTimeout(400) {
        reRegisterPending = false
        ensureRegistered().foreach(_ => ())
      }
    }

  /** 按当前**已启用**的 provider 重新注册（幂等：先注销再注册）。 */
  def ensureRegistered(): Future[Unit] = {
    val path = scriptPath
    if (path.isEmpty) Future.successful(())
    else
      ParserProviderLocal
        .loadAll()
        .flatMap { providers =>
          val active = providers.values.toList.filter(_.status == "active")
          ProviderHostAccess
            .filterReady(active)
            .flatMap { ready =>
              val matches =
                ready.map(provider => ProviderHostAccess.originPattern(provider.pageUrl)).filter(_.nonEmpty).distinct
              ChromeAPI.scripting
                .unregisterContentScripts(js.Dictionary[js.Any]("ids" -> js.Array(ScriptId)))
                .toFuture
                .recover { case _ => () }
                .flatMap { _ =>
                  if (matches.isEmpty) Future.successful(())
                  else {
                    val registration =
                      JsBridge.obj(
                        "id"                    -> (ScriptId),
                        "js"                    -> (js.Array(path)),
                        "matches"               -> (js.Array(matches*)),
                        "runAt"                 -> ("document_start"),
                        "allFrames"             -> (false),
                        "persistAcrossSessions" -> (true)
                      )
                        .asInstanceOf[RegisteredContentScript]
                    ChromeAPI.scripting.registerContentScripts(js.Array(registration)).toFuture.map(_ => ())
                  }
                }
                .map { _ =>
                  PopCanLog.debug("webchat", s"content script registered matches=${matches.mkString(",")}")
                  ()
                }
                .recover { case e =>
                  PopCanLog.warn("webchat", s"content script register failed: ${e.getMessage}")
                  ()
                }
            }
            .recover { case e =>
              PopCanLog.warn("webchat", s"content script permission check failed: ${e.getMessage}")
              ()
            }
        }
        .recover { case _ => () }
  }

  /** 已经打开的 tab 不会被注册影响，得马上补一次。 */
  def injectNow(tabId: Int): Future[Unit] = {
    val path = scriptPath
    if (path.isEmpty) Future.successful(())
    else
      ChromeAPI.scripting
        .executeScript(js.Dictionary(
          "target" -> JsBridge.obj("tabId" -> (tabId)),
          "files"  -> js.Array(path)
        ))
        .toFuture
        .map(_ => ())
        .recover { case _ => () }
  }
}
