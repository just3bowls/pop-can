package com.just3bowls.model.localapi

/** Shared Bearer/API-key validation for every local HTTP operation. */
object ApiKeyAuth {
  def bearerToken(authorization: String): Option[String] = {
    val value = Option(authorization).getOrElse("").trim
    val space = value.indexOf(' ')
    if (space <= 0) None
    else {
      val scheme = value.substring(0, space)
      val token  = value.substring(space + 1).trim
      if (!scheme.equalsIgnoreCase("Bearer") || token.isEmpty) None else Some(token)
    }
  }

  def findValid(keys: List[ApiKeyConfig], authorization: String): Option[ApiKeyConfig] =
    bearerToken(authorization).flatMap(token => keys.find(key => !key.revoked && key.secret == token))
}
