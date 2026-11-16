package com.just3bowls.model.localapi

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * One page-lifetime native-messaging transport shared by Memory and the HTTP worker.
 *
 * Chrome owns the host process through this port. Keeping connection setup here gives every caller one single-flight
 * connection attempt and one cooldown after a fatal runtime error, instead of letting independent Memory polls spawn
 * competing native hosts.
 */
final class NativeRuntimeConnection(
    host: String,
    onMessage: js.Any => Unit,
    onDisconnect: (Double, String) => Unit
) {
  private var port: js.UndefOr[js.Any]          = js.undefined
  private var connecting: Option[Promise[Unit]] = None
  private var connectedAt                       = 0.0
  private var blockedUntil                      = 0.0
  private var blockedReason                     = ""
  def isConnected: Boolean                      = port.isDefined
  def ensureConnected(): Future[Unit]           =
    port.toOption match {
      case Some(_) => Future.successful(())
      case None    =>
        val now = js.Date.now()
        if (now < blockedUntil)
          Future.failed(new RuntimeException(
            if (blockedReason.nonEmpty) blockedReason
            else s"Local runtime reconnect is cooling down for ${Math.ceil((blockedUntil - now) / 1000.0).toInt}s"
          ))
        else
          connecting match {
            case Some(pending) => pending.future
            case None          =>
              val pending = Promise[Unit]()
              connecting = Some(pending)
              try {
                val next = JsBridge.asObject(ChromeAPI.runtime.connectNative(host))
                port = next
                connectedAt = js.Date.now()
                next.onMessage.addListener(((message: js.Any) => onMessage(message)): js.Function1[js.Any, Unit])
                next.onDisconnect.addListener(((_: js.Any) => handleDisconnect(next)): js.Function1[js.Any, Unit])
                connecting = None
                pending.success(())
              } catch {
                case error: Throwable =>
                  connecting = None
                  port = js.undefined
                  pending.failure(error)
              }
              pending.future
          }
    }
  def post(message: js.Object): Future[Unit]    =
    ensureConnected().flatMap { _ =>
      port.toOption match {
        case Some(current) =>
          try {
            JsBridge.call(current, "postMessage", message)
            Future.successful(())
          } catch {
            case error: Throwable => Future.failed(error)
          }
        case None          => Future.failed(new RuntimeException("Local runtime disconnected before postMessage"))
      }
    }

  /** Fire-and-forget path for server responses, which are already owned by task cleanup. */
  def postIfConnected(message: js.Object): Unit                 =
    port.foreach { current =>
      try JsBridge.call(current, "postMessage", message)
      catch {
        case error: Throwable =>
          PopCanLog.warn("server", s"native postMessage failed: ${Option(error.getMessage).getOrElse(error.toString)}")
      }
    }
  def blockReconnect(millis: Double, reason: String = ""): Unit = {
    blockedUntil = js.Math.max(blockedUntil, js.Date.now() + millis)
    blockedReason = reason
  }
  def clearReconnectBlock(): Unit                               = {
    blockedUntil = 0.0
    blockedReason = ""
  }

  /** Explicit page-unload/Stop teardown. The disconnect callback intentionally does not fire for this path. */
  def disconnect(): Unit                              = {
    val current = port
    port = js.undefined
    current.foreach { value =>
      try JsBridge.call(value, "disconnect")
      catch { case _: Throwable => () }
    }
  }
  private def handleDisconnect(current: js.Any): Unit =
    if (port.toOption.contains(current)) {
      val livedMs = js.Date.now() - connectedAt
      val reason  =
        ChromeAPI.runtime.lastError.toOption
          .flatMap(value => JsBridge.stringField(value, "message"))
          .filter(_.nonEmpty)
          .getOrElse("")
      port = js.undefined
      onDisconnect(livedMs, reason)
    }
}
