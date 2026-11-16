package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.diagnostics.*

import com.just3bowls.facade.ChromeAPI
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * Provider origin 权限的唯一入口。
 *
 * `status=active` 只表达用户启用了这一家；它是否真的能被 content script / server 使用，还必须由 Chrome 的 origin grant 决定。这里不缓存权限： 用户可以从扩展面板或
 * `chrome://extensions` 改变授权，缓存很容易变成第二份真相。
 */
object ProviderHostAccess {
  def originPattern(pageUrl: String): String                  = {
    val origin = Option(pageUrl).getOrElse("").trim.replaceFirst("^(https?://[^/]+).*$", "$1")
    if (origin.startsWith("http://") || origin.startsWith("https://")) origin + "/*" else ""
  }
  def hostOf(pageUrl: String): String                         =
    try {
      val parsed = JsBridge.urlConstructor.newInstance(pageUrl.trim)
      JsBridge.undefinedString(parsed.host).toOption.filter(_.nonEmpty).getOrElse(pageUrl)
    } catch {
      case _: Throwable => pageUrl
    }
  private def containsOrigin(origin: String): Future[Boolean] =
    if (origin.isEmpty) Future.successful(false)
    else
      ChromeAPI.permissions
        .contains(js.Dictionary("origins" -> js.Array(origin)))
        .toFuture
        .recover { case e =>
          PopCanLog.warn("permissions", s"contains failed origin=$origin: ${e.getMessage}")
          false
        }
  def contains(pageUrl: String): Future[Boolean]              =
    containsOrigin(originPattern(pageUrl))

  /**
   * 必须在页面点击 / 提交的同步调用栈里进来，`permissions.request` 依赖用户手势。已有授权时 Chrome 会直接 resolve，不会重复弹框。
   */
  def request(pageUrl: String): Future[Boolean] = {
    val origin = originPattern(pageUrl)
    if (origin.isEmpty) Future.successful(false)
    else
      ChromeAPI.permissions
        .request(js.Dictionary("origins" -> js.Array(origin)))
        .toFuture
  }

  /** 一次查询整批 provider；某一家查询失败按未授权处理，避免一个坏 URL 拖垮整个面板。 */
  def readiness(providers: Iterable[ParserProviderLocal]): Future[Map[String, Boolean]]        = {
    val entries = providers.toList.map(provider => provider.id -> provider.pageUrl)
    if (entries.isEmpty) Future.successful(Map.empty)
    else {
      val origins = entries.map(_._2).flatMap(url => Option(originPattern(url)).filter(_.nonEmpty)).distinct
      Future
        .sequence(origins.map(origin => containsOrigin(origin).map(origin -> _)))
        .map(_.toMap)
        .map { byOrigin =>
          entries.map { case (id, url) =>
            val origin = originPattern(url)
            id -> (origin.nonEmpty && byOrigin.getOrElse(origin, false))
          }.toMap
        }
    }
  }
  def filterReady(providers: Iterable[ParserProviderLocal]): Future[List[ParserProviderLocal]] =
    readiness(providers).map(ready => providers.toList.filter(provider => ready.getOrElse(provider.id, false)))
}
