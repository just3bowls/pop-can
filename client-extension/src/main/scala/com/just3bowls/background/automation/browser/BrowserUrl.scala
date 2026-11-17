package com.just3bowls.background.automation.browser

import com.just3bowls.facade.JsBridge

/** URL normalization shared by browser navigation and batch extraction. */
private[background] object BrowserUrl {
  def normalize(rawUrl: String): Option[String] = {
    val trimmed = rawUrl.trim
    if (trimmed.isEmpty) None
    else {
      val withScheme =
        if (trimmed.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) trimmed
        else if (trimmed.startsWith("about:") || trimmed.startsWith("data:")) trimmed
        else s"https://$trimmed"
      if (withScheme.matches("(?i)^https?://.+")) Some(withScheme)
      else if (withScheme.startsWith("about:") || withScheme.startsWith("data:")) Some(withScheme)
      else None
    }
  }

  /** HTTP(S) URL 的匹配键；忽略 fragment、默认端口和末尾 `/`，保留 query。 */
  def canonicalHttp(rawUrl: String): Option[String] =
    normalize(rawUrl).flatMap { normalized =>
      try {
        val parsed = JsBridge.newInstance(JsBridge.urlConstructor, normalized)
        val scheme = JsBridge.asString(parsed.protocol).toLowerCase
        if (scheme != "http:" && scheme != "https:") None
        else {
          parsed.hash = ""
          val path = JsBridge.asString(parsed.pathname)
          if (path.length > 1 && path.endsWith("/")) parsed.pathname = path.dropRight(1)
          Some(JsBridge.asString(parsed.href))
        }
      } catch {
        case _: Throwable => None
      }
    }
}
