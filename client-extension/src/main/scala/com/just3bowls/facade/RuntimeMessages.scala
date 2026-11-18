package com.just3bowls.facade
import scala.scalajs.js
@js.native
trait RuntimeMessage     extends js.Object:
  def `type`: js.UndefOr[String] = js.native
@js.native
trait InputPromptMessage extends RuntimeMessage:
  def provider: js.UndefOr[String]      = js.native
  def prompt: js.UndefOr[String]        = js.native
  def inputXPath: js.UndefOr[String]    = js.native
  def generationId: js.UndefOr[String]  = js.native
  def stream: js.UndefOr[Boolean]       = js.native
  def keyId: js.UndefOr[String]         = js.native
  def localTaskKind: js.UndefOr[String] = js.native

  /** 结构化请求体：模型调用方给进来，后台据此决定"增量还是全量"以及"输入框里打什么"。 */
  def bodyJson: js.UndefOr[String] = js.native

  /** 与 `bodyJson.messages` 等长的会话消息 id；插件页合成出来的消息（场景 system、工具轮）为空串。 */
  def messageIds: js.UndefOr[js.Array[String]] = js.native

  /** 插件页 Chat 的会话 id；server 转发没有会话，留空。 */
  def sessionId: js.UndefOr[String] = js.native

  /** `delta` = 只投新增消息；`full` = 投整份 body。 */
  def contextMode: js.UndefOr[String] = js.native

  /** Transient marker-repair instruction for this delivery only; never part of `bodyJson.messages`. */
  def protocolRepair: js.UndefOr[String] = js.native

  /** `reuse` = 复用同一段 provider 对话；`fresh` = 每轮 New Chat 复位。留空时后台按 `contextMode` 推导（full ⇒ fresh，delta ⇒ reuse）。 */
  def conversation: js.UndefOr[String] = js.native

  /** 是否允许后台用 Fetch 改写请求体注入模版（插件页 Chat 开，server 转发关）。 */
  def inject: js.UndefOr[Boolean] = js.native

  /**
   * `> 0` = 这一轮直接驱动这个已存在的 tab（网页原生会话的 host tab），不走 `ManagedTabManager` 分配： 不 New Chat、不切焦点、也不占用 provider slot。`0` =
   * 今天的分配路径。
   */
  def hostTabId: js.UndefOr[Int] = js.native

  /** `file` 模式的附件：非空时后台先把 `attachmentBody` 造成同名 `.txt` 附件塞进 provider 的文件输入框，再打 `prompt`。 */
  def attachmentName: js.UndefOr[String] = js.native
  def attachmentBody: js.UndefOr[String] = js.native

  /**
   * plugin 侧 run 的正文投递模式（`inline` / `file`）：后台据此决定"全量那一投"是把整份模版改写进请求体，还是把 body 造成附件上传。
   *
   * 只影响全量形态；增量轮只投消息增量，不受它影响。server 转发不用这个字段（它已经在 `prompt` / 附件里自己决定了）。
   */
  def promptMode: js.UndefOr[String] = js.native

  /**
   * 这一轮用过的 provider 对话要不要**绑给会话**（`thWebChatHosts`）：chat / Retry 这类"这条 tab 就是本会话的对话"为 true —— New Chat 出来的新 URL
   * 立刻绑、失败也绑（否则胶囊停在 Unassigned、后续问答接不上）。网页原生 host 轮为 false。
   */
  def bindConversation: js.UndefOr[Boolean] = js.native

  /** Exact LangChain tool-surface revision used by this provider conversation. */
  def toolProfileId: js.UndefOr[String] = js.native

/** 后台给插件页的一次性提示（例如"这家 provider 不支持注入，本轮按整段模版发送"）。 */
@js.native
trait TabAiNoticeMessage extends RuntimeMessage:
  def generationId: js.UndefOr[String] = js.native
  def text: js.UndefOr[String]         = js.native

/** Structured progress for the provider turn currently owned by this generation. */
@js.native
trait TabAiProgressMessage extends RuntimeMessage:
  def generationId: js.UndefOr[String] = js.native
  def phase: js.UndefOr[String]        = js.native
  def provider: js.UndefOr[String]     = js.native
  def detail: js.UndefOr[String]       = js.native
  def attempt: js.UndefOr[Int]         = js.native
  def recoveryIndex: js.UndefOr[Int]   = js.native
  def recoveryBudget: js.UndefOr[Int]  = js.native

/** INPUT_PROMPT 的响应 — Background → PopCan 主页面 */
@js.native
trait PromptResponse extends js.Object:
  def ok: js.UndefOr[Boolean]          = js.native
  def content: js.UndefOr[String]      = js.native
  def thinking: js.UndefOr[String]     = js.native
  def totalTokens: js.UndefOr[Int]     = js.native
  def finishReason: js.UndefOr[String] = js.native

