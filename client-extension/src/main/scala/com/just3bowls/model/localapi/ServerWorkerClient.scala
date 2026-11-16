package com.just3bowls.model.localapi

import com.just3bowls.facade.JsBridge

import com.just3bowls.api.memory.MemoryExtractionResult
import com.just3bowls.api.memory.MemoryResponseEnvelope
import com.just3bowls.api.protocol.NativeFragments
import com.just3bowls.api.protocol.OpenAiSse
import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.api.provider.ServerRouting
import com.just3bowls.facade.{ChromeAPI, ClientRequestChunkMessage, ClientRequestMessage, ServerTaskMessage}
import com.just3bowls.model.localapi.ApiKeyConfig
import com.just3bowls.model.localapi.ApiKeyStore
import com.just3bowls.model.chat.ModelRunRequest
import com.just3bowls.model.localapi.Msg
import com.just3bowls.model.chat.OpenAiStreamEncoder
import com.just3bowls.model.chat.OpenAiTurnEncoder
import com.just3bowls.model.provider.ParserProviderLocal
import com.just3bowls.model.provider.ProviderChatSettings
import com.just3bowls.model.provider.ProviderHostAccess
import com.just3bowls.model.chat.TabAiEvent
import com.just3bowls.model.chat.TabAiExecutor
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.chat.ThinOpenAi
import com.just3bowls.model.chat.ValidatedModelRuntime
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
final class ServerWorkerClient(
    isServerRunning: () => Boolean,
    setServerRunning: Boolean => Unit,
    setServerStatus: String => Unit,
    onApiKeysUpdated: List[ApiKeyConfig] => Unit,
    applyProviderXPaths: String => Unit,
    registerDisposer: (() => Unit) => Unit,
    showError: String => Unit
) {
  private val ActiveTasksStorageKey      = "serverWorkerActiveTasks"
  private val CancelledTasksStorageKey   = "serverWorkerCancelledTasks"
  private var serverWorkerDesired        = false
  private val memoryPending              = mutable.Map.empty[String, Promise[MemoryResponseEnvelope]]
  private var httpStartSentOnCurrentPort = false
  private val nativeRuntime              = new NativeRuntimeConnection(
    Msg.DefaultNativeHost,
    message => handleHostMessage(message),
    (livedMs, reason) => handleHostDisconnect(livedMs, reason)
  )
  registerDisposer { () =>
    clearReconnectTimer()
    nativeRuntime.disconnect()
  }

  /** 上一个 host 是**带着原因退出的**（`server_fatal`）：`onDisconnect` 别再把它改写成 Stopped / Reconnecting。 */
  private var hostFatal = false

  /** 正在装配的请求体分片（`requestId` → (头消息, 装配器)）。 */
  private val pendingRequests      = mutable.Map.empty[String, (ClientRequestMessage, NativeFragments.Assembly)]
  val activeServerTaskProviders    = mutable.Map.empty[String, String]
  val activeServerTaskKeyIds       = mutable.Map.empty[String, String]
  val activeServerTaskStreaming    = mutable.Map.empty[String, Boolean]
  val activeServerTaskModels       = mutable.Map.empty[String, String]
  private val streamEncoders       = mutable.Map.empty[String, OpenAiStreamEncoder]
  private val activeServerAttempts =
    mutable.Map.empty[String, (String, String)] // client requestId -> (provider, internal taskId)
  /** 这一轮的**原始 body**：首次投递与 failover 都把它交给统一的 TemplateInjection 决策。 */
  val activeServerTaskBodies = mutable.Map.empty[String, String]

  /** 当前这次投递使用的 route mode；TemplateInjection 据此统一生成 preview / 注入模版 / 附件。 */
  private val activeServerTaskModes = mutable.Map.empty[String, String]

  /** 这一轮候选的 `provider -> prompt mode`：failover 时查下一家用哪套投递。 */
  val activeServerTaskRouteModes = mutable.Map.empty[String, List[(String, String)]]
  val thinClientRequests         = mutable.Set.empty[String]
  val thinClientHeadsSent        = mutable.Set.empty[String]
  val runningTaskKeys            = mutable.Set.empty[(String, String)]
  val queuedServerTasks          = mutable.Map.empty[(String, String), mutable.Queue[ServerTaskMessage]]
  val cancelledServerTaskIds     = mutable.Set.empty[String]

  /** 加权轮询的游标（按 key，页面内存即可；刷新后从头开始）。 */
  private val routeCursors = mutable.Map.empty[String, Int]

  /**
   * Memory may run without the OpenAI HTTP face. This connects the native host without issuing `server_start`; callers
   * can then exchange Memory frames over the same stdio port.
   */
  def ensureRuntimeConnected(notifyErrors: Boolean = false): Future[Unit]                                          =
    nativeRuntime
      .ensureConnected()
      .map { _ =>
        reconnectAttempts = 0
        setServerStatus(if (isServerRunning()) "Connected" else "Stopped")
        restoreDesiredHttpIfNeeded()
      }
      .recover { case e =>
        val message = Option(e.getMessage).getOrElse("native host unavailable")
        if (notifyErrors) showError(s"Local runtime connection failed: $message")
        throw e
      }
  def requestMemory(action: String, payload: String = "{}", timeoutMs: Int = 2000): Future[MemoryResponseEnvelope] = {
    val requestId = js.Date.now().toString + "-" + js.Math.random().toString.replace(".", "").take(10)
    val promise   = Promise[MemoryResponseEnvelope]()
    ensureRuntimeConnected(notifyErrors = false).flatMap { _ =>
      memoryPending.update(requestId, promise)
      nativeRuntime
        .post(
          JsBridge.obj(
            "type"      -> ("memory_request"),
            "requestId" -> (requestId),
            "action"    -> (action),
            "payload"   -> (payload)
          )
        )
        .failed
        .foreach { error =>
          memoryPending.remove(requestId).foreach(_.tryFailure(error))
        }
      js.timers.setTimeout(timeoutMs) {
        memoryPending.remove(requestId).foreach(
          _.tryFailure(new RuntimeException(s"Memory request timed out: $action"))
        )
      }
      promise.future
    }
  }
  def submitMemoryExtractionResult(result: MemoryExtractionResult): Unit                                           =
    nativeRuntime.postIfConnected(
      JsBridge.obj(
        "type"      -> ("memory_extract_result"),
        "requestId" -> (result.requestId),
        "jobId"     -> (result.jobId),
        "ok"        -> (result.ok),
        "body"      -> (result.body),
        "error"     -> (result.error)
      )
    )

  /** 客户端一次请求（taskId）的候选与已试过的 provider、以及这次要用的策略 / failover，供失败转移用。 */
  private val taskCandidates = mutable.Map.empty[String, List[ServerRouting.Route]]
  private val taskTried      = mutable.Map.empty[String, Set[String]]
  private val taskRouting    = mutable.Map.empty[String, (String, String)]

  /** 从候选里挑下一个（可排除已试过的）：`weighted` 走按 key 的游标，`random` 按权重随机。 */
  private def pickRoute(
      keyId: String,
      routes: List[ServerRouting.Route],
      strategy: String,
      exclude: Set[String] = Set.empty
  ): Option[String] = {
    val pool = routes.filter(route => !exclude.contains(route.provider))
    if (pool.isEmpty) None
    else if (strategy == ServerRouting.StrategyRandom) ServerRouting.pickRandom(pool, Math.random())
    else
      ServerRouting.nextRoundRobin(pool, routeCursors.getOrElse(keyId, 0)).map { case (provider, next) =>
        routeCursors.update(keyId, next)
        provider
      }
  }
  private def failServerTask(taskId: String): Unit                                     = {
    val provider = activeServerTaskProviders.getOrElse(taskId, "")
    thinClientRequests -= taskId
    thinClientHeadsSent -= taskId
    cleanupCompletedTask(taskId)
    ChromeAPI.runtime.sendMessage(JsBridge.obj(
      "type"         -> ("CANCEL_TASK"),
      "generationId" -> (taskId),
      "provider"     -> (provider),
      "managed"      -> (true)
    ))
      .toFuture
      .foreach(_ => ())
  }
  def cancelServerTask(taskId: String): Unit                                           =
    if (taskId.nonEmpty && !cancelledServerTaskIds.contains(taskId)) {
      cancelledServerTaskIds += taskId
      activeServerAttempts.get(taskId).foreach { case (provider, attemptId) =>
        TabAiExecutor.cancel(attemptId, provider)
      }
      failServerTask(taskId)
    }
  private def executeOrEnqueue(msg: ServerTaskMessage): Unit                           = {
    val provider = msg.provider.getOrElse("")
    val keyId    = msg.keyId.getOrElse("")
    val group    = (provider, keyId)
    if (runningTaskKeys.contains(group)) {
      val queue = queuedServerTasks.getOrElseUpdate(group, mutable.Queue.empty)
      queue.enqueue(msg)
      PopCanLog.debug("server", s"task queued provider=$provider key=$keyId")
    } else {
      runningTaskKeys += group
      runServerTask(msg)
    }
  }
  @scala.annotation.nowarn("msg=unused explicit parameter") // Scala 3 currently reports provider despite its uses below
  private def releaseServerTask(taskId: String, provider: String, keyId: String): Unit = {
    val group = (provider, keyId)
    runningTaskKeys -= group
    queuedServerTasks.get(group).foreach { queue =>
      if (queue.nonEmpty) {
        val next = queue.dequeue()
        runningTaskKeys += group
        PopCanLog.debug("server", s"drain next task id=${next.id.getOrElse("")} provider=$provider key=$keyId")
        runServerTask(next)
      }
      if (queue.isEmpty) queuedServerTasks.remove(group)
    }
  }
  private def runServerTask(msg: ServerTaskMessage): Unit                              = {
    val taskId     = msg.id.getOrElse("")
    val provider   = msg.provider.getOrElse("")
    val streaming  = msg.stream.getOrElse(false)
    val keyId      = msg.keyId.getOrElse("")
    val body       = activeServerTaskBodies.getOrElse(taskId, "")
    val promptMode = activeServerTaskModes.getOrElse(taskId, RoutePromptMode.Inline)
    if (cancelledServerTaskIds.contains(taskId)) {
      PopCanLog.debug("server", s"ignoring canceled task $taskId")
      releaseServerTask(taskId, provider, keyId)
    } else {
      activeServerTaskProviders.update(taskId, provider)
      activeServerTaskKeyIds.update(taskId, keyId)
      activeServerTaskStreaming.update(taskId, streaming)
      activeServerTaskModels.update(taskId, msg.model.getOrElse(provider))
      persistServerWorkerState()
      applyProviderXPaths(provider)
      val model          = activeServerTaskModels.getOrElse(taskId, provider)
      val sseEncoder     =
        if (streaming) Some(streamEncoders.getOrElseUpdate(taskId, new OpenAiStreamEncoder(taskId, model)))
        else None
      val bufferedEvents = mutable.ListBuffer.empty[TabAiEvent]
      ValidatedModelRuntime
        .run(
          ModelRunRequest(
            provider = provider,
            bodyJson = body,
            keyId = keyId,
            contextMode = ProviderChatSettings.New,
            conversation = "fresh",
            promptMode = promptMode,
            inject = true
          ),
          onEvent = {
            case TabAiEvent.StepStarted | TabAiEvent.InternalRecoveryStarted(_) =>
              bufferedEvents.clear()
            case event                                                          =>
              bufferedEvents += event
          },
          onTaskStarted = task => activeServerAttempts.update(taskId, (provider, task))
        )
        .map { result =>
          if (!result.ok && thinClientRequests.contains(taskId)) {
            if (!tryFailover(taskId, provider, result.error))
              finishThinRequest(
                taskId,
                500,
                ThinOpenAi.errorJson(result.error, "server_error", "browser_worker_error")
              )
          } else if (thinClientRequests.contains(taskId)) {
            if (streaming) {
              ensureClientHead(taskId, 200, "text/event-stream")
              val frames =
                sseEncoder.toList.flatMap { encoder =>
                  bufferedEvents.toList.flatMap(encoder.onEvent) ++ encoder.finish(result)
                }
              frames.foreach(text => sendClientChunk(taskId, text))
              sendClientEnd(taskId)
              thinClientRequests -= taskId
              thinClientHeadsSent -= taskId
              cleanupCompletedTask(taskId)
            } else
              finishThinRequest(taskId, 200, OpenAiTurnEncoder.json(result, model))
          }
        }
        .recover { case e =>
          if (thinClientRequests.contains(taskId)) {
            if (!tryFailover(taskId, provider, Option(e.getMessage).getOrElse("")))
              finishThinRequest(
                taskId,
                500,
                ThinOpenAi.errorJson(e.getMessage, "server_error", "browser_worker_error")
              )
          }
        }
        .andThen { case _ =>
          releaseServerTask(taskId, provider, keyId)
        }
    }
  }
  private def cleanupFinalSseState(taskId: String): Unit                               = {
    activeServerTaskStreaming.remove(taskId)
    activeServerTaskModels.remove(taskId)
    streamEncoders.remove(taskId)
  }

  /** 客户端一次请求收口：路由相关的临时状态（候选 / 已试过 / 策略）一并清掉。 */
  private def cleanupTaskRouting(taskId: String): Unit = {
    taskCandidates.remove(taskId)
    taskTried.remove(taskId)
    taskRouting.remove(taskId)
    activeServerTaskBodies.remove(taskId)
    activeServerTaskModes.remove(taskId)
    activeServerTaskRouteModes.remove(taskId)
  }

  /** 一个 task 无论成功、失败还是取消，最终只从这里退出：状态、路由和持久化一次收干净。 */
  private def cleanupCompletedTask(taskId: String): Unit = {
    activeServerAttempts.remove(taskId)
    activeServerTaskProviders.remove(taskId)
    activeServerTaskKeyIds.remove(taskId)
    cleanupTaskRouting(taskId)
    cleanupFinalSseState(taskId)
    persistServerWorkerState()
  }

  /**
   * failover=`next`：本轮**还没给客户端写过任何内容**时，换下一个候选重试（不重复已试过的）。
   *
   * 已经写过内容（SSE 头一发就等于流开了）就不换 —— 换家只会把两条流拼在一起；候选全部试完返回 false，由调用方按错误收口。
   */
  private def tryFailover(taskId: String, failedProvider: String, error: String): Boolean = {
    val routing     = taskRouting.getOrElse(taskId, (ServerRouting.StrategyWeighted, ServerRouting.FailoverOff))
    val tried       = taskTried.getOrElse(taskId, Set.empty[String]) + failedProvider
    taskTried.update(taskId, tried)
    val candidates  = taskCandidates.getOrElse(taskId, Nil)
    val keyId       = activeServerTaskKeyIds.getOrElse(taskId, "")
    val nothingSent = !thinClientHeadsSent.contains(taskId)
    if (
      routing._2 != ServerRouting.FailoverNext || candidates.isEmpty || !nothingSent ||
      cancelledServerTaskIds.contains(taskId)
    ) false
    else
      pickRoute(keyId, candidates, routing._1, exclude = tried) match {
        case None       => false
        case Some(next) =>
          PopCanLog.warn(
            "server",
            s"failover task=$taskId ${PopCanLog.short(failedProvider, 24)} -> $next " +
              s"(${PopCanLog.short(error, 80)})"
          )
          val stream   = activeServerTaskStreaming.getOrElse(taskId, false)
          val model    = activeServerTaskModels.getOrElse(taskId, ServerRouting.ModelName)
          // 只切换下一家的 mode；原始 body 不变，preview / 注入 / 附件都由 TemplateInjection 重新决定。
          val nextMode = RoutePromptMode.modeFor(activeServerTaskRouteModes.getOrElse(taskId, Nil), next)
          activeServerTaskModes.update(taskId, nextMode)
          val msg      = JsBridge.obj(
            "id"       -> (taskId),
            "provider" -> (next),
            "prompt"   -> (""),
            "stream"   -> (stream),
            "keyId"    -> (keyId),
            "model"    -> (model),
            "finalSse" -> (true)
          ).asInstanceOf[ServerTaskMessage]
          executeOrEnqueue(msg)
          true
      }
  }
  private def sendClientHead(
      requestId: String,
      status: Int,
      contentType: String
  ): Unit =
    postToHost(ServerWireMessages.clientResponseHead(requestId, status, contentType))
  private def sendClientChunk(requestId: String, data: String): Unit                      =
    if (data.nonEmpty)
      // 单条消息别顶到 Chrome 的上限（扩展→host 名义 64MB，但没必要赌）：按 native 分片规则切，host 侧会按顺序拼回 HTTP body。
      NativeFragments.split(data).foreach { part =>
        postToHost(ServerWireMessages.clientResponseChunk(requestId, part))
      }
  private def sendClientEnd(requestId: String): Unit                                      =
    postToHost(ServerWireMessages.clientResponseEnd(requestId))

  /** 发一条消息给 native host；host 不在了（port 已断开）就静默丢弃 —— 上层已经在断开回调里收尾在途任务。 */
  private def postToHost(message: js.Object): Unit                                        =
    nativeRuntime.postIfConnected(message)
  private def respondClientJson(
      requestId: String,
      status: Int,
      body: String
  ): Unit = {
    sendClientHead(requestId, status, "application/json")
    sendClientChunk(requestId, body)
    sendClientEnd(requestId)
  }
  private def ensureClientHead(requestId: String, status: Int, contentType: String): Unit =
    if (!thinClientHeadsSent.contains(requestId)) {
      sendClientHead(requestId, status, contentType)
      thinClientHeadsSent += requestId
    }
  private def finishThinRequest(id: String, status: Int, body: String): Unit              = {
    // 流已经开始后不能再补第二个 HTTP head；用 SSE error event 收口，让客户端能结束消费而不是挂住。
    if (thinClientHeadsSent.contains(id)) {
      sendClientChunk(id, OpenAiSse.data(body))
      sendClientChunk(id, OpenAiSse.done)
      sendClientEnd(id)
    } else respondClientJson(id, status, body)
    thinClientRequests -= id
    thinClientHeadsSent -= id
    cleanupCompletedTask(id)
  }
  private def handleClientRequest(msg: ClientRequestMessage): Unit                        = {
    val requestId = msg.requestId.getOrElse("")
    val path      = msg.path.getOrElse("")
    val method    = msg.method.getOrElse("GET")
    if (requestId.isEmpty) return
    if (method == "GET" && path == "/v1/models") {
      // 客户端永远只配这一个模型名；落到哪个 provider 由这个请求用的 API key 在 Config → API keys 里决定。
      respondClientJson(requestId, 200, ThinOpenAi.modelsJson(List(ServerRouting.ModelName)))
    } else if (method == "POST" && path == "/v1/chat/completions") {
      val body = msg.body.getOrElse("")
      if (body.trim.isEmpty) {
        respondClientJson(
          requestId,
          400,
          ThinOpenAi.errorJson("Missing request body", "invalid_request_error", "missing_body")
        )
      } else {
        handleThinChatRequest(requestId, msg.headers.getOrElse(""), body)
      }
    } else {
      respondClientJson(
        requestId,
        404,
        ThinOpenAi.errorJson(s"Not found: $method $path", "invalid_request_error", "not_found")
      )
    }
  }
  private def handleThinChatRequest(
      requestId: String,
      rawHeaders: String,
      body: String
  ): Unit = {
    val parsed     =
      try Some(JsBridge.asObject(js.JSON.parse(body)))
      catch { case _: Throwable => None }
    val model      =
      parsed.flatMap(value => JsBridge.stringField(value, "model")).getOrElse("")
    if (model.isEmpty || model.trim.isEmpty) {
      respondClientJson(
        requestId,
        400,
        ThinOpenAi.errorJson("model must not be empty", "invalid_request_error", "invalid_model")
      )
      return
    }
    val authHeader =
      try {
        val parsedHeaders = JsBridge.asObject(js.JSON.parse(rawHeaders))
        JsBridge.undefinedString(parsedHeaders.authorization).getOrElse("")
      } catch { case _: Throwable => "" }
    ApiKeyStore.load().foreach { keys =>
      val token = authHeader.stripPrefix("Bearer").trim
      keys.find(key => !key.revoked && key.secret == token) match {
        case None      =>
          respondClientJson(
            requestId,
            401,
            ThinOpenAi.errorJson("Invalid API key", "invalid_request_error", "invalid_api_key")
          )
        case Some(key) =>
          // 记录这个 key 最近被使用的时间（内部有 30 秒节流），失败不影响请求。
          ApiKeyStore.touchKey(key.id).foreach(updated => updated.foreach(keys => onApiKeysUpdated(keys)))
          ParserProviderLocal
            .loadAll()
            .flatMap { providers =>
              // 路由先按存储状态收敛，再由 Chrome 的实际 origin grant 决定最终可用集合。
              val configured       = ServerRouting
                .normalize(key.routes.map(route => ServerRouting.Route(route.provider, route.weight)))
                .filter(route => providers.get(route.provider).exists(_.status == "active"))
              val enabledProviders = configured.flatMap(route => providers.get(route.provider)).distinct
              ProviderHostAccess.filterReady(enabledProviders).map { ready =>
                val readyIds       = ready.map(_.id).toSet
                val missingOrigins = enabledProviders
                  .filterNot(provider => readyIds.contains(provider.id))
                  .map(provider => ProviderHostAccess.originPattern(provider.pageUrl))
                  .filter(_.nonEmpty)
                  .distinct
                (configured, configured.filter(route => readyIds.contains(route.provider)), missingOrigins)
              }
            }
            .map { case (configured, candidates, missingOrigins) =>
              val providerOpt = pickRoute(key.id, candidates, key.strategy)
              if (providerOpt.isEmpty) {
                val (message, code) =
                  if (configured.isEmpty)
                    (
                      "No available provider for this API key - configure it in Config → API keys (Routing)",
                      "provider_not_configured"
                    )
                  else
                    (
                      s"No routed provider has host access " +
                        s"(${if (missingOrigins.nonEmpty) missingOrigins.mkString(", ") else "unknown origin"}). " +
                        "Open the Pop Can panel → Config → Providers, grant access, then retry.",
                      "host_permission_required"
                    )
                respondClientJson(requestId, 400, ThinOpenAi.errorJson(message, "invalid_request_error", code))
              } else {
                val provider   = providerOpt.get
                PopCanLog.info(
                  "server",
                  s"route model=$model provider=$provider key=${key.id} strategy=${key.strategy}"
                )
                taskCandidates.update(requestId, candidates)
                taskTried.update(requestId, Set(provider))
                taskRouting.update(requestId, (key.strategy, key.failover))
                val stream     =
                  parsed.flatMap(value => JsBridge.booleanField(value, "stream")).getOrElse(false)
                // 投递模式跟着选中的那条路由走；body 原件留给 failover 按下一家的 mode 重新投递。
                val routeModes = key.routes.map(route => route.provider -> route.promptMode)
                val promptMode = RoutePromptMode.modeFor(routeModes, provider)
                activeServerTaskBodies.update(requestId, body)
                activeServerTaskModes.update(requestId, promptMode)
                activeServerTaskRouteModes.update(requestId, routeModes)
                val msg        = JsBridge.obj(
                  "id"       -> (requestId),
                  "provider" -> (provider),
                  "prompt"   -> (""),
                  "stream"   -> (stream),
                  "keyId"    -> (key.id),
                  "model"    -> (model),
                  "finalSse" -> (true)
                ).asInstanceOf[ServerTaskMessage]
                thinClientRequests += requestId
                executeOrEnqueue(msg)
              }
            }
            .recover { case e =>
              PopCanLog.error("server", s"provider access preflight failed request=$requestId", e)
              respondClientJson(
                requestId,
                500,
                ThinOpenAi.errorJson(
                  s"Provider access preflight failed: ${e.getMessage}",
                  "server_error",
                  "provider_access_check_failed"
                )
              )
            }
      }
    }
  }
  var reconnectTimer: js.UndefOr[js.timers.SetTimeoutHandle]                              = js.undefined
  var reconnectAttempts                                                                   = 0
  def clearReconnectTimer(): Unit                                                         = {
    reconnectTimer.foreach(h => js.timers.clearTimeout(h))
    reconnectTimer = js.undefined
  }
  def saveServerWorkerPreference(enabled: Boolean): Unit                                  =
    ChromeAPI.storage.local.set(
      js.Dictionary("serverWorkerEnabled" -> JsBridge.asObject(enabled))
    ).toFuture.foreach(_ => ())
  private def persistServerWorkerState(): Unit                                            = {
    val tasks     = js.Dictionary[js.Any]()
    activeServerTaskProviders.foreach { case (id, provider) =>
      tasks.update(
        id,
        JsBridge.obj("provider" -> (provider), "keyId" -> (activeServerTaskKeyIds.getOrElse(id, "")))
      )
    }
    val cancelled = js.Array[js.Any](cancelledServerTaskIds.toSeq.map(JsBridge.asObject(_))*)
    PopCanLog.debug(
      "server",
      s"persist state active=${activeServerTaskProviders.keys.mkString(",")} cancelled=${cancelledServerTaskIds.mkString(",")}"
    )
    ChromeAPI.storage.local.set(js.Dictionary(
      ActiveTasksStorageKey    -> JsBridge.asObject(tasks),
      CancelledTasksStorageKey -> JsBridge.asObject(cancelled)
    )).toFuture
      .recover { case e => PopCanLog.error("server", "persist active tasks failed", e) }
      .foreach(_ => ())
  }
  private def restoreServerWorkerState(): Unit                                            = {
    activeServerTaskProviders.clear()
    activeServerTaskBodies.clear()
    activeServerTaskModes.clear()
    activeServerTaskRouteModes.clear()
    activeServerTaskKeyIds.clear()
    cancelledServerTaskIds.clear()
    persistServerWorkerState()
    PopCanLog.debug("server", "cleared stale worker state")
  }
  def scheduleReconnect(): Unit                                                           =
    if (serverWorkerDesired && reconnectTimer.isEmpty) {
      val base   = math.min(30000, math.pow(2, reconnectAttempts).toInt * 1000)
      val jitter = js.Math.floor(js.Math.random() * 500).toInt
      reconnectAttempts += 1
      PopCanLog.debug("server", s"scheduling reconnect in ${base + jitter}ms (attempt $reconnectAttempts)")
      reconnectTimer = js.timers.setTimeout(base + jitter) {
        reconnectTimer = js.undefined
        if (serverWorkerDesired) startServerWorker()
      }
    }

  /**
   * 开启服务 = `connectNative`。
   *
   * host 进程由 Chrome 拉起并**随插件页的 native port 存活**。Stop 只关闭 8080 子服务，Memory 继续可用；关闭插件页或 浏览器才会终止 host。连接可用性看
   * `onDisconnect`，运行时恢复由 RuntimeIpc 的握手/接管负责。
   */
  def startServerWorker(): Unit =
    if (!isServerRunning()) {
      clearReconnectTimer()
      nativeRuntime.clearReconnectBlock()
      hostFatal = false
      serverWorkerDesired = true
      saveServerWorkerPreference(true)
      setServerRunning(true)
      setServerStatus("Starting...")
      ensureRuntimeConnected(notifyErrors = true)
        .map { _ =>
          if (!httpStartSentOnCurrentPort) {
            httpStartSentOnCurrentPort = true
            postToHost(JsBridge.obj("type" -> ("server_start")))
          }
        }
        .recover { case e =>
          setServerRunning(false)
          setServerStatus("Error")
          showError(s"Native host 连接失败：${Option(e.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => ())
    }

  /** A successful Memory reconnect must restore an HTTP service the user had explicitly left enabled. */
  private def restoreDesiredHttpIfNeeded(): Unit =
    if (serverWorkerDesired && !isServerRunning() && !httpStartSentOnCurrentPort) {
      httpStartSentOnCurrentPort = true
      setServerRunning(true)
      setServerStatus("Starting...")
      postToHost(JsBridge.obj("type" -> ("server_start")))
    }

  /** host → 扩展：`client_request` / `client_request_chunk` / `client_cancel`。 */
  private def handleHostMessage(message: js.Any): Unit =
    try {
      val msg     = JsBridge.asObject(message)
      val msgType = JsBridge.undefinedString(msg.`type`).getOrElse("")
      if (msgType == "client_request") {
        val head   = msg.asInstanceOf[ClientRequestMessage]
        // 线格式契约（与 `api.NativeFragments.outbound` 对称）：`bodyFrames <= 1` ⇒ 正文就在 head.body 里（没有 chunk 帧）；
        // `> 1` ⇒ head.body 留空，正文按 seq 跟在 client_request_chunk 帧里攒齐。缺字段按 1 算。
        val frames = head.bodyFrames.toOption.getOrElse(1)
        if (frames <= NativeFragments.MaxFrames) {
          val requestId = head.requestId.getOrElse("")
          if (requestId.nonEmpty) {
            if (frames <= 1) handleClientRequest(head)
            else pendingRequests.update(requestId, (head, new NativeFragments.Assembly(frames)))
          }
        } else {
          val requestId = head.requestId.getOrElse("")
          PopCanLog.warn("server", s"request $requestId has too many frames: $frames")
          if (requestId.nonEmpty)
            respondClientJson(
              requestId,
              413,
              ThinOpenAi.errorJson(
                s"Request body needs $frames fragments (limit ${NativeFragments.MaxFrames})",
                "invalid_request_error",
                "body_too_large"
              )
            )
        }
      } else if (msgType == "client_request_chunk") {
        val chunk = msg.asInstanceOf[ClientRequestChunkMessage]
        val id    = chunk.requestId.getOrElse("")
        pendingRequests.get(id).foreach { case (head, assembly) =>
          assembly.accept(chunk.seq.getOrElse(-1), chunk.data.getOrElse("")) match {
            case None       => ()
            case Some(body) =>
              pendingRequests.remove(id)
              handleClientRequest(
                JsBridge.obj(
                  "type"      -> ("client_request"),
                  "requestId" -> (id),
                  "method"    -> (head.method.getOrElse("GET")),
                  "path"      -> (head.path.getOrElse("")),
                  "headers"   -> (head.headers.getOrElse("")),
                  "body"      -> (body)
                ).asInstanceOf[ClientRequestMessage]
              )
          }
        }
      } else if (msgType == "client_cancel") {
        val requestId = JsBridge.undefinedString(msg.requestId).getOrElse("")
        if (requestId.nonEmpty) {
          pendingRequests.remove(requestId)
          PopCanLog.info("server", s"client cancel received $requestId")
          cancelServerTask(requestId)
        }
      } else if (msgType == "memory_response") {
        val requestId = JsBridge.undefinedString(msg.requestId).getOrElse("")
        val ok        = JsBridge.undefinedBoolean(msg.ok).getOrElse(false)
        val payload   = JsBridge.undefinedString(msg.payload).getOrElse("{}")
        val error     = JsBridge.undefinedString(msg.error).getOrElse("")
        memoryPending.remove(requestId).foreach(_.trySuccess(MemoryResponseEnvelope(requestId, ok, payload, error)))
      } else if (msgType == "server_ready") {
        setServerRunning(true)
        serverWorkerDesired = true
        setServerStatus("Connected")
      } else if (msgType == "runtime_retry") {
        val reason = JsBridge.undefinedString(msg.message).getOrElse("runtime connection closed")
        hostFatal = true
        httpStartSentOnCurrentPort = false
        setServerRunning(false)
        if (serverWorkerDesired) {
          setServerStatus("Reconnecting...")
          scheduleReconnect()
        } else setServerStatus("Stopped")
        PopCanLog.warn("memory", s"runtime proxy disconnected; reconnecting: $reason")
      } else if (msgType == "runtime_fatal") {
        val code     = JsBridge.undefinedString(msg.code).getOrElse("")
        val reason   = JsBridge.undefinedString(msg.message).getOrElse("runtime unavailable")
        val readable =
          if (code == "runtime_owner_unverified")
            s"Memory runtime 的旧进程身份无法验证，已停止自动接管：$reason。" +
              "关闭所有 Pop Can 页面或结束残留 PopCanHost 后重试。"
          else s"Local runtime recovery failed ($code): $reason"
        hostFatal = true
        serverWorkerDesired = false
        clearReconnectTimer()
        setServerRunning(false)
        setServerStatus(s"Runtime error: $readable")
        nativeRuntime.blockReconnect(30000.0, readable)
        PopCanLog.error("memory", s"runtime fatal code=$code reason=$reason")
        showError(readable)
      } else if (msgType == "server_fatal") {
        // Only the HTTP child failed. The native host and Memory stay alive on the same port.
        val code     = JsBridge.undefinedString(msg.code).getOrElse("")
        val reason   = JsBridge.undefinedString(msg.message).getOrElse("")
        val taken    = code == "port_in_use"
        val readable =
          if (taken)
            s"127.0.0.1:8080 已被另一个 Pop Can host 占用：另一个插件面板正在服务就关掉那个面板；" +
              s"否则清掉残留进程 ${Msg.FreePortCommand}"
          else s"Native host 启动失败：${reason.take(120)}"
        hostFatal = true
        serverWorkerDesired = false
        clearReconnectTimer()
        httpStartSentOnCurrentPort = false
        // 同 `handleHostDisconnect`：先改"没在跑"，再改状态，按钮文案才不会停在 Stop。
        setServerRunning(false)
        setServerStatus(if (taken) "Port in use: 127.0.0.1:8080" else "Error")
        PopCanLog.error("server", s"native host fatal code=$code reason=$reason")
        showError(readable)
      } else if (msgType == "server_stopped") {
        setServerRunning(false)
        if (!hostFatal) setServerStatus("Stopped")
      } else PopCanLog.debug("server", s"ignoring host message type=$msgType")
    } catch {
      case e: Throwable =>
        setServerStatus(s"Message error: ${e.getMessage}")
        // 状态点只看"连没连上"，错误本身要能看清（长错误可点正文复制）——走页面右上角统一 toast。
        showError(s"Server message error: ${Option(e.getMessage).getOrElse("unknown error")}")
    }

  /** host 断开：Chrome 在 disconnect 事件当下把原因放在 `runtime.lastError`，transport 已读取并清空旧 port。 */
  private def handleHostDisconnect(livedMs: Double, text: String): Unit = {
    httpStartSentOnCurrentPort = false
    // 已经是 `server_fatal` / `runtime_fatal` 收尾的（原因 + 状态都写好了）：这里不要再改写状态。
    val fatal = hostFatal
    hostFatal = false
    // **先落"没在跑"再改状态**：面板按钮的文案由状态信号驱动渲染，顺序反了就会出现"其实已经断了，按钮还写着 Stop"。
    setServerRunning(false)
    if (!fatal && text.nonEmpty) {
      // Chrome 的原文对用户没信息量，翻成人话。
      val lower     = text.toLowerCase
      val notFound  = lower.contains("not found")
      val forbidden = lower.contains("forbidden") || lower.contains("access to the specified native messaging host")
      val readable  =
        if (notFound)
          s"Native host 未安装或 host 名不对（当前 host=${Msg.DefaultNativeHost}）。" +
            s"注册一次（Linux/macOS）：bash server/scripts/pop-can.sh register --classpath" +
            s"；Windows：powershell -ExecutionPolicy Bypass -File server/scripts/pop-can.ps1 register"
        else if (forbidden)
          s"Native host 拒绝了这个扩展：manifest 的 allowed_origins 不是当前扩展 ID（${ChromeAPI.runtime.id}）。" +
            s"重新运行 host setup，或显式用这个 ID 覆盖注册"
        else text
      showError(readable)
      setServerStatus(s"Error: $readable")
      if (notFound || forbidden) {
        serverWorkerDesired = false
        saveServerWorkerPreference(false)
        clearReconnectTimer()
        nativeRuntime.blockReconnect(30000.0, readable)
      }
    }
    failAllActiveTasksOnDisconnect()
    memoryPending.values.foreach(_.tryFailure(new RuntimeException("Local runtime disconnected")))
    memoryPending.clear()
    pendingRequests.clear()
    PopCanLog.warn(
      "server",
      s"native host disconnected${if (text.nonEmpty) s": $text" else ""}${if (fatal) " (after fatal)" else ""}"
    )
    if (!fatal) {
      // (<3s 且没有 lastError) = launcher / host 参数类的确定性失败，不能让 Memory 轮询继续制造 JVM。
      if (serverWorkerDesired && text.isEmpty && livedMs < 3000) {
        serverWorkerDesired = false
        clearReconnectTimer()
        setServerStatus("Error: host exited right after start")
        showError(
          "Native host 起来就退出了（不是端口被占那种，原因见 native-host 的 host.log）。" +
            "常见：launcher 的启动参数没传到（需 -Dpop-can.native-host=true 那份）、JDK/JRE 不对、或 exe 路径失效。"
        )
      } else if (serverWorkerDesired) {
        setServerStatus("Reconnecting...")
        scheduleReconnect()
      } else if (text.isEmpty) setServerStatus("Stopped")
    }
  }
  private def failAllActiveTasksOnDisconnect(): Unit                    =
    activeServerTaskProviders.keys.toList.foreach { taskId =>
      failServerTask(taskId)
    }
    queuedServerTasks.values.toList.flatten.foreach { msg =>
      failServerTask(msg.id.getOrElse(""))
    }
    queuedServerTasks.clear()
    runningTaskKeys.clear()
    thinClientRequests.clear()
    thinClientHeadsSent.clear()
  def stopServerWorker(): Unit                                          = {
    serverWorkerDesired = false
    saveServerWorkerPreference(false)
    clearReconnectTimer()
    // Stop only tears down the HTTP child service. The native host and Memory runtime stay alive
    // until the extension page's native port closes.
    httpStartSentOnCurrentPort = false
    postToHost(JsBridge.obj("type" -> ("server_stop")))
    pendingRequests.clear()
    setServerRunning(false)
    setServerStatus("Stopped")
    PopCanLog.info("server", "HTTP service stopped; Memory runtime remains available")
  }
  def loadServerWorkerPreference(): Unit                                =
    ChromeAPI.storage.local.get(
      js.Array(
        "serverWorkerEnabled",
        ActiveTasksStorageKey,
        CancelledTasksStorageKey
      )
    ).toFuture.foreach { result =>
      restoreServerWorkerState()
      val enabled = result.get("serverWorkerEnabled").exists(JsBridge.asBoolean(_))
      PopCanLog.info("server", s"preference enabled=$enabled")
      if (enabled) {
        serverWorkerDesired = true
        startServerWorker()
      }
    }
  def isServerActive(status: String): Boolean                           =
    LocalApiRuntimeStatus.isActive(status, isServerRunning())
}
