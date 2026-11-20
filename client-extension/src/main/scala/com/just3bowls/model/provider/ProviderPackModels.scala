package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.diagnostics.PopCanLog

import scala.scalajs.js

final case class VerifiedProviderEnvelope(
    envelopeText: String,
    payloadText: String,
    payload: js.Any,
    keyId: String
)

final case class ProviderPackSettings(
    autoUpdate: Boolean = false,
    lastCheckedAt: Double = 0.0,
    lastError: String = ""
)

final case class ProviderPackMeta(
    bundleVersion: Int = 0,
    providerCount: Int = 0,
    source: String = "",
    installedAt: Double = 0.0,
    sha256: String = "",
    keyId: String = ""
)

final case class ProviderPackStatus(
    installed: Boolean = false,
    bundleVersion: Int = 0,
    providerCount: Int = 0,
    source: String = "",
    installedAt: Double = 0.0,
    autoUpdate: Boolean = false,
    lastCheckedAt: Double = 0.0,
    updateAvailableVersion: Int = 0,
    busy: Boolean = false,
    error: String = ""
) {
  def toJs: js.Object =
    JsBridge.obj(
      "installed"              -> (installed),
      "bundleVersion"          -> (bundleVersion),
      "providerCount"          -> (providerCount),
      "source"                 -> (source),
      "installedAt"            -> (installedAt),
      "autoUpdate"             -> (autoUpdate),
      "lastCheckedAt"          -> (lastCheckedAt),
      "updateAvailableVersion" -> (updateAvailableVersion),
      "busy"                   -> (busy),
      "error"                  -> (error)
    )
}

object ProviderPackStatus {
  def fromJs(value: js.Any): ProviderPackStatus =
    ProviderPackStatus(
      installed = JsBridge.booleanField(value, "installed").getOrElse(false),
      bundleVersion = JsBridge.intField(value, "bundleVersion").getOrElse(0),
      providerCount = JsBridge.intField(value, "providerCount").getOrElse(0),
      source = JsBridge.stringField(value, "source").getOrElse(""),
      installedAt = JsBridge.doubleField(value, "installedAt").getOrElse(0.0),
      autoUpdate = JsBridge.booleanField(value, "autoUpdate").getOrElse(false),
      lastCheckedAt = JsBridge.doubleField(value, "lastCheckedAt").getOrElse(0.0),
      updateAvailableVersion = JsBridge.intField(value, "updateAvailableVersion").getOrElse(0),
      busy = JsBridge.booleanField(value, "busy").getOrElse(false),
      error = JsBridge.stringField(value, "error").getOrElse("")
    )
}

final case class ProviderPackIndex(
    bundleVersion: Int,
    minExtensionVersion: String,
    generatedAt: String,
    sha256: String,
    packUrl: String,
    providers: List[(String, String)]
)

object ProviderPackIndex {
  def parse(payload: js.Any): Either[String, ProviderPackIndex] = {
    val kind                = JsBridge.stringField(payload, "kind").getOrElse("")
    val packSchema          = JsBridge.intField(payload, "packSchemaVersion").getOrElse(0)
    val providerSchema      = JsBridge.intField(payload, "providerSchemaVersion").getOrElse(0)
    val bundleVersion       = JsBridge.intField(payload, "bundleVersion").getOrElse(0)
    val minExtensionVersion = JsBridge.stringField(payload, "minExtensionVersion").getOrElse("")
    val generatedAt         = JsBridge.stringField(payload, "generatedAt").getOrElse("")
    val sha256              = JsBridge.stringField(payload, "sha256").getOrElse("")
    val packUrl             = JsBridge.stringField(payload, "packUrl").getOrElse("")
    val providers           = JsBridge.arrayField(payload, "providers").toList.map { item =>
      JsBridge.stringField(item, "id").getOrElse("") -> JsBridge.stringField(item, "name").getOrElse("")
    }
    if (kind != "provider-index") Left("Provider index has the wrong kind")
    else if (packSchema != 1) Left(s"Unsupported provider pack schema: $packSchema")
    else if (providerSchema != 1) Left(s"Unsupported provider schema: $providerSchema")
    else if (bundleVersion <= 0) Left("Provider index has no positive bundleVersion")
    else if (minExtensionVersion.trim.isEmpty) Left("Provider index has no minExtensionVersion")
    else if (!sha256.matches("^[0-9a-f]{64}$")) Left("Provider index has an invalid SHA256")
    else if (!packUrl.matches("^provider-pack-v[0-9]+\\.popcan\\.json$"))
      Left("Provider index has an invalid pack URL")
    else if (providers.exists(_._1.trim.isEmpty)) Left("Provider index contains an empty provider id")
    else
      Right(
        ProviderPackIndex(
          bundleVersion,
          minExtensionVersion,
          generatedAt,
          sha256,
          packUrl,
          providers
        )
      )
  }
}

object ProviderPackVersions {
  private def parts(value: String): Option[List[Int]] = {
    val pieces = value.split("\\.").toList
    if (pieces.length != 3 || pieces.exists(piece => !piece.matches("^[0-9]+$"))) None
    else Some(pieces.map(_.toInt))
  }

  def satisfies(extensionVersion: String, minimum: String): Boolean =
    (parts(extensionVersion), parts(minimum)) match {
      case (Some(current), Some(required)) =>
        current.zip(required).find { case (a, b) => a != b } match {
          case Some((a, b)) => a > b
          case None         => true
        }
      case _                               =>
        PopCanLog.warn("provider-catalog", s"invalid version comparison extension=$extensionVersion minimum=$minimum")
        false
    }
}

object ProviderPackInstallPolicy {
  def validate(
      currentVersion: Int,
      currentSha256: String,
      candidateVersion: Int,
      candidateSha256: String
  ): Either[String, Unit] =
    if (candidateVersion < currentVersion)
      Left(s"Provider pack rollback rejected: v$candidateVersion < v$currentVersion")
    else if (candidateVersion == currentVersion && currentSha256 != candidateSha256)
      Left("Replacement with different content at the same pack version is rejected")
    else Right(())
}

object ProviderPackBootstrapPolicy {
  def validSha256(value: String): Boolean =
    Option(value).exists(_.trim.matches("^[0-9a-f]{64}$"))

  def marker(extensionVersion: String, bootstrapSha256: String): String =
    s"${extensionVersion.trim}:${bootstrapSha256.trim.toLowerCase}"

  def alreadySeen(seen: String, extensionVersion: String, bootstrapSha256: String): Boolean =
    validSha256(bootstrapSha256) && seen.trim.equalsIgnoreCase(marker(extensionVersion, bootstrapSha256))
}