/** 归一化增量 — Background → PopCan 主页面 */
@js.native
trait NormalizedChunk      extends js.Object:
  def `type`: js.UndefOr[String]       = js.native
  def generationId: js.UndefOr[String] = js.native
  def seq: js.UndefOr[Int]             = js.native
  def contentDelta: js.UndefOr[String] = js.native
  def done: js.UndefOr[Boolean]        = js.native
  def finishReason: js.UndefOr[String] = js.native
  def error: js.UndefOr[String]        = js.native

  /** 后台这次任务累计收到的原始字符数（用于区分"没捕到"与"解析没产出"）。 */
  def rawChars: js.UndefOr[Int] = js.native

  /**
   * 解析器的正文过滤回退了已下发内容：消费者要**先清空自己累积的正文**再接收 `contentDelta`。
   *
   * 正常路径永远是 append-only（字段缺失 = false）；见 `ContentPipeline` / `StreamSettlement`。
   */
  def resetContent: js.UndefOr[Boolean] = js.native
@js.native
trait ServerTaskMessage    extends RuntimeMessage:
  def id: js.UndefOr[String]          = js.native
  def provider: js.UndefOr[String]    = js.native
  def prompt: js.UndefOr[String]      = js.native
  def stream: js.UndefOr[Boolean]     = js.native
  def keyId: js.UndefOr[String]       = js.native
  def sessionMode: js.UndefOr[String] = js.native
  def maxTurns: js.UndefOr[Int]       = js.native
  def model: js.UndefOr[String]       = js.native
  def finalSse: js.UndefOr[Boolean]   = js.native

  /** `file` 模式：随这一轮带着的附件（名字 + body 原文）。 */
  def attachmentName: js.UndefOr[String] = js.native
  def attachmentBody: js.UndefOr[String] = js.native
@js.native
trait ClientRequestMessage extends RuntimeMessage:
  def requestId: js.UndefOr[String] = js.native
  def method: js.UndefOr[String]    = js.native
  def path: js.UndefOr[String]      = js.native
  def headers: js.UndefOr[String]   = js.native
  def body: js.UndefOr[String]      = js.native

  /** 请求体切成了几片（≥1）；body 随后的 `client_request_chunk` 里逐片到达。 */
  def bodyFrames: js.UndefOr[Int] = js.native

/** host → 扩展的请求体分片（native messaging 的 host→扩展方向单条只有 ~1MB）。 */
@js.native
trait ClientRequestChunkMessage extends RuntimeMessage:
  def requestId: js.UndefOr[String] = js.native
  def seq: js.UndefOr[Int]          = js.native
  def data: js.UndefOr[String]      = js.native
@js.native
trait ReleaseKeyTabsMessage     extends RuntimeMessage:
  def keyId: js.UndefOr[String] = js.native
@js.native
trait ActivateServerTabMessage  extends RuntimeMessage:
  def tabId: js.UndefOr[Int] = js.native
@js.native
trait CancelTaskMessage         extends RuntimeMessage:
  def generationId: js.UndefOr[String] = js.native
  def provider: js.UndefOr[String]     = js.native
  def managed: js.UndefOr[Boolean]     = js.native
@js.native
trait ProviderPackMessage       extends RuntimeMessage:
  def action: js.UndefOr[String]      = js.native
  def text: js.UndefOr[String]        = js.native
  def autoUpdate: js.UndefOr[Boolean] = js.native
@js.native
trait BrowserToolCallMessage    extends RuntimeMessage:
  def toolCallId: js.UndefOr[String] = js.native
  def name: js.UndefOr[String]       = js.native
  def args: js.UndefOr[String]       = js.native

  /** chat session 标识；browser_* 工具据此定位自己的常驻 tab。 */
  def sessionId: js.UndefOr[String]    = js.native
  def sessionTitle: js.UndefOr[String] = js.native

/** 查询「这条会话在用哪些 provider URL」。 */
@js.native
trait QuerySessionUrlsMessage extends RuntimeMessage:
  def sessionId: js.UndefOr[String] = js.native

/** 结束一段 URL 在本会话里的使命（列表里按 ×）：tab 留着，下次这个 provider 要开新对话。 */
@js.native
trait SessionUrlEndMessage extends RuntimeMessage:
  def sessionId: js.UndefOr[String] = js.native
  def provider: js.UndefOr[String]  = js.native
  def url: js.UndefOr[String]       = js.native

/** 所有只携带一条会话 id 的 WebChat 生命周期消息。 */
@js.native
trait WebChatSessionMessage extends RuntimeMessage:
  def sessionId: js.UndefOr[String] = js.native

@js.native
trait WebChatFocusUrlMessage extends RuntimeMessage:
  def url: js.UndefOr[String] = js.native

@js.native
trait WebChatStateMessage extends RuntimeMessage:
  def state: js.UndefOr[WebChatCapsuleState] = js.native

