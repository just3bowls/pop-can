package com.just3bowls.background.automation.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.background.webchat.*

import com.just3bowls.api.protocol.Bytes
import com.just3bowls.api.marker.PromptArtifact
import com.just3bowls.facade.{ChromeAPI, LastWebRequestBody}
import com.just3bowls.model.chat.{AgentCapabilitySnapshot, AgentToolSpec, ChatMessage, ChatScenario}
import com.just3bowls.model.provider.ParserProviderInfo
import com.just3bowls.model.chat.AgentBody
import com.just3bowls.model.chat.OpenAiRequestPreview
import com.just3bowls.model.provider.PromptAttachment
import com.just3bowls.model.provider.PromptSettings
import com.just3bowls.model.provider.ProviderRequestRule
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.chat.ThinOpenAi
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 一次模型调用的投递方式：输入框里打什么、要不要改写请求体注入模版、投递成功后往水位线上写什么。
 *
 * `template` 为 `None` 只用于 file / 显式不注入路径；inline 模型投递必须产生 template，否则整轮直接失败。
 */
final private[background] case class InjectionPlan(
    typedText: String,
    template: Option[String],
    notice: String,
    modeTag: String,
    commit: Option[() => Unit],
    /** 给 trace evidence 用的补充说明（例如增量本轮投了几条、跳过几条自家回答）。 */
    detail: String = "",
    /** `file` 模式的全量那一投：这份 body 作为附件交给页面（`template` 为空，正文不进输入框也不改写请求体）。 */
    attachment: Option[PromptAttachment] = None
)

/**
 * 「输入用户文本 + 劫持请求体注入模版」的决策与水位线。
 *
 * 水位线统一落在 `WebChatHosts`（**按对话 URL 记账**，和网页原生共用同一张表）：`seedToMessageId` = 这段对话已经拿到的最后一条会话消息 id。 `delta`
 * 模式就靠它把载荷裁成"自那之后的新消息"；换对话（URL 不同）、id 不在路径上都退回全量。
 */
