package com.just3bowls.api
import com.just3bowls.api.webchat.*

import zio.test._

/**
 * 归属不变式的金值测试：用例全部来自真实站点踩过的坑（gml 的 `cid=`、kimi 的补 id、mimo 的 hash 路由、回首页）。
 */
object ConversationUrlSpec extends ZIOSpecDefault {

  private val gmlOld   = "https://chatglm.cn/main/alltoolsdetail?t=1791540802603&lang=zh&cid=6ac8be5f23426b51f40f0cab"
  private val gmlFresh = "https://chatglm.cn/main/alltoolsdetail?t=1791549080180&lang=zh"
  private val gmlFreshWithId =
    "https://chatglm.cn/main/alltoolsdetail?t=1791549080180&lang=zh&cid=6ac8de9e685c21bac7a50859"

  // wenxin：新对话页 `?enter_type=sidebar_dialog&internal=1` 在首条消息之后变成
  // `/search/<convId>?enter_type=sidebar_dialog`——路径变长、`internal=1` 被页面消费掉，属同一段对话。
  private val wenxinNewChat      = "https://wenxin.baidu.com/search?enter_type=sidebar_dialog&internal=1"
  private val wenxinConversation = "https://wenxin.baidu.com/search/12178959057772851263?enter_type=sidebar_dialog"

  private val cases: List[(String, String, String, Boolean)] = List(
    // 同一段对话：URL 只会变长
    ("同一段对话，完全相同的 URL", "https://a/x", "https://a/x", true),
    ("补上对话 id", "https://www.kimi.com/chat", "https://www.kimi.com/chat/abc", true),
    ("hash 路由补 id", "https://aistudio.xiaomimimo.com/#/c", "https://aistudio.xiaomimimo.com/#/chat/abc", true),
    ("gml：new chat 页拿到 cid", gmlFresh, gmlFreshWithId, true),
    ("追加无关参数", "https://a/x?p=1", "https://a/x?p=1&refer__991=z", true),
    ("query 顺序无所谓", "https://a/x?b=1&a=2", "https://a/x?a=2&b=1&c=3", true),
    // kimi 真实路由：新对话页 `/?chat_enter_method=new_chat` 在首条消息后变成 `/chat/<id>?chat_enter_method=new_chat`
    (
      "kimi：新对话页 → 会话路由",
      "https://www.kimi.com/?chat_enter_method=new_chat",
      "https://www.kimi.com/chat/abc?chat_enter_method=new_chat",
      true
    ),
    // 路径变长 + 旧参数被消费（wenxin 的 internal=1）：算同一段
    ("wenxin：新对话页 → 会话页（丢 internal）", wenxinNewChat, wenxinConversation, true),
    ("合成：路径变长、旧参数之一消失", "https://a/x?p=1&q=2", "https://a/x/y?p=1", true),
    // 新对话 / 非法变更：一律不认
    ("换一段对话（path id）", "https://www.kimi.com/chat/aaa", "https://www.kimi.com/chat/bbb", false),
    ("换一段对话（query id）", gmlOld, gmlFreshWithId, false),
    ("gml：手动 new chat", gmlOld, gmlFresh, false),
    ("参数被改写", "https://a/x?t=1", "https://a/x?t=2", false),
    ("路径变长但键的值被改写", "https://a/x?t=1&lang=zh", "https://a/x/y?t=2&lang=zh", false),
    ("换 host", "https://a.com/chat", "https://b.com/chat/x", false),
    ("同前缀的另一个 host 不算", "https://a.com/x", "https://a.com.evil.com/x/y", false),
    ("回首页（变短）", "https://www.kimi.com/chat/abc", "https://www.kimi.com/", false),
    ("回 new chat 页（去掉 id）", "https://www.kimi.com/chat/abc", "https://www.kimi.com/chat", false),
    ("去掉参数", "https://a/x?p=1", "https://a/x", false),
    ("丢了 hash 路由（变短）", "https://a/x#/chat/abc", "https://a/x", false),
    ("空值", "https://a/x", "", false)
  )

  def spec: Spec[Any, Any] = suite("ConversationUrl")(
    test("只认 URL 只增不减的延伸，其余算新对话") {
      val wrong = cases.filter { case (_, bound, current, expected) =>
        ConversationUrl.isExtensionOf(bound, current) != expected
      }
      assertTrue(wrong.isEmpty)
    },
    test("sameConversation 只允许 URL 细化或同路由的 query 清理") {
      val checks = List(
        (
          "https://chatglm.cn/main/alltoolsdetail?t=1&lang=zh&cid=abc&refer__991=noise",
          "https://chatglm.cn/main/alltoolsdetail?t=1&lang=zh&cid=abc",
          true
        ),
        (
          "https://chatglm.cn/main/alltoolsdetail?t=1&lang=zh&cid=abc",
          "https://chatglm.cn/main/alltoolsdetail?t=2&lang=zh&cid=abc",
          false
        ),
        (
          "https://chatglm.cn/main/alltoolsdetail?t=1&lang=zh&cid=abc",
          "https://chatglm.cn/main/alltoolsdetail",
          false
        ),
        ("https://www.doubao.com/chat", "https://www.doubao.com/chat/38449504951530754", true),
        ("https://www.doubao.com/chat/38449540262806274", "https://www.doubao.com/chat", false),
        (
          "https://www.doubao.com/chat/38449540241152514",
          "https://www.doubao.com/chat/38449504951530754",
          false
        )
      )
      val wrong  = checks.collect {
        case (bound, current, expected) if ConversationUrl.sameConversation(bound, current) != expected =>
          s"$bound -> $current"
      }
      assertTrue(wrong.isEmpty)
    },
    test("手动 New Chat 回到更短路由不能被当成旧对话") {
      val oldConversation  = "https://www.doubao.com/chat/38449540241152514"
      val freshChat        = "https://www.doubao.com/chat"
      val nextConversation = "https://www.doubao.com/chat/38449504951530754"
      assertTrue(
        !ConversationUrl.sameConversation(oldConversation, freshChat),
        !ConversationUrl.sameConversation(oldConversation, nextConversation),
        ConversationUrl.sameConversation(freshChat, nextConversation)
      )
    }
  )
}
