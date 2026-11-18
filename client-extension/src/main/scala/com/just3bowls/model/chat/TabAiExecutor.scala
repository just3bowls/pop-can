package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.provider.*
import com.just3bowls.model.localapi.*

import com.just3bowls.facade.{ChromeAPI, MessageSender, TabAiProgressMessage}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
sealed trait TabAiEvent
object TabAiEvent {

  /** 一个外部模型请求开始；marker 内部 repair 不产生新的 StepStarted。 */
  case object StepStarted extends TabAiEvent

  /** 一个外部模型请求完成；hasToolCalls 表示最终结果是否发起了工具调用。 */
  final case class StepFinished(hasToolCalls: Boolean) extends TabAiEvent

  /** Marker 协议校验失败后的内部恢复；不增加 Step / Turn / Attempt。 */
  final case class InternalRecoveryStarted(reason: String) extends TabAiEvent
  final case class Thinking(delta: String)                 extends TabAiEvent
  final case class Content(delta: String)                  extends TabAiEvent

  /** 清空回答区已流式展示的内容（该轮实际是工具轮，内容不属于最终回答）。 */
  case object ContentReset extends TabAiEvent

  /** 工具开始执行，带模型实际传入的参数。 */
  final case class ToolCall(name: String, arguments: String) extends TabAiEvent

  /** 工具执行结束，带完整返回内容与耗时。 */
  final case class ToolFinished(name: String, ok: Boolean, result: String, durationMs: Double) extends TabAiEvent

  /** 过程快照（steps JSON），由 AgentProcessCollector 发出，UI 直接赋值渲染。 */
  final case class ProcessUpdated(stepsJson: String) extends TabAiEvent

  /** 一个模型回合最终失败（模型调用 / 工具执行 / 降级作答都没成）：把当前 step 收尾成 error，别一直挂着 running。 */
  final case class StepFailed(message: String) extends TabAiEvent

  /** 可人工 Retry 的失败 Turn：保存恢复到下一次模型调用所需的现场。 */
  final case class TurnFailed(
      provider: String,
      keyId: String,
      messagesJson: String,
      toolNames: List[String],
      message: String
  ) extends TabAiEvent

  /** 后台的一次性提示（例如"这家 provider 不支持注入，本轮按整段模版发送"）。 */
  final case class Notice(text: String)            extends TabAiEvent
  final case class Progress(progress: RunProgress) extends TabAiEvent

  /** Human-in-the-loop 输入，例如 Plan 审批期间对计划的修改请求。 */
  final case class HumanInput(label: String, text: String) extends TabAiEvent

  /** LangGraph interrupt 抛出的计划；UI 处理后通过 AgentPlanApproval.resolve 恢复。 */
  final case class PlanProposed(planId: String, plan: String) extends TabAiEvent
}
final case class TabToolCall(
    name: String,
    arguments: String
)
final case class TabAiResult(
    ok: Boolean,
    content: String,
    thinking: String,
    toolCalls: List[TabToolCall],
    finishReason: String,
    error: String,
    rawChars: Int = 0,
    gen: String = "",
    /** 失败原因是不是 marker 协议类（上层据此决定要不要自动重试）。 */
    protocolFailure: Boolean = false,
    protocolErrors: List[String] = Nil,
    normalizedText: String = ""
)

/** 让 LangGraph 自定义模型直接执行浏览器页 AI：发送 INPUT_PROMPT 并消费 NORMALIZED_CHUNK。 */
object TabAiExecutor {
  final private case class Pending(
      producer: FinalSseProducer,
      promise: Promise[TabAiResult],
      onEvent: TabAiEvent => Unit,
      provider: String,
      var rawChars: Int = 0,
      var responseStarted: Boolean = false
  )
  private val pending     = scala.collection.mutable.Map.empty[String, Pending]
  private var initialized = false

