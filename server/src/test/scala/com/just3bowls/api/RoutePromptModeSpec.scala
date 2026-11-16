package com.just3bowls.api
import com.just3bowls.api.provider.*

import zio.test._

/**
 * 每条路由（key × provider）的 prompt mode 金值。
 *
 * 关键口径：`file` 只认这一个词（大小写/空白无关），其余都归 `inline`。
 */
object RoutePromptModeSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("RoutePromptMode")(
    test("normalize：file 认大小写与空白，其余一律 inline") {
      assertTrue(
        RoutePromptMode.normalize("file") == RoutePromptMode.File,
        RoutePromptMode.normalize("FILE") == RoutePromptMode.File,
        RoutePromptMode.normalize("  File ") == RoutePromptMode.File,
        RoutePromptMode.normalize("inline") == RoutePromptMode.Inline,
        RoutePromptMode.normalize("") == RoutePromptMode.Inline,
        RoutePromptMode.normalize("whatever") == RoutePromptMode.Inline
      )
    },
    test("modeFor：按 provider 查，查不到按 inline") {
      val entries = List("gml" -> "file", "kimi" -> "INLINE")
      assertTrue(
        RoutePromptMode.modeFor(entries, "gml") == RoutePromptMode.File,
        RoutePromptMode.modeFor(entries, "kimi") == RoutePromptMode.Inline,
        RoutePromptMode.modeFor(entries, "deepseek") == RoutePromptMode.Inline,
        RoutePromptMode.modeFor(Nil, "gml") == RoutePromptMode.Inline
      )
    }
  )
}
