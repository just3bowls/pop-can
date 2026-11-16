package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.ChromeAPI
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 「这条会话的这个 provider 下次要开新对话」的待办（落 `chrome.storage.local["thSessionFreshChats"]`）。
 *
 * 会话 URL 列表里按下 × = 结束当前那段 URL：tab 留着，但这个 provider 在本会话里不该再继续用旧对话，所以
 *   - 插件页 chat 跑这个 provider 时强制 New Chat（见 `BackgroundMain` 的 conversation 决策）；
 *   - 网页原生侧该 provider 出现一个**未归属的新对话 URL** 时自动绑回本会话（见 `adoptNewUrl`）。
 *
 * `ended` 记下被结束掉的 URL：那个页面还停在原 URL 时不能被立刻回绑，否则等于没解绑。 形状：`{sessionId: {provider: {ended: [url…], at: epochMs}}}`。
 */
object SessionFreshChats {
  val Key = "thSessionFreshChats"

  /** 每条会话最多记多少个待换新的 provider（正常只有一两个）。 */
  private val LimitPerSession = 12

  /** 这个 (session, provider) 是否在等新对话。 */
  def isPending(sessionId: String, provider: String): Future[Boolean] =
    entries(sessionId).map(_.contains(provider))

  /** 记一次「结束这个 URL，下次换新对话」；同一 provider 重复按下时合并 ended。 */
  def mark(sessionId: String, provider: String, endedUrl: String): Future[Unit] =
    if (sessionId.isEmpty || provider.isEmpty) Future.successful(())
    else
      loadAll().flatMap { all =>
        val perProvider = all.getOrElse(sessionId, Map.empty)
        val previous    = perProvider.getOrElse(provider, Entry(Nil, 0.0))
        val urls        = if (endedUrl.isEmpty) previous.ended
        else (previous.ended.filterNot(_ == endedUrl) :+ endedUrl).takeRight(8)
        val next        = perProvider.updated(provider, Entry(urls, js.Date.now()))
        val trimmed     = next.toSeq.sortBy { case (_, entry) => -entry.at }.take(LimitPerSession).toMap
        saveAll(all.updated(sessionId, trimmed))
      }

  /** 换新对话已经发生（或已不需要）：清掉这条待办。 */
  def clear(sessionId: String, provider: String): Future[Unit] =
    if (sessionId.isEmpty || provider.isEmpty) Future.successful(())
    else
      loadAll().flatMap { all =>
        all.get(sessionId).flatMap(_.get(provider)) match {
          case None    => Future.successful(())
          case Some(_) =>
            val perProvider = all.getOrElse(sessionId, Map.empty) - provider
            saveAll(if (perProvider.isEmpty) all - sessionId else all.updated(sessionId, perProvider))
        }
      }

  /** 会话被删掉时把它的待办一起清掉。 */
  def removeSession(sessionId: String): Future[Unit] =
    if (sessionId.isEmpty) Future.successful(())
    else loadAll().flatMap(all => saveAll(all - sessionId))

  /**
   * 网页原生侧用：这个 provider 出现了一个未归属的 `url`，哪条会话在等它换新对话？
   *
   * 取 `at` 最新的那条待办；`ended` 里含这个 URL 的直接跳过（它正是刚被结束掉的那段，不能被立刻回绑）。
   */
  def newestSessionFor(provider: String, url: String): Future[Option[String]] =
    if (provider.isEmpty || url.isEmpty) Future.successful(None)
    else
      loadAll()
        .map { all =>
          all.toList
            .flatMap { case (sessionId, perProvider) =>
              perProvider.get(provider).filterNot(_.ended.contains(url)).map(entry => (sessionId, entry.at))
            }
            .sortBy { case (_, at) => -at }
            .headOption
            .map(_._1)
        }
        .recover { case _ => None }
  // ---------------------------------------------------------------- 读写
  final private case class Entry(ended: List[String], at: Double)
  private def entries(sessionId: String): Future[Map[String, Entry]]          =
    if (sessionId.isEmpty) Future.successful(Map.empty) else loadAll().map(_.getOrElse(sessionId, Map.empty))
  private def loadAll(): Future[Map[String, Map[String, Entry]]]              =
    ChromeAPI.storage.local
      .get(js.Array(Key))
      .toFuture
      .map { values =>
        values
          .get(Key)
          .filter(value => value != null && !js.isUndefined(value))
          .map { raw =>
            JsBridge.asDictionary(raw).toMap.flatMap { case (sessionId, perProvider) =>
              val restored = perProviderFromJs(perProvider)
              if (restored.isEmpty) None else Some(sessionId -> restored)
            }
          }
          .getOrElse(Map.empty)
      }
      .recover { case _ => Map.empty }
  private def perProviderFromJs(raw: js.Any): Map[String, Entry]              =
    if (raw == null || js.isUndefined(raw)) Map.empty
    else
      JsBridge.asDictionary(raw).toMap.map { case (provider, entry) =>
        val dynamic = JsBridge.asObject(entry)
        provider -> Entry(
          ended = JsBridge.asArray(dynamic.ended).toList.map(_.toString).filter(_.nonEmpty),
          at = JsBridge.undefinedDouble(dynamic.at).getOrElse(0.0)
        )
      }
  private def entryToJs(entry: Entry): js.Any                                 =
    JsBridge.obj("ended" -> (js.Array(entry.ended.map(JsBridge.asObject(_))*)), "at" -> (entry.at))
  private def saveAll(all: Map[String, Map[String, Entry]]): Future[Unit]     =
    val jsAll = js.Dictionary[js.Any](all.toSeq.map { case (sessionId, perProvider) =>
      sessionId -> js.Dictionary[js.Any](perProvider.toSeq.map { case (provider, entry) =>
        provider -> entryToJs(entry)
      }*)
    }*)
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](Key -> jsAll))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }
}
