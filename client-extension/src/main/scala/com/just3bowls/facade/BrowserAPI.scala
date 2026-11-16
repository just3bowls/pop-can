package com.just3bowls.facade
import scala.scalajs.js
import scala.scalajs.js.annotation._

/** Chrome 全局 API */
@js.native
@JSGlobal("chrome")
object ChromeAPI extends Browser

/** browser.* 根接口 */
@js.native
trait Browser extends js.Object {
  val runtime: Runtime             = js.native
  val tabs: Tabs                   = js.native
  val windows: Windows             = js.native
  val tabGroups: TabGroups         = js.native
  val storage: Storage             = js.native
  val notifications: Notifications = js.native
  val action: Action               = js.native
  val debugger: Debugger           = js.native
  val alarms: Alarms               = js.native
  val scripting: Scripting         = js.native
  val permissions: Permissions     = js.native
  val search: Search               = js.native
  // …根据需要再加其他命名空间
}

/** browser.runtime.* */
@js.native
trait Runtime extends js.Object {
  def sendMessage(message: js.Any, options: js.UndefOr[js.Object] = js.undefined): js.Promise[js.Any] = js.native
  def getURL(path: String): String                                                                    = js.native
  def getManifest(): ExtensionManifest                                                                = js.native
  def onMessage: EventSignal[js.Function3[js.Any, MessageSender, js.Function1[js.Any, Unit], js.Any]] = js.native

  /**
   * native messaging：连上本地 host（host 名由 manifest 里的 `allowed_origins` 绑定扩展 ID）。
   *
   * 返回的 Port：`postMessage(obj)` / `onMessage.addListener` / `onDisconnect.addListener` / `disconnect()`； host 进程由
   * Chrome 拉起并随这个 port 存活 —— **disconnect 就是关闭服务**。
   */
  def connectNative(application: String): js.Dynamic = js.native

  /** 本扩展的 ID：装 native host 时要写进 manifest 的 `allowed_origins`。 */
  def id: String = js.native

  /** `connectNative` 失败原因（例如 host 未安装）挂在 `runtime.lastError` 上，在 `onDisconnect` 里读。 */
  def lastError: js.UndefOr[js.Dynamic] = js.native
}

@js.native
trait ExtensionManifest extends js.Object {
  def version: String = js.native
}

/** browser.tabs.* */
@js.native
trait Tabs extends js.Object {
  def query(queryInfo: js.Dictionary[js.Any]): js.Promise[js.Array[Tab]]                           = js.native
  def getCurrent(): js.Promise[Tab]                                                                = js.native
  def create(createProperties: js.Dictionary[js.Any]): js.Promise[Tab]                             = js.native
  def get(tabId: Int): js.Promise[Tab]                                                             = js.native
  def update(tabId: Int, updateProperties: js.Dictionary[js.Any]): js.Promise[Tab]                 = js.native
  def reload(tabId: Int, reloadProperties: js.UndefOr[js.Object] = js.undefined): js.Promise[Unit] = js.native
  def warmup(tabId: Int): js.Promise[Unit]                                                         = js.native
  def remove(tabIds: Int | js.Array[Int]): js.Promise[Unit]                                        = js.native
  def group(options: js.Dictionary[js.Any]): js.Promise[Int]                                       = js.native
  def sendMessage(
      tabId: Int,
      message: js.Any,
      options: js.UndefOr[js.Object] = js.undefined
  ): js.Promise[js.Any] = js.native

  /** 监听 tab 更新事件（URL 变化、加载状态变化等） */
  val onUpdated: EventSignal[js.Function3[Int, TabChangeInfo, Tab, Unit]] = js.native

  /** 监听 tab 关闭事件 */
  val onRemoved: EventSignal[js.Function2[Int, TabRemoveInfo, Unit]] = js.native

  /** 监听活动 tab 变化（网页原生会话用它决定谁该开接管窗口）。 */
  val onActivated: EventSignal[js.Function1[TabActiveInfo, Unit]] = js.native

  /** 监听新建 tab（Tabs 面板实时刷新用）。 */
  val onCreated: EventSignal[js.Function1[Tab, Unit]] = js.native

  /** 监听 tab 移动 / 归组变化（拖进拖出组）。 */
  val onMoved: EventSignal[js.Function2[Int, js.Object, Unit]]    = js.native
  val onAttached: EventSignal[js.Function2[Int, js.Object, Unit]] = js.native
  val onDetached: EventSignal[js.Function2[Int, js.Object, Unit]] = js.native
}

/** tab 激活事件信息 */
@js.native
trait TabActiveInfo extends js.Object {
  val tabId: js.UndefOr[Int]    = js.native
  val windowId: js.UndefOr[Int] = js.native
}

/** tab 更新事件信息 */
@js.native
trait TabChangeInfo extends js.Object {
  val status: js.UndefOr[String]           = js.native
  val url: js.UndefOr[String]              = js.native
  val groupId: js.UndefOr[Int]             = js.native
  val discarded: js.UndefOr[Boolean]       = js.native
  val autoDiscardable: js.UndefOr[Boolean] = js.native
}

