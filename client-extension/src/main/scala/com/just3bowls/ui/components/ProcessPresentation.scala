package com.just3bowls.ui.components

/** Presentation rules shared by the Process panel and its regression tests. */
private[ui] object ProcessPresentation {
  final case class Turn(contentVisible: Boolean, showAnswerHint: Boolean)

  def turn(content: String, finalAnswer: Boolean): Turn =
    Turn(
      contentVisible = content.nonEmpty && !finalAnswer,
      showAnswerHint = finalAnswer
    )

  def reasonOpen(running: Boolean): Boolean = running
}
