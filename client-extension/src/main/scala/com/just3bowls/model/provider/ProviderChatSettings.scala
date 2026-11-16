package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import com.just3bowls.api.chat.ChatSendPolicy
import com.just3bowls.api.provider.ProviderChatArgs
import com.just3bowls.facade.ChromeAPI
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 两件事，都只跟"某个 provider 的投递设置"有关：
 *
 *   1. **固定值（pin）的存储**：`chrome.storage.local["thProviderPinnedSettings"]`，文档是 `{ "<providerId>": { thread, prompt } }`。
 *      用户明确"这家以后都这样"时才落库（输入框 `/provider` 里打 `pin`），`unpin` 删条目；没固定过的 provider 不在表里。**两项都写** —— 只写一项没法表达"这家永远
 *      continue（哪怕这条会话没用过它）"。 2. **线上 wire 词汇**：后台 / 执行器认的是 `new|delta`（`continue` 的线上叫法）与 `inline|file`；用户看到的是
 *      `new|continue`（`api.ChatSendPolicy`）。 两套词汇只在一处相交： `normalizeConversation`。
 *
 * 这里**不再有"每个 provider 一份、改了就长期生效"的设置** —— 常态是会话推导默认（用过 → continue，没用过 → new）+ 只作用一次的行内参数，见 `api.ChatSendPolicy`。
 */
object ProviderChatSettings {

  /** 固定值落库的键。 */
  val Key = "thProviderPinnedSettings"

  /** 线上值：复用同一段对话、只投增量。 */
  val Delta = "delta"

  /** 线上值：每轮新对话、投整份模版。 */
  val New = "new"

  /** 线上值：全量那一投把正文改写进请求体。 */
  val Inline = "inline"

  /** 线上值：正文作为 `.txt` 附件投递（输入框里只打指针句）。 */
  val File = "file"

  /** 用户词汇（`new|continue`，含历史写法 `delta`）→ 线上值。 */
  def normalizeConversation(raw: String): String =
    Option(raw).map(_.trim.toLowerCase) match {
      case Some(ProviderChatArgs.ThreadNew)      => New
      case Some(ProviderChatArgs.ThreadContinue) => Delta
      case Some(Delta)                           => Delta
      case _                                     => Delta
    }
  def normalizePrompt(raw: String): String       =
    if (Option(raw).map(_.trim.toLowerCase).contains(File)) File else Inline

  /** 用户词汇的 thread 值（存/显示用）：`delta` 与 `continue` 都算 `continue`。 */
  def userThread(raw: String): String =
    if (Option(raw).map(_.trim.toLowerCase).contains(ProviderChatArgs.ThreadNew)) ProviderChatArgs.ThreadNew
    else ProviderChatArgs.ThreadContinue

  /** 用户词汇的 prompt 值（存/显示用）。 */
  def userPrompt(raw: String): String =
    if (Option(raw).map(_.trim.toLowerCase).contains(File)) ProviderChatArgs.PromptFile
    else ProviderChatArgs.PromptInline

  /** 全表（providerId → 固定值）；没固定过的 provider 不在表里。 */
  def load(): Future[Map[String, ChatSendPolicy.Settings]] =
    ChromeAPI.storage.local
      .get(js.Array(Key))
      .toFuture
      .map { values =>
        values
          .get(Key)
          .filter(value => value != null && !js.isUndefined(value))
          .map { raw =>
            JsBridge.asDictionary(raw).toMap.flatMap { case (providerId, entry) =>
              val thread = JsBridge.stringField(entry, "thread")
              val prompt = JsBridge.stringField(entry, "prompt")
              if (thread.isEmpty && prompt.isEmpty) None
              else
                Some(
                  providerId -> ChatSendPolicy
                    .Settings(
                      thread = thread.map(userThread).getOrElse(ProviderChatArgs.ThreadContinue),
                      prompt = prompt.map(userPrompt).getOrElse(ProviderChatArgs.PromptInline)
                    )
                    .normalized
                )
            }
          }
          .getOrElse(Map.empty)
      }
      .recover { case _ => Map.empty }

  /** 固定这一家：写完整两项（解除固定用 `clearPinned`）。 */
  def setPinned(provider: String, settings: ChatSendPolicy.Settings): Future[Unit] =
    if (provider.isEmpty) Future.successful(())
    else load().flatMap(all => save(all.updated(provider, settings.normalized)))

  /** 解除这一家的固定；本来没固定就是 no-op。 */
  def clearPinned(provider: String): Future[Unit]                           =
    if (provider.isEmpty) Future.successful(())
    else load().flatMap(all => save(all - provider))
  private def save(all: Map[String, ChatSendPolicy.Settings]): Future[Unit] =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](Key -> toJs(all)))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }
  private def toJs(all: Map[String, ChatSendPolicy.Settings]): js.Any       =
    js.Dictionary[js.Any](all.toSeq.map { case (providerId, settings) =>
      val entry = js.Dictionary[js.Any]()
      entry.update("thread", settings.thread)
      entry.update("prompt", settings.prompt)
      providerId -> JsBridge.asObject(entry)
    }*)
}
