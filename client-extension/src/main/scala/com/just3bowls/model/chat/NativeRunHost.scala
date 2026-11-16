package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import com.just3bowls.facade.{ChromeAPI, MessageSender}
import com.just3bowls.api.memory.MemoryContext
import com.just3bowls.api.memory.MemoryQuery
import com.just3bowls.api.memory.MemoryTurn
import com.just3bowls.model.chat.AgentCancellation
import com.just3bowls.model.chat.LocalAgentModel
import com.just3bowls.model.chat.TabAiExecutor
import com.just3bowls.model.chat.TabToolCall
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import zio.json._
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/**
 * 网页原生会话的 agent host（插件主页侧）。
 *
 * SW 观察到「用户在 provider 页发出的第 1 轮」并注入/解析完以后，把这一轮交给这里：用**和插件页 chat 完全同一套** `LocalAgentRunner` + 工具 + 章节 过程收集器跑完后续工具轮，把
 * user / assistant（带 steps）写回会话，并把结果回执给 SW（SW 负责水位线、胶囊与重新武装）。
 *
 * 只有宿主职责：协议解析、取消、进度广播。真正的 agent 逻辑在 `ChatRunner.runNativeTurn`。
 */
object NativeRunHost {
  final private case class Run(runId: String, sessionId: String, cancellation: AgentCancellation)
  private val runs                                                                = mutable.Map.empty[String, Run]
  private var initialized                                                         = false
  private var memoryIngest: MemoryTurn => Unit                                    = _ => ()
  private var memoryRecall: MemoryQuery => Future[MemoryContext]                  = _ =>
    Future.successful(MemoryContext.Empty)
  def setMemoryIngestHandler(handler: MemoryTurn => Unit): Unit                   = memoryIngest = handler
  def setMemoryRecallHandler(handler: MemoryQuery => Future[MemoryContext]): Unit = memoryRecall = handler
  def initialize(registerDisposer: (() => Unit) => Unit): Unit                    =
    if (!initialized) {
      initialized = true
      val listener: js.Function3[js.Any, MessageSender, js.Function1[js.Any, Unit], js.Any] =
        (message, _, _) => {
          val dynamic = JsBridge.asObject(message)
          asString(dynamic.`type`) match {
            case "NATIVE_RUN_START"      => JsBridge.asObject(start(dynamic).toJSPromise)
            case "NATIVE_RUN_CANCEL"     =>
              cancel(asString(dynamic.runId))
              js.undefined
            case "MEMORY_INGEST_TURN"    =>
              ingestMemoryTurn(dynamic)
              js.undefined
            case "MEMORY_RECALL_REQUEST" =>
              JsBridge.asObject(recallMemory(dynamic).toJSPromise)
            case _                       => js.undefined
          }
        }
      ChromeAPI.runtime.onMessage.addListener(listener)
      registerDisposer(() => ChromeAPI.runtime.onMessage.removeListener(listener))
    }
  private def start(spec: js.Any): Future[js.Object]                              = {
    val runId = asString(JsBridge.field(spec, "runId"))
    if (runId.isEmpty)
      Future.successful(JsBridge.obj("ok" -> (false), "error" -> ("missing runId")))
    else {
      val sessionId    = asString(JsBridge.field(spec, "sessionId"))
      val provider     = asString(JsBridge.field(spec, "provider"))
      val convKey      = asString(JsBridge.field(spec, "convKey"))
      val tabId        = JsBridge.intField(spec, "tabId").getOrElse(0)
      val userText     = asString(JsBridge.field(spec, "userText"))
      val regen        = JsBridge.booleanField(spec, "treatAsRegenerate").getOrElse(false)
      val useTools     = JsBridge.booleanField(spec, "useBrowserTools").getOrElse(true)
      val round        = JsBridge.asObject(JsBridge.field(spec, "firstRound"))
      val toolCalls    = JsBridge.undefinedArrayAny(round.toolCalls).toOption
        .map(_.toList)
        .getOrElse(Nil)
        .map(call => TabToolCall(asString(JsBridge.field(call, "name")), asString(JsBridge.field(call, "arguments"))))
      val seeded       = LocalAgentModel.SeededRound(asString(round.content), asString(round.thinking), toolCalls)
      val cancellation = new AgentCancellation(() => TabAiExecutor.cancelRunning(provider))
      val turnSpec     = ChatRunner.NativeTurnSpec(
        runId = runId,
        sessionId = sessionId,
        provider = provider,
        origin = s"web:$convKey",
        userText = userText,
        treatAsRegenerate = regen,
        useBrowserTools = useTools,
        hostTabId = tabId
      )
      runs.update(runId, Run(runId, sessionId, cancellation))
      PopCanLog.info(
        "webchat",
        s"native run host start run=$runId session=$sessionId provider=$provider tab=$tabId " +
          s"toolCalls=${toolCalls.size} regen=$regen"
      )
      val started      = Promise[js.Object]()
      var acceptedAssistantId = ""
      var acceptedUserId      = ""
      ChatRunner
        .runNativeTurn(
          turnSpec,
          seeded,
          cancellation,
          onStarted = (userId, assistantId) => {
            acceptedUserId = userId
            acceptedAssistantId = assistantId
            if (!started.isCompleted)
              started.success(
                JsBridge.obj("ok" -> (true), "runId" -> (runId), "userId" -> (userId), "assistantId" -> (assistantId))
              )
          },
          onProgress = () => requestSessionRefresh(sessionId),
          onFinished = (ok, error, protocolFailure) => {
            runs.remove(runId)
            if (ok && acceptedAssistantId.nonEmpty)
              ChatStore.sessionPathMessages(sessionId).foreach { path =>
                path.find(_.id == acceptedAssistantId).foreach { assistant =>
                  memoryIngest(
                    MemoryTurn(
                      turnId = assistant.id,
                      sessionId = sessionId,
                      userMessageId = acceptedUserId,
                      assistantMessageId = assistant.id,
                      attemptId = assistant.activeAttemptIdOrLegacy,
                      provider = provider,
                      mode = assistant.mode,
                      origin = s"web:$convKey",
                      userText = userText,
                      assistantText = assistant.content,
                      untrustedEvidence =
                        assistant.steps.contains("browser_search") || assistant.steps.contains("browser_extract"),
                      completedAt = js.Date.now().toLong
                    )
                  )
                }
              }
            sendDone(runId, ok, error, protocolFailure)
          }
        )
        .failed
        .foreach { e =>
          // `runNativeTurn` 内部保证 onFinished 至少被调一次；这里兜底只负责别让 ack 挂住。
          if (!started.isCompleted)
            started.failure(new RuntimeException(Option(e.getMessage).getOrElse("native run failed")))
        }
      started.future.recover { case e =>
        JsBridge.obj("ok" -> (false), "error" -> (Option(e.getMessage).getOrElse("native run failed")))

      }
    }
  }
  private def cancel(runId: String): Unit                                         =
    if (runId.nonEmpty) runs.get(runId).foreach(_.cancellation.cancel())

