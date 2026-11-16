package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.{LlmResponseMetadata, PlanGraphState, PlanToolArguments}
import com.just3bowls.model.provider.*
import com.just3bowls.model.diagnostics.*

import com.just3bowls.api.chat.AgentModePolicy
import com.just3bowls.api.chat.AgentStepPolicy
import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.{
  AIMessage,
  HumanMessage,
  LangChainBaseMessage,
  LangChainRunnable,
  LangGraphCommand,
  LangGraphMemorySaver,
  SystemMessage,
  ToolMessage
}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
final case class LocalAgentOutcome(
    content: String,
    thinking: String,
    toolCalls: List[TabToolCall],
    handoffs: List[AgentHandoff] = Nil
)
final case class AgentHandoff(from: String, mode: String, reason: String)

/** 浏览器侧要用的 chat session 上下文：sessionId 定位常驻 tab，title 用作 tab 组名。 */
final case class AgentBrowserContext(sessionId: String, sessionTitle: String)
object AgentBrowserContext {
  val empty: AgentBrowserContext = AgentBrowserContext("", "")
}

/** 一次 agent 运行的取消令牌；取消后会中断当前页 AI 调用，并在模型/工具边界抛错终止图。 */
final class AgentCancellation(onCancelPage: () => Unit = () => ()) {
  @volatile private var cancelled = false
  def cancel(): Unit              = {
    cancelled = true
    onCancelPage()
  }
  def check(): Unit               =
    if (cancelled) throw new RuntimeException("Task canceled")
  def isCancelled: Boolean        = cancelled
}

/** 用 LangChain createAgent harness 跑 UI 的 Chat agent，并由 LangGraph 管理 Plan / handoff。 */
object LocalAgentRunner {

  /** 一次工具调用的完整记录：撞到步数上限时用它拼"至少基于已有证据"的最终作答。 */
  final private case class ToolEvidence(name: String, arguments: String, result: String)

  /** chat 默认的工具轮数（一轮 = 模型一次 + 工具一次）。 */
  private val DefaultToolRounds  = 8
  private val RequestModeHandoff = AgentToolSpec(
    name = "request_mode_handoff",
    description =
      "Escalate this Auto-mode turn when a different declared execution mode is required to complete the request correctly.",
    kind = ToolKind.Local,
    params = List(
      ToolParam("mode", ToolParamKind.Text, "The required target mode."),
      ToolParam("reason", ToolParamKind.Text, "Why the current mode is insufficient.")
    ),
    terminal = true
  )

