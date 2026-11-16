package com.just3bowls.server.runtime

import zio._
import zio.json._

import java.io.{BufferedInputStream, BufferedOutputStream}
import java.net.Socket
import java.nio.channels.{FileChannel, FileLock}

final case class RuntimeProcessIdentity(
    pid: Long,
    startedAt: Long,
    command: String,
    arguments: List[String]
)

object RuntimeProcessIdentity {
  given JsonCodec[RuntimeProcessIdentity] = DeriveJsonCodec.gen
}

/**
 * Ephemeral coordination record for the single Memory/runtime owner.
 *
 * `starting` is published immediately after acquiring the lock and before Memory initialization. This prevents a
 * follower from treating the previous process's endpoint as current while the real leader is still loading SQLite,
 * Lucene or the embedding model.
 */
final case class RuntimeEndpoint(
    version: Int = RuntimeEndpoint.SchemaVersion,
    state: String,
    leaderId: String,
    protocolVersion: Int,
    process: RuntimeProcessIdentity,
    port: Int,
    token: String,
    startedAt: Long
)

object RuntimeEndpoint {
  val SchemaVersion   = 2
  val ProtocolVersion = 1
  val Starting        = "starting"
  val Ready           = "ready"

  given JsonCodec[RuntimeEndpoint] = DeriveJsonCodec.gen
}

final case class RuntimeJoinError(code: String, detail: String) extends RuntimeException(detail)

object RuntimeJoinError {
  val EndpointUnavailable = "runtime_endpoint_unavailable"
  val StartupTimeout      = "runtime_startup_timeout"
  val ProbeFailed         = "runtime_probe_failed"
  val OwnerUnverified     = "runtime_owner_unverified"
  val TakeoverFailed      = "runtime_takeover_failed"
  val JoinFailed          = "runtime_join_failed"
}

final case class RuntimeJoinLimits(
    startupWait: Duration = 30.seconds,
    probeAttempts: Int = 3,
    probeRetryDelays: List[Duration] = List(250.millis, 750.millis),
    connectTimeoutMillis: Int = 1000,
    responseTimeoutMillis: Int = 2000,
    gracefulExitWait: Duration = 3.seconds,
    forcedExitWait: Duration = 2.seconds,
    lockWait: Duration = 5.seconds
)

final class RuntimeLeaderLease private[server] (
    val channel: FileChannel,
    val lock: FileLock,
    val leaderId: String,
    val process: RuntimeProcessIdentity,
    val startedAt: Long
) extends AutoCloseable {
  override def close(): Unit = {
    try lock.release()
    catch { case _: Throwable => () }
    try channel.close()
    catch { case _: Throwable => () }
  }
}

final private[server] class RuntimeProxyConnection(
    val endpoint: RuntimeEndpoint,
    val socket: Socket,
    val in: BufferedInputStream,
    val out: BufferedOutputStream
) extends AutoCloseable {
  override def close(): Unit =
    try socket.close()
    catch { case _: Throwable => () }
}

sealed trait RuntimeRole
final case class RuntimeLeaderRole(lease: RuntimeLeaderLease)         extends RuntimeRole
final case class RuntimeProxyRole(connection: RuntimeProxyConnection) extends RuntimeRole