/** tab 移除事件信息 */
@js.native
trait TabRemoveInfo extends js.Object {
  val windowId: Int            = js.native
  val isWindowClosing: Boolean = js.native
}

/** runtime 消息发送方信息 */
@js.native
trait MessageSender extends js.Object {
  val id: js.UndefOr[String]           = js.native
  val tab: js.UndefOr[Tab]             = js.native
  val frameId: js.UndefOr[Int]         = js.native
  val url: js.UndefOr[String]          = js.native
  val origin: js.UndefOr[String]       = js.native
  val tlsChannelId: js.UndefOr[String] = js.native
}

/** 单个标签页对象 */
@js.native
trait Tab extends js.Object {
  val id: js.UndefOr[Int]                  = js.native
  val windowId: js.UndefOr[Int]            = js.native
  val groupId: js.UndefOr[Int]             = js.native
  val index: js.UndefOr[Int]               = js.native
  val active: js.UndefOr[Boolean]          = js.native
  val openerTabId: js.UndefOr[Int]         = js.native
  val url: js.UndefOr[String]              = js.native
  val title: js.UndefOr[String]            = js.native
  val favIconUrl: js.UndefOr[String]       = js.native
  val status: js.UndefOr[String]           = js.native
  val pinned: js.UndefOr[Boolean]          = js.native
  val discarded: js.UndefOr[Boolean]       = js.native
  val autoDiscardable: js.UndefOr[Boolean] = js.native
}

/** browser.alarms.* */
@js.native
trait Alarms extends js.Object {
  def create(name: String, alarmInfo: js.Dictionary[js.Any]): Unit = js.native
  def clear(name: String): js.Promise[Boolean]                     = js.native
  val onAlarm: EventSignal[js.Function1[Alarm, Unit]]              = js.native
}
@js.native
trait Alarm  extends js.Object {
  val name: js.UndefOr[String]            = js.native
  val scheduledTime: js.UndefOr[Double]   = js.native
  val periodInMinutes: js.UndefOr[Double] = js.native
}

/** browser.windows.* */
@js.native
trait Windows extends js.Object {
  def create(queryOptions: js.Dictionary[js.Any]): js.Promise[Window]                                    = js.native
  def get(
      windowId: Int,
      queryOptions: js.UndefOr[js.Dictionary[js.Any]] = js.undefined
  ): js.Promise[Window] = js.native
  def getLastFocused(queryOptions: js.UndefOr[js.Dictionary[js.Any]] = js.undefined): js.Promise[Window] = js.native
  def update(windowId: Int, updateProperties: js.Dictionary[js.Any]): js.Promise[Window]                 = js.native
}

/** 单个浏览器窗口对象 */
@js.native
trait Window extends js.Object {
  val id: js.UndefOr[Int]          = js.native
  val focused: js.UndefOr[Boolean] = js.native
}

/** browser.tabGroups.* */
@js.native
trait TabGroups extends js.Object {
  def get(groupId: Int): js.Promise[TabGroup]                                                            = js.native
  def update(groupId: Int, updateProperties: js.Dictionary[js.Any]): js.Promise[TabGroup]                = js.native
  def query(queryInfo: js.UndefOr[js.Dictionary[js.Any]] = js.undefined): js.Promise[js.Array[TabGroup]] = js.native

  /** 组的增删改（改名 / 改色 / 关组）都要让 Tabs 面板跟着变。 */
  val onCreated: EventSignal[js.Function1[TabGroup, Unit]]  = js.native
  val onUpdated: EventSignal[js.Function1[TabGroup, Unit]]  = js.native
  val onRemoved: EventSignal[js.Function1[js.Object, Unit]] = js.native
  val onMoved: EventSignal[js.Function1[js.Object, Unit]]   = js.native
}

/** browser.search.*：只使用浏览器当前默认搜索引擎，不接受引擎参数。 */
@js.native
trait Search   extends js.Object {
  def query(queryInfo: js.Dictionary[js.Any]): js.Promise[Unit] = js.native
}
@js.native
trait TabGroup extends js.Object {
  val id: Int                   = js.native
  val title: js.UndefOr[String] = js.native
  val color: js.UndefOr[String] = js.native
}

/** browser.storage.* */
@js.native
trait Storage extends js.Object {
  val local: StorageArea = js.native
  val sync: StorageArea  = js.native

  /** MV3 service worker 生命周期内的存储；浏览器重启即清空。 */
  val session: StorageArea                                                             = js.native
  val onChanged: EventSignal[js.Function2[js.Dictionary[StorageChange], String, Unit]] = js.native
}

/** storage 值变化信息 */
@js.native
trait StorageChange extends js.Object {
  val oldValue: js.UndefOr[js.Any] = js.native
  val newValue: js.UndefOr[js.Any] = js.native
}
@js.native
trait StorageArea   extends js.Object {

