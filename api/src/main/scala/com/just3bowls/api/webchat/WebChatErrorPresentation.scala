package com.just3bowls.api.webchat

/**
 * Provider-page error summaries shown by the native-chat capsule/toast.
 *
 * The raw backend error remains available separately for copy/diagnostics. This mapping only separates page-control
 * failures from marker-protocol failures so the two are not conflated.
 */
object WebChatErrorPresentation {

  def summary(provider: String, detail: String): String = {
    val name = displayName(provider)
    if (isProtocolFailure(detail))
      s"$name could not continue this turn: the response did not follow the marker protocol."
    else if (isPageControlFailure(detail))
      s"$name could not continue this turn: the page input controls were not ready."
    else if (isConnectionFailure(detail))
      s"$name could not continue this turn: the browser connection failed."
    else
      s"$name could not continue this turn."
  }

  def isCancellation(detail: String): Boolean = {
    val text = Option(detail).getOrElse("").toLowerCase
    text.contains("task canceled") || text.contains("task cancelled") || text.contains("cancelled by user")
  }

  private def isProtocolFailure(detail: String): Boolean = {
    val text = Option(detail).getOrElse("")
    text.contains("@@BEG:response@@") ||
    text.contains("marker protocol") ||
    text.contains("required marker") ||
    text.contains("unpaired marker")
  }

  private def isPageControlFailure(detail: String): Boolean = {
    val text = Option(detail).getOrElse("").toLowerCase
    text.contains("input element not found") ||
    text.contains("required markers not ready") ||
    text.contains("input marker") ||
    text.contains("marker input") ||
    text.contains("new chat click failed") ||
    text.contains("new-chat selector")
  }

  private def isConnectionFailure(detail: String): Boolean = {
    val text = Option(detail).getOrElse("").toLowerCase
    text.contains("attach failed") ||
    text.contains("debugger") ||
    text.contains("fetch.enable") ||
    text.contains("browser connection")
  }

  private def displayName(provider: String): String = {
    val trimmed = Option(provider).getOrElse("").trim
    if (trimmed.isEmpty) "The provider"
    else trimmed.substring(0, 1).toUpperCase + trimmed.substring(1)
  }
}
