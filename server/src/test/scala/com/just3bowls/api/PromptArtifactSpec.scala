package com.just3bowls.api
import com.just3bowls.api.marker.*

import zio.json._
import zio.test._

object PromptArtifactSpec extends ZIOSpecDefault {

  private val body =
    """{"model":"doubao","messages":[{"role":"system","content":"system"},{"role":"user","content":"LangGraph记忆模块设计模式"}],"tools":[]}"""

  private val fullPrompt =
    s"""${PromptArtifact.ChatPersona}
       |
       |${PromptArtifact.ProtocolLead}
       |
       |@@BEG:response@@
       |@@END:response@@
       |
       |${PromptArtifact.RequestBodyLead}
       |
       |$body""".stripMargin

  private val deltaPayload =
    s"""{"messages":[{"role":"user","content":${fullPrompt.toJson}}],"tools":[]}"""

  def spec: Spec[Any, Any] = suite("PromptArtifact")(
    test("unwraps a full inline prompt") {
      assertTrue(PromptArtifact.unwrap(fullPrompt).contains("LangGraph记忆模块设计模式"))
    },
    test("unwraps native delta payloads and nested prompts") {
      assertTrue(PromptArtifact.unwrap(deltaPayload).contains("LangGraph记忆模块设计模式"))
    },
    test("compares user turns after unwrapping either side") {
      assertTrue(
        PromptArtifact.sameUserTurn(deltaPayload, "LangGraph记忆模块设计模式"),
        !PromptArtifact.sameUserTurn(deltaPayload, "another question")
      )
    },
    test("leaves ordinary text and unrelated JSON untouched") {
      assertTrue(
        PromptArtifact.unwrap("normal question").isEmpty,
        PromptArtifact.unwrap("""{"messages":[{"role":"user","content":"normal"}]}""").isEmpty
      )
    },
    test("malformed artifacts fall back to the original") {
      val broken = s"${PromptArtifact.ChatPersona}\n\n${PromptArtifact.ProtocolLead}\n@@BEG:response@@"
      assertTrue(PromptArtifact.unwrap(broken).isEmpty, PromptArtifact.userTextOrOriginal(broken) == broken)
    }
  )
}
