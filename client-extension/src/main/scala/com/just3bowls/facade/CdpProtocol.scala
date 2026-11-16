package com.just3bowls.facade
import scala.scalajs.js

/** Page.addScriptToEvaluateOnNewDocument 的结果。 */
@js.native
trait AddScriptResult extends js.Object:
  def identifier: js.UndefOr[String] = js.native

@js.native
trait FetchPostDataEntry extends js.Object:
  def bytes: js.UndefOr[String] = js.native

@js.native
trait FetchPausedRequest extends js.Object:
  def url: js.UndefOr[String]                                   = js.native
  def postData: js.UndefOr[String]                              = js.native
  def postDataEntries: js.UndefOr[js.Array[FetchPostDataEntry]] = js.native
  def headers: js.UndefOr[js.Dictionary[String]]                = js.native

@js.native
trait FetchRequestPaused extends js.Object:
  def requestId: js.UndefOr[String]           = js.native
  def request: js.UndefOr[FetchPausedRequest] = js.native
  def networkId: js.UndefOr[String]           = js.native

@js.native
trait NetworkRequest           extends js.Object:
  def url: js.UndefOr[String]      = js.native
  def method: js.UndefOr[String]   = js.native
  def postData: js.UndefOr[String] = js.native
@js.native
trait NetworkRequestWillBeSent extends js.Object:
  def requestId: js.UndefOr[String] = js.native
  def request: NetworkRequest       = js.native
  def `type`: js.UndefOr[String]    = js.native
@js.native
trait NetworkResponse          extends js.Object:
  def status: js.UndefOr[Int]      = js.native
  def mimeType: js.UndefOr[String] = js.native
@js.native
trait NetworkResponseReceived  extends js.Object:
  def requestId: js.UndefOr[String] = js.native
  def response: NetworkResponse     = js.native
@js.native
trait NetworkDataReceived      extends js.Object:
  def requestId: js.UndefOr[String]      = js.native
  def timestamp: js.UndefOr[Double]      = js.native
  def dataLength: js.UndefOr[Int]        = js.native
  def encodedDataLength: js.UndefOr[Int] = js.native
  def data: js.UndefOr[String]           = js.native
@js.native
trait NetworkLoadingFinished   extends js.Object:
  def requestId: js.UndefOr[String] = js.native
@js.native
trait NetworkWebSocketCreated  extends js.Object:
  def requestId: js.UndefOr[String] = js.native
  def url: js.UndefOr[String]       = js.native

/** WebSocket 帧的载荷；opcode 1 = text（payloadData 是明文），2 = binary（payloadData 是 base64）。 */
@js.native
trait NetworkWebSocketFramePayload       extends js.Object:
  def opcode: js.UndefOr[Int]         = js.native
  def payloadData: js.UndefOr[String] = js.native
  def mask: js.UndefOr[Boolean]       = js.native
@js.native
trait NetworkWebSocketFrame              extends js.Object:
  def requestId: js.UndefOr[String]          = js.native
  def response: NetworkWebSocketFramePayload = js.native
@js.native
trait NetworkWebSocketClosed             extends js.Object:
  def requestId: js.UndefOr[String] = js.native
@js.native
trait NetworkGetResponseBodyResult       extends js.Object:
  def body: js.UndefOr[String]           = js.native
  def base64Encoded: js.UndefOr[Boolean] = js.native
@js.native
trait NetworkStreamResourceContentResult extends js.Object:
  def bufferedData: js.UndefOr[String] = js.native

/** Runtime.evaluate / Runtime.callFunctionOn 返回的远程对象。 */
@js.native
trait CdpRemoteObject extends js.Object:
  def `type`: js.UndefOr[String]      = js.native
  def value: js.UndefOr[js.Any]       = js.native
  def objectId: js.UndefOr[String]    = js.native
  def description: js.UndefOr[String] = js.native

@js.native
trait CdpExceptionDetails extends js.Object:
  def text: js.UndefOr[String]               = js.native
  def exception: js.UndefOr[CdpRemoteObject] = js.native
  def lineNumber: js.UndefOr[Int]            = js.native
  def columnNumber: js.UndefOr[Int]          = js.native

