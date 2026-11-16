package com.just3bowls.facade
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** marked: Markdown -> HTML */
@js.native
@JSImport("marked", "marked")
object Marked extends js.Object {
  def parse(markdown: String): String | js.Promise[String] = js.native
}

/** DOMPurify: sanitize rendered HTML */
@js.native
@JSImport("dompurify", JSImport.Default)
object DOMPurify extends js.Object {
  def sanitize(html: String): String = js.native
}
