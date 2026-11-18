package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import com.just3bowls.api.chat.AgentModePolicy
import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.{AIMessage, HumanMessage, LangChainBaseMessage, ToolMessage}
import com.just3bowls.model.provider.ProviderChatSettings
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js

/** Chat/Agent 运行入口；持久化仍由 ChatStore 负责。 */
object ChatRunner {
  final case class NativeTurnSpec(
      runId: String,
      sessionId: String,
      provider: String,
      origin: String,
      userText: String,
      treatAsRegenerate: Boolean,
      useBrowserTools: Boolean,
      hostTabId: Int
  )

  def runAssistantVariant(
      sessionId: String,
      user: ChatMessage,
      provider: String,
      onEvent: TabAiEvent => Unit,
      registerCancel: (() => Unit) => Unit,
      maxSteps: Int,
      useBrowserTools: Boolean = false,
      contextMode: String = ProviderChatSettings.Delta,
      conversation: String = "",
      /** 正文投递方式（`inline` / `file`）：由插件页按 `/provider` 的本次设置给出。 */
      promptMode: String = ProviderChatSettings.Inline,
      /** 插件页给这条新回答留的占位 id：落库复用同一个 id（同 `runChat`）。 */
      assistantId: Option[String] = None,
      /** Existing top-level Attempts and recoveries, preserved while the selected run is executing. */
      attempts: List[ChatAttempt] = Nil,
      activeAttemptId: String = "",
      agentMode: String = AgentModePolicy.React,
      requestedMode: String = AgentModePolicy.React,
      modeReason: String = "retry",
      initialProtocolRepair: Option[String] = None
  ): Future[ChatMessage] =
    ChatStore.getMessagePath(user.id).flatMap { context =>
      val cancellation = new AgentCancellation(() => TabAiExecutor.cancelRunning(provider))
      val collector    = new AgentProcessCollector(onEvent)
      registerCancel(() => cancellation.cancel())
      val answerId     = assistantId.getOrElse(ChatStore.nextId())
      // 回答的 createdAt 严格晚于它挂的那条 user（同毫秒并列会让插件页选错叶子）。
      val answerAt     = ChatStore.childCreatedAt(Some(user.createdAt))
      browserContextOf(sessionId).flatMap { browserContext =>
        LocalAgentRunner
          .run(
            provider,
            messagesToLangChain(context),
            collector.onEvent,
            maxSteps,
            cancellation,
            browserContext,
            useBrowserTools,
            contextIDs = contextIdsOf(context),
            contextMode = contextMode,
            conversation = conversation,
            promptMode = promptMode,
            // 重试 = 换一段新对话重问：新 URL 也属于这条会话，成功失败都要绑住（否则胶囊停在 Unassigned）。
            bindConversation = true,
            tabRefs = user.tabRefs,
            mode = AgentModePolicy.normalize(agentMode),
            // Retry/Regenerate re-runs the mode already recorded on this turn.
            allowHandoff = false,
            initialProtocolRepair = initialProtocolRepair
          )
          .flatMap { outcome =>
            val assistant = ChatMessage(
              id = answerId,
              sessionId = sessionId,
              parentId = Some(user.id),
              role = "assistant",
              content = outcome.content,
              thinking = "",
              status = "success",
              toolCalls = toolCallsJson(outcome.toolCalls),
              toolCallId = "",
              toolResults = "",
              provider = provider,
              mergedFrom = Nil,
              createdAt = answerAt,
              updatedAt = answerAt,
              mode = user.mode,
              steps = collector.stepsJson,
              attempts = attempts,
              activeAttemptId = activeAttemptId,
              requestedMode = AgentModePolicy.normalize(requestedMode),
              effectiveMode = AgentModePolicy.normalize(agentMode),
              modePath = routePathJson(requestedMode, agentMode, modeReason),
              agentRunId = s"agent-${user.id}",
              browserTools = useBrowserTools
            )
            ChatStore.addMessage(assistant).map(_ => assistant)
          }
          .recoverWith { case e =>
            val errorAssistant = ChatMessage(
              id = answerId,
              sessionId = sessionId,
              parentId = Some(user.id),
              role = "assistant",
              content = Option(e.getMessage).getOrElse("Agent failed"),
              thinking = "",
              status = "error",
              toolCalls = "",
              toolCallId = "",
              toolResults = "",
              provider = provider,
              mergedFrom = Nil,
              createdAt = answerAt,
              updatedAt = answerAt,
              mode = user.mode,
              steps = collector.stepsJson,
              attempts = attempts,
              activeAttemptId = activeAttemptId,
              requestedMode = AgentModePolicy.normalize(requestedMode),
              effectiveMode = AgentModePolicy.normalize(agentMode),
              modePath = routePathJson(requestedMode, agentMode, modeReason),
              agentRunId = s"agent-${user.id}",
              browserTools = useBrowserTools
            )
            ChatStore.addMessage(errorAssistant).flatMap(_ => Future.failed(e))
          }
      }
    }

