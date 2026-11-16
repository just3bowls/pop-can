package com.just3bowls.ui.panel.pages

import com.just3bowls.api.memory.MemoryStatus

class MemoryTriggerPresentationSpec extends munit.FunSuite {
  private def status(
      enabled: Boolean = true,
      active: Int = 0,
      pending: Int = 0,
      queued: Int = 0
  ): MemoryStatus =
    MemoryStatus(
      enabled = enabled,
      activeCount = active,
      pendingCount = pending,
      queuedJobs = queued
    )

  test("checking is visibly distinct from off") {
    val view = MemoryTriggerPresentation.view(MemoryTriggerState.Checking)
    assertEquals(view.text, "checking…")
    assertEquals(view.tone, MemoryTriggerTone.Checking)
    assert(view.segments.last.color != "#b91c1c")
  }

  test("ready renders colored status markers without separators") {
    val view = MemoryTriggerPresentation.view(
      MemoryTriggerState.Ready(status(active = 4, pending = 2, queued = 3))
    )
    assertEquals(view.text, "● 4 ◐ 2 ↻ 3")
    assertEquals(view.tone, MemoryTriggerTone.Ready)
    assertEquals(view.tooltip, "4 active, 2 pending, 3 queued. Click to inspect.")
  }

  test("zero counts are omitted") {
    val view = MemoryTriggerPresentation.view(
      MemoryTriggerState.Ready(status(active = 1))
    )
    assertEquals(view.text, "● 1")
  }

  test("active, pending, and queued use their semantic colors") {
    val view = MemoryTriggerPresentation.view(
      MemoryTriggerState.Ready(status(active = 1, pending = 2, queued = 3))
    )
    assertEquals(view.segments.find(_.text == "● 1").map(_.color), Some("#15803d"))
    assertEquals(view.segments.find(_.text == "◐ 2").map(_.color), Some("#b45309"))
    assertEquals(view.segments.find(_.text == "↻ 3").map(_.color), Some("#2563eb"))
  }

  test("all-zero counts render an idle state") {
    val view = MemoryTriggerPresentation.view(
      MemoryTriggerState.Ready(status())
    )
    assertEquals(view.text, "idle")
    assertEquals(view.segments.last.color, "#6b7280")
  }

  test("disabled memory is off rather than unavailable") {
    val view = MemoryTriggerPresentation.view(
      MemoryTriggerState.Ready(status(enabled = false))
    )
    assertEquals(view.text, "off")
    assertEquals(view.tone, MemoryTriggerTone.Off)
    assertEquals(view.tooltip, "Memory is disabled in Config. Click to inspect.")
  }

  test("unavailable carries the failure reason") {
    val view = MemoryTriggerPresentation.view(
      MemoryTriggerState.Unavailable("Memory request timed out: status")
    )
    assertEquals(view.text, "unavailable")
    assertEquals(view.tone, MemoryTriggerTone.Unavailable)
    assert(view.tooltip.contains("Memory request timed out: status"))
  }
}