  /**
   * LangGraph 的 recursionLimit 数的是 superstep：一轮 ReAct = agent 节点 + tools 节点 = 2 步。
   *
   * 所以工具轮数 N 要传 `2N + 2`（多留一轮余量给"读完就作答"那一步），与研究侧 `max(4, rounds*2+2)` 一致。
   */
  def run(
      provider: String,
      initialMessages: js.Array[LangChainBaseMessage],
      onEvent: TabAiEvent => Unit,
      maxSteps: Int = DefaultToolRounds,
      cancellation: AgentCancellation = new AgentCancellation(),
      browserContext: AgentBrowserContext = AgentBrowserContext.empty,
      useBrowserTools: Boolean = false,
      contextIDs: List[String] = Nil,
      contextMode: String = ProviderChatSettings.Delta,
      conversation: String = "",
      /**
       * 正文投递方式（`inline` / `file`）：由调用方按 `/provider` 的本次设置给出（固定值 / 一次性覆盖都算），缺省 `inline`。
       *
       * 它只影响"要投全量"的那一投怎么送，同一个 run 里所有模型轮共用同一个值。
       */
      promptMode: String = ProviderChatSettings.Inline,
      /**
       * 首轮已经被投递过（网页原生会话：用户自己发出、SW 注入并解析）时，把它直接喂给图，不重复调 provider。
       */
      firstRound: Option[LocalAgentModel.SeededRound] = None,
      /** `> 0` = 所有模型轮都直接驱动这个已存在的 tab（原生 host tab），不走 provider tab 分配。 */
      hostTabId: Int = 0,
      /** 这一轮用过的对话要不要绑给会话：chat 与气泡上的 Retry/Regenerate 为 true；网页原生 host 轮为 false。 */
      bindConversation: Boolean = false,
      /** User-selected tabs attached to this run only. */
      tabRefs: List[BrowserTabRef] = Nil,
      /** Hidden recall block for this model run only; never persisted as a ChatMessage. */
      memoryContext: String = "",
      /** ReAct / Plan. Browser tools are an execution capability, not a separate mode. */
      mode: String = AgentModePolicy.React,
      /** Only Auto may escalate while running; explicit / pinned modes stay locked. */
      allowHandoff: Boolean = false,
      /** Transient repair directive used by the first provider call of a native auto-retry. */
      initialProtocolRepair: Option[String] = None
  ): Future[LocalAgentOutcome] = {
    val requestedMode                                                    = AgentModePolicy.normalize(mode)
    val repairState                                                      =
      initialProtocolRepair.map(value => new LocalAgentModel.RepairState(Some(value)))
    def runPlanMode(): Future[LocalAgentOutcome]                         =
      runPlanExecute(
        provider,
        initialMessages,
        onEvent,
        maxSteps,
        cancellation,
        browserContext,
        useBrowserTools,
        contextIDs,
        conversation,
        promptMode,
        hostTabId,
        bindConversation,
        tabRefs,
        memoryContext,
        repairState
      )
    def runReactMode(handoffEnabled: Boolean): Future[LocalAgentOutcome] =
      runReact(
        provider,
        initialMessages,
        onEvent,
        maxSteps,
        cancellation,
        browserContext,
        useBrowserTools,
        contextIDs,
        contextMode,
        conversation,
        promptMode,
        firstRound,
        hostTabId,
        bindConversation,
        tabRefs,
        memoryContext,
        handoffEnabled,
        repairState
      )
    if (requestedMode == AgentModePolicy.Plan)
      runPlanMode()
    else
      runReactMode(allowHandoff)
  }

