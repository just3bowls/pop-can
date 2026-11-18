package com.just3bowls.background.runtime
import com.just3bowls.background.bootstrap.*
import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.automation.provider.*
import com.just3bowls.background.webchat.*

import com.just3bowls.api.provider.PageIdentity
import com.just3bowls.facade.{ChromeAPI, Tab => ChromeTab}
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.chat.{RunPhase, RunProgress}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
private[background] object ManagedTabManager {

  /** 每个 slot 记住多少个用过的会话（会话 URL 列表只需要最近的那几条）。 */
  private val SessionUsageLimit                                             = 20
  def findTab(modelName: String, keyId: String): Future[Option[ManagedTab]] =
    TabStore.load().map { window =>
      TabStore.findTab(window, modelName, keyId).map(_._2)
    }
  def findProviderByTabId(tabId: Int): Future[Option[String]]               =
    TabStore.load().map { window =>
      window.groups.collectFirst {
        case (modelName, group) if group.tabs.values.exists(_.liveTabId.contains(tabId)) => modelName
      }
    }

  /**
   * 对账：只把"tab 已经不存在"的 slot 的 `tabId` 置 0（**保留 slot 与 mode/focus/会话使用记录**）， 不再整条删除记录 —— 这就是"tab 换了身份就丢偏好、下次又新建一个"的根源。
   * 顺带清扫内存里卡死的任务（任务不落盘，重启即空）。
   */
  def reconcileManagedTabs(): Future[Unit] =
    TabStore.updateAsync { window =>
      val slots    = TabStore.allSlots(window).flatMap { case (modelName, managed) =>
        managed.liveTabId.map(id => (modelName, managed, id))
      }
      val checks   = slots.map { case (_, _, tabId) =>
        ChromeAPI.tabs.get(tabId).toFuture.map(_ => tabId -> true).recover { case _ => tabId -> false }
      }
      val detached = slots.map(_._3).toSet
      Future.sequence(checks).map { results =>
        val alive = results.collect { case (tabId, true) => tabId }.toSet
        var next  = window
        detached.foreach { tabId =>
          if (!alive.contains(tabId)) {
            PopCanLog.info("tabs", s"slot detached: tab $tabId is gone; keeping the slot for reuse")
            next = TabStore.detachSlot(next, tabId)
          }
        }
        TabTasks.sweepStale(js.Date.now())
        (next, ())
      }
    }
  def findOrCreateTab(
      modelName: String,
      keyId: String,
      url: String,
      conversation: ConversationPolicy,
      sessionId: String = "",
      toolProfileId: String = "",
      gen: String = "",
      onProgress: RunProgress => Unit = _ => ()
  ): Future[(Int, ManagedTab)] =
    reconcileManagedTabs()
      .flatMap(_ => TabOwnership.reconcileAll())
      .flatMap(_ => createOrReuseTab(modelName, keyId, url, conversation, sessionId, toolProfileId, gen, onProgress))
  private def createOrReuseTab(
      modelName: String,
      keyId: String,
      url: String,
      conversation: ConversationPolicy,
      sessionId: String,
      toolProfileId: String,
      gen: String,
      onProgress: RunProgress => Unit
  ): Future[(Int, ManagedTab)] =
    TabStore.load().flatMap { window =>
      TabStore.findSlot(window, modelName, keyId).flatMap(_.liveTabId) match {
        case Some(tabId) =>
          // **tab 固定 provider**：slot 里那个 tab 永远优先用；用之前把它的对话对齐到 (本会话, 这个 provider)。
          onProgress(RunProgress(RunPhase.PreparingProvider, modelName, detail = "reuse"))
          val managed = TabStore.findSlot(window, modelName, keyId).get
          ChromeAPI.tabs.get(tabId).toFuture
            .flatMap { _ =>
              waitForTabFree(modelName, tabId, managed).flatMap { _ =>
                TabStore.load().flatMap { latest =>
                  TabStore.findSlot(latest, modelName, keyId) match {
                    case Some(current) =>
                      alignConversation(
                        modelName,
                        tabId,
                        sessionId,
                        conversation,
                        toolProfileId,
                        gen
                      ).flatMap { effective =>
                        prepareExistingTab(modelName, tabId, current, effective, gen, sessionId, onProgress)
                          .flatMap { _ =>
                            TabStore.load().map { refreshed =>
                              TabStore.findTab(refreshed, modelName, keyId)
                                .map { case (id, updated) => (id, updated) }
                                .getOrElse((tabId, current))
                            }
                          }
                      }
                    case None          =>
                      Future.failed(new Exception(s"Managed tab disappeared while waiting: $tabId"))
                  }
                }
              }
            }
            .recoverWith {
              case e if isMissingTabError(e) =>
                // tab 已经没了：保留 slot（focus/任务都留着），走"采纳或新建"。
                PopCanLog.warn(
                  "tabs",
                  s"stale tab provider=$modelName tab=$tabId error=${e.getMessage}; slot detached, adopt or create"
                )
                TabStore.update { currentWindow =>
                  (TabStore.detachSlot(currentWindow, tabId), ())
                }.flatMap(_ =>
                  adoptOrCreateTab(modelName, keyId, url, conversation, sessionId, toolProfileId, gen, onProgress)
                )
            }
        case None        =>
          adoptOrCreateTab(modelName, keyId, url, conversation, sessionId, toolProfileId, gen, onProgress)
      }
    }

  /**
   * 让这个 tab 停在 `(session, 这个 provider)` 该待的那段对话上，返回这一轮实际需要的对话策略（必要时升级成 `Fresh`）。
   *
   * **tab 固定 provider**：一个 provider 只用一个 tab，它可以承载多条会话的对话 —— 切会话靠"把 tab 导航到那条会话的对话 URL"。 判定顺序（`conversation == Fresh`
   * 时直接交给后面的 New Chat 复位，不动 URL）：
   *
   *   1. tab 现在就是**本会话**那段 → 直接复用（`conversation` 原样）； 2. 本会话在这个 provider **有绑定过的对话** → 把 tab 导航过去再复用（水位线按对话 URL
   *      记着，`delta` 自然续上）； 3. 现在那段**没主**（`thWebChatHosts` 查不到）→ 直接用，跑完 `commitTip` 会绑给本会话； 4. 现在那段是**别的会话**的 → 就地
   *      `Fresh`（New Chat）—— 绝不接着别人的对话。
   *
   * 需要导航 / New Chat（会动到页面上那段对话）之前先问一句那个 tab 忙不忙：正忙就带可读原因失败，别把正在跑的一轮打断。
   */
  private def alignConversation(
      modelName: String,
      tabId: Int,
      sessionId: String,
      conversation: ConversationPolicy,
      toolProfileId: String,
      gen: String
  ): Future[ConversationPolicy] =
    if (sessionId.isEmpty || conversation == ConversationPolicy.Fresh) Future.successful(conversation)
    else
      currentTabUrl(tabId).flatMap(WebChatHosts.findConversation).flatMap { currentHost =>
        def compatible(host: WebChatHost): Boolean =
          toolProfileId.isEmpty || host.toolProfileId == toolProfileId
        val belongsToMe  = currentHost.exists(host => host.sessionId == sessionId && compatible(host))
        val staleCurrent = currentHost.exists(host => host.sessionId == sessionId && !compatible(host))
        if (belongsToMe) Future.successful(conversation)
        else
          WebChatHosts
            .findBySession(sessionId)
            .map(_.find(host => host.provider == modelName && compatible(host)).map(_.convKey).getOrElse(""))
            .flatMap { sessionConv =>
              if (sessionConv.nonEmpty)
                guardBusy(tabId).flatMap { _ =>
                  PopCanLog.info(
                    "tabs",
                    s"switch provider tab to this session's conversation provider=$modelName tab=$tabId " +
                      s"session=$sessionId url=${PopCanLog.short(sessionConv, 70)}"
                  )
                  navigateToConversation(modelName, tabId, sessionConv, gen).map(_ => ConversationPolicy.Reuse)
                }
              else if (staleCurrent)
                Future.successful(ConversationPolicy.Fresh)
              else if (currentHost.isEmpty) Future.successful(conversation)
              else
                guardBusy(tabId).map { _ =>
                  PopCanLog.info(
                    "tabs",
                    s"tab holds another session's conversation provider=$modelName tab=$tabId " +
                      s"owner=${currentHost.map(_.sessionId).getOrElse("")}; new chat for session=$sessionId"
                  )
                  ConversationPolicy.Fresh
                }
            }
      }

  /** 要动页面上那段对话之前：tab 正忙（原生 agent run / 正在产出 / 我们的任务）就带可读原因失败。 */
  private def guardBusy(tabId: Int): Future[Unit] =
    if (WebChatService.tabBusyForAllocator(tabId)) Future.failed(new RuntimeException(WebChatService.tabBusyMessage))
    else Future.successful(())

  /** tab 当前 URL（拿不到就给空串，调用方按"没主"处理）。 */
  private def currentTabUrl(tabId: Int): Future[String] =
    ChromeAPI.tabs
      .get(tabId)
      .toFuture
      .map(_.url.toOption.getOrElse(""))
      .recover { case _ => "" }

  /** 把 tab 导航到那条对话，并等它加载完 + 内容脚本重新注入 + 输入框 marker 就位（复用路径不再等 marker）。 */
  private def navigateToConversation(provider: String, tabId: Int, targetUrl: String, gen: String): Future[Unit] =
    PhaseTiming.timedFuture(gen, "switch") {
      ChromeAPI.tabs
        .update(tabId, js.Dictionary[js.Any]("url" -> targetUrl))
        .toFuture
        .flatMap(_ => waitForTabLoaded(tabId))
        .flatMap(_ => PageMarkers.ensure(tabId, provider, List("input")))
        .map(_ => ())
    }

  /**
   * slot 里没有可用 tab（第一次用，或旧 tab 已被关掉）时： 1) 先“采纳”窗口里已有的候选 tab（同一 key 组内 + URL 匹配该 provider， **网页原生会话的宿主 tab 也算候选**）； 2)
   * 命中多个 → 留一个（本会话的 → 活动 tab → index 最小）、其余**空闲**的关掉； 3) 一个都没有才新建 tab。
   *
   * 采纳之后同样过 `alignConversation`：把它的对话切到本会话那段（没有就走 New Chat），绝不会接着别人的对话。
   */
  private def adoptOrCreateTab(
      modelName: String,
      keyId: String,
      url: String,
      conversation: ConversationPolicy,
      sessionId: String,
      toolProfileId: String,
      gen: String,
      onProgress: RunProgress => Unit
  ): Future[(Int, ManagedTab)] =
    ServerTabManager.hostWindow().flatMap { windowId =>
      candidatesFor(windowId, keyId, url).flatMap { candidates =>
        if (candidates.isEmpty) {
          createAndSaveTab(modelName, keyId, url, conversation, onProgress)
        } else {
          onProgress(RunProgress(RunPhase.PreparingProvider, modelName, detail = "reuse"))
          val keep               = candidates
            .find(tab =>
              sessionId.nonEmpty && tab.id.toOption.exists(id => WebChatService.hostSessionId(id).contains(sessionId))
            )
            .orElse(candidates.find(_.active.getOrElse(false)))
            .orElse(candidates.sortBy(_.index.getOrElse(Int.MaxValue)).headOption)
            .get
          val keepId             = keep.id.getOrElse(-1)
          // 只关**空闲**的重复 tab：正在跑的一轮（原生 / 我们的任务 / 正在产出）不打断；它的对话绑定还在，下次会导航回去。
          val dropIds: List[Int] = candidates
            .flatMap(_.id.toOption)
            .filter(_ != keepId)
            .filterNot(WebChatService.tabBusyForAllocator)
          val dropFuture         =
            if (dropIds.isEmpty) Future.successful(())
            else
              ChromeAPI.tabs.remove(dropIds.toJSArray).toFuture.map(_ => ()).recover { case _ => () }
          PopCanLog.info(
            "tabs",
            s"adopt existing provider tab=$keepId provider=$modelName key=$keyId " +
              s"(closing duplicate tabs: ${dropIds.mkString(",")})"
          )
          dropFuture.flatMap { _ =>
            TabStore.update { window =>
              val existing =
                TabStore.findSlot(window, modelName, keyId)
                  .getOrElse(ManagedTab.fresh(keyId, keepId, url, js.Date.now()))
              (
                TabStore.upsertSlot(
                  window,
                  modelName,
                  existing.copy(tab = existing.tab.copy(tabId = keepId), updatedAt = js.Date.now())
                ),
                ()
              )
            }.flatMap { _ =>
              TabStore.load().flatMap { refreshed =>
                TabStore.findSlot(refreshed, modelName, keyId) match {
                  case Some(adopted) =>
                    alignConversation(
                      modelName,
                      keepId,
                      sessionId,
                      conversation,
                      toolProfileId,
                      gen
                    ).flatMap { effective =>
                      prepareExistingTab(modelName, keepId, adopted, effective, gen, sessionId, onProgress)
                        .flatMap { _ =>
                          TabStore.load().map { after =>
                            TabStore.findTab(after, modelName, keyId).getOrElse((keepId, adopted))
                          }
                        }
                    }
                  case None          =>
                    Future.failed(new Exception("adopted slot disappeared"))
                }
              }
            }
          }
        }
      }
    }

  /** 候选 tab：同一 key 的 Chrome 组里、URL 匹配该 provider 的页面。 */
  private def candidatesFor(windowId: Int, keyId: String, providerUrl: String): Future[List[ChromeTab]] =
    ProviderTabGroup.existing(windowId, keyId).flatMap {
      case None          => Future.successful(Nil)
      case Some(groupId) =>
        ChromeAPI.tabs
          .query(js.Dictionary[js.Any]("windowId" -> windowId))
          .toFuture
          .map(_.toList.filter { tab =>
            // tab 固定 provider：网页原生会话的宿主 tab 也算候选 —— 采纳后 `alignConversation` 会把它的对话切到本会话那段
            // （本会话有绑定就导航过去，没有就 New Chat），所以不存在"往别人的对话里塞模版"。
            tab.groupId.toOption.contains(groupId) && tab.url.toOption.exists(tabUrl => urlMatches(providerUrl, tabUrl))
          })
          .recover { case _ => Nil }
    }

  /** 保守匹配：委托给共享的 `PageIdentity`（去掉 query / hash 后路径互为前缀即同一家，doubao 的 /chat 与 /chat/<id> 都算）。 */
  private def urlMatches(providerUrl: String, tabUrl: String): Boolean =
    PageIdentity.sameProviderPage(providerUrl, tabUrl)
  private def isMissingTabError(error: Throwable): Boolean             =
    Option(error.getMessage).exists { message =>
      message.contains("No tab with id") || message.contains("No tab with given id")
    }

  /** 任务只在内存（`TabTasks`）：同一 slot 已有在跑的任务就拒绝。 */
  def startTask(
      modelName: String,
      keyId: String,
      task: Task
  ): Future[Unit] =
    try {
      TabTasks.start(modelName, keyId, task)
      Future.successful(())
    } catch { case e: Throwable => Future.failed(e) }
  def finishTask(
      modelName: String,
      keyId: String,
      taskId: String,
      ok: Boolean,
      reason: Option[String]
  ): Future[Unit] = {
    val slotTabF = TabStore.load().map { window =>
      TabStore.findSlot(window, modelName, keyId).flatMap(_.liveTabId)
    }
    TabTasks.finish(taskId, ok, reason)
    slotTabF.flatMap { slotTabId =>
      if (BackgroundState.releasedKeyIds.contains(keyId) && slotTabId.nonEmpty) {
        BackgroundState.releasedKeyIds -= keyId
        closeSlotTab(modelName, keyId)
      } else {
        Future.successful(())
      }
    }
  }

  /** 关掉 slot 当前的 tab（若有）并把 slot 整个删掉：key 被释放时用。 */
  def closeSlotTab(modelName: String, keyId: String): Future[Unit] =
    TabStore.load().flatMap { window =>
      val tabToClose = TabStore.findSlot(window, modelName, keyId).flatMap(_.liveTabId)
      val closeFut   =
        tabToClose match {
          case Some(tabId) =>
            // slot 连同它的对话一起消失：把这段对话的水位线记录也清掉（对话没了，别再留记录）。
            TemplateInjection.clearConversation(tabId).foreach(_ => ())
            ChromeAPI.tabs.remove(tabId).toFuture.map(_ => ()).recover { case _ => () }
          case None        => Future.successful(())
        }
      closeFut.flatMap { _ =>
        TabStore.update { w =>
          (TabStore.removeSlot(w, modelName, keyId), ())
        }
      }
    }

  /** tab 被关掉（用户关 / 浏览器回收）：只把 slot 的 tabId 置 0，保留 mode/focus/任务。 */
  def detachTabByTabId(tabId: Int): Future[Unit] =
    TabStore.load().flatMap { window =>
      if (TabStore.findByTabId(window, tabId).isEmpty) Future.successful(())
      else
        TabStore.update { current =>
          (TabStore.detachSlot(current, tabId), ())
        }
    }

  /**
   * 让实际 group 里的 incoming tab 接管 `(provider,key)` slot。
   *
   * 同组同 Provider 按“新 tab 替换旧 tab”：先改完 slot，再关闭旧目标 tab；旧 tab 的 onRemoved 不会再清掉新 owner。slot 的会话使用记录 / 状态从旧目标继承，页面 URL 使用
   * incoming 的实时值。
   */
  def claimTabOwnership(modelName: String, keyId: String, tabId: Int, url: String): Future[Unit] =
    if (modelName.isEmpty || keyId.isEmpty || tabId <= 0) Future.successful(())
    else
      TabStore
        .updateAsync { window =>
          val target        = TabStore.findSlot(window, modelName, keyId)
          val replacedTabId = target.flatMap(_.liveTabId).filter(_ != tabId)
          val detached      = TabStore.detachSlot(window, tabId)
          val now           = js.Date.now()
          val claimed       = target
            .map(old =>
              old.copy(
                tab = old.tab.copy(tabId = tabId),
                page = old.page.copy(currentUrl = url),
                updatedAt = now
              )
            )
            .getOrElse(ManagedTab.fresh(keyId, tabId, url, now))
          Future.successful((TabStore.upsertSlot(detached, modelName, claimed), replacedTabId))
        }
        .flatMap {
          case Some(oldTabId) =>
            PopCanLog.info(
              "tabs",
              s"ownership replaced provider=$modelName key=$keyId old=$oldTabId new=$tabId"
            )
            ChromeAPI.tabs.remove(oldTabId).toFuture.map(_ => ()).recover { case _ => () }
          case None           => Future.successful(())
        }
  def removeTabByTabId(tabId: Int): Future[Unit] = detachTabByTabId(tabId)
  def updateCurrentUrl(modelName: String, keyId: String, url: String): Future[Unit]              =
    TabStore.update { window =>
      TabStore.findTab(window, modelName, keyId) match {
        case Some((_, managed)) =>
          val nextManaged = managed.copy(
            page = managed.page.copy(currentUrl = url),
            updatedAt = js.Date.now()
          )
          val next        = TabStore.upsertSlot(window, modelName, nextManaged)
          (next, ())
        case None               =>
          (window, ())
      }
    }
  def updateCurrentUrlByTabId(tabId: Int, url: String): Future[Unit]                             =
    TabStore.update { window =>
      TabStore.findByTabId(window, tabId) match {
        case Some((modelName, managed)) =>
          val nextManaged = managed.copy(
            page = managed.page.copy(currentUrl = url),
            updatedAt = js.Date.now()
          )
          val next        = TabStore.upsertSlot(window, modelName, nextManaged)
          (next, ())
        case None                       =>
          (window, ())
      }
    }
  def releaseKeyTabs(keyId: String): Future[Unit]                                                =
    if (keyId.isEmpty) Future.successful(())
    else {
      BackgroundState.releasedKeyIds += keyId
      TabStore.load().flatMap { window =>
        val slotsToClose = TabStore.allSlots(window).collect {
          case (modelName, managed) if managed.keyId == keyId && !TabTasks.isBusy(modelName, managed.keyId) =>
            modelName
        }
        Future.sequence(slotsToClose.map(modelName => closeSlotTab(modelName, keyId))).map(_ => ())
      }
    }

  /**
   * 记一次「这条会话用过这个 (provider, key) slot」：会话 URL 列表据此列出该会话在用的 provider URL。
   *
   * 最近使用在后、上限 `SessionUsageLimit`；只记 id，不碰 tab 本身。
   */
  def noteSessionUsage(modelName: String, keyId: String, sessionId: String): Future[Unit] =
    if (modelName.isEmpty || keyId.isEmpty || sessionId.isEmpty) Future.successful(())
    else
      TabStore.update { window =>
        TabStore.findSlot(window, modelName, keyId) match {
          case Some(managed) if !managed.usedBySessionIds.lastOption.contains(sessionId) =>
            (TabStore.upsertSlot(window, modelName, managed.withSessionUsage(sessionId, SessionUsageLimit)), ())
          case _                                                                         =>
            (window, ())
        }
      }

  /**
   * 反向操作：这条会话不再「用着」这个 provider 的 slot（会话 URL 列表里按 × 结束这个 provider）。
   *
   * 只摘会话 id，tab 与 key 偏好都不动；该 provider 下的所有 key 一起摘（同一会话很少跨 key 用同一 provider）。
   */
  def forgetSessionUsage(modelName: String, sessionId: String): Future[Unit] =
    if (modelName.isEmpty || sessionId.isEmpty) Future.successful(())
    else
      TabStore.update { window =>
        val updated = window.groups.get(modelName).map { current =>
          val tabs = current.tabs.collect {
            case (keyId, managed) if managed.usedBySessionIds.contains(sessionId) =>
              keyId -> managed.copy(
                usedBySessionIds = managed.usedBySessionIds.filterNot(_ == sessionId),
                updatedAt = js.Date.now()
              )
          }
          if (tabs.isEmpty) window
          else window.copy(groups = window.groups.updated(modelName, current.copy(tabs = current.tabs ++ tabs)))
        }
        (updated.getOrElse(window), ())
      }
  def cancelTaskById(taskId: String, reason: String): Future[Unit]           =
    if (taskId.isEmpty) Future.successful(())
    else {
      TabTasks.cancel(taskId, reason)
      Future.successful(())
    }
  def updateTaskState(
      taskId: String,
      operation: TaskOperation,
      phase: Phase
  ): Future[Unit] = {
    TabTasks.updateState(taskId, operation, phase)
    Future.successful(())
  }
  def protectAllManagedTabs(): Future[Unit]                                  =
    TabStore.load().flatMap { window =>
      val tabIds = TabStore.allSlots(window).flatMap { case (_, managed) => managed.liveTabId }
      Future.sequence(tabIds.map(TabKeepAlive.protectTab)).map(_ => ())
    }
  private def createAndSaveTab(
      modelName: String,
      keyId: String,
      url: String,
      conversation: ConversationPolicy,
      onProgress: RunProgress => Unit
  ): Future[(Int, ManagedTab)] =
    ServerTabManager.hostWindow().flatMap { windowId =>
      ChromeAPI.tabs
        // 新 tab 一律后台建：要不要前台化由这一轮 run 的 focus 决定（见背景里的 withTaskFocus）。
        .create(js.Dictionary[js.Any]("url" -> url, "windowId" -> windowId, "active" -> false))
        .toFuture
        .flatMap { created =>
          created.id.toOption match {
            case None        =>
              Future.failed(new Exception("Managed tab creation failed: no tab id"))
            case Some(tabId) =>
              // 先落库（tabId 已知），再做加载 / 注入 / marker 校验：后续失败也复用这个 tab，不再留无记录孤儿。
              val placeholderF =
                TabStore.update { window =>
                  // slot 已存在时保留它的会话使用记录——tab 重建不该抹掉「谁用过我」。
                  val fresh = ManagedTab.fresh(keyId, tabId, url, js.Date.now())
                  val kept  = TabStore.findSlot(window, modelName, keyId)
                    .map(old => fresh.copy(usedBySessionIds = old.usedBySessionIds))
                    .getOrElse(fresh)
                  (TabStore.upsertSlot(window, modelName, kept), kept)
                }
              placeholderF.flatMap { placeholder =>
                finishCreatingTab(modelName, tabId, placeholder, url, conversation, onProgress).recoverWith { case e =>
                  // 失败也保留 slot（tabId 已在），只把状态标成失败：下一次复用同一个 tab 重试，不再多开。
                  TabStore.update { w =>
                    val current = TabStore.findSlot(w, modelName, keyId).getOrElse(placeholder)
                    val failed  = current.copy(
                      tab = current.tab.copy(
                        state = TabState.Create(Phase.Failed(Option(e.getMessage).getOrElse("create failed")))
                      ),
                      updatedAt = js.Date.now()
                    )
                    (TabStore.upsertSlot(w, modelName, failed), ())
                  }.flatMap(_ => Future.failed(e))
                }
              }
          }
        }
    }

  /** 新建 tab 的收尾：归组 → 等加载 → 注入 → 等 marker → 写回最终状态；任何一步失败都保留 slot。 */
  private def finishCreatingTab(
      modelName: String,
      tabId: Int,
      placeholder: ManagedTab,
      url: String,
      conversation: ConversationPolicy,
      onProgress: RunProgress => Unit
  ): Future[(Int, ManagedTab)] =
    ProviderTabGroup
      .attach(tabId, placeholder.keyId)
      .flatMap(_ => waitForTabLoaded(tabId))
      // 新 tab 落在 provider 首页：把同一 URL 上的旧水位线记录清掉，这一轮必须全量投递。
      .flatMap(_ => TemplateInjection.clearConversation(tabId))
      .flatMap { _ =>
        val roles = requiredRolesOf(conversation)
        onProgress(RunProgress(
          RunPhase.PreparingPage,
          modelName,
          detail = if (roles.contains("new-chat")) "new-chat" else "input"
        ))
        PageMarkers.ensure(tabId, modelName, roles)
      }
      .flatMap { _ =>
        TabStore.update { window =>
          val current = TabStore.findSlot(window, modelName, placeholder.keyId).getOrElse(placeholder)
          val next    = current.copy(
            tab = Tab(tabId, TabState.Create(Phase.Success)),
            page = Page(
              currentUrl = url,
              state = PageState(PageOperation.Inject, Phase.Success)
            ),
            updatedAt = js.Date.now()
          )
          (TabStore.upsertSlot(window, modelName, next), ())
        }.flatMap { _ =>
          TabStore.load().map { latest =>
            val managed = TabStore.findSlot(latest, modelName, placeholder.keyId).getOrElse(placeholder)
            (managed.tab.tabId, managed)
          }
        }
      }
  private def prepareExistingTab(
      modelName: String,
      tabId: Int,
      managed: ManagedTab,
      conversation: ConversationPolicy,
      gen: String,
      /** 这一轮属于哪条会话：New Chat 复位时只作废**本会话**那段对话的水位线，别人那段不动（见 `clearConversationOwnedBy`）。 */
      ownerSessionId: String,
      onProgress: RunProgress => Unit
  ): Future[Unit] =
    // 复用历史遗留 / 被用户拖出组的 tab 时，把它放回该 key 的分组。
    PhaseTiming.timedFuture(gen, "prepare") {
      ProviderTabGroup.attach(tabId, managed.keyId).flatMap(_ => TabKeepAlive.ensureTabReady(tabId)).flatMap {
        _ =>
          val requested   = requiredRolesOf(conversation)
          val shouldReset = conversation == ConversationPolicy.Fresh
          onProgress(RunProgress(
            RunPhase.PreparingPage,
            modelName,
            detail = if (requested.contains("new-chat")) "new-chat" else "input"
          ))
          PageMarkers.ensure(tabId, modelName, requested).flatMap { _ =>
            if (shouldReset)
              ServerTabManager.loadNewChatXPath(modelName).flatMap { xpath =>
                if (xpath.trim.isEmpty) Future.failed(new Exception("New Chat XPath is not configured"))
                else {
                  onProgress(RunProgress(RunPhase.StartingNewChat, modelName))
                  // 先按**点击前**的 URL 作废这段对话的水位线：New Chat 复位 = 换一段全新对话（URL 不变的 provider 靠它兜住）。
                  // tab 固定 provider 之后这里也可能是"别人那段对话"，所以只作废本会话（或没主）的那一段。
                  (if (ownerSessionId.nonEmpty) TemplateInjection.clearConversationOwnedBy(tabId, ownerSessionId)
                   else TemplateInjection.clearConversation(tabId))
                    .flatMap(_ =>
                      CdpFlowExecutor.clickXPathViaCDP(tabId, xpath, role = "new-chat", provider = modelName)
                    )
                    .flatMap { ok =>
                      if (ok) {
                        val afterResetRoles = requested.filterNot(_ == "new-chat")
                        BackgroundState.invalidateMarkerRoles(tabId, afterResetRoles)
                        onProgress(RunProgress(RunPhase.PreparingPage, modelName, detail = "input"))
                        PageMarkers.ensure(tabId, modelName, afterResetRoles)
                      } else {
                        Future.failed(new Exception(
                          "New chat click failed: the new-chat button was not clickable, " +
                            "or the tab is held by DevTools / another debugger"
                        ))
                      }
                    }
                }
              }
            else Future.successful(())
          }
      }
    }
  private def waitForTabFree(
      modelName: String,
      tabId: Int,
      managed: ManagedTab,
      timeoutMs: Int = 180000
  ): Future[Unit] = {
    val deadline                = js.Date.now() + timeoutMs
    def attempt(): Future[Unit] =
      if (!TabTasks.isBusy(modelName, managed.keyId)) Future.successful(())
      else if (js.Date.now() > deadline) Future.failed(new Exception(s"Managed tab busy timeout: $tabId"))
      else delay(200).flatMap(_ => attempt())
    attempt()
  }

  /** 等 tab 加载完成：事件（`tabs.onUpdated`）优先，轮询兜底（见 `Wait.forTabComplete`）。 */
  private def waitForTabLoaded(tabId: Int, timeoutMs: Int = 30000): Future[Unit] =
    Wait.forTabComplete(tabId, timeoutMs).recoverWith { case _ =>
      Future.failed(new Exception(s"Managed tab load timeout: $tabId"))
    }
  private def delay(ms: Int): Future[Unit]                                       = {
    val p = Promise[Unit]()
    js.timers.setTimeout(ms)(p.success(()))
    p.future
  }

  /** 每轮需要的 marker：`Fresh`（要 New Chat 复位）除了输入框还要 new-chat 按钮，`Reuse` 只等输入框。 */
  private def requiredRolesOf(conversation: ConversationPolicy): List[String] =
    conversation match {
      case ConversationPolicy.Fresh => List("new-chat", "input")
      case ConversationPolicy.Reuse => List("input")
    }
}
