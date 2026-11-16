package com.just3bowls.background.messaging

import com.just3bowls.background.provider.ProviderPackManager
import com.just3bowls.facade.*

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

private[background] object ProviderPackMessageHandler {
  def handles(messageType: String): Boolean = messageType == "PROVIDER_PACK"

  def handle(message: js.Any): js.Promise[js.Any] = {
    val request                = message.asInstanceOf[ProviderPackMessage]
    val action                 = request.action.getOrElse("")
    val result: Future[js.Any] =
      action match {
        case "status"   => ProviderPackManager.status().map(statusOk)
        case "check"    => ProviderPackManager.check().map(statusOk)
        case "install"  => ProviderPackManager.installOnline().map(statusOk)
        case "import"   => ProviderPackManager.importText(request.text.getOrElse("")).map(statusOk)
        case "settings" =>
          ProviderPackManager.setAutoUpdate(request.autoUpdate.getOrElse(false)).map(statusOk)
        case _          => Future.successful(error(s"Unknown provider pack action: $action"))
      }
    result
      .recover { case e => error(Option(e.getMessage).getOrElse("Provider pack operation failed")) }
      .toJSPromise
  }

  private def statusOk(status: com.just3bowls.model.provider.ProviderPackStatus): js.Any =
    JsBridge.obj("ok" -> (true), "status" -> (status.toJs))
  private def error(message: String): js.Any                                             =
    JsBridge.obj("ok" -> (false), "error" -> (message))
}
