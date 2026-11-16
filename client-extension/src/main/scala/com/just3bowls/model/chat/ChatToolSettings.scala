package com.just3bowls.model.chat

import com.just3bowls.facade.ChromeAPI
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * chat 是否声明内置 browser 工具（全局一份，落 `chrome.storage.local`）。
 *
 * `on` = 按注册表把 `browser_*` 声明给网页 AI；`off` = 一个工具都不声明，让网页 AI 用它自己的能力回答。 键缺失视为 `on`，老用户行为不变。
 */
object ChatToolSettings {
  val Key                                  = "popCanChatBrowserTools"
  private val Off                          = "off"
  def load(): Future[Boolean]              =
    ChromeAPI.storage.local
      .get(js.Array(Key))
      .toFuture
      .map(result => result.get(Key).map(_.toString.trim.toLowerCase).forall(_ != Off))
      .recover { case _ => true }
  def save(enabled: Boolean): Future[Unit] =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](Key -> (if (enabled) "on" else Off)))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }
}