  /**
   * Plan-and-Execute：计划节点用 LangGraph interrupt 等待批准，execute 节点复用 ReAct 子流程。
   *
   * 图本身只使用 LangGraph 的 StateGraph / interrupt / Command / MemorySaver； 这里只把网页 AI 与 UI approval bridge 接进节点。
   */
  private def runPlanExecute(
      provider: String,
      initialMessages: js.Array[LangChainBaseMessage],
      onEvent: TabAiEvent => Unit,
      maxSteps: Int,
      cancellation: AgentCancellation,
      browserContext: AgentBrowserContext,
      useBrowserTools: Boolean,
      contextIDs: List[String],
      conversation: String,
      promptMode: String,
      hostTabId: Int,
      bindConversation: Boolean,
      tabRefs: List[BrowserTabRef],
      memoryContext: String,
      repair: Option[LocalAgentModel.RepairState]
  ): Future[LocalAgentOutcome] = {
    val messageReducer: js.Function2[js.Array[js.Any], js.Array[js.Any], js.Array[js.Any]] =
      (left, right) => left.concat(right)
    val messagesDefault: js.Function0[js.Array[js.Any]]                                    = () => js.Array[js.Any]()
    val stateAnnotation                                               = LangGraph.root(js.Dictionary(
      "messages" -> LangGraph.channel(
        JsBridge.obj("reducer" -> (messageReducer), "default" -> (messagesDefault))
      ),
      "plan"     -> LangGraph.channel(
        JsBridge.obj("default" -> ((() => ""): js.Function0[String]))
      ),
      "approval" -> LangGraph.channel(
        JsBridge.obj("default" -> ((() => null): js.Function0[js.Any]))
      ),
      "outcome"  -> LangGraph.channel(
        JsBridge.obj("default" -> ((() => null): js.Function0[js.Any]))
      )
    ))
    val submitPlan                                                    = AgentToolSpec(
      name = "submit_plan",
      description =
        "Submit the execution plan. Do not answer the user's underlying question; put the complete plan in this tool call.",
      kind = ToolKind.Local,
      params = List(ToolParam("plan", ToolParamKind.Text, "The complete markdown execution plan.")),
      terminal = true
    )
    val plannerSurface                                                = AgentToolSurface(List(submitPlan))
    def plannerMessages(note: String): js.Array[LangChainBaseMessage] = {
      val revision = Option(note).getOrElse("").trim
      val extra    = if (revision.isEmpty) "" else s"\n\nUser revision request: $revision"
      val prompt = new SystemMessage(JsBridge.obj("content" -> (ChatScenario.planRequestPolicy + extra))).asInstanceOf[
        LangChainBaseMessage
      ]
      js.Array(prompt).concat(withScenario(initialMessages, plannerSurface, Nil, memoryContext))
    }
    def generatePlan(note: String): Future[String]                    = {
      cancellation.check()
      val conversationState = new LocalAgentModel.ConversationState(plannerSurface.revision)
      val model             = LocalAgentModel
        .create(
          provider,
          List(submitPlan),
          onEvent,
          cancellation,
          sessionId = browserContext.sessionId,
          contextIds = "" :: contextIDs,
          contextMode = ProviderChatSettings.New,
          conversation = conversation,
          promptMode = promptMode,
          hostTabId = hostTabId,
          bindConversation = bindConversation,
          conversationState = conversationState,
          repair = repair
        )
        .asInstanceOf[LangChainRunnable]
      StructuredAgentRunner
        .run(
          model,
          StructuredAgentSpec(
            toolSpecs = List(submitPlan),
            messages = plannerMessages(note),
            toolRounds = 1,
            validateTerminal = Some { (_: String, args: js.Any) =>
              JsBridge.stringField(args, "plan").map(_.trim).filter(
                _.nonEmpty
              ) match {
                case Some(_) => None
                case None    => Some("submit_plan.plan must be a non-empty markdown plan")
              }
            }
          ),
          onEvent,
          cancellation
        )
        .map { result =>
          val fromTool = result.toolArgs.get("submit_plan").flatMap { arguments =>
            try {
              val args = js.JSON.parse(arguments).asInstanceOf[PlanToolArguments]
              args.plan.toOption.map(_.trim).filter(_.nonEmpty)
            } catch { case _: Throwable => None }
          }
          fromTool.orElse(Option(result.text).map(_.trim).filter(_.nonEmpty)).getOrElse("(no plan generated)")
        }
    }
    val planNode: js.Function1[js.Any, js.Any]                        = (rawState: js.Any) => {
      cancellation.check()
      val state = rawState.asInstanceOf[PlanGraphState]
      val note  = state.approval.toOption
        .filter(value => value != null && !js.isUndefined(value))
        .map(_.note.getOrElse(""))
        .getOrElse("")
      JsBridge.asObject(
        generatePlan(note)
          .map { plan =>
            // 新计划开始新一轮审批：清掉上一次修改请求，静态 interruptBefore 会再次停在 gate 前。
            JsBridge.obj("plan" -> (plan), "approval" -> (null))
          }
          .toJSPromise
      )
    }
    // 真正的暂停由 compile(interruptBefore) 完成；该 gate 在 resume 后只负责继续路由。
    val approvalNode: js.Function1[js.Any, js.Any]                    = (_: js.Any) => JsBridge.obj()
    val executeNode: js.Function1[js.Any, js.Any]                     = (rawState: js.Any) => {
      val state          = rawState.asInstanceOf[PlanGraphState]
      val plan           = state.plan.getOrElse("")
      val note           = state.approval.toOption
        .filter(value => value != null && !js.isUndefined(value))
        .map(_.note.getOrElse(""))
        .getOrElse("")
      val plannerContext =
        new SystemMessage(JsBridge.obj("content" -> (ChatScenario.approvedPlanPolicy(plan, note)))).asInstanceOf[
          LangChainBaseMessage
        ]
      JsBridge.asObject(
        runReact(
          provider,
          js.Array(plannerContext).concat(initialMessages),
          onEvent,
          maxSteps,
          cancellation,
          browserContext,
          useBrowserTools,
          "" :: contextIDs,
          ProviderChatSettings.New,
          conversation,
          promptMode,
          None,
          hostTabId,
          bindConversation,
          tabRefs,
          memoryContext,
          allowHandoff = false,
          repair
        ).map { outcome =>
          val result = JsBridge.obj(
            "content"   -> (outcome.content),
            "thinking"  -> (outcome.thinking),
            "toolCalls" -> (js.Array(outcome.toolCalls.map(call =>
              JsBridge.obj("name" -> (call.name), "arguments" -> (call.arguments))
            )*))
          )
          JsBridge.obj("outcome" -> (result))
        }.toJSPromise
      )
    }
    val approvalRoute: js.Function1[js.Any, js.Any]                   = (rawState: js.Any) => {
      val state  = rawState.asInstanceOf[PlanGraphState]
      val action = state.approval.toOption
        .filter(value => value != null && !js.isUndefined(value))
        .map(_.action.getOrElse("cancel"))
        .getOrElse("cancel")
      action match {
        case "execute" => "execute_node"
        case "modify"  => "plan_node"
        case _         => LangGraph.END
      }
    }
    val graph                                                         = LangGraph
      .graph(stateAnnotation)
      .addNode("plan_node", planNode)
      .addNode("approval_node", approvalNode)
      .addNode("execute_node", executeNode)
      // 初始计划在进入图之前生成；图只负责暂停审批、修改重规划和执行。
      .addEdge(LangGraph.START, "approval_node")
      .addEdge("plan_node", "approval_node")
      .addConditionalEdges(
        "approval_node",
        approvalRoute,
        js.Array[js.Any]("plan_node", "execute_node", LangGraph.END)
      )
      .addEdge("execute_node", LangGraph.END)
      .compile(JsBridge.obj(
        "checkpointer"    -> (new LangGraphMemorySaver),
        "interruptBefore" -> (js.Array[js.Any]("approval_node"))
      ))
    val threadId = s"plan-${js.Date.now()}-${Math.round(Math.random() * 1e9)}"
    val config                                                        = JsBridge.obj(
      "configurable"   -> (JsBridge.obj("thread_id" -> (threadId))),
      "recursionLimit" -> (math.max(12, AgentStepPolicy.reactRecursionLimit(maxSteps) + 6))
    )
    def finish(state: js.Any): Future[LocalAgentOutcome]              = {
      val outcome  = JsBridge.undefinedAny(JsBridge.field(state, "outcome")).toOption
      val approval = Option(JsBridge.field(state, "approval"))
        .filter(value => value != null && !js.isUndefined(value))
        .map(JsBridge.asObject(_))
      val plan     = JsBridge.stringField(state, "plan").getOrElse("").trim
      if (outcome.isEmpty && approval.isEmpty && plan.nonEmpty) {
        cancellation.check()
        val planId = s"$threadId-${Math.round(Math.random() * 1e9)}"
        AgentPlanApproval.request(planId, plan, onEvent).flatMap { decision =>
          if (decision.action == "modify" && decision.note.trim.nonEmpty)
            onEvent(TabAiEvent.HumanInput("Plan revision", decision.note.trim))
          graph
            .invoke(
              new LangGraphCommand(JsBridge.obj(
                "resume" -> (JsBridge.obj("action" -> (decision.action), "note" -> (decision.note))),
                "update" -> (JsBridge.obj("approval" -> (JsBridge.obj(
                  "action" -> (decision.action),
                  "note"   -> (decision.note)
                ))))
              )),
              config
            )
            .toFuture
            .flatMap(raw => finish(JsBridge.asObject(raw)))
        }
      } else
        outcome match {
          case Some(value) =>
            val rawOutcome = JsBridge.asAny(value)
            val calls      =
              JsBridge.undefinedArrayAny(JsBridge.field(rawOutcome, "toolCalls")).toOption.map(_.toList)
                .getOrElse(Nil)
                .map(call =>
                  TabToolCall(
                    JsBridge.stringField(call, "name").getOrElse(""),
                    JsBridge.stringField(call, "arguments").getOrElse("")
                  )
                )
            Future.successful(LocalAgentOutcome(
              content = JsBridge.stringField(rawOutcome, "content").getOrElse(""),
              thinking = JsBridge.stringField(rawOutcome, "thinking").getOrElse(""),
              toolCalls = calls
            ))
          case None        =>
            Future.successful(LocalAgentOutcome("Plan canceled.", "", Nil))
        }
    }
    generatePlan("").flatMap { initialPlan =>
      graph.invoke(
        JsBridge.obj("messages" -> (initialMessages), "plan" -> (initialPlan), "approval" -> (null)).asInstanceOf[
          js.Object
        ],
        config
      ).toFuture.flatMap(raw => finish(JsBridge.asObject(raw)))
    }
  }
  private def runReact(
      provider: String,
      initialMessages: js.Array[LangChainBaseMessage],
      onEvent: TabAiEvent => Unit,
      maxSteps: Int,
      cancellation: AgentCancellation,
      browserContext: AgentBrowserContext,
      useBrowserTools: Boolean,
      contextIDs: List[String],
      contextMode: String,
      conversation: String,
      promptMode: String,
      firstRound: Option[LocalAgentModel.SeededRound],
      hostTabId: Int,
      bindConversation: Boolean,
      tabRefs: List[BrowserTabRef],
      memoryContext: String,
      allowHandoff: Boolean,
      repair: Option[LocalAgentModel.RepairState]
  ): Future[LocalAgentOutcome] = {
    val evidence          = mutable.ArrayBuffer.empty[ToolEvidence]
    val seed              = firstRound.map(round => new LocalAgentModel.SeedState(Some(round)))
    val runTabRefs        = if (useBrowserTools) tabRefs else Nil
    var handoff           = Option.empty[AgentHandoff]
    val baseTools         =
      AgentToolRegistry.chatToolsFor(useBrowserTools)
    val handoffModes      = if (allowHandoff) List(AgentModePolicy.Plan) else Nil
    val toolSpecs         = baseTools ++ (if (handoffModes.nonEmpty) List(RequestModeHandoff) else Nil)
    val toolSurface       = AgentToolSurface(toolSpecs)
    val conversationState = new LocalAgentModel.ConversationState(toolSurface.revision)
    // 场景 / Memory system 消息由 withScenario 前置，它不对应任何会话消息，id 记空串。
    val contextIds        = "" :: contextIDs
    val toolObjects       = toolSpecs.map { toolDef =>
      val callTool = (name: String, argsText: String) =>
        if (name == RequestModeHandoff.name) {
          cancellation.check()
          parseHandoff(TabToolCall(name, argsText), AgentModePolicy.React, handoffModes) match {
            case Some(request) =>
              handoff = Some(request)
              val interrupt = JsBridge.errorConstructor("Plan handoff requested")
              interrupt.name = "GraphInterrupt"
              interrupt.interrupts = js.Array(JsBridge.obj("value" -> (JsBridge.obj(
                "mode"   -> (request.mode),
                "reason" -> (request.reason)
              ))))
              throw js.JavaScriptException(interrupt)
            case None          =>
              Future.failed(new RuntimeException("request_mode_handoff requested an unsupported target mode"))
          }
        } else
          BrowserToolRunner
            .call(browserContext.sessionId, browserContext.sessionTitle, name, argsText)
            .map { result =>
              evidence += ToolEvidence(name, argsText, textOf(result))
              result
            }
      LocalAgentModel.createLangChainTool(toolDef, callTool, cancellation, onEvent)
    }
    val model             = LocalAgentModel.create(
      provider,
      toolSpecs,
      onEvent,
      cancellation,
      sessionId = browserContext.sessionId,
      contextIds = contextIds,
      contextMode = contextMode,
      conversation = conversation,
      promptMode = promptMode,
      hostTabId = hostTabId,
      bindConversation = bindConversation,
      conversationState = conversationState,
      seed = seed,
      repair = repair
    )
    val app               = AgentHarness.create(model, toolObjects, Nil)
    val config            = JsBridge.obj("recursionLimit" -> (AgentStepPolicy.reactRecursionLimit(maxSteps)))
    def runRuntimeHandoff(request: AgentHandoff): Future[LocalAgentOutcome] =
      request.mode match {
        case AgentModePolicy.Plan =>
          runPlanExecute(
            provider,
            initialMessages,
            onEvent,
            maxSteps,
            cancellation,
            browserContext,
            useBrowserTools,
            contextIDs,
            conversation,
            promptMode,
            hostTabId,
            bindConversation,
            tabRefs,
            memoryContext,
            repair
          ).map(_.copy(handoffs = List(request)))
        case _                    =>
          Future.successful(LocalAgentOutcome(content = "", thinking = "", toolCalls = Nil, handoffs = List(request)))
      }
    // 新一轮的起点：先清掉上一轮留下的 tool tab（用户可以在提问前看完上一轮的页面）。
    val runF                                                                = BrowserToolRunner
      .bindReferencedTabs(browserContext.sessionId, browserContext.sessionTitle, runTabRefs)
      .flatMap { case (validTabs, previousTabId) =>
        val input =
          JsBridge.obj("messages" -> (withScenario(
            initialMessages,
            toolSurface,
            validTabs,
            memoryContext,
            handoffModes
          )))
        BrowserToolRunner
          .clearPreviousToolTabs(validTabs.map(_.tabId).toSet + previousTabId)
          .flatMap { _ =>
            app.invoke(input, config)
              .toFuture
              .flatMap { raw =>
                val state    = JsBridge.asObject(raw)
                val messages =
                  JsBridge.undefinedArrayAny(state.messages).getOrElse(js.Array[js.Any]())
                val last     = messages.last
                val outcome  = LocalAgentOutcome(
                  content = contentOf(last),
                  thinking = reasoningOf(last),
                  toolCalls = toolCallsOf(last)
                )
                handoff match {
                  case Some(request) =>
                    onEvent(TabAiEvent.Notice(
                      s"Auto handoff: ${AgentModePolicy.React} → ${request.mode} (${request.reason})"
                    ))
                    runRuntimeHandoff(request)
                  case None          => Future.successful(outcome)
                }
              }
              .recoverWith {
                case _ if handoff.nonEmpty && !cancellation.isCancelled                         =>
                  val request = handoff.get
                  onEvent(TabAiEvent.Notice(
                    s"Auto handoff: ${AgentModePolicy.React} → ${request.mode} (${request.reason})"
                  ))
                  runRuntimeHandoff(request)
                case e if isRecursionLimit(e) && evidence.nonEmpty && !cancellation.isCancelled =>
                  PopCanLog.warn(
                    "agent",
                    s"step budget exhausted after ${evidence.size} tool call(s); asking for a final answer from the evidence"
                  )
                  finalAnswerPass(
                    provider,
                    initialMessages,
                    evidence.toList,
                    onEvent,
                    cancellation,
                    browserContext.sessionId,
                    contextIDs,
                    contextMode,
                    conversation,
                    promptMode,
                    memoryContext,
                    conversationState
                  )
              }
          }
      }
    runF
      .transformWith { result =>
        BrowserToolRunner.releaseReferencedTabs(browserContext.sessionId).transform(_ => result)
      }
      .recoverWith { case e =>
        onEvent(TabAiEvent.StepFailed(Option(e.getMessage).getOrElse(e.toString)))
        Future.failed(e)
      }
  }

