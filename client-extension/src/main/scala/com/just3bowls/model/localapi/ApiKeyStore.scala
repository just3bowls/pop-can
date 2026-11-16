package com.just3bowls.model.localapi

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.diagnostics.*

import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.api.provider.ServerRouting
import com.just3bowls.facade.{ApiKeyRouteStorage, ApiKeyStorage, ChromeAPI}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** server 转发的一个候选项：provider + 权重（≥1）+ 这一家的 prompt 投递模式。 */
final case class ApiKeyRoute(provider: String, weight: Int, promptMode: String = RoutePromptMode.Inline)
final case class ApiKeyConfig(
    id: String,
    secret: String,
    name: String,
    revoked: Boolean,
    createdAt: Double,
    lastUsedAt: Double,
    /**
     * server 转发的候选 provider（空 = 未配置，请求会被拒）。
     *
     * 客户端固定只用 `ServerRouting.ModelName` 这一个模型名，落到哪家由这里决定；见 `Config → API keys` 每个 key 的 Routing。
     */
    routes: List[ApiKeyRoute] = Nil,
    /** `weighted`（加权轮询，默认）/ `random`（按权重随机）。 */
    strategy: String = ServerRouting.StrategyWeighted,
    /** `off`（默认）/ `next`：本轮还没给客户端写过内容就失败时，换下一个候选重试。 */
    failover: String = ServerRouting.FailoverOff
)
object ApiKeyConfig {
  def fromStorage(raw: ApiKeyStorage): ApiKeyConfig =
    ApiKeyConfig(
      id = raw.id.getOrElse(""),
      secret = raw.secret.getOrElse(""),
      name = raw.name.getOrElse(""),
      revoked = raw.revoked.getOrElse(false),
      createdAt = raw.createdAt.getOrElse(js.Date.now()),
      lastUsedAt = raw.lastUsedAt.getOrElse(0.0),
      routes = raw.routes.toOption
        .map(_.toList.map(route =>
          ApiKeyRoute(
            provider = route.provider.getOrElse(""),
            weight = route.weight.getOrElse(1),
            promptMode = RoutePromptMode.normalize(route.promptMode.getOrElse(""))
          )
        ))
        .getOrElse(Nil)
        .filter(route => route.provider.trim.nonEmpty)
        .map(route => route.copy(provider = route.provider.trim, weight = math.max(1, route.weight))),
      strategy =
        if (raw.strategy.toOption.contains(ServerRouting.StrategyRandom)) ServerRouting.StrategyRandom
        else ServerRouting.StrategyWeighted,
      failover =
        if (raw.failover.toOption.contains(ServerRouting.FailoverNext)) ServerRouting.FailoverNext
        else ServerRouting.FailoverOff
    )
  def toStorage(key: ApiKeyConfig): ApiKeyStorage   =
    JsBridge.obj(
      "id"         -> (key.id),
      "secret"     -> (key.secret),
      "name"       -> (key.name),
      "revoked"    -> (key.revoked),
      "createdAt"  -> (key.createdAt),
      "lastUsedAt" -> (key.lastUsedAt),
      "routes"     -> (js.Array(key.routes.map { route =>
        JsBridge.obj("provider" -> (route.provider), "weight" -> (route.weight), "promptMode" -> (route.promptMode))
          .asInstanceOf[ApiKeyRouteStorage]
      }*)),
      "strategy"   -> (key.strategy),
      "failover"   -> (key.failover)
    )
      .asInstanceOf[ApiKeyStorage]
}
object ApiKeyStore  {
  val SystemApiKeyId           = "system-default"
  val SystemApiKeyName         = "System Default"
  val SystemApiKeySecretPrefix = "th_sys_"

  /** 整张 key 表的存储键（`chrome.storage.local`）；插件页 / SW 都靠它监听"全局 prompt 模式变了"。 */
  val StorageKey                         = "popCanApiKeys"
  def load(): Future[List[ApiKeyConfig]] =
    ChromeAPI.storage.local.get(StorageKey).toFuture.flatMap { result =>
      val stored     = result.get(StorageKey) match {
        case Some(raw) =>
          JsBridge.asTypedArray[ApiKeyStorage](raw).toList.map(ApiKeyConfig.fromStorage)
        case None      =>
          Nil
      }
      val normalized = {
        val deduped = dedupe(stored).map(key =>
          if (key.id == SystemApiKeyId) key.copy(name = SystemApiKeyName) else key
        )
        if (deduped.exists(key => key.id == SystemApiKeyId && !key.revoked)) deduped
        else systemApiKey() :: deduped.filterNot(_.id == SystemApiKeyId)
      }
      if (normalized != stored) {
        val dropped = stored.filterNot(key => normalized.contains(key))
        if (dropped.nonEmpty)
          PopCanLog.warn(
            "keys",
            s"normalized keys, dropped: " + dropped.map(key => s"id=${key.id}").mkString(", ")
          )
        save(normalized).map(_ => normalized)
      } else {
        Future.successful(normalized)
      }
    }