  /** Native turns without a tool handoff are recorded by SW; the page maps the persisted tip back to Memory. */
  private def ingestMemoryTurn(spec: js.Any): Unit          = {
    val sessionId   = asString(JsBridge.field(spec, "sessionId"))
    val provider    = asString(JsBridge.field(spec, "provider"))
    val origin      = asString(JsBridge.field(spec, "origin"))
    val userText    = asString(JsBridge.field(spec, "userText"))
    val assistantId = asString(JsBridge.field(spec, "assistantMessageId"))
    if (sessionId.nonEmpty && assistantId.nonEmpty)
      ChatStore.sessionPathMessages(sessionId).foreach { path =>
        path.find(_.id == assistantId).foreach { assistant =>
          val user = path.takeWhile(_.id != assistant.id).reverse.find(_.role == "user")
          memoryIngest(
            MemoryTurn(
              turnId = assistant.id,
              sessionId = sessionId,
              userMessageId = user.map(_.id).getOrElse(""),
              assistantMessageId = assistant.id,
              attemptId = assistant.activeAttemptIdOrLegacy,
              provider = provider,
              mode = assistant.mode,
              origin = origin,
              userText = userText,
              assistantText = assistant.content,
              untrustedEvidence =
                assistant.steps.contains("browser_search") || assistant.steps.contains("browser_extract"),
              completedAt = js.Date.now().toLong
            )
          )
        }
      }
  }
  private def recallMemory(spec: js.Any): Future[js.Object] = {
    val payload = asString(JsBridge.field(spec, "payload"))
    payload.fromJson[MemoryQuery] match {
      case Left(error)  =>
        Future.successful(
          JsBridge.obj("ok" -> (false), "error" -> (s"invalid Memory query: $error"))
        )
      case Right(query) =>
        memoryRecall(query)
          .map(context =>
            JsBridge.obj("ok" -> (true), "payload" -> (context.toJson))
          )
          .recover { case error =>
            JsBridge.obj("ok" -> (false), "error" -> (Option(error.getMessage).getOrElse("Memory recall failed")))

          }
    }
  }

  /** 进度已写库：让 SW 广播 `WEBCHAT_SESSION_UPDATED`，正显示这条会话的插件页就能实时刷新。 */
  private def requestSessionRefresh(sessionId: String): Unit                                      =
    if (sessionId.nonEmpty)
      ChromeAPI.runtime
        .sendMessage(
          JsBridge.obj("type" -> ("NATIVE_RUN_PROGRESS"), "sessionId" -> (sessionId))
        )
        .toFuture
        .recover { case _ => () }
        .foreach(_ => ())
  private def sendDone(runId: String, ok: Boolean, error: String, protocolFailure: Boolean): Unit =
    ChromeAPI.runtime
      .sendMessage(
        JsBridge.obj(
          "type"            -> ("NATIVE_RUN_DONE"),
          "runId"           -> (runId),
          "ok"              -> (ok),
          "error"           -> (error),
          "protocolFailure" -> (protocolFailure)
        )
      )
      .toFuture
      .recover { case _ => () }
      .foreach(_ => ())
  private def asString(value: js.Any): String                                                     =
    if (value == null || js.isUndefined(value)) "" else value.toString
}