  def runChat(
      sessionId: String,
      userContent: String,
      provider: String,
      parentId: Option[String],
      onEvent: TabAiEvent => Unit,
      registerCancel: (() => Unit) => Unit = _ => (),
      mode: String = "chat",
      maxSteps: Int = 10,
      useBrowserTools: Boolean = false,
      contextMode: String = ProviderChatSettings.Delta,
      conversation: String = "",
      /** 正文投递方式（`inline` / `file`）：由插件页按 `/provider` 的本次设置给出。 */
      promptMode: String = ProviderChatSettings.Inline,
      /**
       * 调用方（插件页）已经渲染成占位的 id：落库复用同一个 id。
       *
       * 一条消息只该有一个身份 —— 占位与落库用两个 id 时，`undo` 会把"还没落库的占位"当成真消息收进 undo 栈，`/redo` 再原样写回存储， 于是同一句话在树里出现两份。
       */
      userId: Option[String] = None,
      assistantId: Option[String] = None,
      /** User-referenced tabs attached to this run only. */
      tabRefs: List[BrowserTabRef] = Nil,
      /** Hidden persistent-memory context for this run only. */
      memoryContext: String = "",
      /** 本轮实际执行的 Agent Mode。 */
      agentMode: String = AgentModePolicy.React,
      /** 当前 Session 请求的模式（ReAct / Plan）。 */
      requestedMode: String = AgentModePolicy.React,
      /** 路由原因，写进 modePath 供过程面板解释。 */
      modeReason: String = ""
  ): Future[ChatMessage] = {
    val now      = js.Date.now()
    val user     = ChatMessage(
      id = userId.getOrElse(ChatStore.nextId()),
      sessionId = sessionId,
      parentId = parentId,
      role = "user",
      content = userContent,
      thinking = "",
      status = "success",
      toolCalls = "",
      toolCallId = "",
      toolResults = "",
      provider = provider,
      mergedFrom = Nil,
      createdAt = now,
      updatedAt = now,
      mode = mode,
      requestedMode = AgentModePolicy.normalize(requestedMode),
      effectiveMode = AgentModePolicy.normalize(agentMode),
      modePath = routePathJson(requestedMode, agentMode, modeReason),
      tabRefs = tabRefs,
      browserTools = useBrowserTools
    )
    val contextF =
      parentId match {
        case Some(id) => ChatStore.getMessagePath(id)
        case None     => Future.successful(Nil)
      }
    for {
      _              <- ChatStore.nameSessionIfPlaceholder(sessionId, userContent)
      context        <- contextF
      browserContext <- browserContextOf(sessionId)
      assistant      <- runWithContext(
        sessionId,
        browserContext,
        context,
        user,
        provider,
        onEvent,
        registerCancel,
        maxSteps,
        useBrowserTools,
        contextMode,
        conversation,
        promptMode,
        assistantId.getOrElse(ChatStore.nextId()),
        tabRefs = tabRefs,
        memoryContext = memoryContext,
        agentMode = agentMode,
        requestedMode = requestedMode,
        modeReason = modeReason
      )
    } yield assistant
  }

