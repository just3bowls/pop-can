package com.just3bowls.api.protocol

/** Stable operations carried by native `client_request` messages. */
enum ClientOperation(val wire: String) {
  case Models         extends ClientOperation("models")
  case ChatCompletion extends ClientOperation("chat_completion")
}

object ClientOperation {
  def fromWire(value: String): Option[ClientOperation] =
    ClientOperation.values.find(_.wire == value.trim.toLowerCase)
}
