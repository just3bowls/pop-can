package com.just3bowls.model.memory

import com.just3bowls.facade.JsBridge

import com.just3bowls.api.memory.MemoryExtractionRequest
import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.model.chat.AgentCancellation
import com.just3bowls.model.chat.ModelRunRequest
import com.just3bowls.model.provider.ProviderChatSettings
import com.just3bowls.model.chat.TabAiEvent
import com.just3bowls.model.chat.ValidatedModelRuntime
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Dedicated background provider turn; never binds to a chat session and never gets browser tools. */
object MemoryExtractionRunner {
  val InternalKeyId                                         = "memory-extraction"
  def run(request: MemoryExtractionRequest): Future[String] = {
    val body = js.JSON.stringify(
      JsBridge.obj(
        "model"    -> (request.provider),
        "messages" -> (js.Array(
          JsBridge.obj("role" -> ("user"), "content" -> (request.prompt))
        )),
        "stream"   -> (false)
      )
    )
    ValidatedModelRuntime
      .run(
        ModelRunRequest(
          provider = request.provider,
          bodyJson = body,
          keyId = InternalKeyId,
          sessionId = "",
          contextMode = ProviderChatSettings.New,
          conversation = "fresh",
          promptMode = RoutePromptMode.normalize(request.promptMode),
          inject = true,
          bindConversation = false
        ),
        onEvent = (_: TabAiEvent) => (),
        cancellation = new AgentCancellation()
      )
      .flatMap { result =>
        if (result.ok) Future.successful(result.content.trim)
        else Future.failed(new RuntimeException(Option(result.error).getOrElse("Memory extraction failed")))
      }
  }
}
