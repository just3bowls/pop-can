package com.just3bowls.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.annotation.JSGlobalScope
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

@js.native
trait SubtleCryptoFacade extends js.Object {
  def importKey(
      format: String,
      keyData: Uint8Array,
      algorithm: js.Object,
      extractable: Boolean,
      usages: js.Array[String]
  ): js.Promise[js.Object] = js.native
  def verify(
      algorithm: js.Object,
      key: js.Object,
      signature: Uint8Array,
      data: Uint8Array
  ): js.Promise[Boolean] = js.native
  def digest(algorithm: String, data: Uint8Array): js.Promise[ArrayBuffer] = js.native
}

@js.native
trait CryptoFacade extends js.Object {
  def subtle: SubtleCryptoFacade = js.native
}

@js.native
trait TextEncoderFacade extends js.Object {
  def encode(value: String): Uint8Array = js.native
}

@js.native
trait TextDecoderFacade extends js.Object {
  def decode(value: Uint8Array): String = js.native
}

@js.native
trait FetchResponseFacade extends js.Object {
  def ok: Boolean                            = js.native
  def status: Int                            = js.native
  def text(): js.Promise[String]             = js.native
  def arrayBuffer(): js.Promise[ArrayBuffer] = js.native
}

@js.native
@JSGlobal("crypto")
object WebCryptoGlobal extends CryptoFacade

@js.native
@JSGlobal("TextEncoder")
class TextEncoderGlobal extends TextEncoderFacade

@js.native
@JSGlobal("TextDecoder")
class TextDecoderGlobal extends TextDecoderFacade

@js.native
@JSGlobalScope
object WebPlatformGlobal extends js.Object {
  def fetch(input: String): js.Promise[FetchResponseFacade] = js.native
}
