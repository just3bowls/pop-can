package com.just3bowls.facade

import org.scalajs.dom
import scala.scalajs.js

/** KeyboardEvent fields missing from the Scala.js DOM facade version used by the extension. */
@js.native
trait CompositionKeyboardEvent extends js.Object:
  def isComposing: Boolean = js.native

object BrowserEvents {
  def isComposing(event: dom.KeyboardEvent): Boolean =
    event.asInstanceOf[CompositionKeyboardEvent].isComposing
}
