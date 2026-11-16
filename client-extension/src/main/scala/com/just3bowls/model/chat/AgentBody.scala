package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import scala.scalajs.js

/**
 * 一轮 agent 请求体的**唯一构造入口**：messages + tools → OpenAI body JSON。
 *
 * 插件页 / 研究页的模型调用（`LocalAgentModel`）和网页原生首轮注入（`TemplateInjection`）都从这里拿 body，保证两条链路给网页 AI 的载荷完全同构 ——
 * 原生那一轮也声明同一套工具，模型回工具轮后由插件页 agent 接着跑。
 */
object AgentBody {

  /** Transient system context; it does not correspond to a persisted chat message. */
  def systemMessage(content: String): js.Any =
    JsBridge.obj("role" -> ("system"), "content" -> (content))

  /** 工具声明（OpenAI `tools` 数组）；没声明工具时返回空数组。 */
  def toolsJson(toolDefs: List[AgentToolSpec]): js.Array[js.Any] =
    js.Array(
      toolDefs.map { tool =>
        JsBridge.obj(
          "type"     -> ("function"),
          "function" -> (JsBridge.obj(
            "name"        -> (tool.name),
            "description" -> (tool.description),
            "parameters"  -> (js.JSON.parse(tool.parametersJson))
          ))
        )
      }*
    )

  /** 组装 body：`tools` 为空时不写这个键（模型只能靠网页端自己的能力回答）。 */
  def body(provider: String, messages: js.Array[js.Any], tools: js.Array[js.Any]): String =
    js.JSON.stringify(JsBridge.obj(
      "model"    -> (provider),
      "messages" -> (messages),
      "stream"   -> (false),
      "tools"    -> (if (tools.nonEmpty) tools else js.undefined)
    ))
}
