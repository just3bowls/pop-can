package com.just3bowls.background.automation.provider

import com.just3bowls.background.bootstrap.*
import com.just3bowls.background.runtime.*
import com.just3bowls.background.automation.cdp.*

import com.just3bowls.facade.{InputPromptMessage, PromptResponse}
import com.just3bowls.model.localapi.Msg
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.diagnostics.TraceStore
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 网页原生 host tab 的一轮：插件主页 agent 把每一轮交给 SW，SW 直接驱动用户已经打开的那条 provider tab。
 *
 * 与 slot 分配路径共用 `TemplateInjection` 决策 / `CdpFlowExecutor` / `PageDelivery.forwardRawChunk`；区别只有三点：不分配 tab（因此绝不 New
 * Chat）、优先后台 Tab、 不写 provider slot 的 task 状态。
 */
private[background] object HostPrompt {
  def run(input: InputPromptMessage, tabId: Int): Future[js.Object] = {
    val provider     = input.provider.toOption.getOrElse("")
    val prompt       = input.prompt.toOption.getOrElse("")
    val inputXPath   = input.inputXPath.toOption.getOrElse("//textarea")
    val generationId = input.generationId.toOption.getOrElse("")
    val bodyJson     = input.bodyJson.toOption.getOrElse("")
    val messageIds   = input.messageIds.toOption.map(_.toList).getOrElse(Nil)
    val sessionId    = input.sessionId.toOption.getOrElse("")
    val contextMode  = TemplateInjection.modeOf(input.contextMode.toOption.getOrElse(""))
    val inject       = input.inject.toOption.getOrElse(false)
    val startedAt    = js.Date.now()
    val log          = PopCanLog.withContext(
      "cdp",
      "gen"      -> generationId,
      "provider" -> provider,
      "tab"      -> tabId.toString
    )
    def finish(result: js.Object): Future[js.Object] = {
      PhaseTiming.mark(generationId, "bgTotal", js.Date.now() - startedAt)
      PhaseTiming.flush(generationId)
      cleanup(generationId)
      Future.successful(result)
    }
    TabKeepAlive.ensureTabReady(tabId).flatMap[js.Object] { _ =>
      ServerTabManager.loadProviderConfig(provider).flatMap[js.Object] { config =>
        BackgroundState.localRawParsers.update(
          generationId,
          config.flatMap(_.parserRule).map { rule =>
            TraceStore.noteRule(generationId, rule)
            new LocalRawParser(rule, handler => TraceStore.noteHandler(generationId, handler))
          }
        )
        val apiPatterns = config.map(_.apiPatterns).getOrElse(Nil)
        val ruleJson    = config.flatMap(_.parserRule).getOrElse("")
        // 网页原生的轮次只做"注入"：对话是用户自己的，不往里塞附件，所以 `promptMode` 一律按 inline（默认值）。
        TemplateInjection
          .resolve(provider, config, tabId, prompt, bodyJson, messageIds, sessionId, contextMode, None, inject)
          .flatMap[js.Object] { injection =>
            TraceStore.noteInject(generationId, injection.modeTag, injection.template)
            if (injection.detail.nonEmpty) TraceStore.noteEvidence(generationId, injection.detail)
            if (injection.notice.nonEmpty) {
              PageDelivery.deliverToPage(
                generationId,
                provider,
                Msg.tabAiNotice(generationId, injection.notice),
                "notice"
              )
            }
            log.info(
              s"host round mode=${injection.modeTag} template=${injection.template.isDefined} " +
                s"typedChars=${injection.typedText.length}"
            )
            BackgroundState.activeCdpTasks.update(generationId, tabId)
            CdpFlowExecutor
              .executeCDPFlow(
                provider,
                tabId,
                injection.typedText,
                inputXPath,
                generationId,
                raw => {
                  PageDelivery.forwardRawChunk(generationId, provider, raw)
                  ()
                },
                onRequest = (url, _, postData) =>
                  TemplateInjection.rememberRequestBody(provider, url, postData).foreach(_ => ()),
                apiPatterns = if (apiPatterns.nonEmpty) Some(apiPatterns) else None,
                followUpPatterns = config.map(_.followUpPatterns).getOrElse(Nil),
                followUpIdentityPath = config.map(_.followUpIdentityPath).getOrElse(""),
                injectTemplate = injection.template,
                injectRule = ruleJson,
                onDelivered = injection.commit.getOrElse(() => ()),
                onInjectFailed = message => {
                  TemplateInjection.markBlocked(provider, ruleJson).foreach(_ => ())
                  log.warn(PopCanLog.short(message, 200))
                }
              )
              .toFuture
              .flatMap { result =>
                val typed = result.asInstanceOf[PromptResponse]
                val ok    = typed.ok.getOrElse(false)
                val text  = typed.content.getOrElse("")
                if (!ok) {
                  log.warn(s"host round failed: ${PopCanLog.short(text, 200)}")
                  PageDelivery.recordFailureEvidence(
                    generationId,
                    provider,
                    sessionId,
                    tabId,
                    new Exception(if (text.nonEmpty) text else "Task failed")
                  )
                }
                finish(result)
              }
              .recoverWith { case e =>
                TraceStore.noteError(generationId, Option(e.getMessage).getOrElse(e.toString))
                PageDelivery.recordFailureEvidence(generationId, provider, sessionId, tabId, e)
                finish(
                  Msg.promptResponse(false, Option(e.getMessage).getOrElse("Host round failed"))
                )
              }
          }
      }
    }.recoverWith { case e =>
      TraceStore.noteError(generationId, Option(e.getMessage).getOrElse(e.toString))
      finish(Msg.promptResponse(false, Option(e.getMessage).getOrElse("Host round failed")))
    }
  }
  private def cleanup(generationId: String): Unit                   = {
    BackgroundState.activeCdpTasks.remove(generationId)
    BackgroundState.clearChunkParsingState(generationId)
  }
}