  /**
   * 已经收尾的 gen：收尾之后仍可能有一两片在途分片。
   *
   * 它们不能再喂给 producer，但**必须回执** —— 回执的含义是"这一轮确实有页面在接"，不是"我把它喂给了 producer"； 漏回执会让 SW 把一次正常的收尾误判成投递失败。
   */
  private val settled                                          = scala.collection.mutable.LinkedHashSet.empty[String]
  private val MaxSettled                                       = 32
  def initialize(registerDisposer: (() => Unit) => Unit): Unit =
    if (!initialized) {
      initialized = true
      val listener: js.Function3[js.Any, MessageSender, js.Function1[js.Any, Unit], js.Any] =
        (message, _, sendResponse) => {
          val dynamic = JsBridge.asObject(message)
          val kind    = asString(dynamic.`type`)
          val id      = asString(dynamic.generationId)
          val handled =
            kind match {
              case "NORMALIZED_CHUNK" =>
                pending.get(id) match {
                  case Some(state) =>
                    val delta = asString(dynamic.contentDelta)
                    val done  = JsBridge.undefinedBoolean(dynamic.done).getOrElse(false)
                    val error = asString(dynamic.error)
                    val chars = JsBridge.undefinedInt(dynamic.rawChars).toOption.getOrElse(0)
                    // 解析器的正文过滤回退了已下发内容：先清空这一轮累积的正文，再接着收。
                    val reset = JsBridge.undefinedBoolean(dynamic.resetContent).getOrElse(false)
                    if (chars > state.rawChars) state.rawChars = chars
                    if (!state.responseStarted) {
                      state.responseStarted = true
                      state.onEvent(TabAiEvent.Progress(RunProgress(
                        RunPhase.LocatingResponse,
                        provider = state.provider
                      )))
                    }
                    if (done && error.isEmpty)
                      state.onEvent(TabAiEvent.Progress(
                        RunProgress(RunPhase.ValidatingResponse, provider = state.provider)
                      ))
                    handleChunk(id, state, delta, done, error, reset)
                    true
                  // 收尾之后的在途分片：只回执（页面确实接住了这一轮），不再喂 producer。
                  case None        => settled.contains(id)
                }
              case "TAB_AI_NOTICE"    =>
                pending.get(id) match {
                  case Some(state) =>
                    val text = asString(dynamic.text)
                    if (text.nonEmpty) state.onEvent(TabAiEvent.Notice(text))
                    true
                  case None        => settled.contains(id)
                }
              case "TAB_AI_PROGRESS"  =>
                pending.get(id) match {
                  case Some(state) =>
                    val progress = dynamic.asInstanceOf[TabAiProgressMessage]
                    val phase    = progress.phase.toOption.flatMap(RunPhase.fromWire)
                    phase.foreach(value =>
                      state.onEvent(TabAiEvent.Progress(
                        RunProgress(
                          phase = value,
                          provider = progress.provider.toOption.getOrElse(""),
                          detail = progress.detail.toOption.getOrElse(""),
                          attempt = progress.attempt.toOption.getOrElse(0),
                          recoveryIndex = progress.recoveryIndex.toOption.getOrElse(0),
                          recoveryBudget = progress.recoveryBudget.toOption.getOrElse(0)
                        )
                      ))
                    )
                    true
                  case None        => settled.contains(id)
                }
              case _                  => false
            }
          // 回执必须走 `sendResponse`：Chrome 只认它，listener 的返回值不是响应。
          // 不是这一页在跑的 gen（页面刷新过 / 这一轮本来就不属于这里）就**不回执**，
          // 让 SW 那边看出"没人接住"，由它按投递失败收口。
          if (handled) sendResponse(consumedAck(id))
          js.undefined
        }
      ChromeAPI.runtime.onMessage.addListener(listener)
      registerDisposer(() => ChromeAPI.runtime.onMessage.removeListener(listener))
    }
  def run(
      provider: String,
      prompt: String,
      onEvent: TabAiEvent => Unit = (_: TabAiEvent) => (),
      keyId: String = ApiKeyStore.SystemApiKeyId,
      /** 页面侧等待一次模型调用的绝对上限；与 `TabModelCall` / `LocalAgentModel` 同一个口径（后台的流等待是 10 分钟）。 */
      timeoutMs: Int = 720000,
      bodyJson: String = "",
      messageIds: List[String] = Nil,
      sessionId: String = "",
      contextMode: String = "",
      protocolRepair: String = "",
      conversation: String = "",
      inject: Boolean = false,
      hostTabId: Int = 0,
      /** `file` 模式：先把这份 body 造成 att.name 附件上传，再打 prompt。 */
      attachment: Option[PromptAttachment] = None,
      /** 正文投递模式（`inline` / `file`）：后台据此决定全量那一投走注入还是附件。 */
      promptMode: String = "",
      /** 这一轮用过的对话要不要绑给会话（插件页 Chat / Retry 为 true；网页原生轮为 false）。 */
      bindConversation: Boolean = false,
      /** Exact LangChain tool-surface revision used by this provider conversation. */
      toolProfileId: String = "",
      /** 把内部 generation id 回传给上层，供取消和精确清理。 */
      onTaskStarted: String => Unit = _ => ()
  ): Future[TabAiResult] = {
    val taskId = nextTaskId()
    onTaskStarted(taskId)
    val p      = Promise[TabAiResult]()
    pending.update(
      taskId,
      Pending(
        producer = new FinalSseProducer(taskId, provider),
        promise = p,
        onEvent = onEvent,
        provider = provider
      )
    )
    // 热路径只取这一家的运行时配置（缓存命中，不再把全部 provider 文档 + 整库反序列化一遍）。
    ParserProviderLocal.getProvider(provider).flatMap { local =>
      val inputSelector = local.map(_.inputSelector).getOrElse("")
      val message       = Msg.inputPrompt(
        provider = provider,
        prompt = prompt,
        inputXPath = inputSelector,
        generationId = taskId,
        stream = false,
        keyId = keyId,
        localTaskKind = "",
        bodyJson = bodyJson,
        messageIds = messageIds,
        sessionId = sessionId,
        contextMode = contextMode,
        protocolRepair = protocolRepair,
        conversation = conversation,
        inject = inject,
        hostTabId = hostTabId,
        attachmentName = attachment.map(_.name).getOrElse(""),
        attachmentBody = attachment.map(_.content).getOrElse(""),
        promptMode = promptMode,
        bindConversation = bindConversation,
        toolProfileId = toolProfileId
      )
      val jsPromise     = ChromeAPI.runtime
        .sendMessage(message)
        .toFuture
      jsPromise.foreach { response =>
        val ok = JsBridge.booleanField(response, "ok").getOrElse(false)
        if (!ok) {
          // 后台失败时把原因写在 content 里（PromptResponse 没有 error 字段），别再兜底成一句看不出原因的话。
          val error = JsBridge.stringField(response, "content")
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse("Browser task failed")
          completeError(taskId, error)
        }
      }
      jsPromise.recover { case e =>
        completeError(taskId, e.getMessage)
      }
      // 兜底：后台任务卡住时不能无限等待。
      js.timers.setTimeout(timeoutMs) {
        pending.remove(taskId).foreach { state =>
          settle(taskId)
          if (!state.promise.isCompleted) {
            cancelTask(taskId, provider)
            state.promise.failure(new RuntimeException("Tab AI task timed out"))
          }
        }
      }
      p.future
    }.recover { case e =>
      completeError(taskId, e.getMessage)
      throw e
    }
  }
  def cancel(taskId: String, provider: String): Unit           =
    cancelTask(taskId, provider)

