package com.just3bowls.api.provider

/**
 * "同一个页面 / 同一条请求" 的 URL 归一化（JVM + Scala.js 通用，`server/test` 有金值单测）。
 *
 * 与 [[ConversationUrl]] 的分工：
 *
 *   - `ConversationUrl.isExtensionOf` 判**一段对话**（同一次聊天，URL 只会变长）；
 *   - `PageIdentity` 判**同一家 provider 页面 / 同一条传输请求**（闲聊上下文无关，只看"去掉 query/hash 后的路径"）。
 *
 * 以前这两类判定各有 2–3 份私有实现（`ManagedTabManager.urlMatches`、`WebChatService.urlMatches`、
 * `CdpFlowExecutor.base`），规则漂移过；现在统一到这里。
 */
object PageIdentity {

  /** 去掉 query / hash 与末尾斜杠 —— 只用于"同一请求 / 同一页面"的宽松比较。 */
  def base(url: String): String =
    Option(url).getOrElse("").trim.split('#').head.split('?').head.stripSuffix("/")

  /**
   * 配置里的 provider 页 URL 与当前 tab 的 URL 是否算同一家。
   *
   * 保守匹配：去掉 query/hash 后路径**互为前缀**即算（doubao 的 `/chat` 与 `/chat/<id>` 都算同一家）。
   */
  def sameProviderPage(configured: String, current: String): Boolean = {
    val c = base(configured)
    val t = base(current)
    c.nonEmpty && t.nonEmpty && (t == c || t.startsWith(c + "/") || c.startsWith(t + "/"))
  }
}
