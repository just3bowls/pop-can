package com.just3bowls.background.webchat

import com.just3bowls.api.webchat.WebChatErrorPresentation
import com.just3bowls.facade.JsBridge

import scala.scalajs.js

/** Stable WEBCHAT_STATE payload for the in-page capsule. */
private[background] object WebChatStateView {
  def encode(
      tabId: Int,
      runtime: Option[Runtime],
      sessionTitles: collection.Map[String, String],
      cdpError: Option[String],
      browserTools: Boolean,
      activeRun: Option[NativeRun],
      canTakeover: Boolean
  ): js.Object = {
    val assoc              = runtime.flatMap(_.assoc)
    val supported          = runtime.exists(_.config.isDefined)
    val title              = assoc.map(host => sessionTitles.getOrElse(host.sessionId, "")).getOrElse("")
    val provider           = assoc
      .map(_.provider)
      .orElse(runtime.flatMap(_.config).map(_.name))
      .getOrElse("")
    val runtimeDetail      = runtime.map(_.lastErrorDetail).filter(_.nonEmpty)
    val errorDetail        = runtimeDetail.orElse(cdpError.filter(_.nonEmpty)).getOrElse("")
    val errorId            =
      runtime.map(_.lastErrorId).filter(_.nonEmpty)
        .orElse(cdpError.map(text => s"cdp:$tabId:${text.hashCode}"))
        .getOrElse("")
    val errorSummary       =
      if (errorDetail.isEmpty) "" else WebChatErrorPresentation.summary(provider, errorDetail)
    JsBridge.obj(
      "supported"      -> (supported),
      "capsuleEnabled" -> (runtime.exists(_.capsuleEnabled)),
      "associated"     -> (assoc.isDefined),
      "sessionId"      -> (assoc.map(_.sessionId).getOrElse("")),
      "title"          -> (title),
      "provider"       -> (provider),
      "behind"         -> (runtime.exists(_.hijacking)),
      "canTakeover"    -> (canTakeover),
      "browserTools"   -> (browserTools),
      "agentRunning"   -> (activeRun.isDefined),
      "agentRunId"     -> (activeRun.map(_.runId).getOrElse("")),
      "error"          -> (errorSummary),
      "errorId"        -> (errorId),
      "errorDetail"    -> (errorDetail),
      "errorAt"        -> (runtime.map(_.lastErrorAt).filter(_ > 0).getOrElse(0.0))
    )
  }
}
