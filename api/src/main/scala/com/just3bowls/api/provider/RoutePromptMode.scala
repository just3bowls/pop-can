package com.just3bowls.api.provider

/**
 * server 转发里**每条路由（key × provider）**各自的 prompt 投递模式（纯逻辑，`server/test` 有金值）。
 *
 * 为什么按路由而不是按 key：`inline`（body 进模版）和 `file`（body 作为 `.txt` 附件）是**这家页面**能不能吃得下的问题 —— 同一个 key 的候选里 kimi 要 inline、gml 可能要
 * file 是完全正常的。
 */
object RoutePromptMode {

  val Inline = "inline"
  val File   = "file"

  /** 只认 `file`（大小写/空白无关），其余一律 `inline`。 */
  def normalize(raw: String): String =
    if (Option(raw).map(_.trim.toLowerCase).contains(File)) File else Inline

  /** 派发 / failover 时按 provider 查它那条路由的 mode；查不到（候选已失效等）按 `inline`。 */
  def modeFor(entries: List[(String, String)], provider: String): String =
    entries.find(_._1 == provider).map(_._2).map(normalize).getOrElse(Inline)
}
