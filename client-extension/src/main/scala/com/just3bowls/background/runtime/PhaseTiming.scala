package com.just3bowls.background.runtime

import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.diagnostics.TraceStore
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.collection.mutable
import scala.scalajs.js

/**
 * 一次 INPUT_PROMPT 的分阶段耗时（按 `gen` 累计），收尾时并进同一条 trace。
 *
 * 采集点分散在 `BackgroundMain` / `ManagedTabManager` / `CdpFlowExecutor`，所以这里放一个进程内的临时表； `flush` 之后立即释放，不长期占用内存。
 */
private[background] object PhaseTiming {
  private val phases = mutable.Map.empty[String, mutable.LinkedHashMap[String, Double]]

  /** 记一段耗时（毫秒，负数按 0 处理）；同一个 phase 重复打点以最后一次为准。 */
  def mark(gen: String, phase: String, ms: Double): Unit =
    if (gen.nonEmpty && phase.nonEmpty) {
      phases.getOrElseUpdate(gen, mutable.LinkedHashMap.empty).update(phase, math.max(0.0, ms))
    }

  /** 量一段异步代码的耗时并打点：成功与失败都记（失败路径的耗时同样要进 trace）。 */
  def timedFuture[A](gen: String, phase: String)(body: => Future[A]): Future[A] = {
    val started = js.Date.now()
    body.transform { result =>
      mark(gen, phase, js.Date.now() - started)
      result
    }
  }

  /** 该 gen 已记录的某个 phase（毫秒），没记过为 0。 */
  private def get(gen: String, phase: String): Double =
    phases.get(gen).flatMap(_.get(phase)).getOrElse(0.0)

  /**
   * 记录 outer 阶段并扣掉已经单独计过时的 inner。
   *
   * `prepare` 发生在 `alloc`（整段 tab 分配）内部，直接两个都记会让 UI 上的数字看起来可以相加；扣掉后每个 phase 互不重叠， 只剩 `bgTotal` 是显式的全程总量。
   */
  def markExcluding(gen: String, outer: String, totalMs: Double, inner: String): Unit =
    mark(gen, outer, totalMs - get(gen, inner))

  /** 把该 gen 的所有 phase 写进 trace（失败只告警）。 */
  def flush(gen: String): Unit =
    phases.remove(gen).foreach { collected =>
      if (collected.nonEmpty) {
        try TraceStore.noteTimings(gen, collected.toMap)
        catch { case e: Throwable => PopCanLog.warn("parser", s"trace timings write failed gen=$gen: ${e.getMessage}") }
      }
    }
}
