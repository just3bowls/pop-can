package com.just3bowls.background.runtime

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 插件窗口状态的持久层 —— **分片 v2**。
 *
 * 旧版把所有东西塞进一个 `popCanPluginWindowState`（实测涨到 15.9MB，其中 14.6MB 是没人读的 `rawEvents`），每次状态变更都是 "整表读 15MB + 整表写 15MB"，一轮发送要串
 * 6–9 次。现在改成：
 *
 *   - 每个 slot 一条权威文档 `thState:v2:slot:<provider>:<keyId>`：`{keyId, provider, tabId, tabState, page, usedBySessionIds,
 *     updatedAt}`
 *   - 结构索引 `thState:v2:meta`（**可重建**）：`{v, providerOrder, groups:{provider:{keyIds}}}`；缺失/损坏时从 slot keys
 *     重建，绝不因为索引问题让一次发送失败
 *   - 迁移标记 `thState:v2:ready`
 *
 * 运行期：SW 是唯一写者，hydrate 一次后全部读走内存；每次变更只把**脏 key** 用一次 `storage.set` 写回（不 debounce），
 * 写失败按退避重试并保留脏标记（下次变更会把它一起带上），永不静默丢。任务记录不在这里（见 `TabTasks`）。
 */
private[background] object TabStore {
  private val MetaKey                                             = "thState:v2:meta"
  private val ReadyKey                                            = "thState:v2:ready"
  private val SlotPrefix                                          = "thState:v2:slot:"
  private def slotKey(provider: String, keyId: String): String    = s"$SlotPrefix$provider:$keyId"
  private def parseSlotKey(key: String): Option[(String, String)] =
    if (!key.startsWith(SlotPrefix)) None
    else {
      val body  = key.stripPrefix(SlotPrefix)
      val index = body.lastIndexOf(':')
      if (index <= 0) None else Some(body.take(index) -> body.drop(index + 1))
    }
  // ---- 进程内状态（SW 生命周期） ----
  private var cached: Option[PluginWindow]                        = None
  private var hydrateF: Future[PluginWindow]                      = null
  private var writeTail: Future[Unit]                             = Future.successful(())

  /** 我们认为**已经落盘**的世界：与 cached 的差集就是脏 key 的来源。 */
  private var persistedSlots: Map[String, ManagedTab]                                 = Map.empty
  private var persistedMetaSig: String                                                = ""
  private var lastError: String                                                       = ""
  def load(): Future[PluginWindow]                                                    = hydrate()
  def update[A](transform: PluginWindow => (PluginWindow, A)): Future[A]              =
    updateAsync { window =>
      try Future.successful(transform(window))
      catch { case e: Throwable => Future.failed(e) }
    }
  def updateAsync[A](transform: PluginWindow => Future[(PluginWindow, A)]): Future[A] = {
    val result = writeTail.flatMap { _ =>
      hydrate().flatMap { window =>
        transform(window).flatMap { case (next, value) =>
          persist(next).map { _ =>
            // 会话 URL 列表实时刷新：任何 slot 写入都推一次最新快照（快照从内存态生成，不再重读 storage）。
            SessionUrls.notifyChanged()
            value
          }
        }
      }
    }
    writeTail = result.map(_ => ()).recover { case _ => () }
    result
  }

  /** 体积/健康度快照（config log 面板显示，让"存储退化"变成看得见的指标）。 */
  def stats(): js.Object                                = {
    val window  = cached.getOrElse(PluginWindow.Empty)
    val slots   = slotMap(window)
    val bytes   = slots.values.map(managed => js.JSON.stringify(writeSlot(managed)).length).sum +
      (if (slots.nonEmpty) js.JSON.stringify(writeMeta(window)).length else 0)
    JsBridge.obj(
      "docs"  -> (slots.size),
      "bytes" -> (bytes),
      "dirty" -> (slots.count { case (key, managed) => !persistedSlots.get(key).contains(managed) }),
      "error" -> (lastError)
    )
  }
  // ---- hydrate ----
  private def hydrate(): Future[PluginWindow]           =
    cached match {
      case Some(window) => Future.successful(window)
      case None         =>
        if (hydrateF == null) hydrateF = doHydrate()
        hydrateF
          .map { window =>
            cached = Some(window)
            window
          }
          // 读失败时不缓存空世界（否则会误判"没有 slot"而去重复开 tab）：本次给空世界，下一次调用重新 hydrate。
          .recover { case e =>
            hydrateF = null
            lastError = Option(e.getMessage).getOrElse("hydrate failed")
            PopCanLog.error("tabs", "tab state hydrate failed; will retry on next call", e)
            PluginWindow.Empty
          }
    }
  private def doHydrate(): Future[PluginWindow]         =
    allKeys().flatMap { keys =>
      val slotKeys = keys.filter(_.startsWith(SlotPrefix))
      val readKeys = (slotKeys :+ MetaKey :+ ReadyKey).distinct
      ChromeAPI.storage.local.get(js.Array(readKeys*)).toFuture.flatMap { values =>
        val ready       =
          values.get(ReadyKey).exists(value => value != null && !js.isUndefined(value) && value.toString == "true")
        val shardWindow = windowFromShards(values.get(MetaKey), slotKeys, values)
        if (!ready) {
          persistedSlots = Map.empty
          persistedMetaSig = ""
          persistFull(shardWindow, readyKey = true)
            .map { _ =>
              PopCanLog.info("tabs", s"tab state initialized: slots=${slotMap(shardWindow).size}")
              shardWindow
            }
            .recover { case e =>
              lastError = Option(e.getMessage).getOrElse("ready marker write failed")
              PopCanLog.error("tabs", "tab state ready marker write failed", e)
              shardWindow
            }
        } else {
          persistedSlots = slotMap(shardWindow)
          persistedMetaSig = metaSignature(shardWindow)
          Future.successful(shardWindow)
        }
      }
    }
  private def windowFromShards(
      metaRaw: Option[js.Any],
      slotKeys: List[String],
      values: js.Dictionary[js.Any]
  ): PluginWindow = {
    val rows: Map[String, List[(String, ManagedTab)]] =
      slotKeys
        .flatMap { key =>
          parseSlotKey(key).flatMap { case (provider, keyId) =>
            values
              .get(key)
              .flatMap(value => readSlotDoc(keyId, JsBridge.asObject(value)))
              .map(managed => (provider, keyId, managed))
          }
        }
        .groupBy(_._1)
        .map { case (provider, list) => provider -> list.map(row => row._2 -> row._3) }
    val orderedProviders: List[String]                = {
      val stored = metaProviderOrder(metaRaw)
      val known  = rows.keys.toList
      stored.filter(known.contains) ++ known.filterNot(stored.contains).sorted
    }
    val groups                                        = orderedProviders.map { provider =>
      val storedKeys = metaKeyIds(metaRaw, provider).getOrElse(Nil)
      val entries    = rows.getOrElse(provider, Nil)
      val ordered    = storedKeys.flatMap(key => entries.find(_._1 == key)) ++
        entries.filterNot(entry => storedKeys.contains(entry._1)).sortBy(_._1)
      provider -> TabGroup(provider, ordered.toMap)
    }.toMap
    PluginWindow(groups)
  }
  // ---- persist ----
  private def persist(next: PluginWindow): Future[Unit] = {
    val nextSlots = slotMap(next)
    val changed   = nextSlots.filter { case (key, managed) => !persistedSlots.get(key).contains(managed) }
    val removed   = persistedSlots.keySet -- nextSlots.keySet
    val sig       = metaSignature(next)
    cached = Some(next)
    attemptWrite(
      next,
      changed.keys.toList,
      removed.toList,
      metaChanged = sig != persistedMetaSig,
      readyKey = false,
      attempt = 0
    )
      .map { _ =>
        persistedSlots = nextSlots
        persistedMetaSig = sig
        lastError = ""
      }
  }

  /** 迁移/重建索引时整份重写。 */
  private def persistFull(window: PluginWindow, readyKey: Boolean): Future[Unit]                 =
    attemptWrite(window, slotMap(window).keys.toList, Nil, metaChanged = true, readyKey = readyKey, attempt = 0)
      .map { _ =>
        persistedSlots = slotMap(window)
        persistedMetaSig = metaSignature(window)
        lastError = ""
      }
  private def attemptWrite(
      window: PluginWindow,
      slotKeysToWrite: List[String],
      keysToRemove: List[String],
      metaChanged: Boolean,
      readyKey: Boolean,
      attempt: Int
  ): Future[Unit] = {
    val slots   = slotMap(window)
    val items   = js.Dictionary.empty[js.Any]
    if (metaChanged) items.update(MetaKey, writeMeta(window))
    slotKeysToWrite.foreach(key => slots.get(key).foreach(managed => items.update(key, writeSlot(managed))))
    if (readyKey) items.update(ReadyKey, true)
    val setF    =
      if (items.isEmpty) Future.successful(()) else ChromeAPI.storage.local.set(items).toFuture.map(_ => ())
    val removeF = if (keysToRemove.isEmpty) Future.successful(()) else removeKeys(keysToRemove)
    setF
      .flatMap(_ => removeF)
      .recoverWith { case e =>
        if (attempt < 3) delay(300 * (attempt + 1)).flatMap(_ =>
          attemptWrite(window, slotKeysToWrite, keysToRemove, metaChanged, readyKey, attempt + 1)
        )
        else {
          lastError = Option(e.getMessage).getOrElse("save failed")
          PopCanLog.error("tabs", s"tab state save failed (kept dirty, will retry on next change): ${lastError}", e)
          Future.failed(e)
        }
      }
  }
  private def removeKeys(keys: List[String]): Future[Unit]                                       =
    if (keys.isEmpty) Future.successful(())
    else ChromeAPI.storage.local.remove(js.Array(keys*)).toFuture.map(_ => ())
  private def allKeys(): Future[List[String]]                                                    = {
    val area       = ChromeAPI.storage.local
    val hasGetKeys = JsBridge.undefinedAny(JsBridge.field(area, "getKeys")).toOption.isDefined
    if (hasGetKeys) area.getKeys().toFuture.map(_.toList)
    else area.get().toFuture.map(_.keys.toList)
  }
  private def delay(ms: Int): Future[Unit]                                                       = {
    val p = Promise[Unit]()
    js.timers.setTimeout(ms)(p.success(()))
    p.future
  }
  // ---- 纯变换（对外 API 形状与旧版一致） ----
  def findTab(window: PluginWindow, modelName: String, keyId: String): Option[(Int, ManagedTab)] =
    findSlot(window, modelName, keyId).map(managed => (managed.tab.tabId, managed))

  /** 按 (provider, keyId) 找 slot：身份与当前有没有 tab 无关。 */
  def findSlot(window: PluginWindow, modelName: String, keyId: String): Option[ManagedTab] =
    window.groups.get(modelName).flatMap(_.tabs.get(keyId))
  def findByTabId(window: PluginWindow, tabId: Int): Option[(String, ManagedTab)]          =
    window.groups.collectFirst {
      case (modelName, group) if group.tabs.values.exists(_.liveTabId.contains(tabId)) =>
        modelName -> group.tabs.values.find(_.liveTabId.contains(tabId)).get
    }

  /** 写入 / 更新一个 slot（按 managed.keyId 定位）。 */
  def upsertSlot(
      window: PluginWindow,
      modelName: String,
      managed: ManagedTab
  ): PluginWindow = {
    val group  = window.groups.getOrElse(modelName, TabGroup.empty(modelName))
    val next   = group.copy(tabs = group.tabs.updated(managed.keyId, managed))
    val groups = window.groups.updated(modelName, next)
    window.copy(groups = groups)
  }

  /** 删掉一个 slot（整个 key 被释放时用；用户只是关掉 tab 时应该保留 slot、把 tabId 置 0）。 */
  def removeSlot(
      window: PluginWindow,
      modelName: String,
      keyId: String
  ): PluginWindow =
    window.groups.get(modelName) match {
      case Some(group) =>
        val next   = group.copy(tabs = group.tabs - keyId)
        val groups =
          if (next.tabs.isEmpty) window.groups - modelName
          else window.groups.updated(modelName, next)
        window.copy(groups = groups)
      case None        =>
        window
    }

  /** 所有 slot（provider 名 + slot）。 */
  def allSlots(window: PluginWindow): List[(String, ManagedTab)] =
    window.groups.values.toList.flatMap(group => group.tabs.values.toList.map(managed => group.modelName -> managed))

  /** tab 被关掉/失效时把 slot 的 tabId 置 0 —— slot 与 focus/会话使用记录都保留。 */
  def detachSlot(window: PluginWindow, tabId: Int): PluginWindow                      =
    allSlots(window).foldLeft(window) { case (acc, (modelName, managed)) =>
      if (managed.liveTabId.contains(tabId))
        upsertSlot(acc, modelName, managed.copy(tab = managed.tab.copy(tabId = 0), updatedAt = js.Date.now()))
      else acc
    }
  // ---- wire ----
  /** key = 完整 slot key，value = slot 文档；provider 可从 key 反解。 */
  private def slotMap(window: PluginWindow): Map[String, ManagedTab]                  =
    window.groups.toList
      .flatMap { case (provider, group) =>
        group.tabs.toList.map { case (keyId, managed) => slotKey(provider, keyId) -> managed }
      }
      .toMap
  private def metaSignature(window: PluginWindow): String                             =
    window.groups.toList
      .map { case (provider, group) => s"$provider=${group.tabs.keys.mkString(",")}" }
      .mkString("|")
  private def metaProviderOrder(raw: Option[js.Any]): List[String]                    =
    raw
      .flatMap(JsBridge.undefinedAny(_).toOption)
      .flatMap(meta => JsBridge.undefinedStringArray(JsBridge.field(meta, "providerOrder")).toOption)
      .map(_.toList.filter(_.nonEmpty))
      .getOrElse(Nil)
  private def metaKeyIds(raw: Option[js.Any], provider: String): Option[List[String]] =
    raw
      .flatMap(JsBridge.undefinedAny(_).toOption)
      .flatMap(meta => JsBridge.undefinedDictionary(JsBridge.field(meta, "groups")).toOption)
      .flatMap(groups => groups.get(provider))
      .flatMap(group => JsBridge.undefinedStringArray(JsBridge.field(group, "keyIds")).toOption)
      .map(_.toList.filter(_.nonEmpty))
  private def writeMeta(window: PluginWindow): js.Any                                 = {
    val order  = window.groups.keys.toList
    val groups = js.Dictionary.empty[js.Any]
    window.groups.foreach { case (provider, group) =>
      groups.update(
        provider,
        JsBridge.obj("keyIds" -> (js.Array(group.tabs.keys.toList*)))
      )
    }
    JsBridge.obj("v" -> (2), "providerOrder" -> (js.Array(order*)), "groups" -> (groups))
  }

  /** slot 的 wire 形态：keyId 是键，tabId 是当前值（0 = 现在没有活着的 tab）。 */
  private def writeSlot(tab: ManagedTab): js.Any                                         =
    JsBridge.obj(
      "keyId"            -> (tab.keyId),
      "tabId"            -> (tab.tab.tabId),
      "updatedAt"        -> (tab.updatedAt),
      "usedBySessionIds" -> (js.Array(tab.usedBySessionIds.toSeq*)),
      "tab"              -> (writeTabState(tab.tab.state)),
      "page" -> (JsBridge.obj("currentUrl" -> (tab.page.currentUrl), "state" -> (writePageState(tab.page.state))))
    )
  private def readSlotDoc(keyId: String, raw: js.Any): Option[ManagedTab]                =
    try {
      val parsedKeyId =
        JsBridge.stringField(raw, "keyId").filter(_.nonEmpty).getOrElse(keyId)
      Some(readManagedTab(JsBridge.intField(raw, "tabId").getOrElse(0), raw, parsedKeyId))
    } catch {
      case e: Throwable =>
        PopCanLog.warn("tabs", s"slot doc corrupt key=$keyId: ${Option(e.getMessage).getOrElse("parse failed")}")
        None
    }
  private def readManagedTab(tabId: Int, raw: js.Any, keyIdOverride: String): ManagedTab =
    ManagedTab(
      tab = Tab(
        tabId = tabId,
        state = readTabState(JsBridge.undefinedAny(JsBridge.field(raw, "tab")))
      ),
      page = Page(
        currentUrl =
          JsBridge.stringField(JsBridge.field(raw, "page"), "currentUrl").getOrElse(""),
        state = readPageState(JsBridge.undefinedAny(JsBridge.field(JsBridge.field(raw, "page"), "state")))
      ),
      keyId = if (keyIdOverride.nonEmpty) keyIdOverride
      else JsBridge.stringField(raw, "keyId").getOrElse(""),
      usedBySessionIds = JsBridge
        .asArray(JsBridge.field(raw, "usedBySessionIds"))
        .toList
        .map(_.toString)
        .filter(_.nonEmpty),
      updatedAt = JsBridge.doubleField(raw, "updatedAt").getOrElse(0.0)
    )
  private def readTabState(raw: js.UndefOr[js.Any]): TabState                            =
    raw.toOption.flatMap(value => JsBridge.stringField(value, "wire")).flatMap {
      case "create"     => Some(TabState.Create(readPhase(JsBridge.field(raw.get, "phase"))))
      case "discarded"  => Some(TabState.Discarded)
      case "warming_up" => Some(TabState.WarmingUp(readPhase(JsBridge.field(raw.get, "phase"))))
      case "close"      => Some(TabState.Close(readPhase(JsBridge.field(raw.get, "phase"))))
      case _            => Some(TabState.Create(Phase.Success))
    }.getOrElse(TabState.Create(Phase.Success))
  private def readPageState(raw: js.UndefOr[js.Any]): PageState                          =
    raw.toOption.map { dynamic =>
      PageState(
        operation =
          JsBridge.stringField(dynamic, "operation").getOrElse("load") match {
            case "inject" => PageOperation.Inject
            case _        => PageOperation.Load
          },
        phase = readPhase(JsBridge.field(dynamic, "phase"))
      )
    }.getOrElse(PageState(PageOperation.Load, Phase.Running))
  private def readPhase(raw: js.UndefOr[js.Any]): Phase                                  =
    raw.toOption.flatMap(value => JsBridge.stringField(value, "wire")) match {
      case Some("failed")  =>
        Phase.Failed(JsBridge.stringField(raw.get, "error").getOrElse(""))
      case Some("success") => Phase.Success
      case _               => Phase.Running
    }
  private def writeTabState(state: TabState): js.Any                                     =
    state match {
      case TabState.Create(phase)    => JsBridge.obj("wire" -> ("create"), "phase" -> (writePhase(phase)))
      case TabState.Discarded        => JsBridge.obj("wire" -> ("discarded"))
      case TabState.WarmingUp(phase) => JsBridge.obj("wire" -> ("warming_up"), "phase" -> (writePhase(phase)))
      case TabState.Close(phase)     => JsBridge.obj("wire" -> ("close"), "phase" -> (writePhase(phase)))
    }
  private def writePageState(state: PageState): js.Any                                   =
    JsBridge.obj(
      "operation" -> (state.operation match {
        case PageOperation.Load   => "load"
        case PageOperation.Inject => "inject"
      }),
      "phase"     -> (writePhase(state.phase))
    )
  private def writePhase(phase: Phase): js.Any                                           =
    phase match {
      case Phase.Running       => JsBridge.obj("wire" -> ("running"))
      case Phase.Failed(error) => JsBridge.obj("wire" -> ("failed"), "error" -> (error))
      case Phase.Success       => JsBridge.obj("wire" -> ("success"))
    }
}
