package com.just3bowls.api.provider

/**
 * Provider page failures that happen before marker validation.
 *
 * These messages deliberately separate transport/provider failures from parser-rule failures so callers do not retry a
 * rule mismatch as if the model had answered with an invalid marker protocol.
 */
object ProviderTurnDiagnostics {

  def noContent(provider: String, rawChars: Int, mode: String): String = {
    val name = Option(provider).map(_.trim).filter(_.nonEmpty).getOrElse("Provider")
    if (rawChars <= 0)
      s"$name completed without returning any response data."
    else {
      val modeText = Option(mode).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")
      s"$name returned $rawChars response characters but the provider rule extracted no answer text " +
        s"(mode: $modeText). The response shape does not match the configured rule."
    }
  }
}
