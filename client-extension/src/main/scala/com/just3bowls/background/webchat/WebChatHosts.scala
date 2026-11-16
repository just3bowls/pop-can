package com.just3bowls.background.webchat

import com.just3bowls.facade.JsBridge

import com.just3bowls.api.webchat.ConversationUrl
import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 「网页原生会话」的归属表，**主键是原生对话 URL（convKey）**，不是 tab：
 *
 *   - 一段 provider 原生对话挂在一条 session 下，各自记自己的上下文水位线；同一条 session 可以挂多段（跨 provider、或同 provider 的多个对话）。
 *   - tab 只是"当前显示的容器"：切到哪个 tab 就按它当前的 URL 查归属；同一段对话开两个 tab 也自然一致。
 *   - 未归属的对话不在表里 → 不记录、不注入。
 *
 * 落 `chrome.storage.local`：SW 重启、扩展重载都不丢。旧版本按 tabId 存的记录（没有 convKey 字段）读取时直接丢弃——会话与消息不受影响。
 */
final private[background] case class WebChatHost(
    /** 原生对话标识：该对话的完整 URL。 */
    convKey: String,
    sessionId: String,
    provider: String,
    /** 上次接管时 session 的末尾消息 id；增量 = 它之后、且不产生于这段对话的消息。 */
    seedToMessageId: String = "",
    /**
     * 上次插件页投递时**整份 body 的消息条数**（0 = 未知，旧记录天然如此）。
     *
     * 只服务插件页的增量裁剪：body 里工具轮那些消息没有会话 id（合成消息），靠 id 推不动水位线， 于是额外记下"这段对话已经见过这份 body 的前 N 条"，下一轮从第 N 条之后开始投。
     */
    seedToBodyCount: Int = 0,
    /** 观测到的上一轮用户文本，用于判断"重新生成"（文本没变）。 */
    lastUserText: String = "",
    /** AgentToolSurface revision used when this provider conversation was created/bound (empty = legacy). */
    toolProfileId: String = "",
    /**
     * 这段对话最近一次出现在哪个 tab 上（0 = 未知 / 老记录）。
     *
     * 给"URL 细化跟随"用：SW 重启后内存里的 lastBinding 会丢，靠这个字段仍能认定 "这段对话之前就在这个 tab 上"，于是 `/chat` → `/chat/<id>` 这类细化可以把绑定与水位线一起搬过去。
     */
    lastTabId: Int = 0,
    /**
     * 这段对话**最后一次真正跑完一轮**的时间（0 = 还没聊过 / 老记录）。
     *
     * 只由"记完一轮"的两处写（网页原生 `record`、插件页 `commitTip`）；绑定（`assign` / `+ New session` / URL 细化跟随）与换绑都不动它， 所以"last 徽标 / 点
     * session 跳最近一段"能区分"最近聊过"和"最近被写过"。
     */
    lastTurnAt: Double = 0.0,
    updatedAt: Double = 0.0
)
private[background] object WebChatHosts {
  private val Key                                        = "thWebChatHosts"
  private var writeTail: Future[Unit]                    = Future.successful(())
  def list(): Future[List[WebChatHost]]                  =
    ChromeAPI.storage.local
      .get(Key)
      .toFuture
      .map { values =>
        values.get(Key) match {
          case Some(raw) if js.Array.isArray(raw) =>
            JsBridge.asArray(raw).toList
              .filter(entry => entry != null && !js.isUndefined(entry))
              .flatMap(parse)
          case _                                  => Nil
        }
      }
      .recover { case _ => Nil }
  def find(convKey: String): Future[Option[WebChatHost]] =
    if (convKey.isEmpty) Future.successful(None) else list().map(_.find(_.convKey == convKey))

  /**
   * 按"**同一段对话**"查归属：先精确命中，命不中就找 URL 形态不同但仍是同一段对话的记录（一边是另一边的"只增不减"延伸）。
   *
   * 为什么需要：provider 页面会在导航/加载后重写地址 —— gml 实测会带上 `&refer__991=&u_atoken=&u_asig=` 这类一次性参数， 于是"表里记的 URL"和"tab 现在的
   * URL"经常是同一段对话的两种写法。精确匹配会让归属/水位线全部对不上（表现为胶囊 Unassigned、 下一轮退化成全量投递）。命中多条时取最近更新的那条。
   */
  def findConversation(convKey: String): Future[Option[WebChatHost]] =
    if (convKey.isEmpty) Future.successful(None)
    else
      list().flatMap { hosts =>
        hosts.find(_.convKey == convKey) match {
          case some @ Some(_) => Future.successful(some)
          case None           =>
            Future.successful(
              hosts
                .filter(host => ConversationUrl.sameConversation(host.convKey, convKey))
                .sortBy(-_.updatedAt)
                .headOption
            )
        }
      }
  def findBySession(sessionId: String): Future[List[WebChatHost]]    =
    list().map(_.filter(_.sessionId == sessionId))

  /**
   * 写入归属，并保证**同一个 (session, provider) 只留一个当前 URL**：同会话同 provider 的其它 convKey 一并删掉。
   *
   * 会话 URL 列表按这个不变式展示（每个 provider 一行），也避免 `/` 与 `/chat/<id>` 这种残留越堆越多。
   */
  def upsertExclusive(host: WebChatHost): Future[Unit] =
    if (host.convKey.isEmpty) Future.successful(())
    else
      update { hosts =>
        val dropped = hosts.filter(other =>
          other.convKey != host.convKey && other.sessionId == host.sessionId && other.provider == host.provider
        )
        if (dropped.nonEmpty) {
          PopCanLog.info(
            "webchat",
            s"host table: dropping ${dropped.size} other url(s) of session=${host.sessionId} provider=${host.provider}"
          )
        }
        hosts
          .filterNot(other =>
            other.convKey == host.convKey ||
              (other.sessionId == host.sessionId && other.provider == host.provider)
          ) :+ merge(hosts, host)
      }

  /**
   * 合并已有记录：水位线只属于"**同一条会话 × 同一家 provider × 同一段对话**"，跨归属/跨对话一律不带。
   *
   *   - 精确命中同一 convKey（正常写入）→ 保已有字段；
   *   - "URL 延伸"（`/chat` → `/chat/<id>`、追加 query 参数、新对话页 → 会话路由）→ 同一段对话的 URL 细化，水位线跟着走；
   *   - 其余（换 id / 变短 / 参数被改写 / 换绑到别的 session）→ 当全新对话，水位线清空，首投走全量。
   *
   * 另外，写入方（比如插件页推水位线）可能不带 `lastUserText`，合并时保留旧值，别把网页原生用来判"重新生成"的那份抹掉。
   */
  private def merge(hosts: List[WebChatHost], host: WebChatHost): WebChatHost = {
    def sameOwner(other: WebChatHost): Boolean =
      other.sessionId == host.sessionId && other.provider == host.provider
    val previous                               = hosts
      .find(other => sameOwner(other) && other.convKey == host.convKey)
      .orElse(hosts.find(other => sameOwner(other) && ConversationUrl.sameConversation(other.convKey, host.convKey)))
    host.copy(
      // 没有新的真实 id（这一轮 payload 全是合成消息）时保留旧水位线 id。
      seedToMessageId = if (host.seedToMessageId.nonEmpty) host.seedToMessageId
      else previous.map(_.seedToMessageId).getOrElse(""),
      seedToBodyCount = math.max(host.seedToBodyCount, previous.map(_.seedToBodyCount).getOrElse(0)),
      lastUserText = if (host.lastUserText.nonEmpty) host.lastUserText
      else previous.map(_.lastUserText).getOrElse(""),
      toolProfileId = if (host.toolProfileId.nonEmpty) host.toolProfileId
      else previous.map(_.toolProfileId).getOrElse(""),
      // 写入方通常知道更准的 tab（绑定/细化跟随/记完一轮）；没带就保留旧值。
      lastTabId = if (host.lastTabId > 0) host.lastTabId else previous.map(_.lastTabId).getOrElse(0),
      // 只有"记完一轮"的写入会带上 lastTurnAt；绑定类写入取 max 保留旧值，绝不被刷掉。
      lastTurnAt = math.max(host.lastTurnAt, previous.map(_.lastTurnAt).getOrElse(0.0)),
      updatedAt = js.Date.now()
    )
  }

  /**
   * 这条会话"最后一次对话"是哪一段：
   *
   *   1. 有 `lastTurnAt`（新数据）→ 取它最大的那条； 2. 都没有（老记录）→ 在有水位线（`seedToMessageId` 或 `seedToBodyCount`）的记录里取 `updatedAt`
   *      最大的那条； 3. 连水位线都没有（比如刚 `+ New session` 绑了一段还没开聊）→ `None`，UI 不该给任何一段挂 `last`。
   */
  def latestConversationOf(hosts: List[WebChatHost]): Option[WebChatHost] = {
    val withTurns = hosts.filter(_.lastTurnAt > 0)
    if (withTurns.nonEmpty) Some(withTurns.maxBy(_.lastTurnAt))
    else {
      val withWatermark = hosts.filter(host => host.seedToMessageId.nonEmpty || host.seedToBodyCount > 0)
      withWatermark.sortBy(-_.updatedAt).headOption
    }
  }
  def remove(convKey: String): Future[Unit]                               =
    update(hosts => hosts.filterNot(_.convKey == convKey))
  def removeBySession(sessionId: String): Future[Unit]                    =
    update(hosts => hosts.filterNot(_.sessionId == sessionId))

  /** 丢掉已经不存在的对话（tab 关掉且没有别的 tab 还在显示它）。 */
  def prune(liveConvKeys: Set[String]): Future[Unit] =
    update(hosts => hosts.filter(host => liveConvKeys.contains(host.convKey)))

  /** 丢掉指向已经不存在的会话的归属（例如从插件页删掉了会话）。 */
  def pruneOrphans(liveSessionIds: Set[String]): Future[Unit]                         =
    list().flatMap { hosts =>
      val orphans = hosts.filterNot(host => liveSessionIds.contains(host.sessionId))
      if (orphans.isEmpty) Future.successful(()) // 没孤儿就不写，避免再触发一次 storage 变更
      else {
        PopCanLog.info("webchat", s"host table: dropping ${orphans.size} orphan binding(s)")
        update(_.filter(host => liveSessionIds.contains(host.sessionId)))
      }
    }
  private def update(transform: List[WebChatHost] => List[WebChatHost]): Future[Unit] = {
    val result = writeTail.flatMap { _ =>
      list().flatMap { hosts =>
        val next = transform(hosts)
        ChromeAPI.storage.local
          .set(js.Dictionary[js.Any](Key -> toJs(next)))
          .toFuture
          .map(_ => ())
          .recoverWith { case e =>
            PopCanLog.warn("webchat", s"host table save failed: ${e.getMessage}")
            Future.successful(())
          }
      }
    }
    writeTail = result.recover { case _ => () }
    result
  }
  private def toJs(hosts: List[WebChatHost]): js.Array[js.Any]                        =
    js.Array(hosts.map { host =>
      JsBridge.obj(
        "convKey"         -> (host.convKey),
        "sessionId"       -> (host.sessionId),
        "provider"        -> (host.provider),
        "seedToMessageId" -> (host.seedToMessageId),
        "seedToBodyCount" -> (host.seedToBodyCount),
        "lastUserText"    -> (host.lastUserText),
        "toolProfileId"   -> (host.toolProfileId),
        "lastTabId"       -> (host.lastTabId),
        "lastTurnAt"      -> (host.lastTurnAt),
        "updatedAt"       -> (host.updatedAt)
      )
    }*)

  /** 旧格式（按 tabId 存、无 convKey）返回 None：升级时自然被丢掉。 */
  private def parse(entry: js.Any): Option[WebChatHost] = {
    val convKey = JsBridge.stringField(entry, "convKey").getOrElse("")
    if (convKey.isEmpty) None
    else
      Some(
        WebChatHost(
          convKey = convKey,
          sessionId = JsBridge.stringField(entry, "sessionId").getOrElse(""),
          provider = JsBridge.stringField(entry, "provider").getOrElse(""),
          seedToMessageId =
            JsBridge.stringField(entry, "seedToMessageId").getOrElse(""),
          seedToBodyCount =
            JsBridge.intField(entry, "seedToBodyCount").getOrElse(0),
          lastUserText = JsBridge.stringField(entry, "lastUserText").getOrElse(""),
          toolProfileId = JsBridge.stringField(entry, "toolProfileId").getOrElse(""),
          lastTabId = JsBridge.intField(entry, "lastTabId").getOrElse(0),
          lastTurnAt = JsBridge.doubleField(entry, "lastTurnAt").getOrElse(0.0),
          updatedAt = JsBridge.doubleField(entry, "updatedAt").getOrElse(0.0)
        )
      )
  }
}
