package com.just3bowls.background.automation.browser

import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.{BrowserSessionRecord, ChromeAPI, JsBridge}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

final private[background] case class BrowserUseSession(
    sessionId: String,
    title: String,
    tabId: Int,
    windowId: Int,
    groupId: Int,
    nextRef: Int,
    refs: Map[String, Int],
    nodeRefs: Map[Int, String],
    knownTabs: Set[Int],
    attachedTabs: List[BrowserTabRef],
    previousTabId: Int,
    url: String,
    pageTitle: String,
    scrollX: Double,
    scrollY: Double,
    scrollContainer: String,
    documentToken: String
)

/** 让各步骤可以就地更新会话；调用链由执行器在同一把 session 锁里串行化。 */
final private[background] class SessionHandle(private var current: BrowserUseSession) {
  def get: BrowserUseSession = current

  def update(f: BrowserUseSession => BrowserUseSession): BrowserUseSession = {
    current = f(current)
    current
  }
}

/** Browser tool session cache and the chrome.storage.session wire format. */
final private[background] class BrowserUseSessionStore {
  private val SessionKeyPrefix = "thBrowserUseSession:"
  private val cache            = mutable.Map.empty[String, BrowserUseSession]

  def handle(session: BrowserUseSession): SessionHandle =
    new SessionHandle(session)

  def load(sessionId: String): Future[Option[BrowserUseSession]] =
    cache.get(sessionId) match {
      case Some(session) => Future.successful(Some(session))
      case None          =>
        val key = sessionKey(sessionId)
        ChromeAPI.storage.session.get(key).toFuture
          .map(values => values.get(key).flatMap(parseSession))
          .map { parsed =>
            parsed.foreach(session => cache.update(sessionId, session))
            parsed
          }
          .recover { case _ => None }
    }

  def save(handle: SessionHandle): Future[Unit] = {
    val session = handle.get
    val items   = js.Dictionary[js.Any](
      sessionKey(session.sessionId) -> toJs(session)
    )
    cache.update(session.sessionId, session)
    ChromeAPI.storage.session.set(items).toFuture.map(_ => ()).recover { case e =>
      PopCanLog.warn("tool", s"browser session persist failed session=${session.sessionId}: ${e.getMessage}")
    }
  }

  private def sessionKey(sessionId: String): String = SessionKeyPrefix + sessionId

  private def toJs(session: BrowserUseSession): BrowserSessionRecord =
    JsBridge.obj(
      "sessionId"        -> (session.sessionId),
      "title"            -> (session.title),
      "tabId"            -> (session.tabId),
      "windowId"         -> (session.windowId),
      "groupId"          -> (session.groupId),
      "nextRef"          -> (session.nextRef),
      "refs"             -> (js.Dictionary[Int](session.refs.toSeq*)),
      "nodeRefs"         -> (js.Dictionary[String](session.nodeRefs.toSeq.map { case (backend, ref) =>
        backend.toString -> ref
      }*)),
      "knownTabs"        -> (js.Array(session.knownTabs.toSeq.sorted*)),
      "attachedTabsJson" -> (js.JSON.stringify(js.Array(session.attachedTabs.map(tab =>
        JsBridge.asObject(
          JsBridge.obj("ref" -> (tab.ref), "tabId" -> (tab.tabId), "title" -> (tab.title), "url" -> (tab.url))
        )
      )*))),
      "previousTabId"    -> (session.previousTabId),
      "url"              -> (session.url),
      "pageTitle"        -> (session.pageTitle),
      "scrollX"          -> (session.scrollX),
      "scrollY"          -> (session.scrollY),
      "scrollContainer"  -> (session.scrollContainer),
      "documentToken"    -> (session.documentToken)
    ).asInstanceOf[BrowserSessionRecord]

  private def parseSession(raw: js.Any): Option[BrowserUseSession] =
    if (js.isUndefined(raw) || raw == null) None
    else {
      val record = raw.asInstanceOf[BrowserSessionRecord]
      val tabId  = record.tabId
      if (tabId < 0) None
      else
        Some(BrowserUseSession(
          sessionId = record.sessionId,
          title = record.title,
          tabId = tabId,
          windowId = record.windowId,
          groupId = record.groupId,
          nextRef = math.max(1, record.nextRef),
          refs = record.refs.toOption.map(_.toMap).getOrElse(Map.empty),
          nodeRefs = record.nodeRefs.toOption
            .map(_.toMap.flatMap { case (backend, ref) => backend.toIntOption.map(_ -> ref) })
            .getOrElse(Map.empty),
          knownTabs = record.knownTabs.toOption.map(_.toList.toSet).getOrElse(Set.empty),
          attachedTabs = parseAttachedTabs(record.attachedTabsJson.toOption.getOrElse("")),
          previousTabId = record.previousTabId.toOption.filter(_ > 0).getOrElse(0),
          url = record.url,
          pageTitle = record.pageTitle,
          scrollX = record.scrollX,
          scrollY = record.scrollY,
          scrollContainer = record.scrollContainer.getOrElse(""),
          documentToken = record.documentToken.getOrElse("")
        ))
    }

  private def parseAttachedTabs(raw: String): List[BrowserTabRef] =
    if (raw.trim.isEmpty) Nil
    else
      try {
        JsBridge.asArray(js.JSON.parse(raw)).toList.flatMap { item =>
          val ref   = JsBridge.stringField(item, "ref").getOrElse("")
          val tabId = JsBridge.intField(item, "tabId").getOrElse(0)
          if (ref.isEmpty || tabId <= 0) None
          else
            Some(BrowserTabRef(
              ref = ref,
              tabId = tabId,
              title = JsBridge.stringField(item, "title").getOrElse(""),
              url = JsBridge.stringField(item, "url").getOrElse("")
            ))
        }
      } catch { case _: Throwable => Nil }
}
