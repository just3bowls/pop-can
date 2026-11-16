package com.just3bowls.background.automation.browser

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.JsonValue
import scala.scalajs.js

/**
 * 正文抽取所需的第三方库源码（readability / turndown）。
 *
 * 来源：`entrypoints/background.ts` 用 vite 的 `?raw` 从 npm 包里取"浏览器可执行的那份文件"
 * （`@mozilla/readability/Readability.js`、`turndown/dist/turndown.js`）打进 bundle，并挂到
 * `globalThis.__thExtractSources`；这里只负责读取、校验并缓存，实际注入由 `BrowserUseExecutor` 通过 CDP `Runtime.evaluate` 完成（与既有的
 * `__popCanLocationScript` 同一模式）。
 */
private[background] object ExtractSources {
  private var cached: Option[(String, String)] = None

  /** (readability 源码, turndown 源码)；缺失时返回 None（此时只走区域/全文兜底）。 */
  def sources: Option[(String, String)] =
    cached.orElse {
      val raw = JsBridge.extractSources
      if (js.isUndefined(raw) || raw == null) None
      else {
        val readability = JsonValue.stringField(raw, "readability").getOrElse("")
        val turndown    = JsonValue.stringField(raw, "turndown").getOrElse("")
        if (readability.isEmpty || turndown.isEmpty) None
        else {
          val value = (readability, turndown)
          cached = Some(value)
          Some(value)
        }
      }
    }
}
