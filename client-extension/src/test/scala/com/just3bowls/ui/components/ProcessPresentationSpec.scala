package com.just3bowls.ui.components

class ProcessPresentationSpec extends munit.FunSuite {
  test("keeps final answers out of the process trace") {
    val presentation = ProcessPresentation.turn("complete answer", finalAnswer = true)

    assert(!presentation.contentVisible)
    assert(presentation.showAnswerHint)
  }

  test("keeps intermediate process content visible") {
    val presentation = ProcessPresentation.turn("intermediate result", finalAnswer = false)

    assert(presentation.contentVisible)
    assert(!presentation.showAnswerHint)
  }

  test("does not show an answer hint without a final-answer turn") {
    val presentation = ProcessPresentation.turn("", finalAnswer = false)

    assert(!presentation.contentVisible)
    assert(!presentation.showAnswerHint)
  }

  test("opens reason only while the turn is running") {
    assert(ProcessPresentation.reasonOpen(running = true))
    assert(!ProcessPresentation.reasonOpen(running = false))
  }
}
