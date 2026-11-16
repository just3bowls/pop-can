package com.just3bowls.background.runtime

import com.just3bowls.model.diagnostics.PopCanLog
import scala.collection.mutable

/**
 * SW 内存里的任务表（**不落盘**）。
 *
 * 任务只服务三件事：busy 判定（同一 slot 不允许并发 run）、按 taskId 更新阶段、过期清扫。全部发生在同一次 SW 生命周期内； 跨 SW 重启"没有任务在跑"本就是事实（旧版
 * `restoreAfterReload` 也只是启动即把 Running 标成 Failed）。
 *
 * 持久状态里因此不再有 `tasks` 数组 —— 观测（跑过什么、拿到什么）只在 trace，状态表只留 tab/page/会话归属。
 */
private[background] object TabTasks {

  /** 超过这个时长仍未结束的任务视为卡死（SW 侧异常路径没走到 finishTask 时兜底）。 */
  val StaleTaskMs: Double = 10 * 60 * 1000
  private val byId        = mutable.LinkedHashMap.empty[String, Task]
  private val bySlot      = mutable.Map.empty[(String, String), mutable.LinkedHashSet[String]]
  private val byTab       = mutable.Map.empty[Int, mutable.LinkedHashSet[String]]
  def start(provider: String, keyId: String, task: Task): Unit =
    if (isBusy(provider, keyId)) throw new Exception("Managed tab is busy; startTask rejected")
    else {
      byId.update(task.taskId, task)
      bySlot.getOrElseUpdate((provider, keyId), mutable.LinkedHashSet.empty).add(task.taskId)
      byTab.getOrElseUpdate(task.tabId, mutable.LinkedHashSet.empty).add(task.taskId)
      ()
    }

  /** 收工（幂等）：任务不存在时什么都不做（重复收尾、迟到回调都走这里）。 */
  def finish(taskId: String, ok: Boolean, reason: Option[String]): Unit =
    byId.get(taskId).foreach { task =>
      byId.remove(taskId)
      detach(task)
      PopCanLog.debug(
        "tabs",
        s"task ${if (ok) "finished" else "failed"} $taskId (${if (ok) "ok" else reason.getOrElse("failed")})"
      )
    }
  def cancel(taskId: String, reason: String): Unit                      =
    finish(taskId, ok = false, reason = Some(reason))

  /** A closed tab invalidates every task bound to it; the next acquire may allocate a fresh tab. */
  def cancelByTab(tabId: Int, reason: String): List[String] = {
    val taskIds = byTab.get(tabId).map(_.toList).getOrElse(Nil)
    taskIds.foreach(taskId => finish(taskId, ok = false, reason = Some(reason)))
    taskIds
  }

  /** 阶段更新：任务已收工就静默忽略（迟到回调不再写任何状态）。 */
  def updateState(taskId: String, operation: TaskOperation, phase: Phase): Unit =
    byId.get(taskId).foreach { task =>
      byId.update(taskId, task.copy(state = TaskState(operation, phase)))
      ()
    }
  def isBusy(provider: String, keyId: String): Boolean                          =
    bySlot.get((provider, keyId)).exists(_.nonEmpty)
  def find(taskId: String): Option[Task]                                        = byId.get(taskId)

  /** 过期清扫：返回被判定为卡死并已收掉的任务 id。 */
  def sweepStale(now: Double): List[String] = {
    val stale = byId.values.filter(task => now - task.startedAt > StaleTaskMs).map(_.taskId).toList
    stale.foreach { taskId =>
      PopCanLog.warn("tabs", s"expiring stale task $taskId (no completion within ${StaleTaskMs.toInt}ms)")
      finish(taskId, ok = false, reason = Some("Task expired: no completion"))
    }
    stale
  }
  def size: Int                             = byId.size
  private def detach(task: Task): Unit      = {
    byTab.get(task.tabId).foreach { ids =>
      ids.remove(task.taskId)
      if (ids.isEmpty) byTab.remove(task.tabId)
    }
    bySlot.foreach { case (slot, ids) =>
      if (ids.remove(task.taskId) && ids.isEmpty) bySlot.remove(slot)
    }
  }
}
