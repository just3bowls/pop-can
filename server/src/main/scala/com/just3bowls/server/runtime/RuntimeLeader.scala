package com.just3bowls.server.runtime

import zio._

import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID

private[server] object RuntimeLeader {
  private val LockName = "runtime.lock"

  def tryAcquire(
      dataDir: Path,
      processControl: RuntimeProcessControl = JvmRuntimeProcessControl
  ): Option[RuntimeLeaderLease] = {
    val dir     = RuntimeEndpointStore.runtimeDir(dataDir)
    Files.createDirectories(dir)
    val channel = FileChannel.open(
      dir.resolve(LockName),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE
    )
    try
      Option(channel.tryLock()) match {
        case Some(lock) =>
          val now   = java.lang.System.currentTimeMillis()
          val lease =
            new RuntimeLeaderLease(channel, lock, UUID.randomUUID().toString, processControl.current(), now)
          try {
            RuntimeEndpointStore.write(
              dataDir,
              RuntimeEndpoint(
                state = RuntimeEndpoint.Starting,
                leaderId = lease.leaderId,
                protocolVersion = RuntimeEndpoint.ProtocolVersion,
                process = lease.process,
                port = 0,
                token = "",
                startedAt = lease.startedAt
              )
            )
            Some(lease)
          } catch {
            case error: Throwable =>
              lease.close()
              throw error
          }
        case None       =>
          channel.close()
          None
      }
    catch {
      case _: OverlappingFileLockException =>
        channel.close()
        None
    }
  }

  /**
   * Join the existing runtime or become the leader. A probe failure permits exactly one stale-leader takeover so a
   * permanently bad owner cannot cause every new Chrome native host to kill processes in a loop.
   */
  def join(
      dataDir: Path,
      processControl: RuntimeProcessControl = JvmRuntimeProcessControl,
      limits: RuntimeJoinLimits = RuntimeJoinLimits()
  ): IO[RuntimeJoinError, RuntimeRole] =
    ZIO.attemptBlocking(joinBlocking(dataDir, processControl, limits)).mapError {
      case error: RuntimeJoinError => error
      case error                   =>
        RuntimeJoinError(
          RuntimeJoinError.JoinFailed,
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        )
    }

  private def joinBlocking(
      dataDir: Path,
      processControl: RuntimeProcessControl,
      limits: RuntimeJoinLimits
  ): RuntimeRole = {
    var takeoverAttempted = false
    while (true) {
      tryAcquire(dataDir, processControl) match {
        case Some(lease) => return RuntimeLeaderRole(lease)
        case None        =>
          val endpoint = awaitEndpoint(dataDir, limits)
          if (endpoint.state != RuntimeEndpoint.Ready) {
            if (takeoverAttempted)
              throw RuntimeJoinError(
                RuntimeJoinError.StartupTimeout,
                s"runtime leader ${endpoint.leaderId} did not become ready"
              )
            takeover(dataDir, endpoint, processControl, limits) match {
              case Some(lease) => return RuntimeLeaderRole(lease)
              case None        => ()
            }
            takeoverAttempted = true
          } else {
            RuntimeProxy.probeWithRetries(dataDir, endpoint, limits) match {
              case Right(connection) => return RuntimeProxyRole(connection)
              case Left(error)       =>
                if (takeoverAttempted)
                  throw RuntimeJoinError(
                    RuntimeJoinError.TakeoverFailed,
                    s"${error.code}: ${error.detail}; automatic takeover already failed once"
                  )
                takeover(dataDir, endpoint, processControl, limits) match {
                  case Some(lease) => return RuntimeLeaderRole(lease)
                  case None        => ()
                }
                takeoverAttempted = true
            }
          }
      }
    }
    throw RuntimeJoinError(RuntimeJoinError.JoinFailed, "unreachable")
  }

  private[server] def awaitEndpoint(dataDir: Path, limits: RuntimeJoinLimits): RuntimeEndpoint = {
    val deadline = java.lang.System.nanoTime() + limits.startupWait.toNanos
    var last     = Option.empty[RuntimeEndpoint]
    while (java.lang.System.nanoTime() < deadline) {
      RuntimeEndpointStore.read(dataDir) match {
        case Some(endpoint) if endpoint.state != RuntimeEndpoint.Starting => return endpoint
        case Some(endpoint)                                               =>
          last = Some(endpoint)
          if (java.lang.System.currentTimeMillis() - endpoint.startedAt >= limits.startupWait.toMillis) return endpoint
        case None                                                         => ()
      }
      Thread.sleep(100L)
    }
    last match {
      case Some(endpoint) => endpoint
      case None           =>
        throw RuntimeJoinError(
          RuntimeJoinError.EndpointUnavailable,
          "another process owns runtime.lock but published no endpoint"
        )
    }
  }

  private def takeover(
      dataDir: Path,
      observed: RuntimeEndpoint,
      processControl: RuntimeProcessControl,
      limits: RuntimeJoinLimits
  ): Option[RuntimeLeaderLease] = {
    val current = RuntimeEndpointStore.read(dataDir).getOrElse(observed)
    if (current.leaderId != observed.leaderId)
      throw RuntimeJoinError(
        RuntimeJoinError.TakeoverFailed,
        s"runtime leader changed from ${observed.leaderId} to ${current.leaderId} during takeover"
      )
    if (processControl.identify(current.process.pid).isEmpty)
      return awaitLeadership(dataDir, current.leaderId, processControl, limits)
    if (!processControl.matches(current.process))
      throw RuntimeJoinError(
        RuntimeJoinError.OwnerUnverified,
        s"could not prove pid=${current.process.pid} is the recorded PopCanHost process"
      )
    if (!processControl.terminate(current.process, limits))
      throw RuntimeJoinError(
        RuntimeJoinError.TakeoverFailed,
        s"could not terminate stale runtime pid=${current.process.pid}"
      )
    awaitLeadership(dataDir, current.leaderId, processControl, limits)
  }

  /** Acquire leadership after termination, or return None when another follower won the race. */
  private def awaitLeadership(
      dataDir: Path,
      staleLeaderId: String,
      processControl: RuntimeProcessControl,
      limits: RuntimeJoinLimits
  ): Option[RuntimeLeaderLease] = {
    val deadline = java.lang.System.nanoTime() + limits.lockWait.toNanos
    while (java.lang.System.nanoTime() < deadline) {
      tryAcquire(dataDir, processControl) match {
        case Some(lease) => return Some(lease)
        case None        =>
          RuntimeEndpointStore.read(dataDir).filter(_.leaderId != staleLeaderId) match {
            case Some(_) => return None
            case None    => ()
          }
      }
      Thread.sleep(100L)
    }
    throw RuntimeJoinError(
      RuntimeJoinError.TakeoverFailed,
      s"timed out waiting to replace stale runtime $staleLeaderId"
    )
  }
}
