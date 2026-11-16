package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.provider.ParserProviderLocal
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * Provider-page control readiness (`input` / `new-chat`) with bounded repair.
 *
 * Provider selectors and fingerprints stay in provider config. This object only coordinates: location-runtime ensure ->
 * ask the page to mark roles -> if stale, force a fresh runtime and retry once.
 */
private[background] object PageMarkers {
  private val DefaultTimeoutMs                                          = 6000
  private val ForceInjectAfter                                          = 2
  def ensure(
      tabId: Int,
      provider: String,
      roles: List[String] = List("input"),
      timeoutMs: Int = DefaultTimeoutMs
  ): Future[Unit] =
    if (tabId <= 0 || roles.isEmpty) Future.successful(())
    else
      ParserProviderLocal.getProvider(provider).flatMap {
        case None         =>
          Future.failed(new Exception(s"Provider config is missing for marker recovery: $provider"))
        case Some(config) =>
          val requested                         = roles.distinct
          val deadline                          = js.Date.now() + timeoutMs
          def attempt(index: Int): Future[Unit] = {
            val forceInjection = index >= ForceInjectAfter
            val forceMarkers   = index > 0
            ContentInjection
              .injectLocation(tabId, force = forceInjection)
              .flatMap(_ => sendEnsure(tabId, config, requested, forceMarkers))
              .flatMap { result =>
                if (result._1) Future.successful(())
                else if (js.Date.now() >= deadline) {
                  val missing = result._2.mkString(",")
                  PopCanLog.warn(
                    "location",
                    s"marker recovery exhausted tab=$tabId provider=$provider roles=$missing"
                  )
                  Future.failed(new Exception(
                    s"Required markers not ready for tab $tabId provider=$provider roles=$missing"
                  ))
                } else {
                  PopCanLog.warn(
                    "location",
                    s"marker recovery retry tab=$tabId provider=$provider missing=${result._2.mkString(",")} " +
                      s"attempt=${index + 1}"
                  )
                  delay(if (index == 0) 250 else 500).flatMap(_ => attempt(index + 1))
                }
              }
          }
          attempt(0)
      }
  private def sendEnsure(
      tabId: Int,
      config: ParserProviderLocal,
      roles: List[String],
      force: Boolean
  ): Future[(Boolean, List[String])] =
    ChromeAPI.tabs
      .sendMessage(
        tabId,
        JsBridge.obj(
          "type"    -> ("LOCATION_ENSURE"),
          "roles"   -> (js.Array(roles*)),
          "force"   -> (force),
          "targets" -> (js.Array(roles.map(role => target(config, role))*))
        )
      )
      .toFuture
      .map { raw =>
        val response = JsBridge.asObject(raw)
        val ok       = JsBridge.undefinedBoolean(response.ok).getOrElse(false)
        val missing  = JsBridge
          .undefinedStringArray(response.missing)
          .toOption
          .map(_.toList)
          .getOrElse(if (ok) Nil else roles)
        (ok, missing)
      }
      .recover { case _ => (false, roles) }
  private def target(config: ParserProviderLocal, role: String): js.Any = {
    val selector    = role match {
      case "input"    => config.inputSelector
      case "new-chat" => config.newChatSelector
      case _          => ""
    }
    val fingerprint = config.fingerprints
      .get(role)
      .map(ParserProviderLocal.fingerprintToDynamic)
      .getOrElse(js.undefined)
    JsBridge.obj("role" -> (role), "selector" -> (selector), "fingerprint" -> (fingerprint))
  }
  private def delay(ms: Int): Future[Unit]                              = {
    val promise = Promise[Unit]()
    js.timers.setTimeout(ms)(promise.success(()))
    promise.future
  }
}