  /** 步数上限降级：把已收集的工具结果重放成消息，用不声明工具的模型要一次最终作答。 */
  private def finalAnswerPass(
      provider: String,
      initialMessages: js.Array[LangChainBaseMessage],
      evidence: List[ToolEvidence],
      onEvent: TabAiEvent => Unit,
      cancellation: AgentCancellation,
      sessionId: String,
      contextIDs: List[String],
      contextMode: String,
      conversation: String,
      promptMode: String,
      memoryContext: String,
      conversationState: LocalAgentModel.ConversationState
  ): Future[LocalAgentOutcome] = {
    cancellation.check()
    val memoryMessage =
      if (memoryContext.trim.isEmpty) js.Array[LangChainBaseMessage]()
      else
        js.Array[LangChainBaseMessage](
          new SystemMessage(JsBridge.obj("content" -> (memoryContext)))
            .asInstanceOf[LangChainBaseMessage]
        )
    val messages      = memoryMessage.concat(initialMessages).concat(evidenceMessages(evidence))
    val memoryIds     = if (memoryMessage.nonEmpty) List("") else Nil
    val model         = LocalAgentModel
      .create(
        provider,
        Nil,
        onEvent,
        cancellation,
        sessionId = sessionId,
        // 合成出来的工具轮（assistant tool_call + tool 结果）与"直接作答"指令都不对应会话消息。
        contextIds = memoryIds ++ contextIDs ++ List.fill(evidence.size * 2 + 1)(""),
        contextMode = contextMode,
        conversation = conversation,
        promptMode = promptMode,
        conversationState = conversationState
      )
      .asInstanceOf[LangChainRunnable]
    model.invoke(JsBridge.asObject(messages)).toFuture.map { raw =>
      val message = JsBridge.asObject(raw)
      LocalAgentOutcome(
        content = contentOf(message),
        thinking = reasoningOf(message),
        toolCalls = Nil
      )
    }
  }