  /** browser_* 工具按 chat session 维护常驻 tab；组名用 session 标题，读不到时留空。 */

  private[chat] def browserContextOf(sessionId: String): Future[AgentBrowserContext] =
    if (sessionId.isEmpty) Future.successful(AgentBrowserContext.empty)
    else
      ChatStore.listSessions()
        .map(sessions => AgentBrowserContext(sessionId, sessions.find(_.id == sessionId).map(_.title).getOrElse("")))
        .recover { case _ => AgentBrowserContext(sessionId, "") }

  private[chat] def runWithContext(
      sessionId: String,
      browserContext: AgentBrowserContext,
      context: List[ChatMessage],
      user: ChatMessage,
      provider: String,
      onEvent: TabAiEvent => Unit,
      registerCancel: (() => Unit) => Unit,
      maxSteps: Int,
      useBrowserTools: Boolean,
      contextMode: String,
      conversation: String,
      promptMode: String,
      assistantId: String,
      tabRefs: List[BrowserTabRef],
      memoryContext: String,
      agentMode: String,
      requestedMode: String,
      modeReason: String
  ): Future[ChatMessage] = {
    val cancellation = new AgentCancellation(() => TabAiExecutor.cancelRunning(provider))
    val collector    = new AgentProcessCollector(onEvent)
    registerCancel(() => cancellation.cancel())
    val path         = context :+ user
    // 回答（成功或失败）的 createdAt 严格晚于它挂的那条 user。
    val answerAt     = ChatStore.childCreatedAt(Some(user.createdAt))
    for {
      _         <- ChatStore.addMessage(user)
      agentMsgs <- Future.successful(messagesToLangChain(path))
      outcome   <- LocalAgentRunner
        .run(
          provider,
          agentMsgs,
          collector.onEvent,
          maxSteps,
          cancellation,
          browserContext,
          useBrowserTools,
          contextIDs = contextIdsOf(path),
          contextMode = contextMode,
          conversation = conversation,
          promptMode = promptMode,
          // 插件页 chat 用的这条 provider 对话就是本会话的对话：New Chat 出来的新 URL 立刻绑住。
          bindConversation = true,
          tabRefs = tabRefs,
          memoryContext = memoryContext,
          mode = AgentModePolicy.normalize(agentMode),
          allowHandoff = false
        )
        .recoverWith { case e =>
          val errorAssistant = ChatMessage(
            id = assistantId,
            sessionId = sessionId,
            parentId = Some(user.id),
            role = "assistant",
            content = Option(e.getMessage).getOrElse("Agent failed"),
            thinking = "",
            status = "error",
            toolCalls = "",
            toolCallId = "",
            toolResults = "",
            provider = provider,
            mergedFrom = Nil,
            createdAt = answerAt,
            updatedAt = answerAt,
            mode = user.mode,
            steps = collector.stepsJson,
            requestedMode = AgentModePolicy.normalize(requestedMode),
            effectiveMode = AgentModePolicy.normalize(agentMode),
            modePath = routePathJson(requestedMode, agentMode, modeReason),
            agentRunId = s"agent-${user.id}",
            browserTools = useBrowserTools
          )
          ChatStore.addMessage(errorAssistant).flatMap(_ => Future.failed(e))
        }
      assistantMode = outcome.handoffs.lastOption.map(_.mode).getOrElse(AgentModePolicy.normalize(agentMode))
      assistant = ChatMessage(
        id = assistantId,
        sessionId = sessionId,
        parentId = Some(user.id),
        role = "assistant",
        content = outcome.content,
        thinking = "",
        status = "success",
        toolCalls = toolCallsJson(outcome.toolCalls),
        toolCallId = "",
        toolResults = "",
        provider = provider,
        mergedFrom = Nil,
        createdAt = answerAt,
        updatedAt = answerAt,
        mode = user.mode,
        steps = collector.stepsJson,
        requestedMode = AgentModePolicy.normalize(requestedMode),
        effectiveMode = assistantMode,
        modePath = routePathJson(requestedMode, assistantMode, modeReason, outcome.handoffs),
        agentRunId = s"agent-${user.id}",
        browserTools = useBrowserTools
      )
      _ <- ChatStore.addMessage(assistant)
    } yield assistant
  }

