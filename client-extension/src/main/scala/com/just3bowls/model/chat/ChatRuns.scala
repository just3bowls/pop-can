package com.just3bowls.model.chat

/** Attempt (A) 与自动 Recovery (R) 的分组、选择和顶层镜像规则。 */
object ChatRuns {
  def topLevel(attempts: List[ChatAttempt]): List[ChatAttempt] =
    attempts.filter(_.parentAttemptId.isEmpty).sortBy(attempt => (attempt.index, attempt.createdAt))

  def recoveries(attempts: List[ChatAttempt], attemptId: String): List[ChatAttempt] =
    attempts
      .filter(_.parentAttemptId == attemptId)
      .sortBy(recovery => (recovery.index, recovery.createdAt))

  def grouped(attempts: List[ChatAttempt]): List[(ChatAttempt, List[ChatAttempt])] =
    topLevel(attempts).map(attempt => attempt -> recoveries(attempts, attempt.id))

  def active(message: ChatMessage): Option[ChatAttempt] = {
    val normalized = ChatMessage.withAttempts(message)
    normalized.attempts.find(_.id == normalized.activeAttemptIdOrLegacy)
  }

  def rootOf(message: ChatMessage, runId: String): Option[ChatAttempt] = {
    val normalized = ChatMessage.withAttempts(message)
    normalized.attempts.find(_.id == runId).flatMap { run =>
      if (run.parentAttemptId.isEmpty) Some(run)
      else normalized.attempts.find(_.id == run.parentAttemptId)
    }
  }

  def nextAttemptIndex(message: ChatMessage): Int =
    topLevel(ChatMessage.withAttempts(message).attempts).map(_.index).maxOption.getOrElse(0) + 1

  def nextRecoveryIndex(message: ChatMessage, attemptId: String): Int =
    recoveries(ChatMessage.withAttempts(message).attempts, attemptId).map(_.index).maxOption.getOrElse(0) + 1

  def activate(message: ChatMessage, runId: String): ChatMessage = {
    val normalized = ChatMessage.withAttempts(message)
    normalized.attempts.find(_.id == runId) match {
      case Some(run) => mirror(normalized.copy(activeAttemptId = run.id), run)
      case None      => normalized
    }
  }

  def updateActive(message: ChatMessage)(update: ChatAttempt => ChatAttempt): ChatMessage = {
    val normalized = ChatMessage.withAttempts(message)
    val runId      = normalized.activeAttemptIdOrLegacy
    normalized.attempts.find(_.id == runId) match {
      case Some(run) =>
        val nextAttempts = normalized.attempts.map(attempt => if (attempt.id == run.id) update(attempt) else attempt)
        val nextRun      = nextAttempts.find(_.id == run.id).getOrElse(run)
        mirror(normalized.copy(attempts = nextAttempts, activeAttemptId = run.id), nextRun)
      case None      => normalized
    }
  }

  def mirror(message: ChatMessage, run: ChatAttempt): ChatMessage =
    message.copy(
      provider = run.provider,
      content = run.content,
      thinking = run.thinking,
      steps = run.steps,
      toolCalls = run.toolCalls,
      toolCallId = run.toolCallId,
      toolResults = run.toolResults,
      status = run.status,
      activeAttemptId = run.id,
      requestedMode = run.requestedMode,
      effectiveMode = run.effectiveMode,
      modePath = run.modePath,
      agentRunId = run.agentRunId,
      browserTools = run.browserTools,
      updatedAt = run.updatedAt
    )
}
