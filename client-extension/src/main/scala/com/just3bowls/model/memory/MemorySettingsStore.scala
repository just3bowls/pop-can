package com.just3bowls.model.memory

import com.just3bowls.api.memory.MemorySettings
import com.just3bowls.facade.{ChromeAPI, JsBridge}

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import zio.json.*

import scala.concurrent.Future
import scala.scalajs.js

/** Extension-owned Memory settings document. The runtime receives a synced copy, but never owns the UI value. */
object MemorySettingsStore {
  val Key = "thMemorySettings:v1"

  final private case class Document(
      schemaVersion: Int,
      settings: MemorySettings,
      updatedAt: Long
  )
  private object Document {
    given JsonCodec[Document] = DeriveJsonCodec.gen
  }

  def read(): Future[Option[MemorySettings]] =
    ChromeAPI.storage.local
      .get(js.Array(Key))
      .toFuture
      .map(values => values.get(Key).flatMap(parseDocument))
      .recover { case _ => None }

  def write(value: MemorySettings): Future[MemorySettings] = {
    val normalized = MemorySettings.normalize(value)
    val document   = Document(
      schemaVersion = 1,
      settings = normalized,
      updatedAt = js.Date.now().toLong
    )
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](Key -> JsBridge.jsonParse(document.toJson)))
      .toFuture
      .map(_ => normalized)
  }

  private[model] def parseDocument(raw: js.Any): Option[MemorySettings] =
    if (raw == null || js.isUndefined(raw)) None
    else
      try {
        val text = JsBridge.jsonStringify(raw)
        text.fromJson[Document].toOption
          .filter(_.schemaVersion == 1)
          .map(document => MemorySettings.normalize(document.settings))
      } catch {
        case _: Throwable => None
      }
}
