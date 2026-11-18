package com.just3bowls.model.chat

enum RunPhase(val wire: String) {
  case PreparingRequest   extends RunPhase("preparing-request")
  case PreparingProvider  extends RunPhase("preparing-provider")
  case PreparingPage      extends RunPhase("preparing-page")
  case StartingNewChat    extends RunPhase("starting-new-chat")
  case WaitingForInput    extends RunPhase("waiting-input")
  case PreparingPrompt    extends RunPhase("preparing-prompt")
  case AttachingFile      extends RunPhase("attaching-file")
  case SendingPrompt      extends RunPhase("sending-prompt")
  case WaitingForResponse extends RunPhase("waiting-response")
  case LocatingResponse   extends RunPhase("locating-response")
  case Thinking           extends RunPhase("thinking")
  case GeneratingAnswer   extends RunPhase("generating-answer")
  case WaitingForFollowUp extends RunPhase("waiting-follow-up")
  case ValidatingResponse extends RunPhase("validating-response")
  case RunningTool        extends RunPhase("running-tool")
  case RepairingResponse  extends RunPhase("repairing-response")
  case Recovering         extends RunPhase("recovering")
  case SavingAnswer       extends RunPhase("saving-answer")
}

object RunPhase {
  def fromWire(value: String): Option[RunPhase] =
    RunPhase.values.find(_.wire == value.trim.toLowerCase)
}

final case class RunProgress(
    phase: RunPhase,
    provider: String = "",
    detail: String = "",
    attempt: Int = 0,
    recoveryIndex: Int = 0,
    recoveryBudget: Int = 0
)
