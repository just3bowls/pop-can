package com.just3bowls.ui.components

import com.just3bowls.model.chat.{RunPhase, RunProgress}

object RunProgressPresentation {
  def label(progress: RunProgress): String = {
    val base =
      progress.phase match {
        case RunPhase.PreparingRequest   => "Preparing request…"
        case RunPhase.PreparingProvider  =>
          if (progress.detail == "reuse") "Reusing provider tab…"
          else "Preparing provider tab…"
        case RunPhase.PreparingPage      =>
          progress.detail match {
            case "new-chat" => "Locating new chat button…"
            case "input"    => "Locating input box…"
            case other      => s"Preparing page${if (other.nonEmpty) s" · $other" else ""}…"
          }
        case RunPhase.StartingNewChat    => "Starting a new chat…"
        case RunPhase.WaitingForInput    => "Waiting for input…"
        case RunPhase.PreparingPrompt    => "Preparing prompt…"
        case RunPhase.AttachingFile      => "Attaching request file…"
        case RunPhase.SendingPrompt      => "Sending prompt…"
        case RunPhase.WaitingForResponse =>
          if (progress.provider.nonEmpty) s"Waiting for response · ${progress.provider}…"
          else "Waiting for response…"
        case RunPhase.LocatingResponse   => "Locating response markers…"
        case RunPhase.Thinking           => "Thinking…"
        case RunPhase.GeneratingAnswer   => "Generating answer…"
        case RunPhase.WaitingForFollowUp => "Waiting for follow-up…"
        case RunPhase.ValidatingResponse => "Validating response…"
        case RunPhase.RunningTool        =>
          if (progress.detail.nonEmpty) s"Running ${progress.detail}…" else "Running tool…"
        case RunPhase.RepairingResponse  =>
          if (progress.attempt > 0) s"Repairing response format · ${progress.attempt}/1…"
          else "Repairing response format…"
        case RunPhase.Recovering         =>
          if (progress.recoveryIndex > 0 && progress.recoveryBudget > 0)
            s"Recovering · R${progress.recoveryIndex} ${progress.recoveryIndex}/${progress.recoveryBudget}…"
          else "Recovering…"
        case RunPhase.SavingAnswer       => "Saving answer…"
      }
    base
  }

  def footer(progress: Option[RunProgress], elapsed: String): String =
    progress.map(label).filter(_.nonEmpty).getOrElse("Generating…") +
      (if (elapsed.nonEmpty) s" $elapsed" else "")

  def elapsed(startedAt: Double, now: Double): String = {
    val totalSeconds = math.max(0L, ((now - startedAt) / 1000.0).toLong)
    if (startedAt <= 0 || totalSeconds < 1) ""
    else if (totalSeconds < 60) s"${totalSeconds}s"
    else s"${totalSeconds / 60}m ${totalSeconds % 60}s"
  }
}