@js.native
trait RuntimeEvaluateResult       extends js.Object:
  def result: js.UndefOr[CdpRemoteObject]               = js.native
  def exceptionDetails: js.UndefOr[CdpExceptionDetails] = js.native
@js.native
trait RuntimeCallFunctionOnResult extends js.Object:
  def result: js.UndefOr[CdpRemoteObject] = js.native

/** Accessibility.getFullAXTree 的节点结构（只声明用得到的字段）。 */
@js.native
trait AxValue      extends js.Object:
  def `type`: js.UndefOr[String] = js.native
  def value: js.UndefOr[js.Any]  = js.native
@js.native
trait AxProperty   extends js.Object:
  def name: String   = js.native
  def value: AxValue = js.native
@js.native
trait AxNode       extends js.Object:
  def nodeId: String                               = js.native
  def ignored: js.UndefOr[Boolean]                 = js.native
  def role: js.UndefOr[AxValue]                    = js.native
  def name: js.UndefOr[AxValue]                    = js.native
  def description: js.UndefOr[AxValue]             = js.native
  def properties: js.UndefOr[js.Array[AxProperty]] = js.native
  def childIds: js.UndefOr[js.Array[String]]       = js.native
  def backendDOMNodeId: js.UndefOr[Int]            = js.native
@js.native
trait AxTreeResult extends js.Object:
  def nodes: js.Array[AxNode] = js.native

/** DOM.resolveNode 结果。 */
@js.native
trait DomResolveNodeResult extends js.Object:
  def `object`: js.UndefOr[CdpRemoteObject] = js.native

/** DOM.getBoxModel 结果。 */
@js.native
trait DomBoxModelResult extends js.Object:
  def model: DomBoxModel = js.native
@js.native
trait DomBoxModel       extends js.Object:
  def content: js.Array[Double] = js.native
  def border: js.Array[Double]  = js.native
  def width: Double             = js.native
  def height: Double            = js.native

/** 我们自己注入页面读取的页级信息（browser_* 快照头部与滚动基准）。 */
@js.native
trait BrowserPageInfo extends js.Object:
  def url: js.UndefOr[String]            = js.native
  def title: js.UndefOr[String]          = js.native
  def token: js.UndefOr[String]          = js.native
  def scrollX: js.UndefOr[Double]        = js.native
  def scrollY: js.UndefOr[Double]        = js.native
  def viewportWidth: js.UndefOr[Double]  = js.native
  def viewportHeight: js.UndefOr[Double] = js.native
  def documentHeight: js.UndefOr[Double] = js.native

/** 正文抽取管道（ExtractPipelineJs）在页面侧算出的候选，Scala 侧据此择优。 */
@js.native
trait ExtractCandidates extends js.Object:
  def url: js.UndefOr[String]              = js.native
  def title: js.UndefOr[String]            = js.native
  def metadata: js.UndefOr[String]         = js.native
  def readability: js.UndefOr[String]      = js.native
  def readabilityTitle: js.UndefOr[String] = js.native

  /** readability 结果里链接文本占比：列表/结果页会很高，用来判断"这其实不是一篇文章"。 */
  def readabilityLinkDensity: js.UndefOr[Double] = js.native
  def full: js.UndefOr[String]                   = js.native
  def note: js.UndefOr[String]                   = js.native

/** 搜索结果记录检测（SearchResults.detectJs）：一条记录。 */
@js.native
trait SearchRecordDto extends js.Object:
  def title: js.UndefOr[String]   = js.native
  def url: js.UndefOr[String]     = js.native
  def snippet: js.UndefOr[String] = js.native
  def date: js.UndefOr[String]    = js.native
  def ad: js.UndefOr[Boolean]     = js.native

