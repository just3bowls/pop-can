package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import scala.scalajs.js

/** 工具参数的 JSON Schema 类型。 */
sealed trait ToolParamKind {
  def jsonType: String
}
object ToolParamKind       {
  case object Text  extends ToolParamKind { val jsonType = "string"  }
  case object Flag  extends ToolParamKind { val jsonType = "boolean" }
  case object Count extends ToolParamKind { val jsonType = "number"  }

  /** 任意 JSON（数组 / 嵌套对象）：schema 片段原样写进 parametersJson，zod 侧用 z.any()。 */
  case object Json extends ToolParamKind { val jsonType = "object" }
}

/** 单个工具参数；`parametersJson` 与 Zod shape 都由它派生。 */
final case class ToolParam(
    name: String,
    kind: ToolParamKind,
    description: String,
    required: Boolean = true,
    /** kind == Json 时原样写进 schema 的片段（含 type/items/properties）。 */
    jsonSchema: String = ""
)

/** 工具的执行归属：决定 background 用哪个执行器。 */
sealed trait ToolKind
object ToolKind {

  /** BrowserUseExecutor：所有浏览器能力都在某个会话（= 一个 tab）上执行。 */
  case object BrowserUse extends ToolKind

  /** 不参与 background 分发：由发起方的 agent 自己执行。 */
  case object Local extends ToolKind
}

/**
 * 一个工具的单一事实来源：name / description / 参数。 `parametersJson`（发给网页模型的 JSON schema）与插件侧的 Zod shape 都从 `params` 派生， 避免像过去那样在
 * parametersJson、schemaShape、defaultTools、dispatcher 四处各写一份。
 */
final case class AgentToolSpec(
    name: String,
    description: String,
    kind: ToolKind,
    params: List[ToolParam],
    /** 成功后直接结束当前 agent，不再把 ToolMessage 交回模型。 */
    terminal: Boolean = false
) {
  def parametersJson: String = {
    val properties = js.Dictionary[js.Any](params.map { param =>
      val schema =
        if (param.jsonSchema.trim.nonEmpty) js.JSON.parse(param.jsonSchema)
        else JsBridge.obj("type" -> (param.kind.jsonType), "description" -> (param.description))
      param.name -> schema
    }*)
    val required   = params.filter(_.required).map(_.name)
    val schema     =
      if (required.isEmpty) JsBridge.obj("type" -> ("object"), "properties" -> (properties))
      else JsBridge.obj("type" -> ("object"), "properties" -> (properties), "required" -> (js.Array(required*)))
    js.JSON.stringify(schema)
  }
}

/** 全部浏览器工具的唯一声明处。 */
object AgentToolRegistry {
  private val BrowserSearch = AgentToolSpec(
    name = "browser_search",
    description =
      "Run a web search with the browser's own default search engine and return a structured list of results " +
        "(title + URL to open + snippet, plus date and an [ad] marker when detected). These are summaries, not page " +
        "content, and the URL may be an engine redirect that resolves to the real page. The result page also opens " +
        "in this session's browser tab: open a result you intend to use with browser_navigate and read it with " +
        "browser_extract (without urls) before citing it. If no result list can be detected the call fails — then use " +
        "browser_snapshot to inspect the page yourself.",
    kind = ToolKind.BrowserUse,
    params = List(ToolParam("query", ToolParamKind.Text, "Search query."))
  )