  /** 中止某个 provider 正在进行的页 AI 任务。 */
  def cancelRunning(provider: String): Unit                   =
    pending.toList.filter(_._2.provider == provider).foreach { case (taskId, _) =>
      cancelTask(taskId, provider)
      completeError(taskId, "Task canceled")
    }
  private def handleChunk(
      id: String,
      state: Pending,
      delta: String,
      done: Boolean,
      error: String,
      reset: Boolean
  ): Unit =
    try {
      if (error.nonEmpty) {
        completeError(id, error)
      } else {
        if (reset) {
          // 过滤回退：producer 与页面累积的正文一起清空，再从这份内容重新开始。
          state.producer.reset()
          state.onEvent(TabAiEvent.ContentReset)
        }
        val produced = state.producer.feed(delta)
        produced.foreach(text => emitSseEvents(text, state.onEvent))
        if (state.producer.failureText.nonEmpty) {
          // `failureText` 只可能来自 marker 校验（`FinalSseProducer.strictResult` 的 Left）。
          completeError(
            id,
            state.producer.failureText,
            protocolFailure = true,
            protocolErrors = state.producer.allProtocolErrors,
            normalizedText = state.producer.normalizedText
          )
        } else if (done) {
          state.producer.finalJson() match {
            case Right(json)   => completeSuccess(id, json)
            case Left(message) =>
              completeError(
                id,
                message,
                protocolFailure = true,
                protocolErrors = state.producer.allProtocolErrors,
                normalizedText = state.producer.normalizedText
              )
          }
        }
      }
    } catch {
      // 兜底：任何解析/回调异常都不能让 Promise 既不 resolve 也不 reject。
      case e: Throwable => completeError(id, Option(e.getMessage).getOrElse(e.toString))
    }
  private def completeSuccess(id: String, json: String): Unit =
    pending.remove(id).foreach { state =>
      settle(id)
      if (!state.promise.isCompleted) {
        try {
          val raw     = JsBridge.asObject(js.JSON.parse(json))
          val choices = JsBridge.undefinedArrayAny(raw.choices).toOption
            .filter(_ != null)
            .getOrElse(js.Array[js.Any]())
          if (choices.isEmpty) completeError(id, "Browser response contained no choices")
          else {
            val choice   = choices(0)
            val message  = orEmptyObject(JsBridge.field(choice, "message"))
            // 工具轮的 content 是 JSON null，必须归一成空串，否则会在下游触发空指针。
            val content  = asString(JsBridge.field(message, "content"))
            val thinking = asString(JsBridge.field(message, "reasoning_content"))
            val calls    = JsBridge.undefinedArrayAny(JsBridge.field(message, "tool_calls"))
              .filter(_ != null)
              .map(_.toList.filter(call => call != null && !js.isUndefined(call)))
              .getOrElse(Nil)
              .map { call =>
                val function = orEmptyObject(JsBridge.field(call, "function"))
                TabToolCall(
                  name = asString(JsBridge.field(function, "name")),
                  arguments = asString(JsBridge.field(function, "arguments"))
                )
              }
            state.promise.success(
              TabAiResult(
                ok = true,
                content = content,
                thinking = thinking,
                toolCalls = calls,
                finishReason =
                  Option(asString(JsBridge.field(choice, "finish_reason"))).filter(_.nonEmpty).getOrElse("stop"),
                error = "",
                rawChars = state.rawChars,
                gen = id,
                normalizedText = state.producer.normalizedText
              )
            )
          }
        } catch {
          case e: Throwable =>
            completeError(id, s"Failed to parse tab AI result: ${Option(e.getMessage).getOrElse(e.toString)}")
        }
      }
    }
  private def completeError(
      id: String,
      error: String,
      protocolFailure: Boolean = false,
      protocolErrors: List[String] = Nil,
      normalizedText: String = ""
  ): Unit =
    pending.remove(id).foreach { state =>
      settle(id)
      if (!state.promise.isCompleted) {
        val text = Option(error).filter(_.trim.nonEmpty).getOrElse("Browser task failed")
        state.promise.success(
          TabAiResult(
            ok = false,
            content = "",
            thinking = "",
            toolCalls = Nil,
            finishReason = "error",
            error = text,
            gen = id,
            protocolFailure = protocolFailure,
            protocolErrors = protocolErrors,
            normalizedText = normalizedText
          )
        )
      }
    }

