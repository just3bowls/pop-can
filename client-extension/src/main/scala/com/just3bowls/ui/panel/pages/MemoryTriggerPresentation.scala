package com.just3bowls.ui.panel.pages

import com.just3bowls.api.memory.MemoryStatus

sealed trait MemoryTriggerState
object MemoryTriggerState {
  case object Checking                          extends MemoryTriggerState
  final case class Ready(value: MemoryStatus)   extends MemoryTriggerState
  final case class Unavailable(message: String) extends MemoryTriggerState
}

sealed trait MemoryTriggerTone
object MemoryTriggerTone {
  case object Ready       extends MemoryTriggerTone
  case object Checking    extends MemoryTriggerTone
  case object Off         extends MemoryTriggerTone
  case object Unavailable extends MemoryTriggerTone
}

final case class MemoryTriggerSegment(text: String, color: String)
final case class MemoryTriggerView(
    segments: List[MemoryTriggerSegment],
    tooltip: String,
    ariaLabel: String,
    tone: MemoryTriggerTone
) {
  def text: String = segments.map(_.text).mkString(" ")
}

object MemoryTriggerPresentation {
  private val Muted = "#6b7280"
  private val Green = "#15803d"
  private val Blue  = "#2563eb"
  private val Amber = "#b45309"
  private val Red   = "#b91c1c"

  def view(state: MemoryTriggerState): MemoryTriggerView =
    state match {
      case MemoryTriggerState.Checking                         =>
        view(
          List(MemoryTriggerSegment("checking…", Blue)),
          "Connecting to the Memory runtime. Click to inspect.",
          "Memory checking. Open stored memories",
          MemoryTriggerTone.Checking
        )
      case MemoryTriggerState.Unavailable(message)             =>
        view(
          List(MemoryTriggerSegment("unavailable", Red)),
          s"Memory unavailable: $message",
          "Memory unavailable. Open stored memories",
          MemoryTriggerTone.Unavailable
        )
      case MemoryTriggerState.Ready(status) if !status.enabled =>
        view(
          List(MemoryTriggerSegment("off", Muted)),
          "Memory is disabled in Config. Click to inspect.",
          "Memory off. Open stored memories",
          MemoryTriggerTone.Off
        )
      case MemoryTriggerState.Ready(status)                    =>
        val counts = List(
          Option.when(status.activeCount > 0)(MemoryTriggerSegment(s"● ${status.activeCount}", Green)),
          Option.when(status.pendingCount > 0)(MemoryTriggerSegment(s"◐ ${status.pendingCount}", Amber)),
          Option.when(status.queuedJobs > 0)(MemoryTriggerSegment(s"↻ ${status.queuedJobs}", Blue))
        ).flatten
        view(
          if (counts.nonEmpty) counts else List(MemoryTriggerSegment("idle", Muted)),
          s"${status.activeCount} active, ${status.pendingCount} pending, ${status.queuedJobs} queued. " +
            "Click to inspect.",
          s"Memory ${status.activeCount} active, ${status.pendingCount} pending, " +
            s"${status.queuedJobs} queued. Open stored memories",
          MemoryTriggerTone.Ready
        )
    }

  private def view(
      body: List[MemoryTriggerSegment],
      tooltip: String,
      ariaLabel: String,
      tone: MemoryTriggerTone
  ): MemoryTriggerView =
    MemoryTriggerView(body, tooltip, ariaLabel, tone)
}
