package com.just3bowls.api
import com.just3bowls.api.marker.*

import zio.test._

object MarkerSettlementSpec extends ZIOSpecDefault {

  private val contentReply =
    """@@BEG:response@@
      |@@BEG:reasoning_content@@
      |Need to answer carefully.
      |@@END:reasoning_content@@
      |
      |@@BEG:content@@
      |Hello **world**.
      |@@END:content@@
      |@@END:response@@""".stripMargin

  private val toolReply =
    """@@BEG:response@@
      |@@BEG:reasoning_content@@
      |Need to search.
      |@@END:reasoning_content@@
      |
      |@@BEG:tool_calls@@
      |@@BEG:tool_call@@
      |@@BEG:name@@browser_search@@END:name@@
      |@@BEG:arguments@@
      |@@BEG:query:string@@AI news@@END:query:string@@
      |@@END:arguments@@
      |@@END:tool_call@@
      |@@END:tool_calls@@
      |@@END:response@@""".stripMargin

  private def streamByPrefixes(raw: String) = {
    var content                                                       = ""
    var reasoning                                                     = ""
    val leaks                                                         = List.newBuilder[String]
    def advance(previous: String, next: String, name: String): String =
      if (!next.startsWith(previous)) {
        leaks += s"$name regressed"
        previous
      } else next
    (0 to raw.length).foreach { length =>
      val settled = MarkerSettlement.settledValues(raw.take(length))
      content = advance(content, settled.content, "content")
      reasoning = advance(reasoning, settled.reasoning, "reasoning")
      List(settled.content, settled.reasoning).foreach { value =>
        if (value.contains("@@BEGIN:") || value.contains("@@END:")) leaks += s"marker leaked at $length"
      }
    }
    (content, reasoning, leaks.result())
  }

  def spec: Spec[Any, Any] = suite("MarkerSettlement")(
    test("every prefix split keeps streamed content equal to strict parse") {
      val finalResult                 = MarkerSettlement.strict(contentReply).toOption.get
      val (content, reasoning, leaks) = streamByPrefixes(contentReply)
      assertTrue(
        leaks.isEmpty,
        content == finalResult.content,
        reasoning == finalResult.reasoning.getOrElse("")
      )
    },
    test("one trailing @ of a closing marker is held back") {
      val closeAt = contentReply.indexOf("@@END:reasoning_content@@")
      val raw     = contentReply.take(closeAt + "@@END:reasoning_content@".length)
      val settled = MarkerSettlement.settledValues(raw)
      assertTrue(
        settled.reasoning == "Need to answer carefully.",
        !settled.reasoning.contains("@@END:")
      )
    },
    test("tool calls are parsed once only after the complete block arrives") {
      val result = MarkerSettlement.strict(toolReply).toOption.get
      assertTrue(
        result.content.isEmpty,
        result.reasoning.contains("Need to search."),
        result.toolCalls == List(MarkerSettlement.ToolCall("browser_search", """{"query":"AI news"}"""))
      )
    },
    test("strict parsing still rejects malformed markers") {
      assertTrue(MarkerSettlement.strict("@@BEG:response@@").isLeft)
    },
    test("strict parsing rejects duplicate singleton marker pairs") {
      val duplicate =
        """@@BEG:response@@
          |@@BEG:reasoning_content@@
          |reason
          |@@END:reasoning_content@@
          |@@BEG:content@@
          |first
          |@@END:content@@
          |@@BEG:content@@
          |second
          |@@END:content@@
          |@@END:response@@""".stripMargin
      assertTrue(
        MarkerSettlement.strict(duplicate).left.exists(_.exists(_.contains("duplicate marker pair")))
      )
    }
  )
}
