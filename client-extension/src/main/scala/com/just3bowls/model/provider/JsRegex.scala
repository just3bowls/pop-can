package com.just3bowls.model.provider

import scala.collection.mutable
import scala.scalajs.js

/** Compiles JavaScript regexes with the small subset of leading inline flags supported by provider rules. */
object JsRegex {
  def compile(pattern: String, global: Boolean = false): Option[js.RegExp] = {
    val (clean, inlineFlags) = stripInlineFlags(pattern)
    val flags                = inlineFlags + (if (global && !inlineFlags.contains("g")) "g" else "")
    try Some(new js.RegExp(clean, flags))
    catch { case _: Throwable => None }
  }
  private def stripInlineFlags(pattern: String): (String, String)          = {
    var rest  = pattern
    val flags = mutable.LinkedHashSet.empty[Char]
    var stop  = false
    while (!stop && rest.startsWith("(?") && rest.indexOf(')') > 2) {
      val close = rest.indexOf(')')
      val body  = rest.substring(2, close)
      if (body.nonEmpty && body.forall("ims".contains(_))) {
        body.foreach(flags += _)
        rest = rest.substring(close + 1)
      } else stop = true
    }
    (rest, flags.toList.sorted.mkString)
  }
}
