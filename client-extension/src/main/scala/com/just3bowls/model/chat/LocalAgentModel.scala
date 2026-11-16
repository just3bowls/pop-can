package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.provider.*
import com.just3bowls.model.localapi.*

import com.just3bowls.facade.{AIMessage, LangChainBaseMessage, LangChainTools, RunnableLambda, Zod, ZodSchema}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/** LangGraph 的“tab AI 自定义模型”。LangGraph 只要求 invoke + bindTools + _modelType。 */
object LocalAgentModel {

  /** chat agent 的工具全部来自注册表；参数 schema 与 Zod shape 都由它派生。 */
  val defaultTools: List[AgentToolSpec] = AgentToolRegistry.chatTools

  /**
   * 首轮已经由别人（网页原生会话的第一次投递）跑完时，把这一轮直接喂给图。
   *
   * 原生聊天：用户在 provider 页发出的那条就是第 1 轮，SW 把它解析成 content / thinking / toolCalls 后交给插件页 agent；agent 不重复调用 provider，直接进入工具节点。
   */
  final case class SeededRound(content: String, thinking: String, toolCalls: List[TabToolCall])

  /** 只消费一次的种子；LangGraph 会重新 bindTools（= 重新 create 一个模型实例），状态必须在这些实例之间共享。 */
  final class SeedState(private var pending: Option[SeededRound]) {
    def take(): Option[SeededRound] = {
      val current = pending
      pending = None
      current
    }
  }
  def createLangChainTool(
      toolDef: AgentToolSpec,
      callTool: (String, String) => Future[js.Any],
      cancellation: AgentCancellation,
      onEvent: TabAiEvent => Unit = (_: TabAiEvent) => ()
  ): js.Object = {
    val schema                             = Zod.obj(zodShapeOf(toolDef))
    val func: js.Function1[js.Any, js.Any] = (input: js.Any) => {
      cancellation.check()
      val argsText  = stringifyArgs(input)
      val startedAt = js.Date.now()
      onEvent(TabAiEvent.ToolCall(toolDef.name, argsText))
      val missing   = missingRequiredArgs(toolDef, input)
      if (missing.nonEmpty) {
        val message =
          s"${toolDef.name} is missing required argument(s): ${missing.mkString(", ")}. " +
            "Correct the tool call and submit again."
        val result  = JsBridge.obj("ok" -> (false), "error" -> (message))
        onEvent(TabAiEvent.ToolFinished(
          toolDef.name,
          ok = false,
          resultText(result),
          js.Date.now() - startedAt
        ))
        JsBridge.asObject(Future.successful(JsBridge.asObject(result)).toJSPromise)
      } else
        JsBridge.asObject(
          callTool(toolDef.name, argsText)
            .map { result =>
              cancellation.check()
              val ok =
                if (js.isUndefined(result) || result == null || js.typeOf(result) != "object") true
                else
                  JsBridge.undefinedBoolean(JsBridge.field(result, "ok")).getOrElse(true)
              onEvent(TabAiEvent.ToolFinished(toolDef.name, ok, resultText(result), js.Date.now() - startedAt))
              result
            }
            .recoverWith { case e =>
              onEvent(TabAiEvent.ToolFinished(
                toolDef.name,
                ok = false,
                Option(e.getMessage).getOrElse(s"${toolDef.name} failed"),
                js.Date.now() - startedAt
              ))
              Future.failed(e)
            }
            .map(JsBridge.asObject(_))
            .toJSPromise
        )
    }
    LangChainTools.tool(
      func,
      JsBridge.obj("name" -> (toolDef.name), "description" -> (toolDef.description), "schema" -> (schema)).asInstanceOf[
        js.Object
      ]
    )
  }

