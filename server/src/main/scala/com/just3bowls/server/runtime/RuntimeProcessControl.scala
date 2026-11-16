package com.just3bowls.server.runtime

/**
 * Process operations are injectable so stale-leader policy can be tested without killing the test JVM.
 */
trait RuntimeProcessControl {
  def current(): RuntimeProcessIdentity
  def identify(pid: Long): Option[RuntimeProcessIdentity]
  def matches(expected: RuntimeProcessIdentity): Boolean
  def terminate(expected: RuntimeProcessIdentity, limits: RuntimeJoinLimits): Boolean
}

object JvmRuntimeProcessControl extends RuntimeProcessControl {
  override def current(): RuntimeProcessIdentity = identityOf(ProcessHandle.current()).getOrElse(
    RuntimeProcessIdentity(ProcessHandle.current().pid(), 0L, "", Nil)
  )

  override def identify(pid: Long): Option[RuntimeProcessIdentity] =
    Option(ProcessHandle.of(pid)).flatMap { optional =>
      if (optional.isPresent) Option(optional.get()).flatMap(identityOf)
      else None
    }

  override def matches(expected: RuntimeProcessIdentity): Boolean =
    identify(expected.pid).exists(actual => sameIdentity(actual, expected))

  override def terminate(expected: RuntimeProcessIdentity, limits: RuntimeJoinLimits): Boolean = {
    if (expected.pid == ProcessHandle.current().pid()) return false
    val handle = Option(ProcessHandle.of(expected.pid)).filter(_.isPresent).map(_.get())
    handle match {
      case None                                                                 => !matches(expected)
      case Some(process) if !sameIdentity(identityOf(process).orNull, expected) => false
      case Some(process)                                                        =>
        try {
          if (process.isAlive) process.destroy()
          if (!awaitExit(process, limits.gracefulExitWait.toMillis) && process.isAlive)
            process.destroyForcibly()
          awaitExit(process, limits.forcedExitWait.toMillis) || !process.isAlive
        } catch {
          case _: Throwable => false
        }
    }
  }

  private def identityOf(process: ProcessHandle): Option[RuntimeProcessIdentity] = {
    val info      = process.info()
    val startedAt =
      if (info.startInstant().isPresent) info.startInstant().get().toEpochMilli
      else 0L
    val command   = if (info.command().isPresent) info.command().get() else ""
    val arguments = if (info.arguments().isPresent) info.arguments().get().toList else Nil
    Some(RuntimeProcessIdentity(process.pid(), startedAt, command, arguments))
  }

  private def sameIdentity(actual: RuntimeProcessIdentity, expected: RuntimeProcessIdentity): Boolean = {
    val actualIdentity  = Option(actual).getOrElse(RuntimeProcessIdentity(-1L, 0L, "", Nil))
    val startDifference = math.abs(actualIdentity.startedAt - expected.startedAt)
    val commandMatches  = normalize(actualIdentity.command) == normalize(expected.command)
    val argumentsMatch  = actualIdentity.arguments.map(normalize) == expected.arguments.map(normalize)
    actualIdentity.pid == expected.pid &&
    startDifference <= 2000L &&
    commandMatches &&
    argumentsMatch
  }

  private def normalize(value: String): String =
    Option(value).getOrElse("").replace('\\', '/').trim.toLowerCase

  private def awaitExit(process: ProcessHandle, waitMillis: Long): Boolean = {
    val deadline = java.lang.System.nanoTime() + math.max(0L, waitMillis) * 1000000L
    while (process.isAlive && java.lang.System.nanoTime() < deadline) Thread.sleep(50L)
    !process.isAlive
  }
}