private[background] object TemplateInjection {
  val ModeDelta = "delta"

  /** 每轮新对话（用户可见名 `new`）。 */
  val ModeNew  = "new"
  val ModeFull = ModeNew

  /** 注入失败的错误都带这个前缀，调用方据此把这个 provider 标记成不可注入。 */
  val FailurePrefix               = "template inject failed"
  private val BlockedKeyPrefix    = "thInjectBlocked:"
  private val CaptureKeyPrefix    = "thProviderRequestCapture:"
  private val ObserveLimit        = 20000
  def modeOf(raw: String): String =
    Option(raw).map(_.trim.toLowerCase) match {
      case Some(ModeNew) => ModeNew
      case _             => ModeDelta
    }

  /**
   * 这一轮该用哪种形态：**没有水位线（首投）或显式 full ⇒ 完整模版**，否则极简增量。
   *
   * 首投必须是完整模版 —— 网页原生第一次注入时，页面上下文里还没有过我们的 persona / protocol / 工具 schema，只发一段 messages 模型不会按协议答。
   */
  def formOf(mode: String, hasWatermark: Boolean): String =
    if (mode == ModeFull || !hasWatermark) ModeFull else ModeDelta

  /**
   * 增量形态：只投消息增量本身（messages 格式），其余全靠对方会话上下文。
   *
   * `tools` 跟着一起带上：工具声明是**每一次请求**的一部分，增量轮不重发它就等于后面几轮没有工具可用（模型只能靠上下文里的历史工具轮"模仿"）。
   */
  def deltaPayload(messages: List[js.Any], tools: Option[js.Array[js.Any]] = None): String = {
    val base = JsBridge.obj("messages" -> (js.Array(messages*)))
    tools.filter(_.nonEmpty).foreach(value => base.tools = value)
    js.JSON.stringify(base)
  }

  /**
   * 全量形态：把这一段消息当成一份 API 请求体，套完整 `ThinOpenAi` 模版。
   *
   * `useBrowserTools` 时补上场景 system + 工具声明，让它和插件页 agent 的首轮 body 完全同构 —— 网页原生第一轮因此也能合法地发起工具调用。
   */
  def fullPayloadOf(provider: String, messages: List[js.Any], toolDefs: List[AgentToolSpec]): String = {
    val tools      = AgentBody.toolsJson(toolDefs)
    val capability = ChatScenario.capabilityPolicy(AgentCapabilitySnapshot(toolDefs))
    val withRole   = AgentBody.systemMessage(capability) :: messages
    ThinOpenAi.buildPrompt(AgentBody.body(provider, js.Array(withRole*), tools))
  }

  /**
   * 网页原生（胶囊）那侧的注入正文：`form` 决定形态，`delta` 是"这段对话还没有的会话消息"，`userText` 是用户刚敲的那句。
   *
   * 与插件页共用同一套形态判定/序列化（插件页的增量在 `resolve` 里裁剪 body，这里直接给消息列表）。
   */
  def buildNativePayload(
      form: String,
      provider: String,
      delta: List[ChatMessage],
      userText: String,
      toolDefs: List[AgentToolSpec],
      memoryContext: String = ""
  ): String = {
    val semanticUser = PromptArtifact.userTextOrOriginal(userText)
    val memory       =
      Option(memoryContext).map(_.trim).filter(_.nonEmpty)
        .map(value => JsBridge.obj("role" -> ("system"), "content" -> (value)))
        .toList
    val history      = delta.map(message => JsBridge.obj("role" -> (message.role), "content" -> (message.content)))
    val pending      =
      if (semanticUser.nonEmpty) List(JsBridge.obj("role" -> ("user"), "content" -> (semanticUser))) else Nil
    val messages     = memory ++ history ++ pending
    if (form == ModeDelta)
      deltaPayload(
        AgentBody.systemMessage(ChatScenario.capabilityPolicy(AgentCapabilitySnapshot(toolDefs))) :: messages,
        Option.when(toolDefs.nonEmpty)(AgentBody.toolsJson(toolDefs))
      )
    else fullPayloadOf(provider, messages, toolDefs)
  }

  /** 页面给的上下文 id 数不对（LangGraph 改过消息数组）时不切片，直接全量。 */
  def resolve(
      provider: String,
      config: Option[ParserProviderInfo],
      tabId: Int,
      typedPrompt: String,
      bodyJson: String,
      messageIds: List[String],
      sessionId: String,
      mode: String,
      protocolRepair: Option[String],
      inject: Boolean,
      /** 正文怎么送：`inline` = 改写进请求体（全量那一投套整份模版、增量轮只投 messages）；`file` = 造成 `.txt` 附件（全量装整份 body，`continue` 装增量）。 */
      promptMode: String = PromptSettings.Inline
  ): Future[InjectionPlan] = {
    val isFile = PromptSettings.normalize(promptMode) == PromptSettings.File
    val typed  = InjectionPlan(typedPrompt, None, "", "typed", None)
    val repair = protocolRepair.map(_.trim).filter(_.nonEmpty)
    // 不注入只剩抓包等显式路径；模型投递（chat / server）必须走下面的统一决策。
    if (!inject) Future.successful(typed)
    else if (bodyJson.trim.isEmpty)
      if (isFile) Future.successful(typed)
      else inlineUnavailable("bodyJson is empty")
    else if (config.isEmpty)
      if (isFile)
        Future.successful(
          typedDelivery(
            provider = provider,
            promptMode = promptMode,
            typedPrompt = typedPrompt,
            bodyJson = bodyJson,
            protocolRepair = protocolRepair,
            commit =
              commitTip(provider, sessionId, messageIds, messageCount(bodyJson), tabId, lastUserTextOf(bodyJson)),
            reason = ""
          )
        )
      else inlineUnavailable("provider config is missing")
    else {
      val cfg          = config.get
      val rule         = cfg.parserRule.getOrElse("")
      val httpPatterns = cfg.apiPatterns.filter(pattern => pattern.trim.toLowerCase.startsWith("http"))
      if (httpPatterns.isEmpty || !ProviderRequestRule.hasRule(rule))
        fileFallbackOrInlineFailure(
          provider = provider,
          promptMode = promptMode,
          typedPrompt = typedPrompt,
          bodyJson = bodyJson,
          protocolRepair = protocolRepair,
          sessionId = sessionId,
          messageIds = messageIds,
          bodyCount = messageCount(bodyJson),
          tabId = tabId,
          reason = "no usable http apiPattern / request.userText rule"
        )
      else
        for {
          blocked  <- readBlocked(provider, rule)
          lastBody <- readLastRequestBody(provider)
          tabUrl   <- currentUrl(tabId)
          record   <- WebChatHosts.findConversation(tabUrl)
        } yield {
          val typedFallback = (reason: String) =>
            fallback(
              provider = provider,
              promptMode = promptMode,
              typedPrompt = typedPrompt,
              bodyJson = bodyJson,
              protocolRepair = protocolRepair,
              sessionId = sessionId,
              messageIds = messageIds,
              bodyCount = messageCount(bodyJson),
              tabId = tabId,
              reason = reason
            )
          if (blocked)
            if (isFile) typedFallback("request injection is disabled for this provider")
            else
              throw new Exception(failureText(
                s"inline requires request rewriting, but request injection is disabled for $provider. " +
                  s"Clear storage key thInjectBlocked:$provider, fix the provider rule, or choose file mode."
              ))
          else if (
            lastBody.exists(body => ProviderRequestRule.readUserText(rule, Bytes.fromPostDataString(body)).isEmpty)
          )
            if (isFile) typedFallback("request.userText does not match the last observed request body")
            else
              throw new Exception(failureText(
                "inline requires request rewriting, but request.userText does not match the last observed " +
                  "request body. Fix the provider rule or choose file mode."
              ))
          else {
            val total    = messageCount(bodyJson)
            val ids      = if (messageIds.length == total) messageIds else Nil
            // 表就是按对话 URL 建的，查得到就只有两种可能：同一条 session（可增量）或别的 session（回全量并按本轮改写归属）。
            val sameConv = record.exists(item => item.sessionId == sessionId && sessionId.nonEmpty)
            val tipId    = if (sameConv) record.map(_.seedToMessageId).getOrElse("") else ""
            val tipIndex = if (mode == ModeDelta && tipId.nonEmpty && ids.nonEmpty) ids.indexOf(tipId) else -1
            // 裁剪点 = max(水位线 id 之后, 上次投递过的 body 长度)：工具轮那些消息没有 id（合成消息），靠 id 推不动水位线。
            val byCount  = if (sameConv) math.min(record.map(_.seedToBodyCount).getOrElse(0), total) else 0
            val dropTo   = if (tipIndex >= 0) math.max(tipIndex + 1, byCount) else -1
            val sliced   = if (dropTo >= 0) deltaMessages(bodyJson, ids, dropTo) else None
            sliced match {
              case Some(item) if isFile =>
                // `continue + file`：这段对话**不动**（不点 New Chat），但本轮不改写请求体 —— 输入框只打指针句，
                // 附件里装"水位线之后的新消息"（与 delta 注入同一份内容）。增量很大时它不占输入框，也不用赌改写能不能过。
                typedDelivery(
                  provider = provider,
                  promptMode = promptMode,
                  typedPrompt = typedPrompt,
                  bodyJson = item._1,
                  protocolRepair = protocolRepair,
                  commit = commitTip(provider, sessionId, item._2, total, tabId, lastUserTextOf(bodyJson)),
                  reason = ""
                )
              case Some(item)           =>
                // 增量轮：只投消息增量本身（messages 格式），persona / protocol / 工具 schema 靠 provider 页自己的会话上下文。
                // 正文根本不进输入框（`inline` 下就是这条路径），所以 `inline` 不用管文件。
                InjectionPlan(
                  typedText = displayText(bodyJson),
                  template = Some(repair.getOrElse(item._1)),
                  notice = "",
                  modeTag = if (repair.nonEmpty) "hijack/repair" else "hijack/delta",
                  commit =
                    if (repair.nonEmpty) None
                    else commitTip(provider, sessionId, item._2, total, tabId, lastUserTextOf(bodyJson)),
                  detail = s"delta messages=${item._3} skippedOwn=${item._4}"
                )
              case None if isFile       =>
                // 全量那一投 + file：body 造成附件，输入框里只打指针句（不注入、不改写请求体）。
                typedDelivery(
                  provider = provider,
                  promptMode = promptMode,
                  typedPrompt = typedPrompt,
                  bodyJson = bodyJson,
                  protocolRepair = protocolRepair,
                  commit = commitTip(provider, sessionId, ids, total, tabId, lastUserTextOf(bodyJson)),
                  reason = ""
                )
              case None                 =>
                InjectionPlan(
                  typedText = displayText(bodyJson),
                  template = Some(repair.getOrElse(ThinOpenAi.buildPrompt(bodyJson))),
                  notice = "",
                  modeTag = if (repair.nonEmpty) "hijack/repair" else "hijack/full",
                  commit =
                    if (repair.nonEmpty) None
                    else commitTip(provider, sessionId, ids, total, tabId, lastUserTextOf(bodyJson))
                )
            }
          }
        }
    }
  }

  /**
   * file 投递与显式不注入路径：`file` 把 body 造成 `.txt` 附件，输入框里只打指针句；`inline` 只会出现在抓包等不注入路径。
   *
   * `reason` 非空 = 这是一条降级路径（规则缺失 / 被标记不可注入 / 上次请求读不出用户原话），照旧写进 notice 与 modeTag。
   */
  private def typedDelivery(
      provider: String,
      promptMode: String,
      typedPrompt: String,
      bodyJson: String,
      protocolRepair: Option[String],
      commit: Option[() => Unit],
      reason: String
  ): InjectionPlan = {
    val suffix = if (reason.isEmpty) "" else s" ($reason)"
    protocolRepair.map(_.trim).filter(_.nonEmpty) match {
      case Some(repair)                                                        =>
        InjectionPlan(
          typedText = repair,
          template = None,
          notice = "",
          modeTag = "typed/repair",
          commit = None
        )
      case None if PromptSettings.normalize(promptMode) == PromptSettings.File =>
        // body 走附件上传（`.txt`，longcat 这类只认 .txt），输入框里只打指针句。
        val name = s"th-request-${js.Date.now().toLong}.txt"
        InjectionPlan(
          typedText = ThinOpenAi.buildFilePrompt(name),
          template = None,
          notice = if (reason.isEmpty) "" else s"$provider: $reason (the request body was uploaded as a file)",
          modeTag = s"typed/file$suffix",
          commit = commit,
          detail = s"file bodyChars=${bodyJson.length}",
          attachment = Some(PromptAttachment(name, bodyJson))
        )
      case None                                                                =>
        InjectionPlan(
          typedText = typedPrompt,
          template = None,
          notice = if (reason.isEmpty) "" else s"$provider: $reason",
          modeTag = s"typed$suffix",
          commit = commit
        )
    }
  }
  private def fallback(
      provider: String,
      promptMode: String,
      typedPrompt: String,
      bodyJson: String,
      protocolRepair: Option[String],
      sessionId: String,
      messageIds: List[String],
      bodyCount: Int,
      tabId: Int,
      reason: String
  ): InjectionPlan =
    typedDelivery(
      provider = provider,
      promptMode = promptMode,
      typedPrompt = typedPrompt,
      bodyJson = bodyJson,
      protocolRepair = protocolRepair,
      commit = commitTip(provider, sessionId, messageIds, bodyCount, tabId, lastUserTextOf(bodyJson)),
      reason = reason
    )
  private def fileFallbackOrInlineFailure(
      provider: String,
      promptMode: String,
      typedPrompt: String,
      bodyJson: String,
      protocolRepair: Option[String],
      sessionId: String,
      messageIds: List[String],
      bodyCount: Int,
      tabId: Int,
      reason: String
  ): Future[InjectionPlan] =
    if (PromptSettings.normalize(promptMode) == PromptSettings.File)
      Future.successful(
        fallback(
          provider = provider,
          promptMode = promptMode,
          typedPrompt = typedPrompt,
          bodyJson = bodyJson,
          protocolRepair = protocolRepair,
          sessionId = sessionId,
          messageIds = messageIds,
          bodyCount = bodyCount,
          tabId = tabId,
          reason = s"$reason (the request body was uploaded as a file)"
        )
      )
    else inlineUnavailable(s"$reason. Fix the provider rule or choose file mode.")
  private def inlineUnavailable(reason: String): Future[InjectionPlan] =
    Future.failed(new Exception(failureText(s"inline requires request rewriting, but $reason")))

  /**
   * 投递成功后推进水位线：把这段对话（`WebChatHosts` 里按 URL 的那条记录）推到"已经拿到这份 body"。
   *
   * 除了最后一个**真实**消息 id，还记下这次 body 的消息条数（`bodyCount`）——工具轮的 payload 全是合成消息（没有 id）， 只靠 id 水位线会停在上一条真实消息上，下一轮就把工具结果与
   * provider 自己刚产出的回答又投一遍。
   *
   * url 在**触发时**才读（也就是这一轮回答落定之后）：会话 id 往往是"第一条消息发出去之后"才出现在地址里 （`/chat` → `/chat/<id>`、`#/c` → `#/chat/<id>`、`/` →
   * `/c/<id>`），用发送前读到的地址会让记录落在已经不存在的那段对话上。
   */
  private def commitTip(
      provider: String,
      sessionId: String,
      ids: List[String],
      bodyCount: Int,
      tabId: Int,
      userText: String
  ): Option[() => Unit] =
    // 只要这次真投了东西（body 非空）就提交：payload 没有真实 id 时 seedToMessageId 留空，由 host 表保留旧值。
    if (sessionId.isEmpty || bodyCount <= 0) None
    else
      Some { () =>
        if (provider.nonEmpty && sessionId.nonEmpty)
          currentUrl(tabId).foreach { url =>
            if (url.nonEmpty) {
              val tipId = ids.filter(_.nonEmpty).lastOption.getOrElse("")
              PopCanLog.info(
                "inject",
                s"context tip committed provider=$provider tip=$tipId bodyCount=$bodyCount url=${PopCanLog.short(url, 80)}"
              )
              WebChatHosts
                .upsertExclusive(
                  WebChatHost(
                    convKey = url,
                    sessionId = sessionId,
                    provider = provider,
                    lastTabId = tabId,
                    seedToMessageId = tipId,
                    seedToBodyCount = bodyCount,
                    // 插件页 task 轮也要把"上一轮用户文本"同步过去，否则之后在页面点"重新生成"会匹配失败、多出一对重复问答。
                    lastUserText = userText,
                    // 这一轮插件页投递完成：记下"最后一次对话"的时间（绑定类写入不会碰它）。
                    lastTurnAt = js.Date.now()
                  )
                )
                .foreach(_ => ())
            }
          }
        ()
      }
  // ---------------------------------------------------------------- 载荷裁剪
  /**
   * 增量载荷：只留"这家 provider 对话里还没有的那些消息"，序列化成 `{"messages":[…]}`。
   *
   * 两处裁剪：
   *   1. 丢掉水位线（`tipId`）之前/含自身的消息； 2. 丢掉紧随其后的那条 assistant —— 它是这轮投递的回答，provider 页自己刚才产出的，上下文里已经有了。
   *
   * 返回 `(messagesJson, ids, messageCount, skippedOwn)`；裁完为空就返回 None（调用方回退全量）。
   */
  private def deltaMessages(
      bodyJson: String,
      ids: List[String],
      dropCount: Int
  ): Option[(String, List[String], Int, Int)] =
    try {
      val parsed   = JsBridge.asObject(js.JSON.parse(bodyJson))
      val messages = JsBridge.undefinedArrayAny(parsed.messages).toOption
      messages.flatMap { list =>
        val all           = list.toList
        // Scenario / attached-tabs / Memory 是**本轮**上下文，不属于可裁剪的会话历史。
        // 水位线只裁 provider 对话里已有的 user/assistant/tool 消息；leading system 必须每轮重投。
        val leadingSystem = all.takeWhile(message => str(JsBridge.field(message, "role")) == "system")
        val kept          = all.drop(dropCount)
        if (kept.isEmpty) None
        else {
          val skippedOwn = kept.takeWhile(message => str(JsBridge.field(message, "role")) == "assistant").length
          val delta      = leadingSystem ++ kept.drop(skippedOwn)
          val deltaIds   = List.fill(leadingSystem.size)("") ++ ids.drop(dropCount).drop(skippedOwn)
          if (delta.isEmpty) None
          else {
            val tools = JsBridge.undefinedArrayAny(parsed.tools).toOption
            Some((deltaPayload(delta, tools), deltaIds, delta.length, skippedOwn))
          }
        }
      }
    } catch {
      case _: Throwable => None
    }
  private def messageCount(bodyJson: String): Int =
    try {
      val parsed = JsBridge.asObject(js.JSON.parse(bodyJson))
      JsBridge.undefinedArrayAny(parsed.messages).toOption.map(_.length).getOrElse(0)
    } catch {
      case _: Throwable => 0
    }

  /** 输入框里打什么（只影响显示）：Chat 与 Server 共用同一份 OpenAI message preview。 */
  private def displayText(bodyJson: String): String =
    OpenAiRequestPreview.of(bodyJson)

  /** body 里最后一条 user 消息的**完整**内容（不截断）：写 host 的 `lastUserText` 用。 */
  private def lastUserTextOf(bodyJson: String): String =
    try {
      val parsed   = JsBridge.asObject(js.JSON.parse(bodyJson))
      val messages = JsBridge.undefinedArrayAny(parsed.messages).toOption.map(_.toList)
        .getOrElse(Nil)
      messages
        .reverse
        .find(message => str(JsBridge.field(message, "role")) == "user")
        .map(message => OpenAiRequestPreview.contentText(JsBridge.field(message, "content")).trim)
        .getOrElse("")
    } catch {
      case _: Throwable => ""
    }
  // ---------------------------------------------------------------- 水位线 / 能力标记
  private def blockedKey(provider: String): String     = s"$BlockedKeyPrefix$provider"

  /** 显式 New Chat / 复位到新会话时，把**当前这段对话**的记录清掉（同一 URL 也能撑住的那些 provider 靠它）。 */
  def clearConversation(tabId: Int): Future[Unit] =
    currentUrl(tabId).flatMap { url =>
      if (url.isEmpty) Future.successful(()) else WebChatHosts.remove(url)
    }

  /**
   * 复用同一个 tab 的对话前作废水位线，但**只作废属于这条会话的那一段**。
   *
   * 一个 tab 固定一家 provider，可以承载多条会话的对话：切会话是靠"把 tab 导航到那条会话的对话 URL"。于是"就地 New Chat"这个动作 可能发生在**别人那段对话**上 ——
   * 那种情况下不能把对方的绑定/水位线删掉（对方还能从会话 URL 列表点回来继续聊）。
   */
  def clearConversationOwnedBy(tabId: Int, sessionId: String): Future[Unit] =
    currentUrl(tabId).flatMap { url =>
      if (url.isEmpty) Future.successful(())
      else
        WebChatHosts.find(url).flatMap { host =>
          val mine = host.forall(existing => sessionId.isEmpty || existing.sessionId == sessionId)
          if (mine) WebChatHosts.remove(url) else Future.successful(())
        }
    }
  private def readBlocked(provider: String, rule: String): Future[Boolean]  =
    ChromeAPI.storage.local
      .get(js.Array(blockedKey(provider)))
      .toFuture
      .map { values =>
        val stored = values.get(blockedKey(provider)).map(_.toString).getOrElse("")
        stored.nonEmpty && stored == ruleHash(rule)
      }
      .recover { case _ => false }

  /** 注入失败后记住"这家 provider + 这份规则"不可注入；规则改了（hash 变了）自动重新尝试。 */
  def markBlocked(provider: String, rule: String): Future[Unit]             =
    if (provider.isEmpty) Future.successful(())
    else
      ChromeAPI.storage.local
        .set(js.Dictionary[js.Any](blockedKey(provider) -> ruleHash(rule)))
        .toFuture
        .map(_ => ())
        .recover { case _ => () }
  private def ruleHash(rule: String): String                                = {
    var hash = 0
    rule.foreach(ch => hash = (hash * 31 + ch.toInt) | 0)
    val hex  = (0 until 8).reverse.map(index => "0123456789abcdef".charAt((hash >>> (index * 4)) & 0xf)).mkString
    s"$hex-${rule.length}"
  }
  private def readLastRequestBody(provider: String): Future[Option[String]] =
    ChromeAPI.storage.local
      .get(js.Array(s"thWebChatLastRequest:$provider"))
      .toFuture
      .map { values =>
        values
          .get(s"thWebChatLastRequest:$provider")
          .filter(value => value != null && !js.isUndefined(value))
          .map(raw => raw.asInstanceOf[LastWebRequestBody].postData.getOrElse(""))
          .filter(_.nonEmpty)
      }
      .recover { case _ => None }

  /** 记下最近一次看到的请求（url + 正文），供下次"这条规则到底读不读得出用户原话"的预检与事后排查。 */
  def rememberRequestBody(provider: String, url: String, postData: String): Future[Unit] =
    if (provider.isEmpty || postData.isEmpty) Future.successful(())
    else
      ChromeAPI.storage.local
        .set(js.Dictionary[js.Any](
          s"thWebChatLastRequest:$provider" -> JsBridge.obj(
            "url"      -> (url),
            "postData" -> (if (postData.length > ObserveLimit) postData.take(ObserveLimit) else postData),
            "at"       -> (js.Date.now())
          )
        ))
        .toFuture
        .map(_ => ())
        .recover { case _ => () }

  /**
   * 开发期抓取完整请求体：只由 `localTaskKind=capture` 的官方 INPUT_PROMPT 轮触发。
   *
   * 普通观测有 20k 上限；fixture 必须保留完整字节（尤其是 gRPC-Web 的长度前缀与多帧），所以这里以 base64 写入一次性 key，抓取脚本读完即删。
   */
  def rememberCapturedRequestBody(
      generationId: String,
      provider: String,
      url: String,
      postData: String
  ): Future[Unit] =
    if (generationId.isEmpty || provider.isEmpty || postData.isEmpty) Future.successful(())
    else {
      val bytes      = Bytes.fromPostDataString(postData)
      val binary     = new StringBuilder(bytes.length)
      bytes.foreach(byte => binary.append((byte & 0xff).toChar))
      val bodyBase64 = JsBridge.asString(JsBridge.btoa(binary.result()))
      ChromeAPI.storage.local
        .set(js.Dictionary[js.Any](
          s"$CaptureKeyPrefix$generationId" -> JsBridge.obj(
            "provider"   -> (provider),
            "url"        -> (url),
            "bodyBase64" -> (bodyBase64),
            "at"         -> (js.Date.now())
          )
        ))
        .toFuture
        .map(_ => ())
        .recover { case _ => () }
    }
  def failureText(message: String): String           = s"$FailurePrefix: $message"
  private def currentUrl(tabId: Int): Future[String] =
    ChromeAPI.tabs.get(tabId).toFuture.map(tab => tab.url.toOption.getOrElse("")).recover { case _ => "" }
  private def str(value: js.Any): String             =
    if (value == null || js.isUndefined(value)) "" else value.toString
}
