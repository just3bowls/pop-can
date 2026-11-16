package com.just3bowls.api.chat
import com.just3bowls.api.provider.*

/**
 * `/provider` 那行参数"最终生效哪一组值"的纯逻辑（JVM + Scala.js 通用，`server/test` 有金值单测）。
 *
 * 优先级：**一次性覆盖 > 固定值（pin） > 会话推导默认**。
 *
 *   - 会话推导默认：这条会话在这个 provider 上**用过**（有绑定的原生对话 URL）→ 接着这段聊；**没用过** → 必须新开一段 —— 对方那段对话里还没有本会话的历史， 只发增量等于让它凭空接话。
 *     后台"没有水位线就投全量"本来就是同一口径，这里只是把它算出来给用户看、并让投递参数与显示同源。
 *   - 固定值：用户明确"这家以后都这样"，落 `thProviderPinnedSettings`，跨会话生效，直到 `unpin`。
 *   - 一次性覆盖：`@` 行里打的、只作用于该 provider 的下一次发送。
 *
 * 词汇有两套：用户看到的 `new|continue` / `inline|file`（本文件定稿），与线上 wire 值 `new|delta` /
 * `inline|file`（`utils/ProviderChatSettings`）。 两者只在一处相交：`wireThread`。
 */
object ChatSendPolicy {

  /** 一段 provider 原生对话的去向：`new` = 新开一段；`continue` = 接着现在这段聊。 */
  final case class Settings(
      thread: String = ProviderChatArgs.ThreadContinue,
      prompt: String = ProviderChatArgs.PromptInline
  ) {
    def normalized: Settings = Settings(
      thread = if (ProviderChatArgs.ThreadValues.contains(thread)) thread else ProviderChatArgs.ThreadContinue,
      prompt = if (ProviderChatArgs.PromptValues.contains(prompt)) prompt else ProviderChatArgs.PromptInline
    )
    def isDefault: Boolean   = normalized == Settings()
  }

  val Default: Settings = Settings()

  /**
   * 会话推导默认：用过 → `continue + inline`；没用过 → `new + inline`。
   *
   * `prompt` 默认恒为 `inline`：`file` 只在"要投全量"时才说得上话，用户要它就得显式打出来。
   */
  def baseline(usedInSession: Boolean): Settings =
    Settings(
      thread = if (usedInSession) ProviderChatArgs.ThreadContinue else ProviderChatArgs.ThreadNew,
      prompt = ProviderChatArgs.PromptInline
    )

  /** Undo makes the next Chat send start a fresh provider conversation, then baseline logic resumes. */
  def afterUndo(usedInSession: Boolean): Settings =
    baseline(usedInSession).copy(thread = ProviderChatArgs.ThreadNew)

  /** 把这一行打出来的值合到基线上：只覆盖写了的项，没写的沿用。 */
  def withArgs(base: Settings, parsed: ProviderChatArgs.Parsed): Settings =
    base
      .copy(
        thread = parsed.thread.getOrElse(base.thread),
        prompt = parsed.prompt.getOrElse(base.prompt)
      )
      .normalized

  /** 有效值：一次性覆盖 > 固定值 > 会话推导默认。 */
  def resolve(
      baseline: Settings,
      pinned: Option[Settings],
      overrideSettings: Option[Settings]
  ): Settings =
    overrideSettings.orElse(pinned).map(_.normalized).getOrElse(baseline.normalized)

  /** Session 级一次性强制项：只改 thread，保留已经解析出的 prompt。 */
  def forceThread(settings: Settings, thread: String): Settings =
    settings.copy(thread = thread).normalized

  /** 线上值：`continue` 对应后台的 `delta`（复用同一段对话、只投增量），`new` 原样。 */
  def wireThread(thread: String): String =
    if (thread == ProviderChatArgs.ThreadNew) "new" else "delta"

  /**
   * 这一项是不是"被用户改出来"的（与**会话推导默认**不同）。
   *
   * 面板据此把它染成琥珀色：默认值是算出来的，用户不该以为是自己设的；改过的才算他的意思。
   */
  def isChanged(value: String, baseline: String): Boolean = value != baseline
}
