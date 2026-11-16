package com.just3bowls.facade
import scala.scalajs.js
@js.native
trait SimpleResultStorage extends js.Object:
  def ok: js.UndefOr[Boolean]   = js.native
  def error: js.UndefOr[String] = js.native

@js.native
trait CdpFlowResult extends js.Object:
  def ok: js.UndefOr[Boolean]     = js.native
  def content: js.UndefOr[String] = js.native
  def error: js.UndefOr[String]   = js.native
