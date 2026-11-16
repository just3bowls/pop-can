package com.just3bowls.ui.components

import munit.FunSuite

class LocalApiStatusSpec extends FunSuite {
  test("maps Local API states to the shared status colors") {
    assertEquals(LocalApiStatus.color("Connected"), "#16a34a")
    assertEquals(LocalApiStatus.color("Connecting..."), "#f59e0b")
    assertEquals(LocalApiStatus.color("Reconnecting..."), "#f59e0b")
    assertEquals(LocalApiStatus.color("Port in use"), "#dc2626")
    assertEquals(LocalApiStatus.color("Stopped"), "#cbd5e1")
  }

  test("treats runtime and transport statuses consistently") {
    assert(LocalApiStatus.isActive("Connected", running = false))
    assert(LocalApiStatus.isActive("Stopped", running = true))
    assert(!LocalApiStatus.isActive("Stopped", running = false))
    assert(LocalApiStatus.isError("Runtime error"))
  }

  test("uses hollow neutral dots only while stopped") {
    assert(LocalApiStatus.indicatorHollow("Stopped", active = false))
    assert(!LocalApiStatus.indicatorHollow("Connected", active = true))
    assert(!LocalApiStatus.indicatorHollow("Port in use", active = false))
  }
}
