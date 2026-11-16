package com.just3bowls.facade
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * debug：负责命名空间开关与上色的日志库（npm: debug）。
 *
 * `enabled` 是惰性 getter，在任何 `enable()` 调用之前会返回 `undefined`，所以只能用 `js.UndefOr[Boolean]` 接住， 不能直接当 `Boolean` 拆箱。
 */
@js.native
trait DebugFn      extends js.Function1[js.Any, Unit] {
  def enabled: js.UndefOr[Boolean]       = js.native
  def extend(namespace: String): DebugFn = js.native
}
@js.native
@JSImport("debug", JSImport.Default)
object DebugModule extends js.Object                  {
  def apply(namespace: String): DebugFn = js.native
  def enable(namespaces: String): Unit  = js.native
  def disable(): Unit                   = js.native
}
