package com.just3bowls.background.runtime

import scala.scalajs.js
enum Phase:
  case Running
  case Failed(error: String)
  case Success
enum TabState:
  case Create(phase: Phase)
  case Discarded
  case WarmingUp(phase: Phase)
  case Close(phase: Phase)
final case class Tab(
    tabId: Int,
    state: TabState
)
enum PageOperation:
  case Load
  case Inject
final case class PageState(
    operation: PageOperation,
    phase: Phase
)
final case class Page(
    currentUrl: String,
    state: PageState
)
enum TaskSource:
  case Server
  case Local
enum LocalTaskKind:
  case Capture
  case GenerateRule
  case EnhanceRule
private[background] object LocalTaskKindWire {
  def fromWire(value: String): Option[LocalTaskKind] =
    value match {
      case "capture"       => Some(LocalTaskKind.Capture)
      case "generate_rule" => Some(LocalTaskKind.GenerateRule)
      case "enhance_rule"  => Some(LocalTaskKind.EnhanceRule)
      case _               => None
    }
  def toWire(value: LocalTaskKind): String           =
    value match {
      case LocalTaskKind.Capture      => "capture"
      case LocalTaskKind.GenerateRule => "generate_rule"
      case LocalTaskKind.EnhanceRule  => "enhance_rule"
    }
}
enum TaskOperation:
  case ResetChat
  case TypePrompt
  case ClickSend
  case AwaitResponse
final case class TaskState(
    operation: TaskOperation,
    phase: Phase
)
final case class Task(
    taskId: String,
    tabId: Int,
    source: TaskSource,
    localTaskKind: Option[LocalTaskKind],
    startedAt: Double,
    finishedAt: Option[Double],
    state: TaskState
)

/**
 * 一次 run 的"对话策略"：这个 provider 对话是复用同一段，还是每轮都开新对话。
 *
 * 由上下文模式推导（见 `BackgroundMain`）：`delta` ⇒ `Reuse`（靠 provider 页自己累积的上下文只投增量）；`full` ⇒ `Fresh`（每轮 New Chat
 * 复位后投整份上下文）；没有会话的 run（server 转发 / 抓包）默认 `Fresh`。
 */
enum ConversationPolicy:
  case Reuse
  case Fresh
final case class ManagedTab(
    tab: Tab,
    page: Page,
    keyId: String,
    /**
     * 用过这个 slot 的会话 id（去重、最近使用在后、上限见 `ManagedTabManager`）。
     *
     * 只服务「这条会话涉及哪些 tab」的会话级视图：provider slot tab 跨会话共享，所以同一段 tab 会同时挂在多个会话名下。
     */
    usedBySessionIds: List[String],
    updatedAt: Double
) {

  /**
   * 当前真正活着的 tab id。
   *
   * slot 的 `tabId` 是 `Option` 语义：`0` 表示"这个 (provider, key) 现在没有活着的 tab"（tab 被关掉/被换掉了）， slot 本身连同使用记录 /
   * 任务记录一起保留，下次跑任务时复用或采纳。
   */
  def liveTabId: Option[Int] = Option(tab.tabId).filter(_ > 0)

  /** 记一次「这条会话用过这个 slot」：已有则挪到末尾（最近使用在后）。 */
  def withSessionUsage(sessionId: String, limit: Int): ManagedTab =
    if (sessionId.isEmpty || usedBySessionIds.lastOption.contains(sessionId)) this
    else copy(
      usedBySessionIds = (usedBySessionIds.filterNot(_ == sessionId) :+ sessionId).takeRight(limit),
      updatedAt = js.Date.now()
    )
}
object ManagedTab {

  /** 新建 tab 时先落库的占位 slot：tabId 已知、页面状态还没走完。 */
  def fresh(keyId: String, tabId: Int, url: String, now: Double): ManagedTab =
    ManagedTab(
      tab = Tab(tabId, TabState.Create(Phase.Running)),
      page = Page(currentUrl = url, state = PageState(PageOperation.Inject, Phase.Running)),
      keyId = keyId,
      usedBySessionIds = Nil,
      updatedAt = now
    )
}
final case class TabGroup(
    modelName: String,
    /** key = keyId（不是 tabId）：身份跟着 (provider, key) 走，tab 换掉也不丢偏好。 */
    tabs: Map[String, ManagedTab]
)
object TabGroup {
  def empty(modelName: String): TabGroup = TabGroup(modelName, Map.empty)
}

/**
 * 内存里的世界状态（分片持久化见 `TabStore`）。
 *
 * 只保留真正被读取的字段：面板 tab（= host window）的真相是 `ServerTabManager.panelTab()`，`TabGroup.groupId` 的真相在 `ProviderTabGroup`， 任务记录在
 * `TabTasks`（内存），历史 revision 已无消费者 —— 都不再进这里。
 */
final case class PluginWindow(
    groups: Map[String, TabGroup]
)
object PluginWindow {
  val Empty: PluginWindow = PluginWindow(Map.empty)
}

/** Phase → 面板展示用的短文本（Tabs 面板与后台快照共用）。 */
private[background] def phaseText(phase: Phase): String =
  phase match {
    case Phase.Running       => "running"
    case Phase.Failed(error) => s"failed:${error}"
    case Phase.Success       => "success"
  }