  /**
   * 与 `messagesToLangChain` 同口径的会话消息 id（该方法会丢掉 system 之类的角色，id 列表必须跟着一起过滤）。
   */

  private val NativeProgressIntervalMs = 600.0

  /**
   * 跑完一条原生轮次的 agent 环：**和插件页 chat 共用 `LocalAgentRunner` + `AgentProcessCollector`**，区别只有两点 —— 首轮由 `firstRound` 喂进来（不再调
   * provider），以及每一轮都驱动 `hostTabId` 指定的那条已存在 tab。
   *
   * `onStarted` 在 user / assistant 落库之后、agent 开跑之前回调；`onProgress` 在进度写库之后回调；`onFinished` 一定被回调一次。
   */
  def runNativeTurn(
      spec: NativeTurnSpec,
      firstRound: LocalAgentModel.SeededRound,
      cancellation: AgentCancellation,
      onStarted: (String, String) => Unit,
      onProgress: () => Unit,
      /** (ok, error, 这次失败是不是 marker 协议类) —— 后两者供 SW 决定要不要把这一轮交回插件页自动重试。 */
      onFinished: (Boolean, String, Boolean) => Unit
  ): Future[Unit] =
    if (spec.sessionId.isEmpty) Future.successful(())
    else
      ChatStore.sessionPathMessages(spec.sessionId).flatMap { path =>
        val now               = js.Date.now()
        val previousAssistant = if (spec.treatAsRegenerate) path.reverse.find(_.role == "assistant") else None
        val (context, user, runningAssistant) = previousAssistant match {
          case Some(previous) =>
            val before  = path.takeWhile(_.id != previous.id)
            val userMsg = before.reverse.find(_.role == "user")
            val running = previous.copy(
              content = "",
              thinking = "",
              status = "running",
              toolCalls = "",
              steps = "",
              provider = spec.provider,
              origin = spec.origin,
              browserTools = spec.useBrowserTools,
              updatedAt = now
            )
            (before, userMsg, running)
          case None           =>
            val userMsg   = ChatMessage(
              id = ChatStore.nextId(),
              sessionId = spec.sessionId,
              parentId = path.lastOption.map(_.id),
              role = "user",
              content = spec.userText,
              thinking = "",
              status = "success",
              toolCalls = "",
              toolCallId = "",
              toolResults = "",
              provider = spec.provider,
              mergedFrom = Nil,
              createdAt = now,
              updatedAt = now,
              origin = spec.origin,
              browserTools = spec.useBrowserTools
            )
            val assistant = ChatMessage(
              id = ChatStore.nextId(),
              sessionId = spec.sessionId,
              parentId = Some(userMsg.id),
              role = "assistant",
              content = "",
              thinking = "",
              status = "running",
              toolCalls = "",
              toolCallId = "",
              toolResults = "",
              provider = spec.provider,
              mergedFrom = Nil,
              createdAt = ChatStore.childCreatedAt(Some(now)),
              updatedAt = now,
              origin = spec.origin,
              browserTools = spec.useBrowserTools
            )
            (path :+ userMsg, Some(userMsg), assistant)
        }
        val persist                           = previousAssistant match {
          case Some(_) => ChatStore.updateMessage(runningAssistant)
          case None    => ChatStore.addMessages(List(user.get, runningAssistant))
        }
        persist
          .flatMap(_ => nameNativeSession(spec))
          .flatMap { _ =>
            onStarted(user.map(_.id).getOrElse(""), runningAssistant.id)
            runNativeAgent(spec, context, runningAssistant, firstRound, cancellation, onProgress)
          }
      }
        .map(_ => onFinished(true, "", false))
        .recover { case e =>
          onFinished(false, Option(e.getMessage).getOrElse("Native agent failed"), MarkerParseFailed.isMarkerFailure(e))
        }

