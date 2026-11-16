package com.just3bowls.background.runtime

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.SimpleResultStorage

object BackgroundResponseFactory {
  def simpleResult(ok: Boolean = false, error: String = ""): SimpleResultStorage =
    JsBridge.obj("ok" -> (ok), "error" -> (error)).asInstanceOf[SimpleResultStorage]
}
