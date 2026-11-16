package com.just3bowls.background.provider

import com.just3bowls.background.runtime.ProviderContentRefresh
import com.just3bowls.facade.{ChromeAPI, JsBridge, WebPlatformGlobal}
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.provider.*

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

private[background] object ProviderPackManager {
  private var busy                   = false
  private var updateAvailableVersion = 0
  private var lastError              = ""
  private val BootstrapPackPath      = "bootstrap/provider-pack.popcan.json"
  private val BootstrapShaPath       = "bootstrap/provider-pack.sha256"

  def status(): Future[ProviderPackStatus] =
    ProviderPackStore.status(busy = busy, updateAvailableVersion = updateAvailableVersion, error = lastError)

  def check(manual: Boolean = true): Future[ProviderPackStatus] =
    serialized {
      val permission =
        if (manual) ProviderPackConfig.containsPermission()
        else Future.successful(true)
      permission.flatMap { granted =>
        if (!granted) Future.failed(new RuntimeException("Provider update permission is not granted"))
        else
          fetchIndexAndVerify().flatMap { index =>
            ProviderPackStore.readMeta().flatMap { meta =>
              updateAvailableVersion =
                if (index.bundleVersion > meta.bundleVersion) index.bundleVersion else 0
              ProviderPackStore.readSettings().flatMap { settings =>
                ProviderPackStore
                  .writeSettings(settings.copy(lastCheckedAt = js.Date.now(), lastError = ""))
                  .map(_ => ())
              }
            }
          }
      }.map(_ => ())
    }.flatMap(_ => status())

  def installOnline(): Future[ProviderPackStatus] =
    serialized {
      ProviderPackConfig.containsPermission().flatMap { granted =>
        if (!granted) Future.failed(new RuntimeException("Provider download permission is not granted"))
        else
          fetchIndexAndVerify().flatMap { index =>
            downloadPack(index).flatMap { verified =>
              installVerified(verified, index, source = "download", enableAutoUpdate = true)
            }
          }
      }.map(_ => ())
    }.flatMap(_ => status())

  def importText(text: String): Future[ProviderPackStatus] =
    installText(text, source = "import", enableAutoUpdate = false)

  private def installText(
      text: String,
      source: String,
      enableAutoUpdate: Boolean
  ): Future[ProviderPackStatus] =
    serialized {
      ProviderPackCrypto.verifyEnvelope(text, ProviderPackConfig.publicKey).flatMap { verified =>
        verifyPackPayload(verified).flatMap { case (bundleVersion, _) =>
          ProviderPackCrypto.sha256Hex(ProviderPackCrypto.utf8Bytes(verified.payloadText)).flatMap { sha =>
            val index = ProviderPackIndex(
              bundleVersion = bundleVersion,
              minExtensionVersion = JsBridge.stringField(verified.payload, "minExtensionVersion").getOrElse(""),
              generatedAt = JsBridge.stringField(verified.payload, "generatedAt").getOrElse(""),
              sha256 = sha,
              packUrl = "",
              providers = Nil
            )
            installVerified(verified, index, source = source, enableAutoUpdate = enableAutoUpdate)
          }
        }.map(_ => ())
      }
    }.flatMap(_ => status())

  def setAutoUpdate(enabled: Boolean): Future[ProviderPackStatus] =
    ProviderPackStore.readSettings().flatMap { settings =>
      ProviderPackStore.writeSettings(settings.copy(autoUpdate = enabled, lastError = "")).map(_ => ())
    }.flatMap(_ => status())

  def startup(): Future[Unit] =
    applyBootstrap().recover { case e =>
      PopCanLog.warn("presets", s"provider pack bootstrap failed: ${e.getMessage}")
      ()
    }.flatMap { _ =>
      ProviderPackStore.readSettings().flatMap { settings =>
        if (settings.autoUpdate && js.Date.now() - settings.lastCheckedAt >= 24L * 60L * 60L * 1000L)
          ProviderPackConfig.containsPermission().flatMap {
            case true  =>
              check(manual = false).flatMap { status =>
                if (status.updateAvailableVersion > 0) installOnline().map(_ => ())
                else Future.successful(())
              }
            case false => Future.successful(())
          }
        else Future.successful(())
      }
    }.recover { case e =>
      PopCanLog.warn("presets", s"provider pack startup check failed: ${e.getMessage}")
      ()
    }

  private def applyBootstrap(): Future[Unit] =
    fetchLocalText(BootstrapShaPath).flatMap {
      case None             => Future.successful(())
      case Some(sidecarRaw) =>
        val bootstrapSha = sidecarRaw.trim.toLowerCase
        if (!ProviderPackBootstrapPolicy.validSha256(bootstrapSha)) {
          PopCanLog.warn("presets", "provider pack bootstrap SHA sidecar is invalid")
          Future.successful(())
        } else
          ProviderPackStore.readBootstrapMarker().flatMap { seen =>
            if (
              ProviderPackBootstrapPolicy.alreadySeen(
                seen,
                ProviderPackConfig.extensionVersion,
                bootstrapSha
              )
            ) Future.successful(())
            else
              fetchLocalText(BootstrapPackPath).flatMap {
                case None           =>
                  PopCanLog.warn("presets", "provider pack bootstrap file is missing")
                  Future.successful(())
                case Some(packText) =>
                  ProviderPackCrypto.sha256Hex(ProviderPackCrypto.utf8Bytes(packText)).flatMap { actualSha =>
                    if (actualSha != bootstrapSha)
                      Future.failed(new RuntimeException("Provider Pack bootstrap SHA256 mismatch"))
                    else
                      inspectBootstrap(packText)
                        .flatMap { case (candidateVersion, candidateSha) =>
                          ProviderPackStore.readMeta().flatMap { current =>
                            ProviderPackInstallPolicy.validate(
                              current.bundleVersion,
                              current.sha256,
                              candidateVersion,
                              candidateSha
                            ) match {
                              case Left(reason) =>
                                PopCanLog.warn("presets", s"provider pack bootstrap ignored: $reason")
                                Future.successful(true)
                              case Right(_)     =>
                                installText(packText, source = "setup", enableAutoUpdate = false)
                                  .map(_ => true)
                                  .recover { case e =>
                                    PopCanLog.warn("presets", s"provider pack bootstrap failed: ${e.getMessage}")
                                    false
                                  }
                            }
                          }
                        }
                        .recover { case e =>
                          PopCanLog.warn("presets", s"provider pack bootstrap rejected: ${e.getMessage}")
                          true
                        }
                        .flatMap {
                          case true  =>
                            ProviderPackStore.writeBootstrapMarker(
                              ProviderPackBootstrapPolicy.marker(
                                ProviderPackConfig.extensionVersion,
                                bootstrapSha
                              )
                            )
                          case false => Future.successful(())
                        }
                  }
              }
          }
    }

  private def inspectBootstrap(text: String): Future[(Int, String)] =
    ProviderPackCrypto.verifyEnvelope(text, ProviderPackConfig.publicKey).flatMap { verified =>
      verifyPackPayload(verified).flatMap { case (version, _) =>
        ProviderPackCrypto.sha256Hex(ProviderPackCrypto.utf8Bytes(verified.payloadText)).map(version -> _)
      }
    }

  private def fetchLocalText(path: String): Future[Option[String]] =
    WebPlatformGlobal
      .fetch(ChromeAPI.runtime.getURL(path))
      .toFuture
      .flatMap { response =>
        if (!response.ok) Future.successful(None)
        else
          response.text().toFuture.flatMap { text =>
            if (ProviderPackCrypto.utf8Bytes(text).length > ProviderPackConfig.MaxDocumentBytes)
              Future.failed(new RuntimeException("Provider bootstrap document exceeds the 2 MB limit"))
            else Future.successful(Some(text))
          }
      }
      .recover { case _ => None }

  private def serialized(body: => Future[Unit]): Future[Unit] =
    if (busy) Future.failed(new RuntimeException("Provider pack operation is already running"))
    else {
      busy = true
      lastError = ""
      val started =
        try body
        catch { case e: Throwable => Future.failed(e) }
      started
        .recoverWith { case e =>
          lastError = Option(e.getMessage).getOrElse(e.toString)
          ProviderPackStore
            .readSettings()
            .flatMap(settings => ProviderPackStore.writeSettings(settings.copy(lastError = lastError)))
            .recover { case _ => () }
            .flatMap(_ => Future.failed(e))
        }
        .andThen { case _ => busy = false }
    }

  private def fetchText(url: String): Future[String] =
    WebPlatformGlobal
      .fetch(url)
      .toFuture
      .flatMap { response =>
        if (!response.ok) Future.failed(new RuntimeException(s"Provider download failed: HTTP ${response.status}"))
        else
          response.text().toFuture.flatMap { text =>
            if (ProviderPackCrypto.utf8Bytes(text).length > ProviderPackConfig.MaxDocumentBytes)
              Future.failed(new RuntimeException("Provider document exceeds the 2 MB limit"))
            else Future.successful(text)
          }
      }

  private def fetchIndexAndVerify(): Future[ProviderPackIndex] =
    fetchText(ProviderPackConfig.IndexUrl)
      .flatMap(text => ProviderPackCrypto.verifyEnvelope(text, ProviderPackConfig.publicKey))
      .flatMap { verified =>
        ProviderPackIndex.parse(verified.payload) match {
          case Left(error)  => Future.failed(new RuntimeException(error))
          case Right(index)
              if !ProviderPackVersions.satisfies(
                ProviderPackConfig.extensionVersion,
                index.minExtensionVersion
              ) =>
            Future.failed(new RuntimeException(
              s"Provider pack v${index.bundleVersion} requires extension ${index.minExtensionVersion}"
            ))
          case Right(index) => Future.successful(index)
        }
      }

  private def downloadPack(index: ProviderPackIndex): Future[VerifiedProviderEnvelope] =
    fetchText(ProviderPackConfig.PackBaseUrl + index.packUrl)
      .flatMap(text => ProviderPackCrypto.verifyEnvelope(text, ProviderPackConfig.publicKey))
      .flatMap { verified =>
        ProviderPackCrypto.sha256Hex(ProviderPackCrypto.utf8Bytes(verified.payloadText)).flatMap { sha =>
          if (sha != index.sha256) Future.failed(new RuntimeException("Provider pack SHA256 mismatch"))
          else
            verifyPackPayload(verified).flatMap {
              case (version, _) if version != index.bundleVersion =>
                Future.failed(new RuntimeException("Provider index and pack versions do not match"))
              case _                                              => Future.successful(verified)
            }
        }
      }

  private def verifyPackPayload(verified: VerifiedProviderEnvelope): Future[(Int, Int)] =
    ProviderPresets.parsePackPayload(verified.payloadText) match {
      case Left(error)   => Future.failed(new RuntimeException(error))
      case Right(bundle)
          if !ProviderPackVersions.satisfies(
            ProviderPackConfig.extensionVersion,
            JsBridge.stringField(verified.payload, "minExtensionVersion").getOrElse("")
          ) =>
        Future.failed(new RuntimeException(
          s"Provider pack v${bundle.version} is not compatible with extension ${ProviderPackConfig.extensionVersion}"
        ))
      case Right(bundle) => Future.successful(bundle.version -> bundle.providers.size)
    }

  private def installVerified(
      verified: VerifiedProviderEnvelope,
      index: ProviderPackIndex,
      source: String,
      enableAutoUpdate: Boolean
  ): Future[Unit] =
    for {
      currentMeta <- ProviderPackStore.readMeta()
      _           <- ProviderPackInstallPolicy.validate(
        currentMeta.bundleVersion,
        currentMeta.sha256,
        index.bundleVersion,
        index.sha256
      ) match {
        case Left(error) => Future.failed(new RuntimeException(error))
        case Right(_)    => Future.successful(())
      }
      bundle      <- ProviderPresets.parsePackPayload(verified.payloadText) match {
        case Left(error)  => Future.failed(new RuntimeException(error))
        case Right(value) => Future.successful(value)
      }
      _           <-
        if (currentMeta.bundleVersion == bundle.version && currentMeta.sha256 == index.sha256)
          Future.successful(())
        else
          for {
            _ <- ProviderPackStore.saveInstalled(
              envelopeText = verified.envelopeText,
              payloadText = verified.payloadText,
              bundleVersion = bundle.version,
              providerCount = bundle.providers.size,
              source = source,
              sha256 = index.sha256,
              keyId = verified.keyId,
              enableAutoUpdate = enableAutoUpdate
            )
            _ <- ProviderPresets.hydrate()
            _ <- ProviderPresets.ensureApplied()
            _ <- ProviderContentRefresh.run(s"provider pack v${bundle.version} installed")
            _ <- ProviderPackStore.markApplied()
          } yield ()
      _ = updateAvailableVersion = 0
    } yield ()
}