  private[chat] def nameNativeSession(spec: NativeTurnSpec): Future[Unit] =
    ChatStore.nameSessionIfPlaceholder(spec.sessionId, spec.userText)

  private[chat] def runNativeAgent(
      spec: NativeTurnSpec,
      context: List[ChatMessage],
      running: ChatMessage,
      firstRound: LocalAgentModel.SeededRound,
      cancellation: AgentCancellation,
      onProgress: () => Unit
  ): Future[LocalAgentOutcome] = {
    var collector: AgentProcessCollector              = null
    val contentBuf                                    = new StringBuilder
    val thinkingBuf                                   = new StringBuilder
    var lastWrite                                     = 0.0
    var scheduled                                     = false
    // 最终回答写库之后，迟到的节流定时器不许再把 status 打回 running（实测踩到过：回答已经写完，状态还挂着）。
    var finished                                      = false
    var timer: js.UndefOr[js.timers.SetTimeoutHandle] = js.undefined
    def writeProgress(): Unit                         =
      if (!finished) {
        lastWrite = js.Date.now()
        ChatStore.updateMessage(
          running.copy(
            content = contentBuf.toString,
            thinking = thinkingBuf.toString,
            status = "running",
            steps = collector.stepsJson,
            updatedAt = lastWrite
          )
        ).foreach(_ => onProgress())
      }
    def stopProgress(): Unit                          = {
      finished = true
      timer.foreach(js.timers.clearTimeout)
      timer = js.undefined
    }
    def scheduleProgress(): Unit                      =
      if (!finished) {
        val now = js.Date.now()
        if (now - lastWrite >= NativeProgressIntervalMs) writeProgress()
        else if (!scheduled) {
          scheduled = true
          timer = js.timers.setTimeout(NativeProgressIntervalMs - (now - lastWrite)) {
            scheduled = false
            writeProgress()
          }
        }
      }
    collector = new AgentProcessCollector(event =>
      event match {
        case TabAiEvent.Content(delta)             => contentBuf.append(delta)
        case TabAiEvent.ContentReset               => contentBuf.clear()
        case TabAiEvent.Thinking(delta)            => thinkingBuf.append(delta)
        case TabAiEvent.InternalRecoveryStarted(_) =>
          contentBuf.clear()
          thinkingBuf.clear()
        case _                                     => ()
      }
      scheduleProgress()
    )
    ChatRunner.browserContextOf(spec.sessionId).flatMap { browserContext =>
      LocalAgentRunner
        .run(
          spec.provider,
          messagesToLangChain(context),
          collector.onEvent,
          cancellation = cancellation,
          browserContext = browserContext,
          useBrowserTools = spec.useBrowserTools,
          contextIDs = contextIdsOf(context),
          // 网页原生这一侧不读插件页的 `/context`：后续工具轮一律按增量走（首投 / 水位线对不上时 `resolve` 自己会退回整份模版）。
          contextMode = ProviderChatSettings.Delta,
          conversation = "reuse",
          firstRound = Some(firstRound),
          hostTabId = spec.hostTabId
        )
        .flatMap { outcome =>
          stopProgress()
          ChatStore.updateMessage(
            running.copy(
              content = outcome.content,
              thinking = "",
              status = "success",
              toolCalls = toolCallsJson(outcome.toolCalls),
              steps = collector.stepsJson,
              updatedAt = js.Date.now()
            )
          ).map(_ => outcome)
        }
        .recoverWith { case e =>
          stopProgress()
          ChatStore.updateMessage(
            running.copy(
              content = Option(e.getMessage).getOrElse("Native agent failed"),
              status = "error",
              steps = collector.stepsJson,
              updatedAt = js.Date.now()
            )
          ).flatMap(_ => Future.failed(e))
        }
    }
  }

