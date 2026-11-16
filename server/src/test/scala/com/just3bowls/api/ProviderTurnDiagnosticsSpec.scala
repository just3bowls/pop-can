package com.just3bowls.api
import com.just3bowls.api.provider.*

import zio.test._

object ProviderTurnDiagnosticsSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("ProviderTurnDiagnostics")(
    test("distinguishes an empty transport from a parser rule miss") {
      assertTrue(
        ProviderTurnDiagnostics.noContent("deepseek", 0, "") ==
          "deepseek completed without returning any response data.",
        ProviderTurnDiagnostics.noContent("deepseek", 19714, "unknown") ==
          "deepseek returned 19714 response characters but the provider rule extracted no answer text " +
          "(mode: unknown). The response shape does not match the configured rule."
      )
    },
    test("normalizes missing provider and mode labels") {
      assertTrue(
        ProviderTurnDiagnostics.noContent("", 12, "") ==
          "Provider returned 12 response characters but the provider rule extracted no answer text " +
          "(mode: unknown). The response shape does not match the configured rule."
      )
    }
  )
}
