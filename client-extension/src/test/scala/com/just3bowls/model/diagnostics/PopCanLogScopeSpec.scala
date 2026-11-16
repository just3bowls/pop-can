package com.just3bowls.model.diagnostics

import munit.FunSuite

class PopCanLogScopeSpec extends FunSuite {
  test("empty and star scope values mean all channels") {
    assertEquals(PopCanLog.scopePatterns(""), List(PopCanLog.AllScopes))
    assertEquals(PopCanLog.scopePatterns("*"), List(PopCanLog.AllScopes))
  }

  test("toggling a channel from all selects only that channel") {
    assertEquals(PopCanLog.toggleScope("*", "cdp", enabled = true), "cdp")
    assertEquals(PopCanLog.toggleScope("", "tabs", enabled = true), "tabs")
  }

  test("toggling channels preserves a stable distinct list") {
    assertEquals(PopCanLog.toggleScope("cdp", "tabs", enabled = true), "cdp,tabs")
    assertEquals(PopCanLog.toggleScope("cdp,tabs", "cdp", enabled = false), "tabs")
    assertEquals(PopCanLog.toggleScope("cdp", "cdp", enabled = false), PopCanLog.AllScopes)
  }
}