  /** JSON 里的 null 与 undefined 一律当作空串：Scala.js 只把 undefined 视为空。 */
  private def asString(value: js.Any): String =
    JsBridge.undefinedString(value).toOption.filter(_ != null).getOrElse("")

  /**
   * 投递回执：SW 侧的 `deliverToPage` 只看 `consumed`，用来区分"这一轮有页面在接"和"没人接住"。
   *
   * 只有还挂着这个 gen 的页面才回执；其它页面（含 content script 与旧版页面）一律返回 `undefined`， 不会把别人的回执抢走。
   */
  private def consumedAck(id: String): js.Any =
    JsBridge.obj("consumed" -> (true), "generationId" -> (id))

  /** 记下已经收尾的 gen（有界，只为给在途分片继续回执）。 */
  private def settle(id: String): Unit                                          =
    if (id.nonEmpty) {
      settled.remove(id)
      settled.add(id)
      while (settled.size > MaxSettled) settled.remove(settled.head)
    }
  private def orEmptyObject(value: js.Any): js.Any                              =
    if (value == null || js.isUndefined(value)) JsBridge.obj() else JsBridge.asObject(value)
  private def cancelTask(taskId: String, provider: String): Unit                =
    ChromeAPI.runtime.sendMessage(
      JsBridge.obj("type" -> ("CANCEL_TASK"), "generationId" -> (taskId), "provider" -> (provider), "managed" -> (true))
    ).toFuture.recover { case _ => () }.foreach(_ => ())
  private def emitSseEvents(sseText: String, onEvent: TabAiEvent => Unit): Unit =
    sseText.split("\n").foreach { line =>
      val trimmed = line.trim
      if (trimmed.startsWith("data:")) {
        val payload = trimmed.substring(5).trim
        if (payload.nonEmpty && payload != "[DONE]") {
          try {
            val obj     = JsBridge.asObject(js.JSON.parse(payload))
            val choices = JsBridge.undefinedArrayAny(obj.choices).getOrElse(js.Array[js.Any]())
            if (choices.nonEmpty) {
              val delta = JsBridge.asObject(JsBridge.field(choices(0), "delta"))
              val c     = asString(delta.content)
              if (c.nonEmpty) onEvent(TabAiEvent.Content(c))
              val r     = asString(delta.reasoning_content)
              if (r.nonEmpty) onEvent(TabAiEvent.Thinking(r))
            }
          } catch {
            case _: Throwable => ()
          }
        }
      }
    }
  private def nextTaskId(): String                                              =
    s"tabai-${js.Date.now()}-${Math.round(Math.random() * 1e9)}"
}