  /** LangChain 的 zod schema 只负责暴露定义，不能依赖它替我们拒绝空参数。 */
  private def missingRequiredArgs(toolDef: AgentToolSpec, input: js.Any): List[String] =
    if (js.isUndefined(input) || input == null || js.typeOf(input) != "object")
      toolDef.params.filter(_.required).map(_.name)
    else {
      val obj = JsBridge.asObject(input)
      toolDef.params.filter(_.required).flatMap { param =>
        val value = JsBridge.field(obj, param.name)
        if (js.isUndefined(value) || value == null) Some(param.name) else None
      }
    }

  /** 由注册表参数派生 Zod shape；必填项在模型漏传时直接报错，可选项标 optional。 */
  private def zodShapeOf(toolDef: AgentToolSpec): js.Dictionary[js.Any] =
    js.Dictionary(toolDef.params.map { param =>
      val base: ZodSchema = param.kind match {
        case ToolParamKind.Text  => Zod.string()
        case ToolParamKind.Flag  => Zod.boolean()
        case ToolParamKind.Count => Zod.number()
        case ToolParamKind.Json  => Zod.any()
      }
      param.name -> (if (param.required) base else base.optional())
    }*)
  def create(
      provider: String,
      boundToolDefs: List[AgentToolSpec] = Nil,
      onEvent: TabAiEvent => Unit = (_: TabAiEvent) => (),
      cancellation: AgentCancellation = new AgentCancellation(),
      keyId: String = ApiKeyStore.SystemApiKeyId,
      /**
       * 页面侧等待一次模型调用的绝对上限。**必须高于后台那条链路的等待窗**（`RequestRewrite` 之后的 CDP 流等待 = 10 分钟）：让后台先到点、把它可读的失败原因（`matched <url> but
       * response did not finish`）交给页面，而不是页面 先掐断成一句没有上下文的 "Tab AI task timed out"。provider 进入自己的深度思考时只吐思考帧、正文很晚才来， 因此这里给
       * 12 分钟的保险值。
       */
      timeoutMs: Int = 720000,
      sessionId: String = "",
      contextIds: List[String] = Nil,
      contextMode: String = ProviderChatSettings.Delta,
      conversation: String = "",
      /** 正文投递方式（`inline` / `file`）：整轮共用，只影响"要投全量"的那一投怎么送。 */
      promptMode: String = ProviderChatSettings.Inline,
      hostTabId: Int = 0,
      /** 这一轮用过的对话要不要绑给会话：插件页 Chat / Retry 为 true；网页原生 host 轮为 false。 */
      bindConversation: Boolean = false,
      seed: Option[SeedState] = None
  ): js.Object = {
    val invokeFn: js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]] =
      (messages: js.Any, _) =>
        invokeModel(
          provider,
          boundToolDefs,
          onEvent,
          messages,
          cancellation,
          keyId,
          sessionId,
          contextIds,
          contextMode,
          conversation,
          promptMode,
          hostTabId,
          bindConversation,
          seed
        ).toJSPromise
    val bindFn: js.Function1[js.Any, js.Object]                                =
      (tools: js.Any) =>
        create(
          provider,
          boundToolDefsFromJs(tools, boundToolDefs),
          onEvent,
          cancellation,
          keyId,
          timeoutMs,
          sessionId,
          contextIds,
          contextMode,
          conversation,
          promptMode,
          hostTabId,
          bindConversation,
          seed
        )
    val runnable                                                               = JsBridge.asObject(RunnableLambda.from(
      invokeFn.asInstanceOf[js.Function1[js.Any, js.Any]]
    ))
    runnable._modelType = "tab-chat"
    // LangChain v1 createAgent 以 BaseChatModel 形状识别模型；PopCan 的流式事件由 onEvent 驱动，不实现 Runnable 的 stream 方法。
    runnable._streamResponseChunks = js.undefined
    runnable.bindTools = bindFn
    runnable
  }
  private def invokeModel(
      provider: String,
      toolDefs: List[AgentToolSpec],
      onEvent: TabAiEvent => Unit,
      messages: js.Any,
      cancellation: AgentCancellation,
      keyId: String,
      sessionId: String,
      contextIds: List[String],
      contextMode: String,
      conversation: String,
      promptMode: String,
      hostTabId: Int,
      bindConversation: Boolean,
      seed: Option[SeedState]
  ): Future[AIMessage] = {
    cancellation.check()
    seed.flatMap(_.take()) match {
      case Some(round) => Future.successful(seededAIMessage(round, onEvent))
      case None        => invokeProvider(
          provider,
          toolDefs,
          onEvent,
          messages,
          cancellation,
          keyId,
          sessionId,
          contextIds,
          contextMode,
          conversation,
          promptMode,
          hostTabId,
          bindConversation
        )
    }
  }
  private def invokeProvider(
      provider: String,
      toolDefs: List[AgentToolSpec],
      onEvent: TabAiEvent => Unit,
      messages: js.Any,
      cancellation: AgentCancellation,
      keyId: String,
      sessionId: String,
      contextIds: List[String],
      contextMode: String,
      conversation: String,
      promptMode: String,
      hostTabId: Int,
      bindConversation: Boolean
  ): Future[AIMessage] = {
    val messageJson = messagesAsOpenAi(messages)
    val toolsJson   = AgentBody.toolsJson(toolDefs)
    val body        = AgentBody.body(provider, messageJson, toolsJson)
    ValidatedModelRuntime
      .run(
        ModelRunRequest(
          provider = provider,
          bodyJson = body,
          keyId = keyId,
          sessionId = sessionId,
          messageIds = messageIdsFor(messageJson, contextIds),
          contextMode = contextMode,
          conversation = conversation,
          promptMode = promptMode,
          inject = true,
          hostTabId = hostTabId,
          bindConversation = bindConversation
        ),
        onEvent,
        cancellation
      )
      .map { result =>
        if (!result.ok) {
          val text = Option(result.error).filter(_.nonEmpty).getOrElse("Tab AI round failed")
          throw (if (result.protocolFailure) new MarkerParseFailed(text) else new RuntimeException(text))
        }
        toAIMessage(result)
      }
  }

  /** 原生首轮的「假模型调用」：只把事件按普通回合的节奏发一遍，然后返回模型已经给出的那条 AIMessage。 */
  private def seededAIMessage(round: SeededRound, onEvent: TabAiEvent => Unit): AIMessage = {
    onEvent(TabAiEvent.StepStarted)
    if (round.thinking.nonEmpty) onEvent(TabAiEvent.Thinking(round.thinking))
    if (round.toolCalls.isEmpty && round.content.nonEmpty) onEvent(TabAiEvent.Content(round.content))
    onEvent(TabAiEvent.StepFinished(round.toolCalls.nonEmpty))
    val fields = JsBridge.obj()
    if (round.thinking.nonEmpty) fields.reasoning_content = round.thinking
    if (round.toolCalls.nonEmpty) {
      fields.content = null
      fields.tool_calls = js.Array(
        round.toolCalls.zipWithIndex.map { case (call, index) =>
          JsBridge.obj(
            "id"   -> (s"call_${tabId()}_$index"),
            "name" -> (call.name),
            "args" -> (parseArguments(call.arguments))
          )
        }*
      )
    } else {
      fields.content = round.content
    }
    new AIMessage(fields)
  }

  /**
   * 与 body 的 messages 一一对应的会话消息 id。
   *
   * `contextIds` 是这次调用"开头那批消息"对应的会话 id（场景 system 与工具轮合成消息为空串）；LangGraph 只会往数组尾部追加， 所以短出来的部分补空串即可。长度对不上（数组被改写过）时返回
   * Nil，后台就退回全量。
   */
  private def messageIdsFor(messageJson: js.Array[js.Any], contextIds: List[String]): List[String] =
    if (contextIds.length <= messageJson.length) contextIds ++ List.fill(messageJson.length - contextIds.length)("")
    else Nil

  /** 只保留调用方声明的工具集里的 def。 */
  private def boundToolDefsFromJs(tools: js.Any, declared: List[AgentToolSpec]): List[AgentToolSpec] = {
    val arr = JsBridge.asArray(tools)
    declared.filter(tool =>
      arr.toList.exists(raw =>
        JsBridge.stringField(raw, "name").getOrElse("") == tool.name
      )
    )
  }
  private def messagesAsOpenAi(messages: js.Any): js.Array[js.Any]                                   = {
    val arr = JsBridge.asTypedArray[LangChainBaseMessage](messages)
    js.Array(arr.toList.map { message =>
      val role = message._getType() match {
        case "human"  => "user"
        case "ai"     => "assistant"
        case "tool"   => "tool"
        case "system" => "system"
        case _        => "user"
      }
      val base = JsBridge.obj("role" -> (role), "content" -> (messageContent(message.content)))
      message.tool_call_id.foreach(id => base.tool_call_id = id)
      message.name.foreach(name => base.name = name)
      if (role == "assistant") {
        val calls = message.tool_calls.getOrElse(js.Array[js.Any]()).toList.flatMap { raw =>
          val call = JsBridge.asObject(raw)
          val name = JsBridge.undefinedString(call.name).getOrElse("")
          if (name.nonEmpty) {
            Some(JsBridge.obj(
              "id"       -> (JsBridge.undefinedString(call.id).getOrElse(s"call_${tabId()}")),
              "type"     -> ("function"),
              "function" -> (JsBridge.obj(
                "name"      -> (name),
                "arguments" -> (js.JSON.stringify(JsBridge.undefinedAny(call.args).getOrElse(JsBridge.obj())))
              ))
            ))
          } else None
        }
        if (calls.nonEmpty) base.tool_calls = js.Array(calls*)
      }
      base
    }*)
  }
  private def messageContent(content: js.Any): String                                                =
    if (js.isUndefined(content) || JsBridge.asObject(content) == null) {
      ""
    } else if (js.typeOf(content) == "string") {
      JsBridge.asString(content)
    } else {
      val parts = JsBridge.asArray(content)
      parts.toList.flatMap { block =>
        JsBridge.stringField(block, "type") match {
          case Some("text") => Option(JsBridge.stringField(block, "text").getOrElse(""))
          case _            => None
        }
      }.mkString("\n")
    }
  private def toAIMessage(result: TabAiResult): AIMessage                                            = {
    val fields = JsBridge.obj()
    if (result.thinking.nonEmpty) {
      fields.reasoning_content = result.thinking
    }
    if (result.toolCalls.nonEmpty) {
      fields.content = null
      fields.tool_calls = js.Array(
        result.toolCalls.zipWithIndex.map { case (call, index) =>
          JsBridge.obj(
            "id"   -> (s"call_${tabId()}_$index"),
            "name" -> (call.name),
            "args" -> (parseArguments(call.arguments))
          )
        }*
      )
    } else {
      fields.content = result.content
    }
    new AIMessage(fields)
  }
  private def parseArguments(arguments: String): js.Any                                              =
    try {
      js.JSON.parse(arguments)
    } catch {
      case _: Throwable => JsBridge.obj()
    }
  private def stringifyArgs(input: js.Any): String                                                   =
    if (js.isUndefined(input) || input == null) "{}"
    else {
      try {
        val text = js.JSON.stringify(input)
        if (js.isUndefined(text) || text == null) "{}" else JsBridge.asString(text)
      } catch {
        case _: Throwable => "{}"
      }
    }
  private def resultText(result: js.Any): String                                                     =
    if (js.isUndefined(result) || result == null) ""
    else if (js.typeOf(result) == "string") JsBridge.asString(result)
    else {
      try {
        val text = js.JSON.stringify(result)
        if (js.isUndefined(text) || text == null) result.toString else JsBridge.asString(text)
      } catch {
        case _: Throwable => result.toString
      }
    }
  private def tabId(): String                                                                        =
    s"${js.Date.now()}-${Math.round(Math.random() * 1e9)}"
}
