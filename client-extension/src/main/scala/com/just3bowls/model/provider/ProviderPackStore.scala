package com.just3bowls.model.provider

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.facade.JsBridge

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

object ProviderPackStore {
  private val EnvelopeKey      = "thProviderPack:envelope"
  private val PayloadKey       = "thProviderPack:payload"
  private val MetaKey          = "thProviderPack:meta"
  private val SettingsKey      = "thProviderPack:settings"
  private val BootstrapSeenKey = "thProviderPack:bootstrapSeen"

  def readEnvelope(): Future[Option[String]] =
    ChromeAPI.storage.local
      .get(js.Array(EnvelopeKey))
      .toFuture
      .map(values => values.get(EnvelopeKey).flatMap(value => Option(value).map(_.toString)).filter(_.nonEmpty))
      .recover { case _ => None }

  def readPayload(): Future[Option[String]] =
    ChromeAPI.storage.local
      .get(js.Array(PayloadKey))
      .toFuture
      .map(values => values.get(PayloadKey).flatMap(value => Option(value).map(_.toString)).filter(_.nonEmpty))
      .recover { case _ => None }

  def readMeta(): Future[ProviderPackMeta] =
    ChromeAPI.storage.local
      .get(js.Array(MetaKey))
      .toFuture
      .map(values =>
        values.get(MetaKey) match {
          case Some(meta) =>
            ProviderPackMeta(
              bundleVersion = JsBridge.intField(meta, "bundleVersion").getOrElse(0),
              providerCount = JsBridge.intField(meta, "providerCount").getOrElse(0),
              source = JsBridge.stringField(meta, "source").getOrElse(""),
              installedAt = JsBridge.doubleField(meta, "installedAt").getOrElse(0.0),
              sha256 = JsBridge.stringField(meta, "sha256").getOrElse(""),
              keyId = JsBridge.stringField(meta, "keyId").getOrElse("")
            )
          case None       => ProviderPackMeta()
        }
      )
      .recover { case _ => ProviderPackMeta() }

  def readSettings(): Future[ProviderPackSettings] =
    ChromeAPI.storage.local
      .get(js.Array(SettingsKey))
      .toFuture
      .map(values =>
        values.get(SettingsKey) match {
          case Some(settings) =>
            ProviderPackSettings(
              autoUpdate = JsBridge.booleanField(settings, "autoUpdate").getOrElse(false),
              lastCheckedAt = JsBridge.doubleField(settings, "lastCheckedAt").getOrElse(0.0),
              lastError = JsBridge.stringField(settings, "lastError").getOrElse("")
            )
          case None           => ProviderPackSettings()
        }
      )
      .recover { case _ => ProviderPackSettings() }

  def writeSettings(settings: ProviderPackSettings): Future[Unit] =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](
        SettingsKey -> JsBridge.obj(
          "autoUpdate"    -> (settings.autoUpdate),
          "lastCheckedAt" -> (settings.lastCheckedAt),
          "lastError"     -> (settings.lastError)
        )
      ))
      .toFuture
      .map(_ => ())

  def readBootstrapMarker(): Future[String] =
    ChromeAPI.storage.local
      .get(js.Array(BootstrapSeenKey))
      .toFuture
      .map(values => values.get(BootstrapSeenKey).flatMap(value => Option(value).map(_.toString)).getOrElse("").trim)
      .recover { case _ => "" }

  def writeBootstrapMarker(marker: String): Future[Unit] =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](BootstrapSeenKey -> marker.trim))
      .toFuture
      .map(_ => ())

  def saveInstalled(
      envelopeText: String,
      payloadText: String,
      bundleVersion: Int,
      providerCount: Int,
      source: String,
      sha256: String,
      keyId: String,
      enableAutoUpdate: Boolean
  ): Future[Unit] = {
    val now  = js.Date.now()
    val meta = JsBridge.obj(
      "bundleVersion" -> (bundleVersion),
      "providerCount" -> (providerCount),
      "source"        -> (source),
      "installedAt"   -> (now),
      "sha256"        -> (sha256),
      "keyId"         -> (keyId)
    )
    for {
      settings <- readSettings()
      _        <- ChromeAPI.storage.local
        .set(js.Dictionary[js.Any](
          EnvelopeKey -> envelopeText,
          PayloadKey  -> payloadText,
          MetaKey     -> meta,
          SettingsKey -> JsBridge.obj(
            "autoUpdate"    -> (settings.autoUpdate || enableAutoUpdate),
            "lastCheckedAt" -> (settings.lastCheckedAt),
            "lastError"     -> ("")
          )
        ))
        .toFuture
    } yield ()
  }

  def activeBundle(): Future[Option[ProviderPresetBundle]] =
    readPayload().map(_.flatMap(ProviderPresets.parsePackPayload(_).toOption))

  /** Follow-up storage write after provider documents settle, so open panels reload after apply finishes. */
  def markApplied(): Future[Unit] =
    readMeta().flatMap { meta =>
      val value = JsBridge.obj(
        "bundleVersion" -> (meta.bundleVersion),
        "providerCount" -> (meta.providerCount),
        "source"        -> (meta.source),
        "installedAt"   -> (meta.installedAt),
        "appliedAt"     -> (js.Date.now()),
        "sha256"        -> (meta.sha256),
        "keyId"         -> (meta.keyId)
      )
      ChromeAPI.storage.local
        .set(js.Dictionary[js.Any](MetaKey -> value))
        .toFuture
        .map(_ => ())
    }

  def status(
      busy: Boolean = false,
      updateAvailableVersion: Int = 0,
      error: String = ""
  ): Future[ProviderPackStatus] =
    for {
      meta     <- readMeta()
      payload  <- readPayload()
      settings <- readSettings()
    } yield ProviderPackStatus(
      installed = meta.bundleVersion > 0 && meta.providerCount > 0 &&
        payload.exists(text => ProviderPresets.parsePackPayload(text).isRight),
      bundleVersion = meta.bundleVersion,
      providerCount = meta.providerCount,
      source = meta.source,
      installedAt = meta.installedAt,
      autoUpdate = settings.autoUpdate,
      lastCheckedAt = settings.lastCheckedAt,
      updateAvailableVersion = updateAvailableVersion,
      busy = busy,
      error = if (error.nonEmpty) error else settings.lastError
    )
}