  /** 动作类工具的可选开关：默认只回状态行，置 true 时连带返回与 browser_snapshot 相同的清单。 */
  private val SnapshotFlag    = ToolParam(
    "snapshot",
    ToolParamKind.Flag,
    "Include an accessibility snapshot of the resulting page (default false).",
    required = false
  )
  private val BrowserNavigate = AgentToolSpec(
    name = "browser_navigate",
    description =
      "Navigate the current browser tab to a URL. After browser_use_tab, this operates on the selected referenced tab " +
        "unless new_tab=true. Returns a one-line status by default; pass " +
        "snapshot=true to also get an accessibility snapshot of the loaded page. The tab stays open between steps, " +
        "so follow-up browser_click / browser_type / browser_scroll / browser_extract act on the same page. Use " +
        "browser_extract without urls when you want the text of the page you just opened. Set new_tab=true only when " +
        "you intentionally want to preserve the current page.",
    kind = ToolKind.BrowserUse,
    params = List(
      ToolParam("url", ToolParamKind.Text, "Absolute http(s) URL to open."),
      ToolParam(
        "new_tab",
        ToolParamKind.Flag,
        "Open in a new tool tab instead of navigating the current tab (default false).",
        required = false
      ),
      SnapshotFlag
    )
  )
  private val BrowserSnapshot = AgentToolSpec(
    name = "browser_snapshot",
    description =
      "Re-read the current page of this session's browser tab as an accessibility snapshot: a URL / Title / Viewport " +
        "header plus a flat, document-ordered list of the interactive elements, headings and landmarks. Every " +
        "interactive node carries a [ref=eN] id you can pass to browser_click / browser_type. Body text is omitted — " +
        "use browser_extract when you need the readable text.",
    kind = ToolKind.BrowserUse,
    params = Nil
  )
  private val BrowserClick    = AgentToolSpec(
    name = "browser_click",
    description =
      "Click the interactive element identified by ref from the most recent snapshot. Returns a one-line status by " +
        "default; pass snapshot=true to also get an updated accessibility snapshot. Refs only stay valid for the " +
        "document they came from: after a navigation or a new tab, take a fresh snapshot first.",
    kind = ToolKind.BrowserUse,
    params = List(
      ToolParam("ref", ToolParamKind.Text, "Ref id from the latest snapshot, e.g. e12."),
      SnapshotFlag
    )
  )
  private val BrowserType     = AgentToolSpec(
    name = "browser_type",
    description =
      "Focus the element identified by ref, replace its current text with the given text, and press Enter when " +
        "submit=true. Returns a one-line status by default; pass snapshot=true to also get an updated accessibility " +
        "snapshot of the page.",
    kind = ToolKind.BrowserUse,
    params = List(
      ToolParam("ref", ToolParamKind.Text, "Ref id from the latest snapshot, e.g. e7."),
      ToolParam("text", ToolParamKind.Text, "Text to type into the element."),
      ToolParam(
        "submit",
        ToolParamKind.Flag,
        "Press Enter after typing to submit the form or search box (default false).",
        required = false
      ),
      SnapshotFlag
    )
  )
  private val BrowserScroll   = AgentToolSpec(
    name = "browser_scroll",
    description =
      "Scroll the viewport of this session's browser tab by direction and an optional amount in CSS pixels " +
        "(default: about one viewport). Returns a one-line status by default; pass snapshot=true to also get an " +
        "updated accessibility snapshot showing what became visible.",
    kind = ToolKind.BrowserUse,
    params = List(
      ToolParam("direction", ToolParamKind.Text, "One of: up, down, left, right."),
      ToolParam(
        "amount",
        ToolParamKind.Count,
        "Scroll distance in CSS pixels; defaults to roughly one viewport.",
        required = false
      ),
      SnapshotFlag
    )
  )
  private val BrowserExtract  = AgentToolSpec(
    name = "browser_extract",
    description =
      "Return the readable main content of the current page of this session's browser tab: metadata → readability " +
        "extraction → matched regions → full text, whichever works first, as markdown with links up to 50000 " +
        "characters. On a search-results page it returns the structured result list (title / URL / snippet) instead " +
        "of article text. Pass urls only for additional or batch pages (max 3): the current tool tab or an existing " +
        "same-URL tab in the tools group is reused; only missing URLs open new tabs. Batch reading does not change the " +
        "current tab and gives no refs. With no urls it reads the page you just navigated to, so do not pass that URL " +
        "again. Use browser_snapshot / browser_click / browser_type when you need to operate the page instead.",
    kind = ToolKind.BrowserUse,
    params = List(
      ToolParam(
        "urls",
        ToolParamKind.Json,
        "Optional absolute http(s) URLs to read instead of the current page (max 3).",
        required = false,
        jsonSchema = """{"type":"array","items":{"type":"string"}}"""
      )
    )
  )
  private val BrowserUseTab   = AgentToolSpec(
    name = "browser_use_tab",
    description =
      "Select one of the user-referenced tabs in the current browser workspace as the operation page. Use a ref such as " +
        "T1 from the attached-tabs list; this reuses the real tab, creates nothing, and makes later browser_snapshot / " +
        "browser_click / browser_type / browser_scroll / browser_extract calls target it.",
    kind = ToolKind.BrowserUse,
    params = List(ToolParam("ref", ToolParamKind.Text, "Referenced tab id such as T1."))
  )

  /** 全部浏览器工具，顺序即模型看到的顺序。 */
  val browserTools: List[AgentToolSpec] = List(
    BrowserSearch,
    BrowserNavigate,
    BrowserSnapshot,
    BrowserClick,
    BrowserType,
    BrowserScroll,
    BrowserExtract,
    BrowserUseTab
  )

  /** chat agent 暴露的全部工具（目前等于浏览器工具全集）。 */
  val chatTools: List[AgentToolSpec] = browserTools

  private val byName: Map[String, AgentToolSpec] = browserTools.map(tool => tool.name -> tool).toMap
  def find(name: String): Option[AgentToolSpec]  = byName.get(name)
}