  private[chat] def contextIdsOf(messages: List[ChatMessage]): List[String]                          =
    messages.filter(message => message.role == "tool" || message.role == "assistant" || message.role == "user").map(
      _.id
    )
  private[chat] def messagesToLangChain(messages: List[ChatMessage]): js.Array[LangChainBaseMessage] = {
    val items: List[LangChainBaseMessage] = messages.flatMap { message =>
      if (message.role == "tool") {
        Some(new ToolMessage(JsBridge.obj("content" -> (message.content), "tool_call_id" -> (message.toolCallId)))
          .asInstanceOf[LangChainBaseMessage])
      } else if (message.role == "assistant") {
        val calls = parseToolCalls(message.toolCalls).map { call =>
          JsBridge.obj("id" -> (call._1), "name" -> (call._2), "args" -> (parseArgs(call._3)))
        }
        Some(new AIMessage(JsBridge.obj(
          "content"    -> (if (calls.nonEmpty) null else message.content),
          "tool_calls" -> (if (calls.nonEmpty) js.Array(calls*) else js.undefined)
        )).asInstanceOf[LangChainBaseMessage])
      } else if (message.role == "user") {
        Some(new HumanMessage(message.content).asInstanceOf[LangChainBaseMessage])
      } else {
        None
      }
    }
    js.Array(items*)
  }
  private[chat] def toolCallsJson(calls: List[com.just3bowls.model.chat.TabToolCall]): String        =
    js.JSON.stringify(js.Array(calls.map { call =>
      JsBridge.obj("id" -> (ChatStore.nextId()), "name" -> (call.name), "arguments" -> (call.arguments))
    }*))
  private[chat] def parseToolCalls(text: String): List[(String, String, String)]                     =
    if (text.trim.isEmpty) {
      Nil
    } else {
      try {
        JsBridge.asArray(js.JSON.parse(text)).toList.map { call =>
          (
            JsBridge.stringField(call, "id").getOrElse(""),
            JsBridge.stringField(call, "name").getOrElse(""),
            JsBridge.stringField(call, "arguments").getOrElse("")
          )
        }
      } catch {
        case _: Throwable =>
          text.split(",").toList.flatMap { raw =>
            try {
              val call = JsBridge.asObject(js.JSON.parse(raw))
              List(
                (
                  JsBridge.undefinedString(call.id).getOrElse(""),
                  JsBridge.undefinedString(call.name).getOrElse(""),
                  JsBridge.undefinedString(call.arguments).getOrElse("")
                )
              )
            } catch {
              case _: Throwable => Nil
            }
          }
      }
    }
  private[chat] def parseArgs(arguments: String): js.Any                                             =
    try {
      js.JSON.parse(arguments)
    } catch {
      case _: Throwable => JsBridge.obj()
    }

  private[chat] def routePathJson(
      requested: String,
      effective: String,
      reason: String,
      handoffs: List[AgentHandoff] = Nil
  ): String = {
    val req           = AgentModePolicy.normalize(requested)
    val eff           = AgentModePolicy.normalize(effective)
    val at            = js.Date.now()
    val initialReason = Option(reason).getOrElse("")
    val entries       =
      handoffs.headOption match {
        case Some(first) =>
          js.Array(
            JsBridge.obj(
              "mode"      -> (AgentModePolicy.normalize(first.from)),
              "requested" -> (req),
              "reason"    -> (initialReason),
              "at"        -> (at)
            )
          ).concat(js.Array(handoffs.map(step =>
            JsBridge.obj(
              "mode"      -> (AgentModePolicy.normalize(step.mode)),
              "requested" -> (req),
              "reason"    -> (step.reason),
              "at"        -> (at)
            )
          )*))
        case None        =>
          js.Array(JsBridge.obj("mode" -> (eff), "requested" -> (req), "reason" -> (initialReason), "at" -> (at)))
      }
    js.JSON.stringify(entries)
  }

}