/** 搜索结果记录检测：一个候选列表（含指标，供 Scala 侧打分选优）。 */
@js.native
trait SearchListDto extends js.Object:
  def path: js.UndefOr[String]                       = js.native
  def sig: js.UndefOr[String]                        = js.native
  def via: js.UndefOr[String]                        = js.native
  def recordCount: js.UndefOr[Int]                   = js.native
  def blockCount: js.UndefOr[Int]                    = js.native
  def distinctUrlRatio: js.UndefOr[Double]           = js.native
  def snippetCoverage: js.UndefOr[Double]            = js.native
  def linkDensity: js.UndefOr[Double]                = js.native
  def inMain: js.UndefOr[Boolean]                    = js.native
  def records: js.UndefOr[js.Array[SearchRecordDto]] = js.native

/** 搜索结果记录检测：整页结果。 */
@js.native
trait SearchDetectResult extends js.Object:
  def url: js.UndefOr[String]                    = js.native
  def title: js.UndefOr[String]                  = js.native
  def profileApplied: js.UndefOr[Boolean]        = js.native
  def lists: js.UndefOr[js.Array[SearchListDto]] = js.native

  /** 诊断：全页最大的同签名兄弟组（hits / 该容器子节点数 / 其中通过记录形态判据的条数）。 */
  def bestSigHits: js.UndefOr[Int]    = js.native
  def bestSigTotal: js.UndefOr[Int]   = js.native
  def bestSigRecords: js.UndefOr[Int] = js.native

/** DOM.resolveNode + Runtime.callFunctionOn 得到的元素视口内中心点。 */
@js.native
trait ElementBox extends js.Object:
  def x: js.UndefOr[Double]      = js.native
  def y: js.UndefOr[Double]      = js.native
  def width: js.UndefOr[Double]  = js.native
  def height: js.UndefOr[Double] = js.native

/** 滚动脚本的返回值：实际被滚动的容器（window 或某个元素）与滚动后的位置。 */
@js.native
trait BrowserScrollResult extends js.Object:
  def target: js.UndefOr[String]  = js.native
  def targetX: js.UndefOr[Double] = js.native
  def targetY: js.UndefOr[Double] = js.native
  def windowX: js.UndefOr[Double] = js.native
  def windowY: js.UndefOr[Double] = js.native

object CdpCommandResults {

  /** Normalize a chrome.debugger command result without stripping a protocol-level field. */
  def commandResult(raw: js.Any): JsObject =
    if (js.isUndefined(raw) || raw == null) JsBridge.obj()
    else JsBridge.asObject(raw)

  def evaluateValue(commandResult: js.Any): js.Any =
    if (js.isUndefined(commandResult) || commandResult == null)
      throw new IllegalArgumentException("Runtime.evaluate returned no command result")
    else {
      val evaluation = JsBridge.asObject(commandResult).asInstanceOf[RuntimeEvaluateResult]
      evaluation.exceptionDetails.toOption match {
        case Some(details) => throw new IllegalArgumentException(evaluationFailure(details))
        case None          =>
          val remote = evaluation.result.toOption.getOrElse(
            throw new IllegalArgumentException("Runtime.evaluate returned no RemoteObject")
          )
          remote.value.toOption.getOrElse {
            if (remote.`type`.toOption.contains("undefined")) js.undefined
            else
              throw new IllegalArgumentException(
                s"Runtime.evaluate returned no value (type=${remote.`type`.toOption.getOrElse("unknown")}, " +
                  s"description=${remote.description.toOption.getOrElse("")})"
              )
          }
      }
    }

  def identifier(raw: js.Any): Option[String] =
    if (js.isUndefined(raw) || raw == null) None
    else
      JsBridge
        .asObject(raw)
        .asInstanceOf[AddScriptResult]
        .identifier
        .toOption
        .filter(_.nonEmpty)

  private def evaluationFailure(details: CdpExceptionDetails): String = {
    val description = details.exception.toOption.flatMap(_.description.toOption)
    val text        = details.text.toOption
    val summary     = (text.toList ++ description.toList).filter(_.nonEmpty).distinct.mkString("; ")
    val location    =
      for {
        line   <- details.lineNumber.toOption
        column <- details.columnNumber.toOption
      } yield s" at $line:$column"
    s"Runtime.evaluate failed: ${if (summary.nonEmpty) summary else "unknown page exception"}${location.getOrElse("")}"
  }
}
