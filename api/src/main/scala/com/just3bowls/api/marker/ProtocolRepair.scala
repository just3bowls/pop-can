package com.just3bowls.api.marker

/** Builds the transient instruction sent after strict marker validation fails. */
object ProtocolRepair {

  def directive(errors: List[String]): String =
    s"""Your immediately preceding response was rejected by strict marker validation:
       |${errors.take(20).map(error => s"- $error").mkString("\n")}
       |
       |Return only a corrected replacement response using exactly the documented marker protocol and declared tools.
       |Fix every listed error. Do not repeat the original request, do not explain, and do not use simplified markers.""".stripMargin
}
