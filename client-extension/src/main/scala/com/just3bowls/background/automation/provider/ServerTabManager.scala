package com.just3bowls.background.automation.provider
import com.just3bowls.background.bootstrap.*

import com.just3bowls.facade.{ChromeAPI, Tab}
import com.just3bowls.model.provider.ParserProviderInfo
import com.just3bowls.model.provider.ParserProviderLocal
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
private[background] object ServerTabManager {

  /** 点图标是**单飞**的：双击 / 连点不会开出两个面板 tab。 */
  private var panelOpenPromise: Option[Promise[Int]] = None
  private[background] def loadProviderConfig(
      provider: String
  ): Future[Option[ParserProviderInfo]] =
    ParserProviderLocal.getProvider(provider).map(_.map(toProviderInfo)).recover { case _ => None }

  /** 由本地 provider 记录直接生成对外信息（网页原生会话按 tab URL 反查 provider 时用）。 */
  private[background] def configFrom(local: ParserProviderLocal): Option[ParserProviderInfo] =
    if (local.pageUrl.trim.isEmpty) None else Some(toProviderInfo(local))
  private def toProviderInfo(config: ParserProviderLocal): ParserProviderInfo =
    // id 用 name：两者的取值一直相同（provider 的 key 就是名字），历史行为不变。
    ParserProviderInfo.fromLocal(config).copy(id = config.name)

  /**
   * 面板 tab 的唯一真相：**面板就是当前窗口里的一个 pinned tab**，不再有"插件专用窗口"这种东西。
   *
   * 于是"provider / tools tab 该开在哪"= "面板 tab 在哪"，不需要任何缓存或落库状态（窗口被用户关掉、面板被搬到别的窗口， 下一次查询自然就是新的真相）。
   */
  private[background] def panelTab(): Future[Option[(Int, Int)]] =
    ChromeAPI.tabs
      .query(js.Dictionary[js.Any]("url" -> pluginMainUrl))
      .toFuture
      .map(
        _.toList
          .flatMap(tab => tab.id.toOption.zip(tab.windowId.toOption))
          .find { case (tabId, windowId) => tabId > 0 && windowId > 0 }
      )
      .recover { case _ => None }

  /** 面板 tab 的 id（原生 agent 的 host 页）。 */
  private def panelTabId(): Future[Option[Int]] = panelTab().map(_.map(_._1))

  /** 这个窗口是不是可以承载 tab / tab group 的普通窗口（popup / devtools 一律不算）。 */
  private def isNormalWindow(windowId: Int): Future[Boolean]   =
    if (windowId <= 0) Future.successful(false)
    else
      ChromeAPI.windows
        .get(windowId, js.Dictionary[js.Any]("windowTypes" -> js.Array("normal")))
        .toFuture
        .map(_ => true)
        .recover { case _ => false }
  private def lastFocusedNormalWindowId(): Future[Option[Int]] =
    ChromeAPI.windows
      .getLastFocused(js.Dictionary[js.Any]("windowTypes" -> js.Array("normal")))
      .toFuture
      .map(window => window.id.toOption.filter(_ > 0))
      .recover { case _ => None }

  /** 面板 tab 恒 pinned：防误关 / 防被挤走。 */
  private def pinTab(tabId: Int): Future[Unit]                                      =
    ChromeAPI.tabs.update(tabId, js.Dictionary[js.Any]("pinned" -> true)).toFuture.map(_ => ())
      .recover { case e =>
        PopCanLog.warn("tabs", s"pin panel tab $tabId failed: ${e.getMessage}")
      }
  private[background] def withTabLock[A](tabId: Int)(body: => Future[A]): Future[A] = {
    val previous = BackgroundState.tabLocks.get(tabId).map(_.future).getOrElse(Future.successful(()))
    val release  = Promise[Unit]()
    BackgroundState.tabLocks.update(tabId, release)
    previous.flatMap { _ =>
      val task =
        try body
        catch { case e: Throwable => Future.failed(e) }
      task.transform { result =>
        release.trySuccess(())
        result
      }
    }
  }
  private[background] def loadProviderUrl(provider: String): Future[String]         =
    loadProviderConfig(provider).map(_.flatMap(config => Option(config.pageUrl).filter(_.trim.nonEmpty)).getOrElse(""))
  private[background] def loadNewChatXPath(provider: String): Future[String]        =
    loadProviderConfig(provider).map(
      _.flatMap(config => Option(config.newChatSelector).filter(_.trim.nonEmpty)).getOrElse("")
    )
  private def pluginMainUrl: String                                                 =
    ChromeAPI.runtime.getURL("entrypoints/main/index.html")

  /**
   * **host window**：provider tab / tools tab / 临时测试 tab 一律开在"面板 tab 所在的那个普通窗口"里。
   *
   * 解析顺序：① 面板 tab 的窗口 → ② 最后聚焦的普通窗口（面板还没开时的后台路径）→ ③ 兜底建一个普通窗口并把面板页放进去。 ① 是唯一真相，**不做缓存**：面板被搬走 / 窗口被关，下一次解析自然是新的结果。
   */
  private[background] def hostWindow(): Future[Int] =
    panelTab().flatMap {
      case Some((_, windowId)) => Future.successful(windowId)
      case None                =>
        lastFocusedNormalWindowId().flatMap {
          case Some(windowId) => Future.successful(windowId)
          case None           =>
            ChromeAPI.windows
              .create(js.Dictionary[js.Any](
                "url"     -> pluginMainUrl,
                "type"    -> "normal",
                "focused" -> false
              ))
              .toFuture
              .flatMap { window =>
                window.id.toOption.filter(_ > 0) match {
                  case Some(windowId) => waitForPanelTab(windowId, js.Date.now() + 10000).map(_ => windowId)
                  case None           => Future.failed(new Exception("host window creation failed"))
                }
              }
        }
    }

  /** 等面板 tab 在指定窗口里出现（兜底建窗口后用它拿到 tabId）。 */
  private def waitForPanelTab(windowId: Int, deadline: Double): Future[Int] =
    ChromeAPI.tabs
      .query(js.Dictionary[js.Any]("url" -> pluginMainUrl, "windowId" -> windowId))
      .toFuture
      .flatMap { tabs =>
        tabs.toList.flatMap(_.id.toOption).find(_ > 0) match {
          case Some(tabId)                      => Future.successful(tabId)
          case None if js.Date.now() < deadline => delay(250).flatMap(_ => waitForPanelTab(windowId, deadline))
          case None                             => Future.failed(new Exception("panel tab did not appear"))
        }
      }
      .recoverWith { case _ =>
        if (js.Date.now() < deadline) delay(250).flatMap(_ => waitForPanelTab(windowId, deadline))
        else Future.failed(new Exception("panel tab did not appear"))
      }

  /**
   * 点扩展图标 / 需要把面板摆到用户眼前：在**点击时所在的那个窗口**里开面板 tab（没有就复用已有的那个）。
   *
   * 绝不 `windows.create` 出一个新界面 —— 兜底建窗口只在"一个普通窗口都没有"时发生（见 `hostWindow`）。双击用单飞 promise 串行化，避免开出两个面板。
   */
  private[background] def openPanel(anchorTab: js.UndefOr[Tab]): Future[Int] =
    panelOpenPromise match {
      case Some(promise) => promise.future
      case None          =>
        val promise = Promise[Int]()
        panelOpenPromise = Some(promise)
        resolveAndRevealPanel(anchorTab).onComplete { result =>
          panelOpenPromise = None
          promise.complete(result)
        }
        promise.future
    }
  private def resolveAndRevealPanel(anchorTab: js.UndefOr[Tab]): Future[Int] =
    panelTab().flatMap {
      // 已有面板（可能在别的窗口）：把它所在窗口带到前台并激活这个 tab，不新建任何东西。
      case Some((tabId, windowId)) =>
        pinTab(tabId).flatMap(_ => revealTab(tabId, windowId))
      case None                    =>
        anchorHostWindow(anchorTab).flatMap { windowId =>
          ChromeAPI.tabs.create(
            js.Dictionary[js.Any](
              "url"      -> pluginMainUrl,
              "windowId" -> windowId,
              "active"   -> true,
              "pinned"   -> true
            )
          ).toFuture.flatMap { tab =>
            tab.id.toOption.filter(_ > 0) match {
              case Some(tabId) => revealTab(tabId, windowId)
              case None        => Future.failed(new Exception("panel tab creation failed"))
            }
          }
        }
    }.recoverWith { case _ => hostWindow() } // 兜底：任何一步失败都退到"只要能有个能用的 host window"
  /** 点击时所在窗口（必须是普通窗口），否则退到最后聚焦的普通窗口 / 兜底窗口。 */
  private def anchorHostWindow(anchorTab: js.UndefOr[Tab]): Future[Int]      =
    anchorTab.toOption.flatMap(_.windowId.toOption).filter(_ > 0) match {
      case Some(windowId) =>
        isNormalWindow(windowId).flatMap {
          case true  => Future.successful(windowId)
          case false => hostWindow()
        }
      case None           => hostWindow()
    }
  private def revealTab(tabId: Int, windowId: Int): Future[Int]              =
    ChromeAPI.tabs.update(tabId, js.Dictionary[js.Any]("active" -> true)).toFuture
      .flatMap(_ =>
        ChromeAPI.windows.update(windowId, js.Dictionary[js.Any]("focused" -> true)).toFuture
      )
      .map(_ => windowId)
      .recover { case _ => windowId }

  /**
   * 插件主页 **tab**（原生 agent 的 host）：有就用现成的，没有就后台开一个 —— 落 host window，不抢焦点、不新建前台窗口。
   *
   * 原生聊天要在插件页里跑 agent 循环，所以这条路径必须尽量无感：窗口/ tab 都以 `active=false`/`focused=false` 出现。
   */
  private[background] def ensurePluginPageTab(): Future[Int]       =
    panelTabId().flatMap {
      case Some(tabId) => Future.successful(tabId)
      case None        =>
        hostWindow().flatMap { windowId =>
          // 兜底建窗口时面板页已经跟着窗口建好了：先查一次，别开出第二个面板。
          panelTabId().flatMap {
            case Some(tabId) => Future.successful(tabId)
            case None        =>
              ChromeAPI.tabs
                .create(js.Dictionary[js.Any](
                  "url"      -> pluginMainUrl,
                  "windowId" -> windowId,
                  "active"   -> false,
                  "pinned"   -> true
                ))
                .toFuture
                .map(_.id.toOption.getOrElse(-1))
                .flatMap {
                  case tabId if tabId > 0 => Future.successful(tabId)
                  case _                  => Future.failed(new Exception("plugin page tab unavailable"))
                }
          }
        }
    }
  private[background] def focusServerTab(tabId: Int): Future[Unit] =
    ChromeAPI.tabs.get(tabId).toFuture.flatMap { tab =>
      ChromeAPI.tabs.update(tabId, js.Dictionary("active" -> true)).toFuture.flatMap { _ =>
        val windowId = tab.windowId.getOrElse(-1)
        if (windowId > 0) {
          ChromeAPI.windows.update(windowId, js.Dictionary("focused" -> true)).toFuture
            .map(_ => ())
            .recover { case _ => () }
        } else {
          Future.successful(())
        }
      }
    }
  private def delay(ms: Int): Future[Unit]                         = {
    val promise = Promise[Unit]()
    js.timers.setTimeout(ms)(promise.success(()))
    promise.future
  }
}
