package com.just3bowls.background.automation.browser

class BrowserUrlSpec extends munit.FunSuite {
  test("canonicalizes valid HTTP URLs") {
    assertEquals(
      BrowserUrl.canonicalHttp("https://example.com/docs/page/#section"),
      Some("https://example.com/docs/page")
    )
    assertEquals(
      BrowserUrl.canonicalHttp("example.com/docs/?q=zio"),
      Some("https://example.com/docs?q=zio")
    )
    assertEquals(
      BrowserUrl.canonicalHttp("https://example.com:443/docs"),
      Some("https://example.com/docs")
    )
  }

  test("rejects non-HTTP pages for batch extraction") {
    assertEquals(BrowserUrl.canonicalHttp("about:blank"), None)
    assertEquals(BrowserUrl.canonicalHttp("ftp://example.com/file"), None)
  }

  test("keeps browser-internal URLs available for navigation") {
    assertEquals(BrowserUrl.normalize("about:blank"), Some("about:blank"))
    assertEquals(BrowserUrl.normalize("data:text/plain,hello"), Some("data:text/plain,hello"))
  }
}
