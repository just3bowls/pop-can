package com.just3bowls.model.chat

import com.just3bowls.facade.JsonValue
import com.just3bowls.model.provider.*
import com.just3bowls.model.localapi.*

import com.just3bowls.api.marker.{DeclaredToolPolicy, ProtocolRepair}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** 一次模拟模型调用的完整请求。body 始终是 OpenAI-compatible chat completion JSON。 */
final case class ModelRunRequest(
    provider: String,
    bodyJson: String,
    keyId: String = ApiKeyStore.SystemApiKeyId,
    sessionId: String = "",
    messageIds: List[String] = Nil,
    contextMode: String = ProviderChatSettings.Delta,
    conversation: String = "",
    promptMode: String = ProviderChatSettings.Inline,
    inject: Boolean = true,
    hostTabId: Int = 0,
    bindConversation: Boolean = false
)

/**
 * 所有 consumer 共用的严格模型层。
 *
 * Chat 与 Server 的差异只能是 OpenAI body 与 provider 路由；marker 校验、全量错误聚合、纠错和 5 分钟预算都在这里。
 */
object ValidatedModelRuntime {
  val BudgetMs: Int                                     = 300000
  val MaxProtocolRepairs: Int                           = 1
  def run(
      request: ModelRunRequest,
      onEvent: TabAiEvent => Unit = (_: TabAiEvent) => (),
      cancellation: AgentCancellation = new AgentCancellation(),
      onTaskStarted: String => Unit = _ => (),
      budgetMs: Int = BudgetMs,
      initialProtocolRepair: Option[String] = None,
      initialRepairCount: Int = 0
  ): Future[TabAiResult] = {
    val effectiveBudgetMs = math.max(1, budgetMs)
    val deadline          = js.Date.now() + effectiveBudgetMs
    def attempt(
        protocolRepair: Option[String],
        recoveryReason: String,
        repairCount: Int,
        reuseConversation: Boolean
    ): Future[TabAiResult] = {
      cancellation.check()
      val remaining = deadline - js.Date.now()
      if (remaining <= 0) {
        val message = s"Model marker validation timed out after ${math.max(1, effectiveBudgetMs / 1000)} seconds"
        if (protocolRepair.nonEmpty) onEvent(TabAiEvent.InternalRecoveryStarted(message))
        onEvent(TabAiEvent.StepFailed(message))
        Future.successful(failed(
          message,
          protocol = true,
          errors = List(message)
        ))
      } else {
        protocolRepair match {
          case Some(_) => onEvent(TabAiEvent.InternalRecoveryStarted(recoveryReason))
          case None    => onEvent(TabAiEvent.StepStarted)
        }
        TabModelCall
          .run(
            request.provider,
            request.bodyJson,
            onEvent,
            cancellation,
            timeoutMs = math.max(1, remaining.toInt),
            keyId = request.keyId,
            sessionId = request.sessionId,
            messageIds = request.messageIds,
            contextMode = request.contextMode,
            protocolRepair = protocolRepair,
            // A repair is an extra turn inside the provider conversation that produced the invalid answer.
            conversation = if (reuseConversation) "reuse" else request.conversation,
            inject = request.inject,
            hostTabId = request.hostTabId,
            promptMode = request.promptMode,
            bindConversation = request.bindConversation,
            onTaskStarted = onTaskStarted
          )
          .recover { case e =>
            failed(Option(e.getMessage).getOrElse(e.toString), protocol = false)
          }
          .flatMap { result =>
            val declaredTools = toolNames(request.bodyJson).toSet
            val violations    =
              if (!result.ok) Nil
              else
                DeclaredToolPolicy.validate(
                  result.content,
                  result.toolCalls.map(call => call.name -> call.arguments),
                  declaredTools
                )
            val checked       =
              if (violations.isEmpty) result
              else
                result.copy(
                  ok = false,
                  finishReason = "error",
                  error = DeclaredToolPolicy.messages(violations).mkString(" | "),
                  protocolFailure = true,
                  protocolErrors = DeclaredToolPolicy.messages(violations)
                )
            if (checked.ok) {
              onEvent(TabAiEvent.StepFinished(checked.toolCalls.nonEmpty))
              Future.successful(checked)
            } else {
              val error  = Option(checked.error).filter(_.nonEmpty).getOrElse("Model call failed")
              val errors = if (checked.protocolErrors.nonEmpty) checked.protocolErrors else List(error)
              if (checked.protocolFailure && js.Date.now() < deadline && repairCount < MaxProtocolRepairs)
                attempt(
                  Some(ProtocolRepair.directive(errors, declaredTools)),
                  errors.mkString(" | "),
                  repairCount + 1,
                  reuseConversation = true
                )
              else {
                if (checked.protocolFailure)
                  onEvent(TabAiEvent.InternalRecoveryStarted(errors.mkString(" | ")))
                onEvent(TabAiEvent.StepFailed(error))
                if (checked.protocolFailure)
                  onEvent(TabAiEvent.TurnFailed(
                    provider = request.provider,
                    keyId = request.keyId,
                    messagesJson = messagesJson(request.bodyJson),
                    toolNames = declaredTools.toList,
                    message = errors.mkString(" | ")
                  ))
                Future.successful(checked)
              }
            }
          }
      }
    }
    attempt(
      initialProtocolRepair,
      recoveryReason = initialProtocolRepair.getOrElse(""),
      repairCount = math.max(0, initialRepairCount),
      reuseConversation = false
    )
  }
  private def failed(
      message: String,
      protocol: Boolean,
      errors: List[String] = Nil
  ): TabAiResult =
    TabAiResult(
      ok = false,
      content = "",
      thinking = "",
      toolCalls = Nil,
      finishReason = "error",
      error = message,
      protocolFailure = protocol,
      protocolErrors = errors
    )
  private def messagesJson(bodyJson: String): String    =
    JsonValue
      .parse(bodyJson)
      .toOption
      .flatMap(value => JsonValue.field(value, "messages"))
      .map(JsonValue.stringify)
      .getOrElse("[]")
  private def toolNames(bodyJson: String): List[String] =
    JsonValue
      .parse(bodyJson)
      .toOption
      .toList
      .flatMap(value => JsonValue.arrayField(value, "tools"))
      .flatMap(tool => JsonValue.objectField(tool, "function"))
      .flatMap(function => JsonValue.stringField(function, "name"))
      .filter(_.nonEmpty)
}
