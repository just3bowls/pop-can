package com.just3bowls.ui.components

import com.just3bowls.model.chat.{RunPhase, RunProgress}

class RunProgressPresentationSpec extends munit.FunSuite {
  test("names page and response marker phases distinctly") {
    assertEquals(
      RunProgressPresentation.label(RunProgress(RunPhase.PreparingPage, detail = "new-chat")),
      "Locating new chat button…"
    )
    assertEquals(
      RunProgressPresentation.label(RunProgress(RunPhase.LocatingResponse)),
      "Locating response markers…"
    )
  }

  test("includes recovery budget and tool name") {
    assertEquals(
      RunProgressPresentation.label(
        RunProgress(RunPhase.Recovering, recoveryIndex = 2, recoveryBudget = 2)
      ),
      "Recovering · R2 2/2…"
    )
    assertEquals(
      RunProgressPresentation.label(RunProgress(RunPhase.RunningTool, detail = "browser_extract")),
      "Running browser_extract…"
    )
  }

  test("formats elapsed time at minute boundaries") {
    assertEquals(RunProgressPresentation.elapsed(1.0, 12_000.0), "11s")
    assertEquals(RunProgressPresentation.elapsed(1.0, 72_000.0), "1m 11s")
    assertEquals(RunProgressPresentation.elapsed(0.0, 72_000.0), "")
  }

  test("round-trips phase wire values") {
    RunPhase.values.foreach(phase => assertEquals(RunPhase.fromWire(phase.wire), Some(phase)))
    assertEquals(RunPhase.fromWire("unknown"), None)
  }
}
