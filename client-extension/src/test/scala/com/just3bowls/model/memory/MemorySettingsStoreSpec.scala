package com.just3bowls.model.memory

import com.just3bowls.api.memory.{MemoryExtractionRequest, MemoryProviderRoute, MemorySettings}
import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.api.provider.ServerRouting
import com.just3bowls.facade.JsBridge

import munit.FunSuite
import zio.json.*

import scala.scalajs.js

class MemorySettingsStoreSpec extends FunSuite {
  test("normalizes settings before storing or syncing them") {
    val normalized = MemorySettings.normalize(MemorySettings(
      autoExtract = true,
      extractionRoutes = List(
        MemoryProviderRoute("  deepseek  ", 0, " FILE "),
        MemoryProviderRoute("   ", 3)
      ),
      extractionStrategy = "RANDOM",
      extractionFailover = "NEXT",
      contextBudgetTokens = 9999
    ))

    assertEquals(normalized.autoExtract, true)
    assertEquals(
      normalized.extractionRoutes,
      List(MemoryProviderRoute("deepseek", 1, RoutePromptMode.File))
    )
    assertEquals(normalized.extractionStrategy, ServerRouting.StrategyRandom)
    assertEquals(normalized.extractionFailover, ServerRouting.FailoverNext)
    assertEquals(normalized.contextBudgetTokens, 4000)
    assertEquals(
      MemorySettings.normalize(normalized.copy(autoExtract = true, extractionRoutes = Nil)).autoExtract,
      false
    )
  }

  test("caps extraction providers at the configured limit") {
    val normalized = MemorySettings.normalize(MemorySettings(
      extractionRoutes = List(
        MemoryProviderRoute("one"),
        MemoryProviderRoute("two"),
        MemoryProviderRoute("three"),
        MemoryProviderRoute("four")
      )
    ))

    assertEquals(MemorySettings.MaxExtractionProviders, 3)
    assertEquals(normalized.extractionRoutes.map(_.provider), List("one", "two", "three"))
  }

  test("deduplicates extraction providers while preserving their first route") {
    val normalized = MemorySettings.normalize(MemorySettings(
      extractionRoutes = List(
        MemoryProviderRoute("one", 2),
        MemoryProviderRoute("two", 1),
        MemoryProviderRoute("one", 3)
      )
    ))

    assertEquals(
      normalized.extractionRoutes,
      List(MemoryProviderRoute("one", 2), MemoryProviderRoute("two", 1))
    )
  }

  test("legacy routes without promptMode decode as inline") {
    val route = """{"provider":"gml","weight":2}""".fromJson[MemoryProviderRoute]

    assertEquals(route, Right(MemoryProviderRoute("gml", 2, RoutePromptMode.Inline)))
  }

  test("legacy extraction requests without promptMode decode as inline") {
    val request =
      """{"requestId":"r1","jobId":"j1","provider":"gml","prompt":"extract"}"""
        .fromJson[MemoryExtractionRequest]

    assertEquals(
      request.map(_.promptMode),
      Right(RoutePromptMode.Inline)
    )
  }

  test("decodes the versioned storage document") {
    val settings = MemorySettings(
      extractionRoutes = List(MemoryProviderRoute("gml", 2)),
      contextBudgetTokens = 900
    )
    val raw      = JsBridge.obj(
      "schemaVersion" -> 1,
      "settings"      -> JsBridge.jsonParse(settings.toJson),
      "updatedAt"     -> 1.0
    )

    assertEquals(MemorySettingsStore.parseDocument(raw), Some(settings))
  }

  test("rejects unsupported schema versions and malformed settings") {
    val unsupported = JsBridge.obj(
      "schemaVersion" -> 2,
      "settings"      -> JsBridge.jsonParse(MemorySettings.Default.toJson),
      "updatedAt"     -> 1.0
    )
    val malformed   = JsBridge.obj(
      "schemaVersion" -> 1,
      "settings"      -> "not-an-object",
      "updatedAt"     -> 1.0
    )
    val missing     = js.undefined

    assertEquals(MemorySettingsStore.parseDocument(unsupported), None)
    assertEquals(MemorySettingsStore.parseDocument(malformed), None)
    assertEquals(MemorySettingsStore.parseDocument(missing), None)
  }
}
