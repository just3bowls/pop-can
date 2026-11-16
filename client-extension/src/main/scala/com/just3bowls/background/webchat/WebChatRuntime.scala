package com.just3bowls.background.webchat

import com.just3bowls.background.automation.cdp.{CdpLease, CdpResponseSession}
import com.just3bowls.background.automation.provider.LocalRawParser
import com.just3bowls.model.chat.ChatMessage
import com.just3bowls.model.chat.RawChunkStreamDecoder
import com.just3bowls.model.provider.ParserProviderInfo

import scala.collection.mutable

/** Mutable state for one provider tab observed by the native web-chat bridge. */
final private[background] class Runtime(val tabId: Int) {
  var config: Option[ParserProviderInfo] = None

  /** 只有实际属于 System Default group 的 tab 才允许显示胶囊并做网页原生观察。 */
  var capsuleEnabled: Boolean = false

  /** 当前这段对话的归属（按 URL 查出来的）；None = 未归属。 */
  var assoc: Option[WebChatHost] = None

  /** 这个 tab 上一次解析到的归属，用来在导航中间态后继续跟随同一段对话。 */
  var lastBinding: Option[WebChatHost] = None

  /** 观察期间长期持有的 CDP 租约（同一 tab 的任务 / 工具只是合流）。 */
  var lease: Option[CdpLease]           = None
  var observing: Boolean                = false
  var hijacking: Boolean                = false
  var parser: LocalRawParser            = null
  var rawDecoder: RawChunkStreamDecoder = null
  val content                           = new StringBuilder

  /** 本组件自己建的观察会话；只有它才能被本组件取消。 */
  var session: Option[CdpResponseSession] = None
  var turnGen: String                     = ""
  var pendingUrl: String                  = ""
  var lastPostData: String                = ""
  var lastKnownUrl: String                = ""

  /** 欠账窗口打开前预计算好的上下文，避免在请求暂停期间读存储。 */
  var pendingDelta: List[ChatMessage] = Nil
  var pendingDeltaReady: Boolean      = false

  /** 这个 tab 此刻正在产出一轮回答。 */
  var streaming: Boolean = false

  /** 这一轮开始时这个 tab 的归属。 */
  var turnAssoc: Option[WebChatHost] = None

  /** 劫持时截下来的用户原话。 */
  var hijackUserText: String  = ""
  var lastErrorId: String     = ""
  var lastErrorDetail: String = ""
  var lastErrorAt: Double     = 0.0

  /** 本轮改写前看到的用户原话，跨重新 arm / 重挂保留。 */
  var turnUserText: String = ""

  /** None = 尚未采样；Some(true) = 插件页任务驱动；Some(false) = 网页原生驱动。 */
  var turnTaskDriven: Option[Boolean] = None

  /** 刚新建 session 后，这个 tab 上出现的下一个新对话 URL 绑到哪条 session。 */
  var pendingAssoc: Option[String] = None
  var pendingAssocAt: Double       = 0.0
}

/** A native conversation currently running the plugin-page agent loop. */
final private[background] case class NativeRun(
    runId: String,
    sessionId: String,
    provider: String,
    convKey: String,
    tabId: Int,
    host: WebChatHost,
    pageTabId: Int,
    assistantId: String
)

/** Run-token registry; all indexing concerns stay outside turn lifecycle code. */
final private[background] class NativeRunRegistry {
  private val runs = mutable.Map.empty[String, NativeRun]

  def put(run: NativeRun): Unit =
    runs.update(run.runId, run)

  def remove(runId: String): Option[NativeRun] =
    runs.remove(runId)

  def findForSession(sessionId: String): Option[NativeRun] =
    runs.values.find(_.sessionId == sessionId)

  def find(sessionId: String, provider: String): Option[NativeRun] =
    runs.values.find(run => run.sessionId == sessionId && run.provider == provider)

  def exists(sessionId: String, provider: String): Boolean =
    find(sessionId, provider).isDefined

  def forTab(tabId: Int): List[NativeRun] =
    runs.values.filter(run => run.pageTabId == tabId || run.tabId == tabId).toList
}
