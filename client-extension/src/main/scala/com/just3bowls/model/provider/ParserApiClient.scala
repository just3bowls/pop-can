package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.provider.ParserProviderLocal
import com.just3bowls.model.provider.ProviderAttachment
import com.just3bowls.model.provider.ProviderNetwork
import com.just3bowls.model.provider.ProviderPage
import com.just3bowls.model.provider.ProviderPresets
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 面板 / 会话看到的 provider 视图对象。
 *
 * 字段就是运行时真正要用的那几样（选择器、规则、附件策略、followUp）；抓包样本、校验结果、激活历史那些安装期工件已经随 capture 流程一起删掉。
 */
final case class ParserProviderInfo(
    id: String,
    name: String,
    page: ProviderPage = ProviderPage(),
    network: ProviderNetwork = ProviderNetwork(),
    attachment: ProviderAttachment = ProviderAttachment(),
    /** `active` = 用户启用了这一家（chat / key 路由只认它）；`draft` = 装着但没启用。 */
    status: String,
    parserRule: Option[String]
) {
  def pageUrl: String                = page.url
  def inputSelector: String          = page.selectors.input
  def newChatSelector: String        = page.selectors.newChat
  def fingerprints                   = page.fingerprints
  def apiPatterns: List[String]      = network.apiPatterns
  def followUpPatterns: List[String] = network.followUpPatterns
  def followUpIdentityPath: String   = network.followUpIdentityPath
  def attachmentStrategy: String     = attachment.strategy
  def attachmentSelector: String     = attachment.selector
}
object ParserProviderInfo {
  def fromLocal(local: ParserProviderLocal): ParserProviderInfo =
    ParserProviderInfo(
      id = local.id,
      name = local.name,
      page = local.page,
      network = local.network,
      attachment = local.attachment,
      status = local.status,
      parserRule = Option(local.parserRule).filter(_.trim.nonEmpty)
    )
}

/**
 * provider 的读写入口（面板与插件页共用）。
 *
 * 这里只做三件事：列出来、启用/停用、删掉。**新增与内容更新都不在这里**——目录由已安装 Provider Pack 与面板 `Import` 决定，入口在
 * [[com.just3bowls.model.provider.ProviderPresets]]。
 *
 * Provider 一律是本地存储（`chrome.storage.local["parserProvider:<id>"]`），不走 server。
 */
object ParserApiClient {
  def list(): Future[List[ParserProviderInfo]] =
    ParserProviderLocal.list().map(_.map(ParserProviderInfo.fromLocal))

  /** 启用 / 停用：只改 `status`，不碰内容与血缘。 */
  def setEnabled(id: String, enabled: Boolean): Future[Unit] =
    ParserProviderLocal.getProvider(id).flatMap {
      case Some(local) =>
        ParserProviderLocal
          .save(local.copy(status = if (enabled) "active" else "draft"))
          .flatMap(_ => refreshProviderContent())
      case None        => Future.successful(())
    }

  /** 删掉一家（内置的会记 tombstone，重启后不会被随包预设塞回来）。 */
  def delete(id: String): Future[Unit] = ProviderPresets.deleteProvider(id)

  /**
   * 权限事件可能早于 `status=active` 落盘；这里在状态写完后显式让后台再刷新一次，保证首次启用时已经打开的 provider tab 也补上 marker / 胶囊。
   */
  def refreshProviderContent(): Future[Unit] =
    ChromeAPI.runtime
      .sendMessage(JsBridge.obj("type" -> ("PROVIDER_CONTENT_REFRESH")))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }
}