  /** 合成的"工具轮"：assistant 的 tool_call + 对应 tool 结果，最后加一条"直接作答"的指令。 */
  private def evidenceMessages(evidence: List[ToolEvidence]): js.Array[js.Any]                                = {
    val calls = evidence.zipWithIndex.flatMap { case (item, index) =>
      val id = s"budget_call_${index}"
      List(
        JsBridge.asObject(new AIMessage(JsBridge.obj(
          "content"    -> (null),
          "tool_calls" -> (js.Array(JsBridge.obj(
            "id"   -> (id),
            "name" -> (item.name),
            "args" -> (parseArgs(item.arguments))
          )))
        ))),
        JsBridge.asObject(new ToolMessage(JsBridge.obj("content" -> (item.result), "tool_call_id" -> (id))))
      )
    }
    val nudge = JsBridge.asObject(
      new HumanMessage(JsBridge.obj(
        "content" -> ("You have reached the step budget. Write the final answer now, using only the tool results above " +
          "(never from memory), in the user's language, and end with the Sources list. Do not call any tool.")
      ))
    )
    js.Array[js.Any]((calls :+ nudge)*)
  }
  private def isRecursionLimit(e: Throwable): Boolean                                                         = {
    val message = Option(e.getMessage).getOrElse("")
    val name    =
      try Option(e.asInstanceOf[js.Error].name).getOrElse("")
      catch { case _: Throwable => "" }
    name.contains("GraphRecursionError") || message.contains("Recursion limit")
  }
  private def parseArgs(arguments: String): js.Any                                                            =
    try {
      if (arguments.trim.isEmpty) JsBridge.obj() else js.JSON.parse(arguments)
    } catch {
      case _: Throwable => JsBridge.obj()
    }
  private def parseHandoff(call: TabToolCall, from: String, allowedModes: List[String]): Option[AgentHandoff] =
    try {
      val args   = JsBridge.asObject(parseArgs(call.arguments))
      val mode   = AgentModePolicy.normalize(JsBridge.undefinedString(args.mode).getOrElse(""))
      val reason = JsBridge.undefinedString(args.reason).getOrElse("").trim
      if (allowedModes.contains(mode) && AgentModePolicy.canHandoff(from, mode, locked = false))
        Some(AgentHandoff(
          AgentModePolicy.normalize(from),
          mode,
          if (reason.nonEmpty) reason else "the current mode cannot complete the request correctly"
        ))
      else None
    } catch { case _: Throwable => None }
  private def textOf(result: js.Any): String                                                                  =
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
  private def contentOf(message: js.Any): String                                                              =
    if (js.isUndefined(JsBridge.field(message, "content")) || JsBridge.field(message, "content") == null) {
      ""
    } else if (js.typeOf(JsBridge.field(message, "content")) == "string") {
      JsBridge.asString(JsBridge.field(message, "content"))
    } else {
      JsBridge.asArray(JsBridge.field(message, "content")).toList.flatMap { block =>
        JsBridge.stringField(block, "type") match {
          case Some("text") => Option(JsBridge.stringField(block, "text").getOrElse(""))
          case _            => None
        }
      }.mkString("\n")
    }

