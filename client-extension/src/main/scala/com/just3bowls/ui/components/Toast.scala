package com.just3bowls.ui.components

import com.just3bowls.facade.JsBridge

import com.raquo.laminar.api.L._
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.scalajs.js

/**
 * 插件页**唯一**的"飘出来的提醒"入口：右上角固定浮层，收编那些没有归属地的消息。
 *
 * 职责边界（和就地反馈分工）：
 *
 *   - 能在原地反馈的**成功**留在原地（按钮翻成 `Copied ✓` 之类），不弹 toast；
 *   - **失败/错误**（命令用法、前置条件、面板动作失败、Server 消息错误）→ toast；
 *   - **面板级结果**（`Restore removed` / `Applied …`）→ toast。
 *
 * 行为：`success` / `info` 3s、`error` 8s 自动消失（可点 ✕ 提前关）；鼠标悬停暂停倒计时，移开继续；同文案 1.5s 内重复只刷新那一条的倒计时（不刷屏）； 最多同时 3
 * 条、新的在上；点正文复制全文（复制成功该条短暂显示 `Copied ✓`，不关闭）。
 *
 * 页面级单例：面板（Providers / API keys / Memory / Log …）直接 `Toast.error(...)`，浮层由 `PopCanPage` 根节点挂一次；胶囊（provider 页）不接这个入口。
 */
