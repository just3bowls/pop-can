package com.just3bowls.api
import com.just3bowls.api.provider.*

import zio.test._

/**
 * `/provider [new|continue] [inline|file] [pin|unpin]` 的金值测试。
 *
 * 规则：第一个 token 是 provider 过滤词，后面的 token 只认那几个值/标志（大小写不敏感、顺序无关，`delta` = `continue`）；出现别的 token、或 `pin` 与 `unpin` 同时出现
 * 就整段当过滤词 （老行为）—— 写错时只会匹配不到 provider，不会静默改设置。
 */
object ProviderChatArgsSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("ProviderChatArgs")(
    test("只有 provider：query 就是它，不带任何设置") {
      val parsed = ProviderChatArgs.parse("gml")
      assertTrue(
        parsed.query == "gml",
        parsed.thread.isEmpty,
        parsed.prompt.isEmpty,
        !parsed.pin,
        !parsed.unpin
      )
    },
    test("只写 thread：`gml new`") {
      val parsed = ProviderChatArgs.parse("gml new")
      assertTrue(parsed.query == "gml", parsed.thread.contains("new"), parsed.prompt.isEmpty)
    },
    test("只写 thread：`gml continue`") {
      val parsed = ProviderChatArgs.parse("gml continue")
      assertTrue(parsed.query == "gml", parsed.thread.contains("continue"), parsed.prompt.isEmpty)
    },
    test("`delta` 是 `continue` 的历史写法") {
      val parsed = ProviderChatArgs.parse("gml delta")
      assertTrue(parsed.query == "gml", parsed.thread.contains("continue"))
    },
    test("只写 prompt：`gml file`") {
      val parsed = ProviderChatArgs.parse("gml file")
      assertTrue(parsed.query == "gml", parsed.thread.isEmpty, parsed.prompt.contains("file"))
    },
    test("focus / unfocus are no longer settings") {
      val on  = ProviderChatArgs.parse("gml focus")
      val off = ProviderChatArgs.parse("gml unfocus")
      assertTrue(
        on.query == "gml focus" && on.thread.isEmpty && on.prompt.isEmpty,
        off.query == "gml unfocus" && off.thread.isEmpty && off.prompt.isEmpty
      )
    },
    test("两个都写：顺序无关") {
      val a = ProviderChatArgs.parse("gml new file")
      val b = ProviderChatArgs.parse("gml file new")
      assertTrue(
        a.thread.contains("new") && a.prompt.contains("file"),
        b.thread.contains("new") && b.prompt.contains("file")
      )
    },
    test("大小写不敏感") {
      val parsed = ProviderChatArgs.parse("GML NEW FILE")
      assertTrue(parsed.query == "GML", parsed.thread.contains("new"), parsed.prompt.contains("file"))
    },
    test("多余空白") {
      val parsed = ProviderChatArgs.parse("  gml   continue  ")
      assertTrue(parsed.query == "gml", parsed.thread.contains("continue"), parsed.prompt.isEmpty)
    },
    test("只写 pin：`gml pin`") {
      val parsed = ProviderChatArgs.parse("gml pin")
      assertTrue(
        parsed.query == "gml",
        parsed.pin,
        !parsed.unpin,
        parsed.thread.isEmpty,
        parsed.prompt.isEmpty
      )
    },
    test("值和 pin 一起写：`gml new file pin`") {
      val parsed = ProviderChatArgs.parse("gml new file pin")
      assertTrue(parsed.thread.contains("new"), parsed.prompt.contains("file"), parsed.pin)
    },
    test("unpin：`gml unpin`") {
      val parsed = ProviderChatArgs.parse("gml unpin")
      assertTrue(parsed.query == "gml", parsed.unpin, !parsed.pin)
    },
    test("pin 与 unpin 同时写：整段当过滤词，什么都不改") {
      val parsed = ProviderChatArgs.parse("gml pin unpin")
      assertTrue(
        parsed.query == "gml pin unpin",
        parsed.thread.isEmpty,
        parsed.prompt.isEmpty,
        !parsed.pin,
        !parsed.unpin
      )
    },
    test("写了不认识的值：整段当过滤词，不改设置") {
      val parsed = ProviderChatArgs.parse("gml foo")
      assertTrue(parsed.query == "gml foo", parsed.thread.isEmpty, parsed.prompt.isEmpty)
    },
    test("拼错的 'ful' 也算过滤词（不会当成 full/new）") {
      val parsed = ProviderChatArgs.parse("gml ful")
      assertTrue(parsed.query == "gml ful", parsed.thread.isEmpty)
    },
    test("空输入") {
      val parsed = ProviderChatArgs.parse("   ")
      assertTrue(parsed.query.isEmpty, parsed.thread.isEmpty, parsed.prompt.isEmpty)
    }
  )
}
