package com.just3bowls.api
import com.just3bowls.api.provider.*

import zio.test._

/**
 * "同一家 provider 页面 / 同一条请求" 的归一化金值测试（用例来自真实 provider 的 URL 形态）。
 */
object PageIdentitySpec extends ZIOSpecDefault {

  private val cases: List[(String, String, String, Boolean)] = List(
    ("完全一致", "https://www.doubao.com/chat", "https://www.doubao.com/chat", true),
    ("根路径 vs 对话页（互为前缀）", "https://www.doubao.com/chat", "https://www.doubao.com/chat/38446298472558594", true),
    ("配置页比 tab 更深", "https://www.qianwen.com/chat/abc", "https://www.qianwen.com/chat", true),
    (
      "query / hash 不参与",
      "https://chatglm.cn/main/alltoolsdetail",
      "https://chatglm.cn/main/alltoolsdetail?t=1#x",
      true
    ),
    ("GML 根路径覆盖会话页", "https://chatglm.cn/", "https://chatglm.cn/chat/general/abc", true),
    ("文心根路径覆盖搜索页", "https://wenxin.baidu.com/", "https://wenxin.baidu.com/search/123", true),
    (
      "元宝 /chat 根路径覆盖具体会话",
      "https://yuanbao.tencent.com/chat/",
      "https://yuanbao.tencent.com/chat/naQivTmsDa/abc",
      true
    ),
    ("末尾斜杠等价", "https://www.kimi.com/chat/", "https://www.kimi.com/chat", true),
    ("不同 host", "https://www.kimi.com/chat", "https://www.moonshot.cn/chat", false),
    ("不同路径", "https://www.doubao.com/chat", "https://www.doubao.com/settings", false),
    ("空 URL 一律 false", "", "https://a/x", false),
    ("空白配置 false", "   ", "https://a/x", false)
  )

  def spec: Spec[Any, Any] = suite("PageIdentity")(
    test("sameProviderPage 金值") {
      val failures = cases.filter { case (_, configured, current, expected) =>
        PageIdentity.sameProviderPage(configured, current) != expected
      }
      assertTrue(failures.isEmpty)
    },
    test("base 去掉 query/hash 与末尾斜杠") {
      assertTrue(
        PageIdentity.base(" https://a/b/?q=1#h ") == "https://a/b",
        PageIdentity.base("https://a/b/") == "https://a/b",
        PageIdentity.base("") == ""
      )
    }
  )
}