@js.native
trait WebChatRenameSessionMessage extends WebChatSessionMessage:
  def title: js.UndefOr[String] = js.native

@js.native
trait WebChatNativeRunMessage extends WebChatSessionMessage:
  def runId: js.UndefOr[String]            = js.native
  def ok: js.UndefOr[Boolean]              = js.native
  def error: js.UndefOr[String]            = js.native
  def protocolFailure: js.UndefOr[Boolean] = js.native

@js.native
trait LocationMarkersReadyMessage extends RuntimeMessage:
  def roles: js.UndefOr[js.Dictionary[Boolean]] = js.native

@js.native
trait NativeRetryTurnMessage extends WebChatSessionMessage:
  def provider: js.UndefOr[String]       = js.native
  def convKey: js.UndefOr[String]        = js.native
  def tabId: js.UndefOr[Int]             = js.native
  def assistantId: js.UndefOr[String]    = js.native
  def reason: js.UndefOr[String]         = js.native
  def protocolRepair: js.UndefOr[String] = js.native

@js.native
trait BrowserTabRefMessage extends js.Object:
  def ref: js.UndefOr[String]   = js.native
  def tabId: js.UndefOr[Int]    = js.native
  def title: js.UndefOr[String] = js.native
  def url: js.UndefOr[String]   = js.native

@js.native
trait ToolTabsClearMessage extends RuntimeMessage:
  def preserveTabIds: js.UndefOr[js.Array[Int]] = js.native

@js.native
trait BrowserTabsBindMessage extends WebChatSessionMessage:
  def sessionTitle: js.UndefOr[String]                 = js.native
  def tabs: js.UndefOr[js.Array[BrowserTabRefMessage]] = js.native

@js.native
trait BrowserTabsReleaseMessage extends WebChatSessionMessage

@js.native
trait SessionUrlRowMessage extends js.Object:
  def tabId: js.UndefOr[Int]            = js.native
  def url: js.UndefOr[String]           = js.native
  def provider: js.UndefOr[String]      = js.native
  def toolProfileId: js.UndefOr[String] = js.native
  def isCurrent: js.UndefOr[Boolean]    = js.native
  def isLast: js.UndefOr[Boolean]       = js.native

@js.native
trait SessionUrlsMessage extends WebChatSessionMessage:
  def latestProvider: js.UndefOr[String]               = js.native
  def urls: js.UndefOr[js.Array[SessionUrlRowMessage]] = js.native

@js.native
trait OkResponse extends js.Object:
  def ok: js.UndefOr[Boolean]   = js.native
  def error: js.UndefOr[String] = js.native

@js.native
trait ConsumedResponse extends js.Object:
  def consumed: js.UndefOr[Boolean] = js.native

@js.native
trait NativeRunAckResponse extends OkResponse:
  def userId: js.UndefOr[String]      = js.native
  def assistantId: js.UndefOr[String] = js.native

@js.native
trait BrowserTabsResponse extends OkResponse:
  def previousTabId: js.UndefOr[Int]                   = js.native
  def tabs: js.UndefOr[js.Array[BrowserTabRefMessage]] = js.native

@js.native
trait BrowserToolCallResponse extends OkResponse:
  def result: js.UndefOr[js.Object] = js.native

@js.native
trait SessionUrlsResponse extends OkResponse:
  def sessionId: js.UndefOr[String]                    = js.native
  def latestProvider: js.UndefOr[String]               = js.native
  def urls: js.UndefOr[js.Array[SessionUrlRowMessage]] = js.native

@js.native
trait WebChatCapsuleState extends js.Object:
  def supported: js.UndefOr[Boolean]      = js.native
  def capsuleEnabled: js.UndefOr[Boolean] = js.native
  def associated: js.UndefOr[Boolean]     = js.native
  def behind: js.UndefOr[Boolean]         = js.native
  def error: js.UndefOr[String]           = js.native
  def errorId: js.UndefOr[String]         = js.native
  def errorDetail: js.UndefOr[String]     = js.native
  def title: js.UndefOr[String]           = js.native
  def provider: js.UndefOr[String]        = js.native
  def sessionId: js.UndefOr[String]       = js.native
  def canTakeover: js.UndefOr[Boolean]    = js.native
  def agentRunning: js.UndefOr[Boolean]   = js.native

@js.native
trait WebChatSessionSummary extends js.Object:
  def id: js.UndefOr[String]                           = js.native
  def title: js.UndefOr[String]                        = js.native
  def urls: js.UndefOr[js.Array[SessionUrlRowMessage]] = js.native

@js.native
trait WebChatActionResponse extends OkResponse:
  def state: js.UndefOr[WebChatCapsuleState] = js.native

@js.native
trait WebChatListResponse extends OkResponse:
  def sessions: js.UndefOr[js.Array[WebChatSessionSummary]] = js.native
