package com.just3bowls.ui.panel.pages

import com.just3bowls.api.memory.MemoryStatus

sealed trait MemoryTriggerState
object MemoryTriggerState {
  case object Checking                          extends MemoryTriggerState
  final case class Ready(value: MemoryStatus)   extends MemoryTriggerState
  final case class Unavailable(message: String) extends MemoryTriggerState
}

final case class MemoryTriggerSegment(text: String, color: String)
final case class MemoryTriggerView(
    segments: List[MemoryTriggerSegment],
    tooltip: String,
    ariaLabel: String
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
          "Memory checking. Open stored memories"
        )
      case MemoryTriggerState.Unavailable(message)             =>
        view(
          List(MemoryTriggerSegment("unavailable", Red)),
          s"Memory unavailable: $message",
          "Memory unavailable. Open stored memories"
        )
      case MemoryTriggerState.Ready(status) if !status.enabled =>
        view(
          List(MemoryTriggerSegment("off", Muted)),
          "Memory is disabled in Config. Click to inspect.",
          "Memory off. Open stored memories"
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
            s"${status.queuedJobs} queued. Open stored memories"
        )
    }

  private def view(
      body: List[MemoryTriggerSegment],
      tooltip: String,
      ariaLabel: String
  ): MemoryTriggerView =
    MemoryTriggerView(MemoryTriggerSegment("memory:", Muted) :: body, tooltip, ariaLabel)
}
