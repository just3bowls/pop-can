package com.just3bowls.api
import com.just3bowls.api.chat.*
import com.just3bowls.api.provider.*

import zio.test._

/**
 * `/provider` 有效设置的纯逻辑金值：会话推导默认 → 固定值 → 一次性覆盖。
 *
 * "用过"= 这条会话在这个 provider 上有绑定的原生对话 URL（页面的 `sessionUrlsVar`）。
 */
object ChatSendPolicySpec extends ZIOSpecDefault {

  private def settings(thread: String, prompt: String): ChatSendPolicy.Settings =
    ChatSendPolicy.Settings(thread, prompt)

  def spec: Spec[Any, Any] = suite("ChatSendPolicy")(
    test("没用过这家：必须新开一段，prompt 默认 inline") {
      assertTrue(ChatSendPolicy.baseline(usedInSession = false) == settings("new", "inline"))
    },
    test("用过这家：接着这段聊，prompt 默认 inline") {
      assertTrue(ChatSendPolicy.baseline(usedInSession = true) == settings("continue", "inline"))
    },
    test("Undo 后下一轮默认 new，且仍允许 pin / 一次性设置覆盖") {
      val base   = ChatSendPolicy.afterUndo(usedInSession = true)
      val pinned = Some(settings("continue", "file"))
      assertTrue(
        base == settings("new", "inline"),
        ChatSendPolicy.resolve(base, None, None) == settings("new", "inline"),
        ChatSendPolicy.resolve(base, pinned, None) == settings("continue", "file"),
        ChatSendPolicy.resolve(base, None, Some(settings("continue", "inline"))) == settings("continue", "inline")
      )
    },
    test("打的值只覆盖写了的那一项") {
      val base   = ChatSendPolicy.baseline(usedInSession = true)
      val parsed = ProviderChatArgs.parse("gml new")
      assertTrue(ChatSendPolicy.withArgs(base, parsed) == settings("new", "inline"))

      val onlyPrompt = ProviderChatArgs.parse("gml file")
      assertTrue(ChatSendPolicy.withArgs(base, onlyPrompt) == settings("continue", "file"))
    },
    test("优先级：一次性覆盖 > 固定值 > 会话默认") {
      val base   = ChatSendPolicy.baseline(usedInSession = false)
      val pinned = Some(settings("continue", "inline"))
      assertTrue(
        ChatSendPolicy.resolve(base, None, None) == settings("new", "inline"),
        ChatSendPolicy.resolve(base, pinned, None) == settings("continue", "inline"),
        ChatSendPolicy.resolve(base, pinned, Some(settings("new", "file"))) == settings("new", "file")
      )
    },
    test("固定值可以表达“这家永远 continue”（即使本会话没用过）") {
      val base = ChatSendPolicy.baseline(usedInSession = false)
      assertTrue(ChatSendPolicy.resolve(base, Some(settings("continue", "file")), None) == settings("continue", "file"))
    },
    test("解绑（unpin）后回到会话默认") {
      val base = ChatSendPolicy.baseline(usedInSession = true)
      assertTrue(ChatSendPolicy.resolve(base, None, None) == base)
    },
    test("clear 的 forced new 覆盖 pin / pending，但保留 prompt；redo 后恢复 continue") {
      val pinned   = Some(settings("continue", "file"))
      val resolved = ChatSendPolicy.resolve(ChatSendPolicy.baseline(usedInSession = true), pinned, None)
      assertTrue(
        ChatSendPolicy.forceThread(resolved, "new") == settings("new", "file"),
        ChatSendPolicy.baseline(usedInSession = true) == settings("continue", "inline")
      )
    },
    test("线上值：continue → delta，new 原样") {
      assertTrue(
        ChatSendPolicy.wireThread("continue") == "delta",
        ChatSendPolicy.wireThread("new") == "new",
        ChatSendPolicy.wireThread("delta") == "delta"
      )
    },
    test("非法词汇归一（脏存储不会把参数搞坏）") {
      assertTrue(
        ChatSendPolicy.Settings("wat", "wat").normalized == ChatSendPolicy.Default,
        ChatSendPolicy.Settings("new", "wat").normalized == settings("new", "inline"),
        ChatSendPolicy.Settings("new", "file").isDefault == false
      )
    }
  )
}
