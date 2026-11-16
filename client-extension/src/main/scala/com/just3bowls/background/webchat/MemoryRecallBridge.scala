package com.just3bowls.background.webchat

import com.just3bowls.facade.JsBridge
import com.just3bowls.background.automation.provider.*

import com.just3bowls.api.memory.MemoryContext
import com.just3bowls.api.memory.MemoryQuery
import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import zio.json._
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** Background-side bridge to the plugin page, which owns the native Memory connection. */
private[background] object MemoryRecallBridge {
  val TimeoutMs: Int                                                                = 900
  def recall(query: MemoryQuery, timeoutMs: Int = TimeoutMs): Future[MemoryContext] = {
    val result = Promise[MemoryContext]()
    val timer  = js.timers.setTimeout(timeoutMs.toDouble) {
      result.trySuccess(MemoryContext.Empty.copy(degraded = true, reason = "native recall timeout"))
    }
    ServerTabManager
      .ensurePluginPageTab()
      .flatMap { pageTabId =>
        ChromeAPI.tabs
          .sendMessage(
            pageTabId,
            JsBridge.obj("type" -> ("MEMORY_RECALL_REQUEST"), "payload" -> (query.toJson))
          )
          .toFuture
      }
      .foreach { raw =>
        val response = JsBridge.asObject(raw)
        val ok       = JsBridge.undefinedBoolean(response.ok).getOrElse(false)
        val payload  = JsBridge.undefinedString(response.payload).getOrElse("")
        val error    = JsBridge.undefinedString(response.error).getOrElse("")
        if (ok)
          payload.fromJson[MemoryContext] match {
            case Right(context) => result.trySuccess(context)
            case Left(parse)    =>
              result.trySuccess(MemoryContext.Empty.copy(degraded = true, reason = s"invalid recall response: $parse"))
          }
        else result.trySuccess(MemoryContext.Empty.copy(degraded = true, reason = error))
      }
    result.future.andThen { case _ =>
      js.timers.clearTimeout(timer)
      ()
    }.recover { case error =>
      PopCanLog.warn("memory", s"native recall bridge failed: ${Option(error.getMessage).getOrElse(error.toString)}")
      MemoryContext.Empty.copy(degraded = true, reason = Option(error.getMessage).getOrElse("recall failed"))
    }
  }
}
