package com.just3bowls.model.localapi

class ApiKeyAuthSpec extends munit.FunSuite {
  private val active  = ApiKeyConfig("active", "secret", "Active", revoked = false, 1.0, 0.0)
  private val revoked = ApiKeyConfig("revoked", "old", "Revoked", revoked = true, 1.0, 0.0)

  test("accepts a case-insensitive Bearer scheme") {
    assertEquals(ApiKeyAuth.bearerToken("bearer secret"), Some("secret"))
    assertEquals(ApiKeyAuth.bearerToken("Bearer   secret  "), Some("secret"))
  }

  test("rejects missing and malformed authorization") {
    assertEquals(ApiKeyAuth.bearerToken(""), None)
    assertEquals(ApiKeyAuth.bearerToken("secret"), None)
    assertEquals(ApiKeyAuth.bearerToken("Basic secret"), None)
    assertEquals(ApiKeyAuth.bearerToken("Bearer "), None)
  }

  test("finds only active keys with matching secrets") {
    assertEquals(ApiKeyAuth.findValid(List(active, revoked), "Bearer secret").map(_.id), Some("active"))
    assertEquals(ApiKeyAuth.findValid(List(active, revoked), "Bearer old"), None)
    assertEquals(ApiKeyAuth.findValid(List(active), "Bearer missing"), None)
    assertEquals(ApiKeyAuth.findValid(List(active), ""), None)
  }
}
