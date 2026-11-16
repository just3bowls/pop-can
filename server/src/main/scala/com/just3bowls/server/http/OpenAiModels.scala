package com.just3bowls.server.http

import zio.json._

/** OpenAI 兼容错误响应。 */
final case class OpenAiError(
    message: String,
    `type`: String,
    param: Option[String] = None,
    code: Option[String] = None
)

object OpenAiError {
  given JsonCodec[OpenAiError] = DeriveJsonCodec.gen
}

final case class OpenAiErrorResponse(error: OpenAiError)

object OpenAiErrorResponse {
  given JsonCodec[OpenAiErrorResponse] = DeriveJsonCodec.gen
}
