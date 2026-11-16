package com.just3bowls.api.marker

/** Builds the transient instruction sent after strict marker validation fails. */
object ProtocolRepair {

  def directive(errors: List[String]): String =
    build(errors, None)

  def directive(errors: List[String], allowedTools: Set[String]): String =
    build(errors, Some(allowedTools))

  private def build(errors: List[String], allowedTools: Option[Set[String]]): String = {
    val toolGuidance = allowedTools
      .map { tools =>
        if (tools.isEmpty)
          "This turn declares no tools. Do not emit tool_calls. Any provider-native search or other capability must run internally, then return only the final content."
        else
          s"This turn allows only these declared tools: ${tools.toList.sorted.mkString(", ")}. Do not call anything else."
      }
      .map(value => s"\n\n$value")
      .getOrElse("")
    s"""Your immediately preceding response was rejected by strict marker validation:
       |${errors.take(20).map(error => s"- $error").mkString("\n")}
       |
       |Return only a corrected replacement response using exactly the documented marker protocol and declared tools.
       |Fix every listed error. Do not repeat the original request, do not explain, and do not use simplified markers.$toolGuidance""".stripMargin
  }
}
