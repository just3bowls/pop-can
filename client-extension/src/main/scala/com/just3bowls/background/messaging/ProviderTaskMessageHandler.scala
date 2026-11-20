package com.just3bowls.background.messaging

import com.just3bowls.facade.JsBridge

import com.just3bowls.background.automation.cdp.CdpSession
import com.just3bowls.background.automation.provider.LocalRawParser
import com.just3bowls.background.bootstrap.BackgroundState
import com.just3bowls.background.runtime.ProviderTurnRunner
import com.just3bowls.facade.*
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.provider.ProviderRequestRule

import scala.collection.mutable
import scala.scalajs.js

private[background] object ProviderTaskMessageHandler {
  val types: Set[String]                = Set("INPUT_PROMPT", "SESSION_CONTEXT_FULL", "CANCEL_TEST", "RULE_REPLAY")
  def handles(msgType: String): Boolean = types.contains(msgType)

  def handle(msgType: String, message: js.Any, sender: MessageSender): js.Any =
    msgType match {
      case "INPUT_PROMPT"         =>
        ProviderTurnRunner.run(message.asInstanceOf[InputPromptMessage])
      case "SESSION_CONTEXT_FULL" =>
        val dynamic  = JsBridge.asObject(message)
        val provider = JsBridge.undefinedString(dynamic.provider).getOrElse("")
        val keyId    = JsBridge.undefinedString(dynamic.keyId).getOrElse("")
        PopCanLog.debug("tabs", s"context full ignored by managed tab model provider=$provider key=$keyId")
        js.undefined
      case "CANCEL_TEST"          =>
        BackgroundState.testCancelRequested = true
        if (BackgroundState.activeTestGenerationId.nonEmpty) {
          BackgroundState.cancelledCdpTasks += BackgroundState.activeTestGenerationId
        }
        if (BackgroundState.activeTestTabId > 0) {
          CdpSession.cancelTab(BackgroundState.activeTestTabId, "Test canceled")
        }
        PopCanLog.info(
          "background",
          s"test cancellation requested gen=${BackgroundState.activeTestGenerationId} tab=${BackgroundState.activeTestTabId}"
        )
        js.Promise.resolve[js.Any](JsBridge.obj("ok" -> (true)))
      case "RULE_REPLAY"          =>
        // 开发用（fixtures 回归网）：用**真引擎**把一段 raw 按给定规则跑一遍，回 content / 命中 handler / mode / 普查。
        // 只接受扩展页发来的请求（内容脚本 / 网页都拿不到这条通道），不碰页面、不动 CDP。
        val replayMsg = JsBridge.asObject(message)
        val fromExt   = sender.tab.toOption
          .flatMap(_.url.toOption)
          .exists(url => url.startsWith("chrome-extension://"))
        if (!fromExt)
          js.Promise.resolve[js.Any](
            JsBridge.obj(
              "ok"    -> (false),
              "error" -> ("RULE_REPLAY is only accepted from extension pages")
            )
          )
        else {
          val rule              = JsBridge.undefinedString(replayMsg.rule).getOrElse("")
          val raw               = JsBridge.undefinedString(replayMsg.raw).getOrElse("")
          val source            = JsBridge.undefinedString(replayMsg.sourceUrl).getOrElse("")
          val requestBodyBase64 =
            JsBridge.undefinedString(replayMsg.requestBodyBase64).toOption.map(_.trim).filter(_.nonEmpty)
          val requestTemplate   =
            JsBridge.undefinedString(replayMsg.requestTemplate).toOption.map(_.trim).filter(_.nonEmpty).getOrElse(
              "Pop Can request replay"
            )
          try {
            requestBodyBase64.flatMap(bytesFromBase64) match {
              case Some(body)                         =>
                val original  = ProviderRequestRule.readUserText(rule, body)
                val rewritten = ProviderRequestRule.writeUserText(rule, body, requestTemplate)
                val roundTrip = rewritten.flatMap(next => ProviderRequestRule.readUserText(rule, next))
                js.Promise.resolve[js.Any](JsBridge.obj(
                  "ok"                 -> (original.nonEmpty),
                  "requestText"        -> (original.getOrElse("")),
                  "requestRewriteText" -> (roundTrip.getOrElse("")),
                  "requestRewritten"   -> (rewritten.nonEmpty),
                  "requestError"       -> (if (rewritten.nonEmpty) "" else ProviderRequestRule.diagnose(rule, body))
                ))
              case None if requestBodyBase64.nonEmpty =>
                js.Promise.resolve[js.Any](
                  JsBridge.obj("ok" -> (false), "error" -> ("requestBodyBase64 is not valid base64"))
                )
              case None                               =>
                val hits   = mutable.Set.empty[String]
                val parser = new LocalRawParser(rule, handler => { hits += handler; () })
                try {
                  if (raw.nonEmpty) parser.feed(raw, source)
                  parser.finish()
                } catch {
                  case error: Throwable => throw new IllegalStateException("response fixture replay failed", error)
                }
                // 整段喂只能校验"最终过滤结果"；分帧回放才校验"消费者实际收到的那份正文" ——
                // 正文过滤的结算 bug（长块被删时把已下发内容之后的恢复点推错）只在分帧时暴露。
                def replayChunks(chunks: List[String]): (String, Int) = {
                  val streamed = new StringBuilder
                  val chunked  = new LocalRawParser(rule, _ => ())
                  var resets   = 0
                  chunks.foreach { chunk =>
                    chunked.feed(chunk, source).foreach { delta =>
                      if (delta.resetContent) {
                        streamed.clear()
                        resets += 1
                      }
                      streamed.append(delta.contentDelta)
                    }
                  }
                  chunked.finish().foreach { delta =>
                    if (delta.resetContent) {
                      streamed.clear()
                      resets += 1
                    }
                    streamed.append(delta.contentDelta)
                  }
                  (streamed.result(), resets)
                }
                val frames = if (raw.isEmpty) List.empty else raw.split("(?<=\\n\\n)").toList
                val ssePlan                                           = if (frames.isEmpty) List(raw) else frames
                val plans                                             = List(
                  "sse"       -> ssePlan,
                  "bytes7"    -> raw.grouped(7).toList,
                  "bytes257"  -> raw.grouped(257).toList,
                  "bytes4096" -> raw.grouped(4096).toList
                )
                val replayed                                          = plans.map { case (name, chunks) =>
                  val (text, resets) = replayChunks(chunks)
                  name -> (text, resets)
                }
                val (streamed, resets) = replayed.find(_._1 == "sse").map(_._2).getOrElse("" -> 0)
                js.Promise.resolve[js.Any](JsBridge.obj(
                  "ok"            -> (true),
                  "content"       -> (parser.filteredContent),
                  "streamed"      -> (streamed),
                  "streamedPlans" -> (js.Dictionary(replayed.map { case (name, value) =>
                    name -> js.Array[js.Any](value._1, value._2)
                  }*)),
                  "resets"        -> (resets),
                  "finishReason"  -> (if (parser.hasFinishSignal) "stop" else ""),
                  "mode"          -> (parser.currentMode),
                  "error"         -> (parser.currentError),
                  "census"        -> (parser.census),
                  "hits"          -> (js.Array(hits.toSeq.sorted*))
                ))
            }
          } catch {
            case e: Throwable =>
              js.Promise.resolve[js.Any](
                JsBridge.obj(
                  "ok"    -> (false),
                  "error" -> (replayFailure(e))
                )
              )
          }
        }
      case _                      => js.undefined
    }

  private def bytesFromBase64(value: String): Option[Array[Byte]] =
    try {
      val binary = JsBridge.asString(JsBridge.atob(value))
      Some(binary.toCharArray.map(ch => (ch & 0xff).toByte))
    } catch {
      case _: Throwable => None
    }

  private def replayFailure(error: Throwable): String = {
    val chain = Iterator
      .iterate(Option(error))(_.flatMap(value => Option(value.getCause)))
      .takeWhile(_.nonEmpty)
      .flatten
      .toList
    chain
      .map { item =>
        val text = Option(item.getMessage).filter(_.nonEmpty).getOrElse("<no message>")
        s"${item.getClass.getSimpleName}: $text"
      }
      .mkString(" caused by ")
  }
}