  /** 同一个 id 只保留一条：优先未撤销的，其次保留数组里靠前的。 */
  private def dedupe(keys: List[ApiKeyConfig]): List[ApiKeyConfig] = {
    val seen = scala.collection.mutable.LinkedHashMap.empty[String, ApiKeyConfig]
    keys.foreach { key =>
      seen.get(key.id) match {
        case Some(existing) => if (existing.revoked && !key.revoked) seen.update(key.id, key)
        case None           => seen.update(key.id, key)
      }
    }
    seen.values.toList
  }

  /** 记录某个 key 最近一次被使用的时间；同一 key 在 throttleMs 内不重复写存储。 */
  def touchKey(id: String, throttleMs: Double = 30000.0): Future[Option[List[ApiKeyConfig]]] =
    load().flatMap { keys =>
      val now = js.Date.now()
      keys.find(_.id == id).map(_.lastUsedAt) match {
        case Some(lastUsed) if now - lastUsed >= throttleMs =>
          val next = touch(keys, id, now)
          save(next).map(_ => Some(next))
        case _                                              =>
          Future.successful(None)
      }
    }
  def save(keys: List[ApiKeyConfig]): Future[Unit]                                           =
    ChromeAPI.storage.local.set(
      js.Dictionary[js.Any](
        StorageKey -> JsBridge.asObject(js.Array(keys.map(ApiKeyConfig.toStorage)*))
      )
    ).toFuture.map(_ => ())
  def generateKey(name: String = ""): ApiKeyConfig                                           = {
    val now = js.Date.now()
    ApiKeyConfig(
      id = s"key_${now}_${randomSuffix(4)}",
      secret = s"th_${randomSecret(24)}",
      name = name.trim,
      revoked = false,
      createdAt = now,
      lastUsedAt = 0.0
    )
  }
  def systemApiKey(): ApiKeyConfig                                                           =
    ApiKeyConfig(
      id = SystemApiKeyId,
      secret = s"$SystemApiKeySecretPrefix${randomHex(32)}",
      name = SystemApiKeyName,
      revoked = false,
      createdAt = js.Date.now(),
      lastUsedAt = 0.0
    )
  def rename(keys: List[ApiKeyConfig], id: String, name: String): List[ApiKeyConfig]         =
    keys.map(key => if (key.id == id) key.copy(name = name.trim) else key)

  /** 设置"这个 key 的 server 请求怎么落到候选 provider"（空列表 = 未配置，请求会被拒）。 */
  def setRouting(
      keys: List[ApiKeyConfig],
      id: String,
      routes: List[ApiKeyRoute],
      strategy: String,
      failover: String
  ): List[ApiKeyConfig] =
    keys.map { key =>
      if (key.id != id) key
      else
        key.copy(
          routes = routes
            .filter(route => route.provider.trim.nonEmpty)
            .map(route =>
              route.copy(
                provider = route.provider.trim,
                weight = math.max(1, route.weight),
                promptMode = RoutePromptMode.normalize(route.promptMode)
              )
            ),
          strategy =
            if (strategy == ServerRouting.StrategyRandom) ServerRouting.StrategyRandom
            else ServerRouting.StrategyWeighted,
          failover =
            if (failover == ServerRouting.FailoverNext) ServerRouting.FailoverNext else ServerRouting.FailoverOff
        )
    }
  def touch(keys: List[ApiKeyConfig], id: String, at: Double = js.Date.now()): List[ApiKeyConfig] =
    keys.map(key => if (key.id == id && !key.revoked) key.copy(lastUsedAt = math.max(key.lastUsedAt, at)) else key)
  private def randomSuffix(length: Int): String                                                   = {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val sb    = new StringBuilder
    var i     = 0
    while (i < length) {
      sb.append(chars.charAt(js.Math.floor(js.Math.random() * chars.length).toInt))
      i += 1
    }
    sb.result()
  }
  private def randomSecret(length: Int): String                                                   = {
    val bytes  = new js.typedarray.Uint8Array(length)
    JsBridge.crypto.getRandomValues(bytes)
    val binary = new StringBuilder
    var i      = 0
    while (i < length) {
      binary.append(JsBridge.stringConstructor.fromCharCode(bytes(i).toInt).toString)
      i += 1
    }
    JsBridge.asString(JsBridge.btoa(binary.result()))
      .replaceAll("\\+", "-")
      .replaceAll("/", "_")
      .replaceAll("=+$", "")
  }
  private def randomHex(length: Int): String                                                      = {
    val chars = "0123456789abcdef"
    val sb    = new StringBuilder
    var i     = 0
    while (i < length) {
      sb.append(chars.charAt(js.Math.floor(js.Math.random() * chars.length).toInt))
      i += 1
    }
    sb.result()
  }
}
