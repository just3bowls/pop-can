package com.just3bowls.server.runtime

import zio.json._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import scala.util.Using

private[server] object RuntimeEndpointStore {
  private val RuntimeDirName = "runtime"
  private val EndpointName   = "endpoint.json"

  def read(dataDir: Path): Option[RuntimeEndpoint] = {
    val path = endpointPath(dataDir)
    if (!Files.isRegularFile(path)) None
    else
      try
        Using.resource(Files.newInputStream(path)) { in =>
          val raw = new String(in.readAllBytes(), StandardCharsets.UTF_8)
          raw.fromJson[RuntimeEndpoint].toOption.filter(_.version == RuntimeEndpoint.SchemaVersion)
        }
      catch { case _: Throwable => None }
  }

  def write(dataDir: Path, endpoint: RuntimeEndpoint): Unit = {
    val dir  = dataDir.resolve(RuntimeDirName)
    Files.createDirectories(dir)
    val path = dir.resolve(EndpointName)
    val tmp  = dir.resolve(s"$EndpointName.${UUID.randomUUID()}.tmp")
    Files.writeString(
      tmp,
      endpoint.toJson,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE
    )
    try Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    catch {
      case _: Throwable       => Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }
    try Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    catch { case _: Throwable => () }
  }

  /** Remove only when the endpoint still belongs to the given leader. */
  def remove(dataDir: Path, leaderId: String): Unit =
    read(dataDir).filter(_.leaderId == leaderId).foreach { current =>
      if (read(dataDir).exists(_.leaderId == current.leaderId))
        try Files.deleteIfExists(endpointPath(dataDir))
        catch { case _: Throwable => () }
    }

  def removeAny(dataDir: Path): Unit =
    try Files.deleteIfExists(endpointPath(dataDir))
    catch { case _: Throwable => () }

  def runtimeDir(dataDir: Path): Path = dataDir.resolve(RuntimeDirName)

  private def endpointPath(dataDir: Path): Path = runtimeDir(dataDir).resolve(EndpointName)
}
