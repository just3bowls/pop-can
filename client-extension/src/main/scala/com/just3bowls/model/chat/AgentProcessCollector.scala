package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import scala.collection.mutable
import scala.scalajs.js

/** 把 agent 运行事件聚合成分回合的过程快照（steps JSON），并把事件继续转发给 UI。 */
final class AgentProcessCollector(emit: TabAiEvent => Unit) {
  final private class ToolState(val name: String, val arguments: String) {
    var result: String     = ""
    var ok: Boolean        = false
    var done: Boolean      = false
    var durationMs: Double = 0.0
  }
  final private class StepState(
      val index: Int,
      val kind: String = "model",
      val label: String = ""
  ) {
    val thinking                  = new StringBuilder
    val content                   = new StringBuilder
    val toolCalls                 = mutable.ArrayBuffer.empty[ToolState]
    var status: String            = "running"
    var error: String             = ""
    var finalAnswer: Boolean      = false
    var forwardedContent: Boolean = false
    def resetForRecovery(): Unit  = {
      thinking.clear()
      content.clear()
      toolCalls.clear()
      status = "running"
      error = ""
      finalAnswer = false
      forwardedContent = false
    }
  }
  private val steps = mutable.ArrayBuffer.empty[StepState]
  private var current: Option[StepState] = None

  /** 当前过程快照，包含仍在进行中的回合；用于落库与实时渲染。 */
  def stepsJson: String                                                      = serialize()
  def onEvent(event: TabAiEvent): Unit                                       =
    event match {
      case TabAiEvent.StepStarted                                =>
        current.foreach(step => if (step.status != "error") step.status = "done")
        current = Some(newStep())
        emitProcess()
      case TabAiEvent.StepFinished(hasToolCalls)                 =>
        // 工具在该轮结束之后才执行，因此这里只定状态，轮次切换交给下一次 StepStarted。
        current.foreach { step =>
          step.status = "done"
          if (hasToolCalls) {
            // 这一轮的内容属于工具轮的中间产物，已经流式展示的部分要撤回。
            if (step.forwardedContent) emit(TabAiEvent.ContentReset)
            step.forwardedContent = false
          } else {
            step.finalAnswer = true
          }
          emitProcess()
        }
      case event @ TabAiEvent.InternalRecoveryStarted(_)         =>
        current.foreach { step =>
          step.resetForRecovery()
          emitProcess()
        }
        emit(event)
      case TabAiEvent.Thinking(delta)                            =>
        current match {
          case Some(step) =>
            step.thinking.append(delta)
            emitProcess()
          case None       =>
            emit(TabAiEvent.Thinking(delta))
        }
      case TabAiEvent.Content(delta)                             =>
        current match {
          case Some(step) =>
            step.content.append(delta)
            if (!step.finalAnswer) {
              step.forwardedContent = true
              emit(TabAiEvent.Content(delta))
            }
          case None       =>
            emit(TabAiEvent.Content(delta))
        }
      case TabAiEvent.ContentReset                               =>
        emit(TabAiEvent.ContentReset)
      case TabAiEvent.ToolCall(name, args)                       =>
        current.foreach { step =>
          step.toolCalls.append(new ToolState(name, args))
          emitProcess()
        }
        emit(TabAiEvent.ToolCall(name, args))
      case TabAiEvent.ToolFinished(name, ok, result, durationMs) =>
        current.foreach { step =>
          step.toolCalls.find(tool => tool.name == name && !tool.done).foreach { tool =>
            tool.result = result
            tool.ok = ok
            tool.done = true
            tool.durationMs = durationMs
          }
          emitProcess()
        }
        emit(TabAiEvent.ToolFinished(name, ok, result, durationMs))
      case TabAiEvent.StepFailed(message)                        =>
        current.foreach { step =>
          step.status = "error"
          step.error = message
          emitProcess()
        }
      case TabAiEvent.TurnFailed(_, _, _, _, message)            =>
        current.foreach { step =>
          step.status = "error"
          step.error = message
          emitProcess()
        }
      case TabAiEvent.HumanInput(label, text)                    =>
        current.foreach(step => if (step.status != "error") step.status = "done")
        val step = newStep(kind = "human", label = label)
        step.content.append(text)
        step.status = "done"
        current = None
        emitProcess()
      case event @ TabAiEvent.PlanProposed(_, _)                 =>
        emit(event)
      case TabAiEvent.Notice(text)                               =>
        emit(TabAiEvent.Notice(text))
      case event @ TabAiEvent.Progress(_)                        =>
        emit(event)
      case TabAiEvent.ProcessUpdated(_)                          =>
        ()
    }
  private def newStep(kind: String = "model", label: String = ""): StepState = {
    val step = new StepState(steps.size + 1, kind, label)
    steps.append(step)
    step
  }
  private def emitProcess(): Unit                                            =
    emit(TabAiEvent.ProcessUpdated(serialize()))
  private def serialize(): String                                            =
    js.JSON.stringify(js.Array(steps.toList.map { step =>
      JsBridge.obj(
        "index"       -> (step.index),
        "kind"        -> (step.kind),
        "label"       -> (step.label),
        "status"      -> (step.status),
        "error"       -> (step.error),
        "finalAnswer" -> (step.finalAnswer),
        "thinking"    -> (step.thinking.toString),
        "content"     -> (step.content.toString),
        "toolCalls"   -> (js.Array(step.toolCalls.toList.map { tool =>
          JsBridge.obj(
            "name"       -> (tool.name),
            "arguments"  -> (tool.arguments),
            "result"     -> (tool.result),
            "ok"         -> (tool.ok),
            "done"       -> (tool.done),
            "durationMs" -> (tool.durationMs)
          )
        }*))
      )
    }*))
}