  /** 获取存储数据；keys 可选，支持 string / string[] / object */
  def get(keys: js.UndefOr[String | js.Array[String] | js.Object] = js.undefined): js.Promise[js.Dictionary[js.Any]] =
    js.native

  /**
   * 只取 key 列表（不取值）：消息 / 会话按"一条记录一个 key"存，读一侧要先知道有哪些 key。
   *
   * Chrome 130+ 才有这个 API，调用点必须带 `get()` + 前缀过滤的兜底。
   */
  def getKeys(): js.Promise[js.Array[String]]                   = js.native
  def set(items: js.Dictionary[js.Any]): js.Promise[Unit]       = js.native
  def remove(keys: String | js.Array[String]): js.Promise[Unit] = js.native
  def clear(): js.Promise[Unit]                                 = js.native
}

/** browser.notifications.* */
@js.native
trait Notifications extends js.Object {
  def create(options: js.Dictionary[js.Any]): js.Promise[String]                         = js.native
  def create(notificationId: String, options: js.Dictionary[js.Any]): js.Promise[String] = js.native
  def clear(id: String): js.Promise[Boolean]                                             = js.native
}

/** browser.action.* - 用于控制扩展图标上的 Badge */
@js.native
trait Action extends js.Object {

  /** 设置 Badge 文字 */
  def setBadgeText(options: js.Dictionary[js.Any]): js.Promise[Unit] = js.native

  /** 设置 Badge 背景色 */
  def setBadgeBackgroundColor(options: js.Dictionary[js.Any]): js.Promise[Unit] = js.native

  /** 点击扩展图标时触发（仅在未设置 default_popup 时有效） */
  val onClicked: EventSignal[js.Function1[Tab, Unit]] = js.native
}

/** browser.debugger.* */
@js.native
trait Debugger  extends js.Object {
  def attach(target: Debuggee, requiredVersion: String): js.Promise[Unit] = js.native
  def detach(target: Debuggee): js.Promise[Unit]                          = js.native

  /** 当前所有可调试 target 及其挂载状态（用来校验"我们以为还挂着"是否属实）。 */
  def getTargets(): js.Promise[js.Array[Debuggee]]                                 = js.native
  def sendCommand(
      target: DebuggerSession,
      method: String,
      commandParams: js.UndefOr[js.Object] = js.undefined
  ): js.Promise[js.UndefOr[js.Object]] = js.native
  val onEvent: EventSignal[js.Function3[DebuggerSession, String, js.Object, Unit]] = js.native
  val onDetach: EventSignal[js.Function2[Debuggee, String, Unit]]                  = js.native
}
@js.native
trait Scripting extends js.Object {
  def executeScript(injection: js.Dictionary[js.Any]): js.Promise[js.Array[js.Dynamic]]    = js.native
  def registerContentScripts(scripts: js.Array[RegisteredContentScript]): js.Promise[Unit] = js.native
  def unregisterContentScripts(filter: js.Dictionary[js.Any]): js.Promise[Unit]            = js.native
}

/** `chrome.scripting.registerContentScripts` 的注册项。 */
@js.native
trait RegisteredContentScript extends js.Object {
  def id: scala.scalajs.js.UndefOr[String]                              = scala.scalajs.js.native
  def js: scala.scalajs.js.UndefOr[scala.scalajs.js.Array[String]]      = scala.scalajs.js.native
  def matches: scala.scalajs.js.UndefOr[scala.scalajs.js.Array[String]] = scala.scalajs.js.native
  def runAt: scala.scalajs.js.UndefOr[String]                           = scala.scalajs.js.native
  def allFrames: scala.scalajs.js.UndefOr[Boolean]                      = scala.scalajs.js.native
  def persistAcrossSessions: scala.scalajs.js.UndefOr[Boolean]          = scala.scalajs.js.native
}
@js.native
trait Permissions             extends js.Object {
  def request(permissions: js.Dictionary[js.Any]): js.Promise[Boolean]  = js.native
  def contains(permissions: js.Dictionary[js.Any]): js.Promise[Boolean] = js.native
  val onAdded: EventSignal[js.Function1[js.Dictionary[js.Any], Unit]]   = js.native
  val onRemoved: EventSignal[js.Function1[js.Dictionary[js.Any], Unit]] = js.native
}

/** Debugger 目标标识 */
@js.native
trait Debuggee extends js.Object {
  val tabId: js.UndefOr[Int]          = js.native
  val extensionId: js.UndefOr[String] = js.native
  val targetId: js.UndefOr[String]    = js.native

  /** 该 target 现在是否挂载着（`chrome.debugger.getTargets()` 返回）。 */
  def attached: js.UndefOr[Boolean] = js.native
}

/** Debugger 会话标识 */
@js.native
trait DebuggerSession extends Debuggee {
  val sessionId: js.UndefOr[String] = js.native
}

/** 事件回调信号，简单封装 onXYZ.addListener */
@js.native
trait EventSignal[Callback] extends js.Object {
  def addListener(cb: Callback): Unit    = js.native
  def removeListener(cb: Callback): Unit = js.native
}