private[ui] object Toast {
  val Info    = "info"
  val Success = "success"
  val Error   = "error"
  final case class Item(id: Long, kind: String, text: String)

  /** 当前显示的消息，新的在最前。 */
  val items: Var[List[Item]] = Var(Nil)
  private val MaxItems       = 3
  private val SuccessMs      = 3000.0
  private val ErrorMs        = 8000.0
  private val MergeWindowMs  = 1500.0
  private val CopiedFlashMs  = 1000.0

  /** 每条自己的倒计时状态：剩余毫秒 + 定时器句柄（悬停时清掉、移开时按剩余时间重排）。 */
  private val timers = scala.collection.mutable.Map.empty[Long, js.timers.SetTimeoutHandle]
  private val remain = scala.collection.mutable.Map.empty[Long, Double]
  private val paused = scala.collection.mutable.Set.empty[Long]

  /** 刚被复制过的条目（短暂显示 `Copied ✓`）。 */
  private val copiedVar                              = Var(0L)
  private var nextId                                 = 0L
  def info(text: String): Unit                       = push(Info, text)
  def success(text: String): Unit                    = push(Success, text)
  def error(text: String): Unit                      = push(Error, text)
  private def durationOf(kind: String): Double       = if (kind == Error) ErrorMs else SuccessMs
  private def push(kind: String, text: String): Unit = {
    val body = Option(text).getOrElse("").trim
    if (body.isEmpty) ()
    else {
      val now      = js.Date.now().toDouble
      val existing = items.now().find(item => item.text == body && remain.getOrElse(item.id, 0.0) > 0)
      existing match {
        case Some(item) if now - lastSeen.getOrElse(item.id, 0.0) <= MergeWindowMs =>
          // 同一条内容短时间内又来了：不新增，只把倒计时拉回满格。
          lastSeen.update(item.id, now)
          armTimer(item.id, durationOf(item.kind))
        case _                                                                     =>
          nextId += 1
          val item = Item(nextId, if (kind == Error) Error else if (kind == Success) Success else Info, body)
          lastSeen.update(item.id, now)
          val kept = (item :: items.now()).take(MaxItems)
          items.set(kept)
          // 被顶掉的旧条目要把它的定时器一起收掉。
          (timers.keySet.toList ++ remain.keySet.toList).distinct.foreach { id =>
            if (!kept.exists(_.id == id)) forget(id)
          }
          armTimer(item.id, durationOf(item.kind))
      }
    }
  }
  private val lastSeen                               = scala.collection.mutable.Map.empty[Long, Double]
  private def forget(id: Long): Unit                 = {
    timers.remove(id).foreach(js.timers.clearTimeout)
    remain.remove(id)
    paused.remove(id)
    lastSeen.remove(id)
  }
  private def armTimer(id: Long, ms: Double): Unit   = {
    timers.remove(id).foreach(js.timers.clearTimeout)
    remain.update(id, ms)
    if (!paused.contains(id)) startTimer(id)
  }
  private def startTimer(id: Long): Unit             = {
    val left = remain.getOrElse(id, 0.0)
    if (left <= 0) dismiss(id)
    else
      timers.update(
        id,
        js.timers.setTimeout(left) {
          dismiss(id)
        }
      )
  }

  /** 悬停：暂停这条的倒计时（把已过的时长从剩余里扣掉）。 */
  def pause(id: Long): Unit =
    if (remain.contains(id) && !paused.contains(id)) {
      paused += id
      timers.remove(id).foreach(js.timers.clearTimeout)
    }

  /** 移开：按剩余时间接着倒。 */
  def resume(id: Long): Unit                     =
    if (paused.contains(id)) {
      paused -= id
      startTimer(id)
    }
  def dismiss(id: Long): Unit                    = {
    if (items.now().exists(_.id == id)) items.update(_.filterNot(_.id == id))
    forget(id)
  }
  private def copy(text: String, id: Long): Unit =
    try {
      val clipboard = JsBridge.navigator.clipboard
      if (!js.isUndefined(clipboard) && clipboard != null) {
        JsBridge.asPromiseAny(clipboard.writeText(text)).toFuture
          .map { _ =>
            copiedVar.set(id)
            js.timers.setTimeout(CopiedFlashMs)(if (copiedVar.now() == id) copiedVar.set(0L))
          }
          .recover { case _ => () }
          .foreach(_ => ())
      }
    } catch { case _: Throwable => () }

  /** 右上角浮层：挂在页面根节点末尾，位于所有应用内浮层之上（含 Config 帮助浮层）。 */
  def renderLayer(): HtmlElement                        =
    div(
      position      := "fixed",
      top           := "12px",
      right         := "12px",
      zIndex        := 1000,
      display.flex,
      flexDirection := "column",
      gap           := "8px",
      width         := "320px",
      maxWidth      := "calc(100vw - 24px)",
      alignItems    := "stretch",
      pointerEvents <-- items.signal.map(list => if (list.isEmpty) "none" else "auto"),
      children <-- items.signal.map(_.map(renderItem))
    )
  private def renderItem(item: Toast.Item): HtmlElement = {
    val accent = item.kind match {
      case Error   => "#dc2626"
      case Success => "#047857"
      case _       => "#6b7280"
    }
    div(
      display.flex,
      alignItems.stretch,
      backgroundColor := "#ffffff",
      border          := "1px solid #e5e7eb",
      borderRadius    := "6px",
      boxShadow       := "0 6px 18px rgba(15,23,42,0.14)",
      overflow.hidden,
      onMouseEnter --> { _ => pause(item.id) },
      onMouseLeave --> { _ => resume(item.id) },
      div(width      := "3px", flexShrink := "0", backgroundColor := accent),
      div(
        flexGrow     := "1",
        minWidth     := "0",
        padding      := "8px 10px",
        div(
          whiteSpace := "pre-wrap",
          wordBreak  := "break-word",
          fontSize   := "11px",
          lineHeight := "15px",
          color      := "#111827",
          cursor     := "copy",
          title      := "Click to copy",
          onClick --> { _ => copy(item.text, item.id) },
          child.text <-- copiedVar.signal.map(copied => if (copied == item.id) "Copied ✓" else item.text)
        )
      ),
      div(
        flexShrink   := "0",
        paddingTop   := "6px",
        paddingRight := "6px",
        button(
          "✕",
          fontSize        := "10px",
          lineHeight      := "1",
          padding         := "2px 4px",
          cursor.pointer,
          backgroundColor := "transparent",
          color           := "#9ca3af",
          border          := "none",
          title           := "Dismiss",
          onClick --> { _ => dismiss(item.id) }
        )
      )
    )
  }
}
