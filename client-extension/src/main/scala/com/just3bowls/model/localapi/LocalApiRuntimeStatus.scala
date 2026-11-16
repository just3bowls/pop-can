package com.just3bowls.model.localapi

/** Shared Local API endpoint and runtime status vocabulary used by the client and page UI. */
object LocalApiRuntimeStatus {
  val Endpoint = "http://127.0.0.1:8080/v1"

  def isActive(status: String, running: Boolean): Boolean =
    running || status == "Connecting..." || status == "Connected" || status == "Reconnecting..."

  def isError(status: String): Boolean =
    status == "Error" ||
      status.startsWith("Port in use") ||
      status.startsWith("Message error") ||
      status.startsWith("Runtime error")

  def isProgress(status: String): Boolean =
    status == "Connecting..." || status == "Reconnecting..."
}
