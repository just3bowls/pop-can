package com.just3bowls.model.diagnostics

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.{TraceDto, TraceHandlerStatDto, TraceMarkersDto}

import scala.scalajs.js

object TraceDtoFactory {
  def handlerStat(id: String, hits: Int): TraceHandlerStatDto =
    JsBridge.obj("id" -> (id), "hits" -> (hits)).asInstanceOf[TraceHandlerStatDto]

  def markers(
      contentChars: Int,
      thinkingChars: Int,
      toolCalls: Int,
      valid: Boolean,
      error: String
  ): TraceMarkersDto =
    JsBridge.obj(
      "contentChars"  -> (contentChars),
      "thinkingChars" -> (thinkingChars),
      "toolCalls"     -> (toolCalls),
      "valid"         -> (valid),
      "error"         -> (error)
    ).asInstanceOf[TraceMarkersDto]

  def trace(
      gen: String,
      at: Double,
      provider: String = "",
      matchedUrl: String = "",
      requestId: String = "",
      chunks: Int = 0,
      rawChars: Int = 0,
      normalizedChars: Int = 0,
      durationMs: Double = 0,
      firstRaw: String = "",
      lastRaw: String = "",
      parserHandlers: List[TraceHandlerStatDto] = Nil,
      markers: js.UndefOr[TraceMarkersDto] = js.undefined,
      timings: js.UndefOr[js.Dictionary[Double]] = js.undefined,
      error: String = ""
  ): TraceDto =
    JsBridge.obj(
      "gen"             -> (gen),
      "at"              -> (at),
      "provider"        -> (provider),
      "matchedUrl"      -> (matchedUrl),
      "requestId"       -> (requestId),
      "chunks"          -> (chunks),
      "rawChars"        -> (rawChars),
      "normalizedChars" -> (normalizedChars),
      "durationMs"      -> (durationMs),
      "firstRaw"        -> (firstRaw),
      "lastRaw"         -> (lastRaw),
      "parserHandlers"  -> (js.Array(parserHandlers*)),
      "markers"         -> (markers),
      "timings"         -> (timings),
      "error"           -> (error)
    ).asInstanceOf[TraceDto]

  def readAll(raw: Option[js.Any]): List[TraceDto] =
    raw match {
      case Some(value) if js.Array.isArray(value) =>
        JsBridge.asTypedArray[TraceDto](value).toList.filter(entry => entry != null && !js.isUndefined(entry))
      case _                                      => Nil
    }

  def writeAll(entries: List[TraceDto]): js.Array[js.Any] =
    js.Array(entries.map(JsBridge.asObject(_))*)
}
