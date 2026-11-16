package com.just3bowls.model.provider

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.facade.JsBridge

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

object ProviderPackConfig {
  val OriginPattern: String = "https://just3bowls.github.io/*"
  val IndexUrl: String      =
    "https://just3bowls.github.io/pop-can/provider-packs/provider-index.popcan.json"
  val PackBaseUrl: String   =
    "https://just3bowls.github.io/pop-can/provider-packs/"
  val MaxDocumentBytes: Int = 2 * 1024 * 1024

  def publicKey: String        = JsBridge.providerPackPublicKey
  def extensionVersion: String = ChromeAPI.runtime.getManifest().version

  def containsPermission(): Future[Boolean] =
    ChromeAPI.permissions
      .contains(js.Dictionary("origins" -> js.Array(OriginPattern)))
      .toFuture
      .recover { case _ => false }

  /** Must be invoked directly from a UI click so the permissions request keeps its user gesture. */
  def requestPermission(): Future[Boolean] =
    ChromeAPI.permissions
      .request(js.Dictionary("origins" -> js.Array(OriginPattern)))
      .toFuture
}
