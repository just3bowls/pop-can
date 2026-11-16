package com.just3bowls.facade
import scala.scalajs.js
@js.native
trait ApiKeyStorage      extends js.Object:
  def id: js.UndefOr[String]         = js.native
  def secret: js.UndefOr[String]     = js.native
  def name: js.UndefOr[String]       = js.native
  def revoked: js.UndefOr[Boolean]   = js.native
  def createdAt: js.UndefOr[Double]  = js.native
  def lastUsedAt: js.UndefOr[Double] = js.native

  /** server 转发的候选列表：provider + 权重（空 = 未配置，请求会被拒）。 */
  def routes: js.UndefOr[js.Array[ApiKeyRouteStorage]] = js.native

  /** 路由策略：`weighted`（加权轮询，默认）/ `random`（按权重随机）。 */
  def strategy: js.UndefOr[String] = js.native

  /** 失败转移：`off`（默认）/ `next`（还没给客户端写过内容就换下一个候选）。 */
  def failover: js.UndefOr[String] = js.native
@js.native
trait ApiKeyRouteStorage extends js.Object:
  def provider: js.UndefOr[String] = js.native
  def weight: js.UndefOr[Int]      = js.native

  /** 这条路由（key × provider）的 prompt 投递模式：`inline` / `file`。 */
  def promptMode: js.UndefOr[String] = js.native

/** chat session 的浏览器侧状态（落在 chrome.storage.session）。 */
@js.native
trait BrowserSessionRecord extends js.Object:
  def sessionId: String                           = js.native
  def title: String                               = js.native
  def tabId: Int                                  = js.native
  def windowId: Int                               = js.native
  def groupId: Int                                = js.native
  def nextRef: Int                                = js.native
  def refs: js.UndefOr[js.Dictionary[Int]]        = js.native
  def nodeRefs: js.UndefOr[js.Dictionary[String]] = js.native

  def knownTabs: js.UndefOr[js.Array[Int]] = js.native
  def url: String                          = js.native
  def pageTitle: String                    = js.native
  def scrollX: Double                      = js.native
  def scrollY: Double                      = js.native

  /** 最近一次 browser_scroll 实际滚动的内层容器描述（滚的是 window 时为空）。 */
  def scrollContainer: js.UndefOr[String] = js.native

  /** 快照所属文档的随机标记；页面换文档后 ref 一律作废。 */
  def documentToken: js.UndefOr[String] = js.native

  /** This run's user-referenced tabs, serialized because storage.session is not a schema source of truth. */
  def attachedTabsJson: js.UndefOr[String] = js.native

  /** Tool operation page to restore when the run-scoped tab references are released. */
  def previousTabId: js.UndefOr[Int] = js.native

@js.native
trait WebChatBadgePosition extends js.Object:
  def left: js.UndefOr[Double]       = js.native
  def top: js.UndefOr[Double]        = js.native
  def collapsed: js.UndefOr[Boolean] = js.native

@js.native
trait LastWebRequestBody extends js.Object:
  def url: js.UndefOr[String]      = js.native
  def postData: js.UndefOr[String] = js.native
  def at: js.UndefOr[Double]       = js.native
