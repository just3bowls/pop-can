package com.just3bowls.model.provider

import munit.FunSuite

class ProviderPresetReportSpec extends FunSuite {
  test("summarizes import actions in readable terms") {
    val report = ProviderPresetReport(
      version = 23,
      inserted = List("new"),
      updated = List("a", "b"),
      adopted = List("same"),
      kept = List("local"),
      skipped = List("deleted")
    )

    assertEquals(
      report.summary,
      "v23 · 1 new · 2 updated · 1 unchanged · 1 kept · 1 skipped"
    )
  }

  test("summarizes an update-only import") {
    val report = ProviderPresetReport(
      version = 23,
      updated = (1 to 9).map(index => s"provider-$index").toList
    )

    assertEquals(
      report.summary,
      "v23 · 0 new · 9 updated · 0 unchanged · 0 kept · 0 skipped"
    )
  }
}
