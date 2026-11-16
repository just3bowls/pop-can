package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.StructuredAgentOutput
import com.just3bowls.api.chat.AgentStepPolicy
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js
final case class StructuredAgentSpec(
    toolSpecs: List[AgentToolSpec],
    messages: js.Array[? <: js.Any],
    toolRounds: Int,
    validateTerminal: Option[(String, js.Any) => Option[String]] = None,
    handlers: Map[String, js.Any => Future[js.Any]] = Map.empty
)
final case class StructuredAgentResult(
    toolArgs: Map[String, String],
    text: String
)

/**
 * 需要 terminal tool 才能提交结果的共享 Agent 执行器。
 *
 * LangChain createAgent 负责 model/tools 循环；这里只处理 PopCan 的“提交工具成功后立即结束”、业务校验和事件桥接。
 */
object StructuredAgentRunner {
  def run(
      model: js.Any,
      spec: StructuredAgentSpec,
      onEvent: TabAiEvent => Unit,
      cancellation: AgentCancellation
  ): Future[StructuredAgentResult] = {
    val captured          = mutable.Map.empty[String, String]
    var terminalSucceeded = false
    val tools             = spec.toolSpecs.map { toolSpec =>
      val callTool: (String, String) => Future[js.Any] = (name, argsText) =>
        if (cancellation.isCancelled) Future.failed(new RuntimeException("Task canceled"))
        else {
          val args    =
            try JsBridge.asObject(js.JSON.parse(argsText))
            catch { case _: Throwable => JsBridge.obj() }
          val invalid =
            if (toolSpec.terminal) spec.validateTerminal.flatMap(validate => validate(name, args))
            else None
          invalid match {
            case Some(error) =>
              Future.successful(JsBridge.obj(
                "ok"    -> (false),
                "error" -> (s"$name rejected: $error Correct the arguments and call $name again.")
              ))
            case None        =>
              val handler = spec.handlers.getOrElse(name, (_: js.Any) => Future.successful(ack))
              handler(args).map { result =>
                val ok =
                  if (js.isUndefined(result) || result == null || js.typeOf(result) != "object") true
                  else
                    JsBridge.undefinedBoolean(JsBridge.field(result, "ok")).getOrElse(true)
                if (ok) {
                  captured.update(name, argsText)
                  if (toolSpec.terminal) terminalSucceeded = true
                }
                result
              }
          }
        }
      LocalAgentModel.createLangChainTool(toolSpec, callTool, cancellation, onEvent)
    }
    val terminalTools     = spec.toolSpecs.filter(_.terminal).map(_.name).toSet
    val middleware        =
      if (terminalTools.isEmpty) Nil
      else List(AgentHarness.terminalToolMiddleware(() => terminalSucceeded))
    val app               = AgentHarness.create(model, tools, middleware)
    app
      .invoke(
        JsBridge.obj("messages"       -> (spec.messages)),
        JsBridge.obj("recursionLimit" -> (AgentStepPolicy.structuredRecursionLimit(spec.toolRounds)))
      )
      .toFuture
      .map(raw => StructuredAgentResult(captured.toMap, lastText(raw)))
  }
  private val ack                              = JsBridge.obj("ok" -> (true))
  private def lastText(raw: js.Any): String    =
    if (js.isUndefined(raw) || raw == null) ""
    else {
      val messages = raw.asInstanceOf[StructuredAgentOutput].messages
        .getOrElse(js.Array[js.Any]())
      messages.reverseIterator.map(messageText).find(_.trim.nonEmpty).getOrElse("")
    }
  private def messageText(raw: js.Any): String =
    if (js.isUndefined(raw) || raw == null) ""
    else {
      val content = JsBridge.field(raw, "content")
      if (js.isUndefined(content) || content == null) ""
      else if (js.typeOf(content) == "string") JsBridge.asString(content)
      else {
        JsBridge
          .asArray(content)
          .toList
          .flatMap { block =>
            JsBridge.stringField(block, "type") match {
              case Some("text") => Option(JsBridge.stringField(block, "text").getOrElse(""))
              case _            => None
            }
          }
          .mkString("\n")
      }
    }
}
