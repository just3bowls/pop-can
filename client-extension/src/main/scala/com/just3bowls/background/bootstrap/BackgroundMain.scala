package com.just3bowls.background.bootstrap

import com.just3bowls.background.messaging.RuntimeMessageRouter

import scala.collection.mutable
import scala.scalajs.js.annotation.JSExportTopLevel

private[background] object BackgroundMain {
  private val backgroundDisposers = mutable.ArrayBuffer.empty[() => Unit]

  @JSExportTopLevel(name = "backgroundMain", moduleID = "background")
  def backgroundMain(): Unit = {
    backgroundDisposers.foreach(_())
    backgroundDisposers.clear()
    BackgroundBootstrap.run()
    BackgroundEvents.install(fn => backgroundDisposers += fn)
    RuntimeMessageRouter.install(fn => backgroundDisposers += fn)
  }
}
