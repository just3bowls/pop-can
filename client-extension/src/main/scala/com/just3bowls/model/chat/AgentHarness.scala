package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.{createLangChainAgent, createLangChainMiddleware, LangGraphApp}
import scala.scalajs.js

/**
 * LangChain createAgent 的薄配置工厂。
 *
 * 这里不实现 Agent Loop、路由、retry 或 checkpoint；这些能力由 LangChain/LangGraph 提供。PopCan 只负责把自定义网页 AI 模型、 工具和事件/终止策略接入 harness。
 */
object AgentHarness {
  def create(
      model: js.Any,
      tools: List[js.Object],
      middleware: List[js.Object] = Nil
  ): LangGraphApp =
    createLangChainAgent(JsBridge.obj(
      "model"      -> (model),
      "tools"      -> (js.Array(tools*)),
      "middleware" -> (js.Array(middleware*))
    ))

  /**
   * commit terminal tool 成功后立即结束本次 Agent，不再让模型为“工具已成功”额外生成一轮。
   *
   * terminal tool 仍会先正常执行；下一次模型调用前由 beforeModel 跳到 end，因此参数校验、captured args、 ToolCall/ToolFinished 事件都保持原路径，也不会额外生成一轮。
   */
  def terminalToolMiddleware(
      succeeded: () => Boolean
  ): js.Object =
    createLangChainMiddleware(JsBridge.obj(
      "name"        -> ("pop-can-terminal-tool"),
      "beforeModel" -> (JsBridge.obj(
        "canJumpTo" -> (js.Array("end")),
        "hook"      -> ((_: js.Any) => if (succeeded()) JsBridge.obj("jumpTo" -> ("end")) else js.undefined)
      ))
    ))
}
