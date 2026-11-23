package com.just3bowls.background.runtime

import com.just3bowls.background.automation.cdp.*
import com.just3bowls.background.automation.provider.*
import com.just3bowls.background.bootstrap.BackgroundState
import com.just3bowls.background.webchat.WebChatService
import com.just3bowls.facade.*
import com.just3bowls.model.diagnostics.*
import com.just3bowls.model.chat.{RunPhase, RunProgress}
import com.just3bowls.model.localapi.*
import com.just3bowls.model.provider.*
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js

/** Slot-allocated provider turn: tab selection, task lifecycle, delivery and CDP completion. */
private[background] object ProviderTurnRunner {

  def run(input: InputPromptMessage): js.Any = {
    val provider                                  = input.provider.getOrElse("")
    val prompt                                    = input.prompt.getOrElse("")
    val inputXPath                                = input.inputXPath.getOrElse("//textarea")
    val generationId                              = input.generationId.getOrElse("")
    val streaming                                 = input.stream.getOrElse(false)
    val keyId                                     = input.keyId.toOption.filter(_.nonEmpty)
    val localTaskKind                             = input.localTaskKind.toOption.flatMap(LocalTaskKindWire.fromWire)
    // 投递方式相关：结构化 body + 会话 id 列表 + 上下文模式 + 是否允许注入（server 转发全部留空/关）。
    val inputBodyJson                             = input.bodyJson.toOption.getOrElse("")
    val inputMessageIds                           = input.messageIds.toOption.map(_.toList).getOrElse(Nil)
    val inputSessionId                            = input.sessionId.toOption.getOrElse("")
    val inputContextMode                          = TemplateInjection.modeOf(input.contextMode.toOption.getOrElse(""))
    val inputProtocolRepair                       =
      input.protocolRepair.toOption.map(_.trim).filter(_.nonEmpty)
    val inputInject                               = input.inject.toOption.getOrElse(false)
    // 调用方自带的附件（server 转发的 `file` 模式）：它已经把 body 与指针句一起备好了，后台原样用。
    val inputAttachment                           = {
      val attachName = input.attachmentName.toOption.getOrElse("")
      if (attachName.isEmpty) None
      else Some(PromptAttachment(attachName, input.attachmentBody.toOption.getOrElse("")))
    }
    // 正文投递模式（全局 `/prompt` 开关）：只影响"全量那一投"走注入还是附件；增量轮只投消息增量，不受它影响。
    val inputPromptMode                           = input.promptMode.toOption.getOrElse("")
    // 这一轮用过的对话要不要绑给会话（chat / Retry 为 true）：New Chat 出来的新 URL 成功失败都要绑住。
    val inputBindConv                             = input.bindConversation.toOption.getOrElse(false)
    val inputToolProfileId                        = input.toolProfileId.toOption.getOrElse("")
    // 这一轮是复用同一段对话（delta）还是每轮 New Chat（full）：显式字段优先，其余由上下文模式推导。
    val conversation                              = conversationPolicyOf(
      input.conversation.toOption.getOrElse(""),
      inputSessionId,
      inputContextMode
    )
    // 会话 URL 列表里按过 × 的 provider：这一轮强制开新对话（新 URL 之后由水位线按当时的地址写回归属）。
    val conversationF: Future[ConversationPolicy] =
      if (inputSessionId.isEmpty) Future.successful(conversation)
      else
        SessionFreshChats
          .isPending(inputSessionId, provider)
          .map(pending => if (pending) ConversationPolicy.Fresh else conversation)
          .recover { case _ => conversation }
    val taskSource                                =
      if (localTaskKind.nonEmpty) TaskSource.Local
      else TaskSource.Server
    def report(progress: RunProgress): Unit       =
      PageDelivery.deliverToPage(
        generationId,
        provider,
        Msg.tabAiProgress(generationId, progress.copy(provider = provider)),
        "progress"
      )
    PopCanLog
      .withContext("cdp", "gen" -> generationId, "provider" -> provider)
      .info(
        s"INPUT_PROMPT stream=$streaming mode=cdp promptChars=${prompt.length} " +
          s"toolProfile=${PopCanLog.short(inputToolProfileId, 32)}"
      )
    new js.Promise[js.Any]((resolve, _) => {
      val bgEntryAt                                         = js.Date.now()
      // 收尾统一入口：先把 SW 侧这段的分阶段耗时并进 trace，再 resolve，避免 bgTotal 漏记。
      def finishWith(result: js.Any): Unit                  = {
        PhaseTiming.mark(generationId, "bgTotal", js.Date.now() - bgEntryAt)
        PhaseTiming.flush(generationId)
        resolve(result)
      }
      val managedKeyId                                      = keyId.getOrElse(ApiKeyStore.SystemApiKeyId)
      report(RunProgress(RunPhase.PreparingProvider))
      val tabFut: Future[Either[String, (Int, ManagedTab)]] = {
        val allocStartedAt = js.Date.now()
        val allocated      = for {
          conv        <- conversationF
          providerUrl <- ServerTabManager.loadProviderUrl(provider)
          result      <-
            if (providerUrl.trim.isEmpty) {
              PopCanLog.warn("background", s"provider url is not configured provider=$provider")
              Future.successful(Left(s"Provider URL is not configured for $provider"))
            } else {
              ManagedTabManager.findOrCreateTab(
                provider,
                managedKeyId,
                providerUrl,
                conversation = conv,
                toolProfileId = inputToolProfileId,
                // 分配按会话归属：同一个会话继续用同一个 tab，只有 slot 里是别人会话的对话时才另找/新建。
                sessionId = inputSessionId,
                gen = generationId,
                onProgress = report
              )
                .map { case (tabId, managed) => Right((tabId, managed)) }
                .recover { case e =>
                  PopCanLog.error("background", s"allocate server tab failed provider=$provider", e)
                  Left(
                    s"Provider tab unavailable for $provider: ${Option(e.getMessage).getOrElse(e.toString)}"
                  )
                }
            }
        } yield result
        allocated
          .recover { case e =>
            PopCanLog.error("background", s"prepare provider tab failed provider=$provider", e)
            Left(
              s"Provider tab unavailable for $provider: ${Option(e.getMessage).getOrElse(e.toString)}"
            )
          }
          .map { result =>
            // alloc = 整段 tab 分配（含 prepare）；扣掉 prepare 后与其它 phase 互不重叠。
            PhaseTiming.markExcluding(generationId, "alloc", js.Date.now() - allocStartedAt, "prepare")
            result
          }
      }
      tabFut.foreach { allocation =>
        allocation match {
          case Right((tabId, _)) =>
            if (BackgroundState.cancelledCdpTasks.contains(generationId)) {
              ManagedTabManager
                .finishTask(
                  provider,
                  managedKeyId,
                  generationId,
                  ok = false,
                  reason = Some("Task canceled")
                )
                .foreach(_ => finishWith(Msg.promptResponse(false, "Task canceled")))
            } else {
              val now  = js.Date.now()
              val task = Task(
                taskId = generationId,
                tabId = tabId,
                source = taskSource,
                localTaskKind = localTaskKind,
                startedAt = now,
                finishedAt = None,
                state = TaskState(TaskOperation.TypePrompt, Phase.Running)
              )
              ManagedTabManager
                .startTask(provider, managedKeyId, task)
                .flatMap { _ =>
                  BackgroundState.activeCdpTasks.update(generationId, tabId)
                  val flowLog                           = PopCanLog.withContext(
                    "cdp",
                    "gen"      -> generationId,
                    "provider" -> provider,
                    "tab"      -> tabId.toString
                  )
                  flowLog.info("preparing managed tab for task")
                  var rawStarted                        = false
                  var requestObserved                   = false
                  def onRequestObserved(): Future[Unit] = {
                    requestObserved = true
                    val usageF =
                      if (inputSessionId.isEmpty) Future.successful(())
                      else
                        ManagedTabManager
                          .noteSessionUsage(provider, managedKeyId, inputSessionId)
                          .flatMap(_ => SessionFreshChats.clear(inputSessionId, provider))
                    val bindF  =
                      if (inputBindConv && inputSessionId.nonEmpty)
                        WebChatService.bindTabConversation(tabId, inputSessionId, provider, inputToolProfileId)
                      else Future.successful(())
                    usageF
                      .flatMap(_ => bindF)
                      .map { _ =>
                        TraceStore.noteEvidence(generationId, "request observed: session usage / binding committed")
                        ()
                      }
                      .recover { case e =>
                        flowLog.warn(s"request observed binding failed: ${e.getMessage}")
                      }
                  }
                  val cdpFuture                         =
                    TabKeepAlive.ensureTabReady(tabId).flatMap { _ =>
                      ServerTabManager.loadProviderConfig(provider).flatMap { config =>
                        BackgroundState.localRawParsers.update(
                          generationId,
                          config
                            .flatMap(_.parserRule)
                            .map { rule =>
                              // 规则快照进 trace，规则以后改了也能回放这次 run。
                              TraceStore.noteRule(generationId, rule)
                              new LocalRawParser(
                                rule,
                                handler => TraceStore.noteHandler(generationId, handler)
                              )
                            }
                        )
                        val apiPatterns    = config.map(_.apiPatterns).getOrElse(Nil)
                        val ruleJson       = config.flatMap(_.parserRule).getOrElse("")
                        // `file` 模式的附件怎么交给页面：由 provider 配置决定（这层 lamdba 之外拿不到 config，所以随 tuple 带出去）。
                        val attachStrategy = config.map(_.attachmentStrategy).getOrElse("")
                        val attachSelector = config.map(_.attachmentSelector).getOrElse("")
                        // 投递方式：能用规则改写请求体就"输入用户文本 + 注入模版"，否则退回整段模版。
                        TemplateInjection
                          .resolve(
                            provider = provider,
                            config = config,
                            tabId = tabId,
                            typedPrompt = prompt,
                            bodyJson = inputBodyJson,
                            messageIds = inputMessageIds,
                            sessionId = inputSessionId,
                            mode = inputContextMode,
                            protocolRepair = inputProtocolRepair,
                            inject = inputInject,
                            promptMode = inputPromptMode,
                            toolProfileId = inputToolProfileId
                          )
                          .map { injection =>
                            TraceStore.noteInject(generationId, injection.modeTag, injection.template)
                            if (injection.detail.nonEmpty) {
                              TraceStore.noteEvidence(generationId, injection.detail)
                            }
                            flowLog.info(
                              s"delivery mode=${injection.modeTag} template=${injection.template.isDefined} " +
                                s"typedChars=${injection.typedText.length}"
                            )
                            if (injection.notice.nonEmpty) {
                              PageDelivery.deliverToPage(
                                generationId,
                                provider,
                                Msg.tabAiNotice(generationId, injection.notice),
                                "notice"
                              )
                            }
                            (
                              apiPatterns,
                              ruleJson,
                              injection,
                              injection.attachment,
                              attachStrategy,
                              attachSelector,
                              config.map(_.followUpPatterns).getOrElse(Nil),
                              config.map(_.followUpIdentityPath).getOrElse("")
                            )
                          }
                      }.flatMap {
                        case (
                              apiPatterns,
                              ruleJson,
                              injection,
                              fileAttachment,
                              attachStrategy,
                              attachSelector,
                              followUpPatterns,
                              followUpIdentityPath
                            ) =>
                          flowLog.info(s"starting CDP flow apiPatterns=${apiPatterns.mkString(",")}")
                          CdpFlowExecutor.executeCDPFlow(
                            provider,
                            tabId,
                            injection.typedText,
                            inputXPath,
                            generationId,
                            raw => {
                              if (!rawStarted) {
                                rawStarted = true
                                report(RunProgress(RunPhase.LocatingResponse))
                              }
                              PageDelivery.forwardRawChunk(generationId, provider, raw)
                              ()
                            },
                            onRequest = (url, _, postData) => {
                              // 普通轮只记有上限的观测样本；显式 capture 轮保留完整字节给 fixture 抓取。
                              val remembered =
                                if (localTaskKind.contains(LocalTaskKind.Capture))
                                  TemplateInjection.rememberCapturedRequestBody(
                                    generationId,
                                    provider,
                                    url,
                                    postData
                                  )
                                else TemplateInjection.rememberRequestBody(provider, url, postData)
                              remembered.foreach(_ => ())
                            },
                            onInputStatus = _ =>
                              ManagedTabManager.updateTaskState(
                                generationId,
                                TaskOperation.TypePrompt,
                                Phase.Success
                              ).foreach(_ => ()),
                            onSendStatus = _ =>
                              ManagedTabManager.updateTaskState(
                                generationId,
                                TaskOperation.ClickSend,
                                Phase.Success
                              ).foreach(_ => ()),
                            onResultStatus = (ok, error) =>
                              ManagedTabManager.updateTaskState(
                                generationId,
                                TaskOperation.AwaitResponse,
                                if (ok) Phase.Success else Phase.Failed(error)
                              ).foreach(_ => ()),
                            onProgress = report,
                            // `file` 模式的全量附件由后台按水位线决定（增量轮没有附件）：调用方自己带的（server 转发）优先保留。
                            attachment = fileAttachment.orElse(inputAttachment),
                            attachmentStrategy = attachStrategy,
                            attachmentSelector = attachSelector,
                            followUpPatterns = followUpPatterns,
                            followUpIdentityPath = followUpIdentityPath,
                            apiPatterns = if (apiPatterns.nonEmpty) Some(apiPatterns) else None,
                            injectTemplate = injection.template,
                            injectRule = ruleJson,
                            onDelivered = injection.commit.getOrElse(() => ()),
                            onRequestObserved = onRequestObserved,
                            onInjectFailed = message => {
                              // 注入失败：记住这家 provider 不可注入（规则改了会自动重试），本轮已经以可读错误结束。
                              TemplateInjection.markBlocked(provider, ruleJson).foreach(_ => ())
                              flowLog.warn(PopCanLog.short(message, 200))
                            }
                          ).toFuture
                      }
                    }
                  cdpFuture
                    .flatMap { result =>
                      BackgroundState.activeCdpTasks.remove(generationId)
                      BackgroundState.clearChunkParsingState(generationId)
                      val flowResult  = result.asInstanceOf[CdpFlowResult]
                      val ok          = flowResult.ok.getOrElse(false)
                      // flow 自己把错误兜住了（`ok=false` 是它的正常失败出口，不是 reject），所以这里
                      // 也要把这一轮按失败收口：错误进 trace、tab 与会话绑定错位时补一行 evidence、
                      // 并把可读原因写进 task 记录。少了这一步，失败轮在 Traces 里就是一个空壳。
                      val failureText =
                        if (ok) ""
                        else
                          flowResult.content.getOrElse("")
                      val reasonOpt   =
                        if (ok) None else Some(if (failureText.nonEmpty) failureText else "Task failed")
                      if (!ok)
                        PageDelivery.recordFailureEvidence(
                          generationId,
                          provider,
                          inputSessionId,
                          tabId,
                          new Exception(reasonOpt.get)
                        )
                      val bindF       =
                        if (requestObserved && inputBindConv && inputSessionId.nonEmpty)
                          WebChatService.bindTabConversation(tabId, inputSessionId, provider, inputToolProfileId)
                        else Future.successful(())
                      bindF
                        .flatMap(_ =>
                          ManagedTabManager
                            .finishTask(
                              provider,
                              managedKeyId,
                              generationId,
                              ok = ok,
                              reason = reasonOpt
                            )
                        )
                        .map(_ => result)
                    }
                    .recover { case e =>
                      BackgroundState.activeCdpTasks.remove(generationId)
                      BackgroundState.clearChunkParsingState(generationId)
                      val bindF =
                        if (requestObserved && inputBindConv && inputSessionId.nonEmpty)
                          WebChatService.bindTabConversation(tabId, inputSessionId, provider, inputToolProfileId)
                        else Future.successful(())
                      bindF
                        .flatMap(_ =>
                          ManagedTabManager
                            .finishTask(
                              provider,
                              managedKeyId,
                              generationId,
                              ok = false,
                              reason = Some(e.getMessage)
                            )
                        )
                        .foreach(_ => ())
                      flowLog.error("CDP flow error", e)
                      // 失败也留证据：错误进 trace 索引；tab URL 与会话绑定 URL 错位时补一行
                      // （"文字进了输入框却没发出去"很多就是这种错位：tab 停在根/别的对话上）。
                      PageDelivery.recordFailureEvidence(generationId, provider, inputSessionId, tabId, e)
                      Msg.promptResponse(false, e.getMessage)
                    }
                }
                .recover { case e =>
                  BackgroundState.activeCdpTasks.remove(generationId)
                  BackgroundState.clearChunkParsingState(generationId)
                  PopCanLog
                    .withContext("cdp", "gen" -> generationId, "provider" -> provider)
                    .error("start task failed", e)
                  // 连 flow 都没进（分配 / marker / focus 之前就挂了）也要在 trace 里留一条错。
                  TraceStore.noteError(generationId, Option(e.getMessage).getOrElse(e.toString))
                  Msg.promptResponse(false, e.getMessage)
                }
                .foreach(finishWith)
            }
          case Left(message)     =>
            TraceStore.noteError(generationId, message)
            finishWith(Msg.promptResponse(false, message))
        }
      }
    })
  }

  /**
   * 这一轮用哪种对话策略（复用同一段对话 / 每轮 New Chat）。
   *
   * 优先看调用方显式给的 `conversation`（抓包会复位）；否则由上下文模式推导：`full` 每轮 New Chat、`delta` 复用； 没有会话的 run（server 转发）默认每轮 New Chat。
   */
  private def conversationPolicyOf(explicit: String, sessionId: String, contextMode: String): ConversationPolicy =
    Option(explicit).map(_.trim.toLowerCase) match {
      case Some("reuse") => ConversationPolicy.Reuse
      case Some("fresh") => ConversationPolicy.Fresh
      case _             =>
        if (sessionId.isEmpty || contextMode == TemplateInjection.ModeFull) ConversationPolicy.Fresh
        else ConversationPolicy.Reuse
    }
}
