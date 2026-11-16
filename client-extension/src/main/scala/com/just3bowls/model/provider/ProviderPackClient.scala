package com.just3bowls.model.provider

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.facade.JsBridge

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

object ProviderPackClient {
  private def request(action: String, extra: (String, js.Any)*): Future[js.Any] =
    ChromeAPI.runtime
      .sendMessage(JsBridge.obj((List(
        "type"   -> (JsBridge.asAny("PROVIDER_PACK")),
        "action" -> (JsBridge.asAny(action))
      ) ++ extra)*))
      .toFuture

  private def statusResponse(action: String, extra: (String, js.Any)*): Future[ProviderPackStatus] =
    request(action, extra*).flatMap { response =>
      if (JsBridge.booleanField(response, "ok").getOrElse(false))
        Future.successful(ProviderPackStatus.fromJs(JsBridge.field(response, "status")))
      else
        Future.failed(new RuntimeException(
          JsBridge.stringField(response, "error").getOrElse(s"Provider pack action failed: $action")
        ))
    }

  def status(): Future[ProviderPackStatus]                        = statusResponse("status")
  def check(): Future[ProviderPackStatus]                         = statusResponse("check")
  def installOnline(): Future[ProviderPackStatus]                 = statusResponse("install")
  def importText(text: String): Future[ProviderPackStatus]        = statusResponse("import", "text" -> (text))
  def setAutoUpdate(enabled: Boolean): Future[ProviderPackStatus] =
    statusResponse("settings", "autoUpdate" -> (enabled))
}
