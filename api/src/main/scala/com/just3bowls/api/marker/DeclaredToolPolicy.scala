package com.just3bowls.api.marker

/** Validates model tool calls against the tools actually declared for the current run. */
object DeclaredToolPolicy {
  final case class Violation(code: String, message: String)

  val UndeclaredTool = "undeclared_tool"
  val EmptyAnswer    = "empty_answer"

  def validate(
      content: String,
      toolCalls: List[(String, String)],
      declaredTools: Set[String]
  ): List[Violation] = {
    val undeclared =
      toolCalls.flatMap { case (name, _) =>
        val normalized = Option(name).getOrElse("").trim
        if (normalized.nonEmpty && declaredTools.contains(normalized)) None
        else
          Some(Violation(
            UndeclaredTool,
            toolViolationMessage(normalized, declaredTools)
          ))
      }
    val empty      =
      if (Option(content).getOrElse("").trim.isEmpty && toolCalls.isEmpty)
        List(Violation(EmptyAnswer, "Final answer must include non-empty content when no tool call is made"))
      else Nil
    undeclared ++ empty
  }

  def messages(violations: List[Violation]): List[String] =
    violations.map(_.message)

  private def toolViolationMessage(name: String, declaredTools: Set[String]): String = {
    val called  = if (name.isEmpty) "an empty tool name" else "tool \"" + name + "\""
    val allowed =
      if (declaredTools.isEmpty) "No tools are declared for this turn."
      else s"Allowed tools: ${declaredTools.toList.sorted.mkString(", ")}."
    s"The model called $called, but it is not declared. $allowed"
  }
}