  /** 每轮补充非工具面的运行上下文；工具能力本身只由 LangChain tool definitions 表达。 */
  private def withScenario(
      messages: js.Array[LangChainBaseMessage],
      surface: AgentToolSurface,
      tabs: List[BrowserTabRef],
      memoryContext: String,
      handoffModes: List[String] = Nil
  ): js.Array[LangChainBaseMessage] = {
    val scenario = List(
      if (surface.browserToolsEnabled) ChatScenario.attachedTabsPolicy(tabs) else "",
      ChatScenario.autoHandoffPolicy(handoffModes)
    ).filter(_.nonEmpty).mkString("\n\n")
    val combined = List(scenario, Option(memoryContext).getOrElse("").trim).filter(_.nonEmpty).mkString("\n\n")
    if (combined.isEmpty) messages
    else {
      PopCanLog.debug(
        "agent",
        s"chat scenario: tools=${surface.toolNames.mkString(",")} revision=${surface.revision} " +
          s"system message ${combined.length} chars tabs=${tabs.size} memory=${memoryContext.nonEmpty}"
      )
      js.Array[LangChainBaseMessage](
        new SystemMessage(JsBridge.obj("content" -> (combined)))
          .asInstanceOf[LangChainBaseMessage]
      ).concat(messages)
    }
  }
  private def reasoningOf(message: js.Any): String            =
    if (
      !js.isUndefined(JsBridge.field(message, "response_metadata")) && JsBridge.field(
        message,
        "response_metadata"
      ) != null
    ) {
      JsBridge
        .field(message, "response_metadata")
        .asInstanceOf[LlmResponseMetadata]
        .reasoning_content
        .getOrElse("")
    } else {
      ""
    }
  private def toolCallsOf(message: js.Any): List[TabToolCall] =
    if (!js.isUndefined(JsBridge.field(message, "tool_calls")) && JsBridge.field(message, "tool_calls") != null) {
      JsBridge.asArray(JsBridge.field(message, "tool_calls")).toList.map { call =>
        TabToolCall(
          name = JsBridge.stringField(call, "name").getOrElse(""),
          arguments =
            js.JSON.stringify(JsBridge.undefinedAny(JsBridge.field(call, "args")).getOrElse(JsBridge.obj()))
        )
      }
    } else {
      Nil
    }
}
