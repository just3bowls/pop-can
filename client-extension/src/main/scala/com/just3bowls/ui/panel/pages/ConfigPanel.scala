package com.just3bowls.ui.panel.pages

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.provider.*
import com.just3bowls.model.memory.*
import com.just3bowls.model.localapi.*
import com.just3bowls.model.diagnostics.*

import com.just3bowls.ui.components.*

import com.just3bowls.facade.{ChromeAPI, TraceDto}
import com.just3bowls.api.memory.MemoryProviderRoute
import com.just3bowls.api.memory.MemorySettings
import com.just3bowls.api.provider.ServerRouting
import com.just3bowls.model.localapi.ApiKeyConfig
import com.just3bowls.model.localapi.ApiKeyRoute
import com.just3bowls.model.localapi.ApiKeyStore
import com.just3bowls.model.diagnostics.PopCanLog
import com.just3bowls.model.diagnostics.TraceStore
import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.CommentNode
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.js
final class ConfigPanelContext(
    val serverStatus: Var[String],
    /** Config 当前选中的子标签（`providers` / `apiKeys` / `memory` / `log`）：页面持有。 */
    val configSection: Var[String],
    val serverWorkerClient: ServerWorkerClient,
    val memoryClient: MemoryClient,
    val apiKeys: Var[List[ApiKeyConfig]],
    val parserProviders: Var[List[ParserProviderInfo]],
    val refreshParserProviders: () => Unit,
    val generateApiKey: String => Unit,
    val renameApiKey: (String, String) => Unit,
    val revokeApiKey: String => Unit,
    val copyApiKey: String => Future[Unit],
    /** `Config → API keys` 每个 key 的 Routing：每条候选 `provider + weight + promptMode`，外加策略 / failover。 */
    val setApiKeyRouting: (String, List[ApiKeyRoute], String, String) => Unit
)
private[ui] object ConfigPanel {

  /** Config → Memory: extraction, routing and recall settings. Stored memories live in the composer. */
  def renderMemorySection(ctx: ConfigPanelContext): HtmlElement = {
    val settings                                                            = Var(MemorySettings.Default)
    val routeEditorState                                                    = ProviderRouteEditor.state()
    def refresh(): Unit                                                     =
      ctx.memoryClient.settings().foreach(settings.set)
    def save(next: MemorySettings): Unit                                    = {
      val normalized = MemorySettings.normalize(next)
      settings.set(normalized)
      ctx.memoryClient
        .configure(normalized)
        .map(settings.set)
        .recover { case e =>
          Toast.error(s"Memory settings failed: ${Option(e.getMessage).getOrElse("unknown error")}")
          refresh()
        }
      ()
    }
    def saveMemoryRouting(
        routes: List[MemoryProviderRoute] = settings.now().extractionRoutes,
        strategy: String = settings.now().extractionStrategy,
        failover: String = settings.now().extractionFailover
    ): Unit =
      save(settings.now().copy(
        extractionRoutes = routes,
        extractionStrategy = strategy,
        extractionFailover = failover
      ))
    def memoryRouteDraft(route: MemoryProviderRoute): ProviderRouteDraft    =
      ProviderRouteDraft(route.provider, route.weight, route.promptMode)
    def saveMemoryDrafts(routes: List[ProviderRouteDraft]): Unit            =
      saveMemoryRouting(routes =
        routes.map(route =>
          MemoryProviderRoute(route.provider, route.weight, route.promptMode)
        )
      )
    div(
      width    := "100%",
      fontSize := "12px",
      onMountCallback(_ => refresh()),
      topicCard(List(
        groupHeading(
          "Settings",
          "Memory extraction and recall settings",
          div(
            "Automatic extraction controls background candidate discovery. ",
            "Strategy and Failover choose between provider routes; each provider chip stores a weight and inline/file mode and can be dragged to reorder. ",
            "Recall budget limits how much Memory context is injected into each run."
          )
        ),
        div(
          styleProp("display")               := "grid",
          styleProp("grid-template-columns") := "130px minmax(0, 1fr)",
          alignItems.center,
          rowGap                             := "8px",
          columnGap                          := "10px",
          span("Strategy", color := "#334155", flexShrink := "0"),
          select(
            width                := "140px",
            fontSize             := "12px",
            padding              := "3px 6px",
            border               := "1px solid #d1d5db",
            borderRadius         := "4px",
            title                := "How automatic Memory extraction chooses among the configured providers.",
            onChange.mapToValue --> { strategy => saveMemoryRouting(strategy = strategy) },
            option(value := ServerRouting.StrategyWeighted, "weighted"),
            option(value := ServerRouting.StrategyRandom, "random"),
            value <-- settings.signal.map(_.extractionStrategy)
          ),
          span("Failover", color := "#334155", flexShrink := "0"),
          select(
            width                := "140px",
            fontSize             := "12px",
            padding              := "3px 6px",
            border               := "1px solid #d1d5db",
            borderRadius         := "4px",
            title := "When an extraction attempt fails, retry another configured provider before giving up.",
            onChange.mapToValue --> { failover => saveMemoryRouting(failover = failover) },
            option(value := ServerRouting.FailoverOff, "off"),
            option(value := ServerRouting.FailoverNext, "next"),
            value <-- settings.signal.map(_.extractionFailover)
          ),
          span("Providers", color := "#334155", flexShrink := "0"),
          ProviderRouteEditor.render(
            state = routeEditorState,
            routes = settings.signal.map(_.extractionRoutes.map(memoryRouteDraft)),
            routesNow = () => settings.now().extractionRoutes.map(memoryRouteDraft),
            providers = ctx.parserProviders.signal,
            providersNow = () => ctx.parserProviders.now(),
            maxProviders = Some(MemorySettings.MaxExtractionProviders),
            scopeLabel = "Memory extraction",
            onSave = saveMemoryDrafts
          ),
          span("Automatic extraction", color := "#334155", flexShrink := "0"),
          div(
            display.flex,
            alignItems.center,
            gap                              := "8px",
            input(
              typ := "checkbox",
              checked <-- settings.signal.map(_.autoExtract),
              onChange.mapToChecked --> { enabled =>
                save(settings.now().copy(autoExtract = enabled))
              }
            )
          ),
          span("Recall budget", color        := "#334155", flexShrink := "0"),
          div(
            display.flex,
            alignItems.center,
            gap                              := "8px",
            input(
              typ                   := "number",
              width                 := "90px",
              value <-- settings.signal.map(_.contextBudgetTokens.toString),
              onChange.mapToValue --> { raw =>
                val parsed = raw.toIntOption.getOrElse(1200)
                save(settings.now().copy(contextBudgetTokens = parsed))
              }
            ),
            span("tokens", fontSize := "10px", color := "#64748b")
          )
        )
      ))
    )
  }

  /** 一处说明收成一个圆形 info 图标：hover / 键盘聚焦显示浮层，不参与页面布局。 */
  private[ui] def helpToggle(hint: String, body: => HtmlElement): HtmlElement             = {
    val hovered       = Var(false)
    val focused       = Var(false)
    val visible       = hovered.signal.combineWith(focused.signal).map { case (hover, focus) =>
      hover || focus
    }
    button(
      `type`       := "button",
      aria.label   := s"Help: $hint",
      aria.expanded <-- visible,
      position     := "relative",
      display      := "inline-flex",
      alignItems.center,
      justifyContent.center,
      width        := "20px",
      height       := "20px",
      padding      := "0",
      border       := "none",
      borderRadius := "999px",
      outline      := "none",
      backgroundColor <-- visible.map(value => if (value) "#f1f5f9" else "transparent"),
      color <-- visible.map(value => if (value) "#475569" else "#94a3b8"),
      boxShadow <-- focused.signal.map(value => if (value) "0 0 0 2px #dbeafe" else "none"),
      cursor       := "help",
      onMouseEnter --> { _ => hovered.set(true) },
      onMouseLeave --> { _ => hovered.set(false) },
      onFocus --> { _ => focused.set(true) },
      onBlur --> { _ => focused.set(false) },
      onKeyDown --> { event =>
        if (event.key == "Escape") {
          hovered.set(false)
          focused.set(false)
        }
      },
      svg.svg(
        svg.width          := "14",
        svg.height         := "14",
        svg.viewBox        := "0 0 16 16",
        svg.fill           := "none",
        svg.stroke         := "currentColor",
        svg.strokeWidth    := "1.6",
        svg.strokeLineCap  := "round",
        svg.strokeLineJoin := "round",
        aria.hidden        := true,
        svg.circle(svg.cx := "8", svg.cy := "8", svg.r    := "6.2"),
        svg.line(svg.x1   := "8", svg.y1 := "7.1", svg.x2 := "8", svg.y2 := "11"),
        svg.circle(
          svg.cx          := "8",
          svg.cy          := "4.7",
          svg.r           := "0.75",
          svg.fill        := "currentColor",
          svg.stroke      := "none"
        )
      ),
      child <-- visible.map { show =>
        if (!show) new CommentNode("")
        else
          div(
            position        := "absolute",
            top             := "22px",
            left            := "0",
            zIndex          := 800,
            width           := "min(360px, calc(100vw - 32px))",
            padding         := "8px 10px",
            backgroundColor := "#111827",
            border          := "1px solid rgba(255,255,255,0.08)",
            borderRadius    := "6px",
            boxShadow       := "0 8px 24px rgba(15,23,42,0.18)",
            fontSize        := "11px",
            color           := "#e5e7eb",
            lineHeight      := "1.45",
            pointerEvents   := "none",
            role            := "tooltip",
            span(
              position        := "absolute",
              top             := "-4px",
              left            := "5px",
              width           := "8px",
              height          := "8px",
              backgroundColor := "#111827",
              transform       := "rotate(45deg)"
            ),
            body
          )
      }
    )
  }
  private[ui] def sectionHeading(
      text: String,
      hint: String,
      body: => HtmlElement,
      trailing: HtmlElement = span()
  ): HtmlElement           =
    div(
      display.flex,
      alignItems.center,
      flexWrap.wrap,
      gap           := "4px",
      rowGap        := "4px",
      paddingBottom := "7px",
      marginBottom  := "8px",
      borderBottom  := "1px solid #eef2f7",
      span(text, fontSize := "12px", fontWeight.bold, color := "#111827"),
      helpToggle(hint, body),
      span(flexGrow       := "1"),
      trailing
    )
  private def groupHeading(text: String, hint: String, body: => HtmlElement): HtmlElement =
    sectionHeading(text, hint, body)
  private def topicCard(content: List[HtmlElement]): HtmlElement                          =
    div(
      padding         := "10px",
      marginBottom    := "12px",
      backgroundColor := "#ffffff",
      border          := "1px solid #e5e7eb",
      borderRadius    := "7px",
      children <-- Val(content)
    )

  /** Neutral action control used throughout Config; semantic state belongs to text, not button color. */
  private[ui] def configButton(
      label: Modifier[HtmlElement],
      onClickAction: () => Unit,
      disabledSignal: Signal[Boolean] = Val(false),
      textSize: String = "11px",
      spacing: String = "4px 9px",
      displaySignal: Signal[String] = Val("inline-flex")
  ): HtmlElement =
    NeutralActionButton.render(
      label,
      onClickAction,
      disabledSignal = disabledSignal,
      textSize = textSize,
      spacing = spacing,
      displaySignal = displaySignal
    )

  /** Shared section toolbar: status/help on the left, section-level actions on the right. */
  private[ui] def sectionToolbar(summary: HtmlElement, actions: List[HtmlElement]): HtmlElement =
    div(
      display.flex,
      alignItems.center,
      flexWrap.wrap,
      gap          := "8px",
      rowGap       := "6px",
      marginBottom := "10px",
      div(minWidth := "0", flexGrow := "1", summary),
      div(
        display.flex,
        alignItems.center,
        justifyContent.flexEnd,
        flexWrap.wrap,
        gap        := "8px",
        children <-- Val(actions)
      )
    )

  /**
   * `Advanced` 展开后的 Routing 子卡片。
   *
   * 候选 route 显示成 chip，点 chip 在上方展开共享编辑器；点击外部或 Enter 提交后立即 `setApiKeyRouting` 落库。
   *
   * prompt mode（inline/file）挂在**每条路由**上，不是每个 key 一个：同一个 key 的候选里，各家页面吃不吃附件可以不同。
   */
  private def renderRoutingBlock(
      ctx: ConfigPanelContext,
      key: ApiKeyConfig,
      routeEditorState: ProviderRouteEditor.State
  ): HtmlElement = {
    def routes: List[ApiKeyRoute] = key.routes
    def save(next: List[ApiKeyRoute], strategy: String = key.strategy, failover: String = key.failover): Unit =
      ctx.setApiKeyRouting(key.id, next, strategy, failover)
    def label(text: String): HtmlElement                                                                      =
      span(text, fontSize := "12px", color := "#111827", flexShrink := "0")
    def routeDraft(route: ApiKeyRoute): ProviderRouteDraft                                                    =
      ProviderRouteDraft(route.provider, route.weight, route.promptMode)
    def saveDrafts(next: List[ProviderRouteDraft]): Unit                                                      =
      save(next.map(route => ApiKeyRoute(route.provider, route.weight, route.promptMode)))
    val strategyFailoverItems                                                                                 = List(
      label("Strategy"),
      select(
        width    := "140px",
        fontSize := "12px",
        padding  := "2px 4px",
        onChange.mapToValue --> { strategy => save(routes, strategy = strategy) },
        option(value := ServerRouting.StrategyWeighted, "weighted"),
        option(value := ServerRouting.StrategyRandom, "random"),
        // `value` 必须在 option 之后：赋值时还没有 option 的话，浏览器会把它重置成第一个选项。
        value    := key.strategy
      ),
      label("Failover"),
      select(
        width    := "140px",
        fontSize := "12px",
        padding  := "2px 4px",
        onChange.mapToValue --> { failover => save(routes, failover = failover) },
        option(value := ServerRouting.FailoverOff, "off"),
        option(value := ServerRouting.FailoverNext, "next"),
        value    := key.failover
      )
    )

    val providerItems           = List(
      label("Providers"),
      ProviderRouteEditor.render(
        state = routeEditorState,
        routes = Val(routes.map(routeDraft)),
        routesNow = () =>
          ctx.apiKeys
            .now()
            .find(_.id == key.id)
            .map(_.routes.map(routeDraft))
            .getOrElse(routes.map(routeDraft)),
        providers = ctx.parserProviders.signal,
        providersNow = () => ctx.parserProviders.now(),
        maxProviders = None,
        scopeLabel = s"API key ${key.name}",
        onSave = saveDrafts
      )
    )
    div(
      width           := "100%",
      minWidth        := "0",
      boxSizing       := "border-box",
      display.flex,
      flexDirection.column,
      gap             := "8px",
      padding         := "10px",
      backgroundColor := "#f8fafc",
      border          := "1px solid #e5e7eb",
      borderRadius    := "7px",
      sectionHeading(
        "Routing",
        "Provider routing",
        div(
          "Strategy chooses providers by weighted round-robin or weighted random. ",
          "Failover=next retries another untried candidate before any output is sent. ",
          "Drag provider chips to change order; click a chip to edit weight and prompt mode; ✕ removes it."
        )
      ),
      div(
        display.grid,
        styleProp("grid-template-columns") := "96px minmax(0, 1fr)",
        alignItems.center,
        gap                                := "6px 8px",
        children <-- Val(strategyFailoverItems ::: providerItems)
      )
    )
  }
  private def renderLogSection(sectionVar: Signal[String]): HtmlElement = {
    val levelVar         = Var("info")
    val scopesVar        = Var("*")
    val scopesOpenVar    = Var(false)
    val traceJsonVar     = Var("[]")
    val traceBodyVar     = Var("")
    val traceEntriesVar  = Var(List.empty[TraceDto])
    val stateStatsVar    = Var("")
    val expandedTraceVar = Var("")
    val traceDetailVar   = Var("")
    var traceDetailCache = Map.empty[String, String]
    var traceDetailEpoch = 0L

    /** 空转记录（web-native 每 ~3 分钟一条）默认不占列表位置。 */
    val showIdleVar                                  = Var(false)
    val actionStateVar                               = Var(Map.empty[String, String])
    val confirmClearVar                              = Var(false)
    def markAction(key: String, state: String): Unit = {
      actionStateVar.update(_ + (key -> state))
      js.timers.setTimeout(1500)(actionStateVar.update(_ - key))
    }
    def actionButton(key: String, idleLabel: String, doneLabel: String)(
        action: () => Future[Unit]
    ): HtmlElement = {
      val state = actionStateVar.signal.map(_.getOrElse(key, ""))
      configButton(
        child.text <-- state.map {
          case "done"   => doneLabel
          case "failed" => "Failed"
          case _        => idleLabel
        },
        () => {
          action()
            .map(_ => markAction(key, "done"))
            .recover { case e =>
              markAction(key, "failed")
              Toast.error(s"$idleLabel failed: ${e.getMessage}")
            }
            .foreach(_ => ())
        },
        textSize = "10px",
        spacing = "2px 8px"
      )
    }
    def copyText(text: String): Future[Unit]         =
      try {
        val clipboard = JsBridge.navigator.clipboard
        if (js.isUndefined(clipboard) || clipboard == null)
          Future.failed(new Exception("Clipboard unavailable"))
        else
          JsBridge.asPromiseAny(clipboard.writeText(text)).toFuture.map(_ => ())
      } catch {
        case e: Throwable => Future.failed(e)
      }
    def loadSettings(): Unit                         =
      ChromeAPI.storage.local
        .get(js.Array(PopCanLog.LevelKey, PopCanLog.ScopesKey))
        .toFuture
        .foreach { values =>
          values.get(PopCanLog.LevelKey).foreach(raw => levelVar.set(raw.toString))
          values.get(PopCanLog.ScopesKey).foreach(raw => scopesVar.set(raw.toString))
        }

    /** 存储健康度：分片后的文档数 / 字节 / 脏标记（由 SW 报，页面不直接读状态文档）。 */
    def loadStateStats(): Unit =
      ChromeAPI.runtime
        .sendMessage(JsBridge.obj("type" -> ("STATE_STATS")))
        .toFuture
        .foreach { raw =>
          val dynamic = JsBridge.asObject(raw)
          val stats   = JsBridge.undefinedAny(dynamic.stats).toOption
          stateStatsVar.set(
            stats
              .map { value =>
                val docs  = JsBridge.intField(value, "docs").getOrElse(0)
                val bytes = JsBridge.doubleField(value, "bytes").getOrElse(0.0)
                val dirty = JsBridge.intField(value, "dirty").getOrElse(0)
                s"docs=$docs bytes=${bytes.toInt} dirty=$dirty"
              }
              .getOrElse("unavailable")
          )
        }

    /** 索引里出现的 gen 列表（body key 由 gen 推导）。 */
    def traceGens(raw: Option[js.Any]): List[String] =
      TraceDtoFactory.readAll(raw).flatMap(_.gen.toOption).filter(_.nonEmpty)
    val phaseOrder                                   = List(
      "build",
      "focus",
      "alloc",
      "prepare",
      "attach",
      "attachFile",
      "focusInput",
      "type",
      "preSend",
      "send",
      "stream",
      "parse",
      "pageTotal",
      "bgTotal"
    )

    /** 时间 ≥1s 用秒显示。 */
    def fmtMs(ms: Double): String =
      if (ms >= 1000) s"${Math.round(ms / 100.0) / 10.0}s" else s"${Math.round(ms)}ms"

    /** 字符数 ≥1000 用 `k` 缩写（78.5k / 1.2k）。 */
    def fmtChars(n: Int): String                =
      if (n < 1000) n.toString
      else {
        val k = Math.round(n / 100.0) / 10.0
        if (k == Math.floor(k)) s"${k.toInt}k" else s"${k}k"
      }
    def shorten(text: String, max: Int): String =
      if (text.length <= max) text else s"${text.take(max)}…"

    /** 空转记录：web-native 观察器每 ~3 分钟给每个被观察 tab 写一条 `no matching API request`。 */
    def isIdle(entry: TraceDto): Boolean                       =
      entry.error.toOption.exists(_.trim == "no matching API request")
    def traceRowKey(entry: TraceDto): String                   =
      entry.gen.toOption
        .filter(_.nonEmpty)
        .getOrElse {
          val at = entry.at.toOption.getOrElse(0.0)
          val id = entry.requestId.toOption.filter(_.nonEmpty).getOrElse("-")
          s"no-gen:$at:$id"
        }
    def traceDetailJson(
        entry: TraceDto,
        bodyText: Option[String],
        bodyError: Option[String] = None
    ): String = {
      val root = JsBridge.obj("trace" -> (entry))
      bodyText match {
        case Some(text) =>
          try root.body = js.JSON.parse(text)
          catch {
            case e: Throwable =>
              root.body = null
              root.bodyText = text
              root.bodyError = Option(e.getMessage).getOrElse("Trace body is not valid JSON")
          }
        case None       =>
          root.body = null
      }
      bodyError.foreach(error => root.bodyError = error)
      JsBridge.asString(js.JSON.stringify(root, null, 2))
    }
    def loadTraceDetail(entry: TraceDto, rowKey: String): Unit = {
      val epoch                         = traceDetailEpoch
      def publish(detail: String): Unit =
        if (traceDetailEpoch == epoch) {
          traceDetailCache += rowKey -> detail
          if (expandedTraceVar.now() == rowKey) traceDetailVar.set(detail)
        }
      entry.gen.toOption.filter(_.nonEmpty) match {
        case Some(gen) =>
          ChromeAPI.storage.local
            .get(TraceStore.bodyKey(gen))
            .toFuture
            .map { values =>
              values
                .get(TraceStore.bodyKey(gen))
                .map(_.toString)
                .filter(_.nonEmpty) match {
                case Some(body) => traceDetailJson(entry, Some(body))
                case None       => traceDetailJson(entry, None, Some("Trace body not stored"))
              }
            }
            .recover { case e =>
              traceDetailJson(
                entry,
                None,
                Some(s"Trace body read failed: ${Option(e.getMessage).getOrElse("unknown error")}")
              )
            }
            .foreach(publish)
        case None      =>
          publish(traceDetailJson(entry, None, Some("Trace has no gen; body key is unavailable")))
      }
    }
    def toggleTrace(entry: TraceDto): Unit                     = {
      val rowKey = traceRowKey(entry)
      if (expandedTraceVar.now() == rowKey) {
        expandedTraceVar.set("")
        traceDetailVar.set("")
      } else {
        expandedTraceVar.set(rowKey)
        traceDetailCache.get(rowKey) match {
          case Some(detail) => traceDetailVar.set(detail)
          case None         =>
            traceDetailVar.set("Loading trace detail…")
            loadTraceDetail(entry, rowKey)
        }
      }
    }

    /** 每条 trace 只占一行（状态 / provider / 时间 / 耗时 / 明细），完整信息（含阶段耗时）进 hover title。 */
    def traceRow(entry: TraceDto): HtmlElement = {
      val rowKey      = traceRowKey(entry)
      val expanded    = expandedTraceVar.signal.map(_ == rowKey)
      val gen         = entry.gen.toOption.filter(_.nonEmpty).getOrElse("(no gen)")
      val provider    = entry.provider.toOption.filter(_.nonEmpty).getOrElse("-")
      val at          = entry.at.toOption.map(ms => new js.Date(ms).toLocaleTimeString()).getOrElse("-")
      val markers     = entry.markers.toOption
      val valid       = markers.flatMap(_.valid.toOption).getOrElse(false)
      val idle        = isIdle(entry)
      val errorTxt    = entry.error.toOption.filter(_.nonEmpty).getOrElse("")
      val rawChars    = entry.rawChars.toOption.getOrElse(0)
      val normChars   = entry.normalizedChars.toOption.getOrElse(0)
      val duration    = fmtMs(entry.durationMs.toOption.getOrElse(0.0))
      val status      = if (idle) "·" else if (errorTxt.nonEmpty) "✗" else if (valid) "✓" else "…"
      val statusColor =
        if (idle) "#9ca3af" else if (errorTxt.nonEmpty) "#dc2626" else if (valid) "#16a34a" else "#9ca3af"
      val markerTxt   =
        markers
          .map(m =>
            s"marker ${m.contentChars.toOption.getOrElse(0)}/${m.thinkingChars.toOption.getOrElse(0)}/" +
              s"${m.toolCalls.toOption.getOrElse(0)}"
          )
          .getOrElse("marker -")
      val detail      =
        if (idle) "no matching API request"
        else if (errorTxt.nonEmpty) s"error: ${shorten(errorTxt, 80)}"
        else s"$markerTxt · raw ${fmtChars(rawChars)}→norm ${fmtChars(normChars)}"
      val timings     = entry.timings.toOption
      val phaseTxt    = phaseOrder
        .flatMap(phase => timings.flatMap(_.get(phase)).map(ms => s"$phase ${fmtMs(ms)}"))
        .mkString(" · ")
      val titleText   =
        s"$provider · $gen\n" +
          s"$at · ${duration} · chunks=${entry.chunks.toOption.getOrElse(0)} · valid=$valid\n" +
          s"raw ${rawChars} → norm ${normChars}\n" +
          s"${entry.matchedUrl.toOption.filter(_.nonEmpty).getOrElse("matched url -")}\n" +
          s"requestId ${entry.requestId.toOption.filter(_.nonEmpty).getOrElse("-")}\n" +
          (if (errorTxt.nonEmpty) s"error: $errorTxt\n" else "") +
          s"timings: ${if (phaseTxt.isEmpty) "-" else phaseTxt}"
      div(
        div(
          role                               := "button",
          tabIndex                           := 0,
          display.grid,
          styleProp("grid-template-columns") := "12px 18px 64px 56px 52px 1fr",
          alignItems.center,
          gap                                := "6px",
          padding                            := "2px 4px",
          borderRadius                       := "3px",
          cursor.pointer,
          fontSize                           := "11px",
          color                              := "#111827",
          title                              := titleText,
          aria.expanded <-- expanded,
          onMouseEnter --> { ev =>
            ev.currentTarget.asInstanceOf[dom.HTMLElement].style.backgroundColor = "#f9fafb"
          },
          onMouseLeave --> { ev =>
            ev.currentTarget.asInstanceOf[dom.HTMLElement].style.backgroundColor = ""
          },
          onClick --> { _ => toggleTrace(entry) },
          onKeyDown --> { ev =>
            if (ev.key == "Enter" || ev.key == " ") {
              ev.preventDefault()
              toggleTrace(entry)
            }
          },
          span(
            child.text <-- expanded.map(open => if (open) "▾" else "▸"),
            color              := "#94a3b8",
            flexShrink         := "0"
          ),
          span(status, color   := statusColor, fontWeight.bold),
          span(provider, whiteSpace.nowrap, overflow.hidden, textOverflow.ellipsis),
          span(at, color       := "#6b7280", whiteSpace.nowrap),
          span(duration, color := "#6b7280", textAlign.right, whiteSpace.nowrap),
          span(detail, color   := "#374151", overflow.hidden, textOverflow.ellipsis, whiteSpace.nowrap)
        ),
        child <-- expanded.map { open =>
          if (!open) emptyNode
          else
            div(
              margin := "2px 2px 6px 36px",
              pre(
                child.text <-- traceDetailVar.signal,
                margin          := "0",
                maxHeight       := "320px",
                overflow        := "auto",
                padding         := "8px",
                fontSize        := "10px",
                lineHeight      := "15px",
                color           := "#334155",
                backgroundColor := "#f9fafb",
                border          := "1px solid #e5e7eb",
                borderRadius    := "4px",
                whiteSpace      := "pre-wrap",
                wordBreak       := "break-all"
              )
            )
        }
      )
    }
    def loadTraces(): Future[Unit]             = {
      expandedTraceVar.set("")
      traceDetailVar.set("")
      traceDetailEpoch += 1
      traceDetailCache = Map.empty
      ChromeAPI.storage.local.get(TraceStore.Key).toFuture.flatMap { values =>
        val raw     = values.get(TraceStore.Key)
        traceJsonVar.set(raw.map(entry => js.JSON.stringify(entry, null, 2)).getOrElse("[]"))
        val entries = TraceDtoFactory.readAll(raw)
        traceEntriesVar.set(entries)
        val gens    = entries.flatMap(_.gen.toOption).filter(_.nonEmpty)
        if (gens.isEmpty) {
          traceBodyVar.set("bodies -")
          Future.successful(())
        } else
          try
            ChromeAPI.storage.local.getKeys().toFuture.map { keys =>
              val stored = gens.count(gen => keys.exists(_ == TraceStore.bodyKey(gen)))
              traceBodyVar.set(s"bodies $stored/${gens.size} · loaded on demand")
            }.recover { case _ =>
              traceBodyVar.set("bodies loaded on demand")
            }
          catch {
            case _: Throwable =>
              traceBodyVar.set("bodies loaded on demand")
              Future.successful(())
          }
      }
    }

    /** 索引 + 每次 run 的完整正文（raw / 归一化 / rule）合成一份可导出的 JSON。 */
    def traceBundle(): Future[String]                      =
      ChromeAPI.storage.local.get(TraceStore.Key).toFuture.flatMap { values =>
        val raw                                          = values.get(TraceStore.Key)
        val gens                                         = traceGens(raw)
        val bodyKeys                                     = gens.map(TraceStore.bodyKey)
        def build(bodies: js.Dictionary[js.Any]): String = {
          val bodyMap = js.Dictionary[js.Any]()
          gens.foreach { gen =>
            bodies.get(TraceStore.bodyKey(gen)).foreach { body =>
              try bodyMap.update(gen, js.JSON.parse(body.toString))
              catch { case _: Throwable => () }
            }
          }
          js.JSON.stringify(
            JsBridge.obj("traces" -> (raw.getOrElse(js.Array[js.Any]())), "bodies" -> (bodyMap)),
            null,
            2
          )
        }
        if (bodyKeys.isEmpty) Future.successful(build(js.Dictionary[js.Any]()))
        else
          ChromeAPI.storage.local
            .get(js.Array(bodyKeys*))
            .toFuture
            .map(build)
            .recover { case _ => build(js.Dictionary[js.Any]()) }
      }
    def downloadJson(name: String, text: String): Unit     = {
      val blob = JsBridge.newInstance(JsBridge.blobConstructor)(
        js.Array(text),
        JsBridge.obj("type" -> ("application/json"))
      )
      val url  = JsBridge.asString(JsBridge.urlConstructor.createObjectURL(blob))
      val link = dom.document.createElement("a").asInstanceOf[dom.HTMLAnchorElement]
      link.href = url
      link.download = name
      dom.document.body.appendChild(link)
      link.click()
      dom.document.body.removeChild(link)
      JsBridge.urlConstructor.revokeObjectURL(url)
    }
    def clearTraces(): Future[Unit]                        =
      ChromeAPI.storage.local.get(TraceStore.Key).toFuture.flatMap { values =>
        val keys = js.Array(TraceStore.Key)
        traceGens(values.get(TraceStore.Key)).foreach(gen => keys.push(TraceStore.bodyKey(gen)))
        ChromeAPI.storage.local.remove(keys).toFuture.map { _ =>
          traceJsonVar.set("[]")
          traceBodyVar.set("bodies -")
          traceEntriesVar.set(Nil)
          expandedTraceVar.set("")
          traceDetailVar.set("")
          traceDetailEpoch += 1
          traceDetailCache = Map.empty
          ()
        }
      }
    def persistLevel(level: String): Unit                  =
      levelVar.set(level)
      ChromeAPI.storage.local.set(js.Dictionary[js.Any](PopCanLog.LevelKey -> level)).toFuture
        .foreach(_ => ())
    def persistScopes(scopes: String): Unit                =
      scopesVar.set(scopes)
      ChromeAPI.storage.local.set(js.Dictionary[js.Any](PopCanLog.ScopesKey -> scopes)).toFuture
        .foreach(_ => ())
    loadSettings()
    loadTraces()
    loadStateStats()
    // 切到 Log section 时刷新一次（追踪记录是异步写进 storage 的，面板挂载时可能还没有数据）。
    val refreshOnOpen: Signal[List[HtmlElement]]           = sectionVar.map { section =>
      if (section == "log") {
        loadSettings()
        loadTraces()
        loadStateStats()
      }
      List.empty[HtmlElement]
    }
    val levelOptions                                       = List("off", "error", "warn", "info", "debug")
    val logControlWidth                                    = "160px"
    def scopeList(raw: String): List[String]               =
      PopCanLog.scopePatterns(raw).filterNot(_ == PopCanLog.AllScopes)
    def scopeLabel(raw: String): String                    = {
      val patterns = scopeList(raw)
      if (patterns.isEmpty) "All channels"
      else if (patterns.size <= 2) patterns.mkString(", ")
      else s"${patterns.size} channels"
    }
    def toggleScope(scope: String, enabled: Boolean): Unit = {
      val raw  = scopesVar.now()
      val next =
        if (raw == PopCanLog.AllScopes && !enabled)
          PopCanLog.KnownScopes.filterNot(_ == scope).mkString(",")
        else PopCanLog.toggleScope(raw, scope, enabled)
      persistScopes(next)
    }
    def addScopePatterns(value: String): Unit              = {
      val additions = scopeList(value)
      if (additions.nonEmpty) {
        val next = (scopeList(scopesVar.now()) ++ additions).distinct
        persistScopes(if (next.isEmpty) PopCanLog.AllScopes else next.mkString(","))
      }
    }
    def scopesDropdown(): HtmlElement                      = {
      var outsideListener: js.UndefOr[js.Function1[dom.MouseEvent, Unit]]   = js.undefined
      div(
        position   := "relative",
        width      := logControlWidth,
        flexShrink := "0",
        onMountCallback { ctx =>
          val root = ctx.thisNode.ref
          outsideListener = { event =>
            val target = event.target.asInstanceOf[dom.Node]
            if (scopesOpenVar.now() && !root.contains(target)) scopesOpenVar.set(false)
          }
          dom.document.addEventListener("mousedown", outsideListener.get)
        },
        onUnmountCallback { _ =>
          outsideListener.foreach(dom.document.removeEventListener("mousedown", _))
          outsideListener = js.undefined
        },
        button(
          `type`          := "button",
          width           := "100%",
          height          := "29px",
          padding         := "4px 8px",
          display.flex,
          alignItems.center,
          justifyContent.spaceBetween,
          gap             := "8px",
          fontSize        := "12px",
          color           := "#111827",
          backgroundColor := "#ffffff",
          border          := "1px solid #e5e7eb",
          borderRadius    := "4px",
          cursor          := "pointer",
          title <-- scopesVar.signal.map { raw =>
            val patterns = scopeList(raw)
            s"Log channels: ${if (patterns.isEmpty) "All channels" else patterns.mkString(", ")}"
          },
          span(
            flexGrow  := "1",
            minWidth  := "0",
            overflow.hidden,
            textOverflow.ellipsis,
            whiteSpace.nowrap,
            textAlign := "left",
            child.text <-- scopesVar.signal.map(scopeLabel)
          ),
          span(
            child.text <-- scopesOpenVar.signal.map(open => if (open) "▴" else "▾"),
            color     := "#94a3b8",
            fontSize  := "10px"
          ),
          onClick --> { _ => scopesOpenVar.update(!_) }
        ),
        child <-- scopesOpenVar.signal.combineWith(scopesVar.signal).map { case (open, raw) =>
          if (!open) emptyNode
          else {
            val selected        = scopeList(raw)
            val all             = raw == PopCanLog.AllScopes || selected.isEmpty
            val custom          = selected.filterNot(PopCanLog.KnownScopes.contains)
            div(
              position        := "absolute",
              top             := "calc(100% + 4px)",
              left            := "0",
              width           := "220px",
              maxHeight       := "260px",
              overflowY.auto,
              zIndex          := 500,
              padding         := "6px",
              backgroundColor := "#ffffff",
              border          := "1px solid #dbe3ee",
              borderRadius    := "6px",
              boxShadow       := "0 8px 24px rgba(15,23,42,0.16)",
              div(
                display.flex,
                flexDirection.column,
                gap := "3px",
                button(
                  `type`          := "button",
                  width           := "100%",
                  padding         := "5px 7px",
                  textAlign       := "left",
                  fontSize        := "11px",
                  color           := (if (all) "#1d4ed8" else "#374151"),
                  backgroundColor := (if (all) "#eff6ff" else "transparent"),
                  border          := "none",
                  borderRadius    := "4px",
                  cursor          := "pointer",
                  "All channels",
                  onClick --> { _ =>
                    persistScopes(PopCanLog.AllScopes)
                    scopesOpenVar.set(false)
                  }
                ),
                PopCanLog.KnownScopes.map { scope =>
                  label(
                    display.flex,
                    alignItems.center,
                    gap      := "6px",
                    padding  := "3px 7px",
                    fontSize := "11px",
                    color    := "#374151",
                    cursor   := "pointer",
                    input(
                      `type`  := "checkbox",
                      checked := (all || selected.contains(scope)),
                      onChange.mapToChecked --> { enabled => toggleScope(scope, enabled) }
                    ),
                    scope
                  )
                },
                custom.map { scope =>
                  label(
                    display.flex,
                    alignItems.center,
                    gap      := "6px",
                    padding  := "3px 7px",
                    fontSize := "11px",
                    color    := "#374151",
                    cursor   := "pointer",
                    input(
                      `type`  := "checkbox",
                      checked := selected.contains(scope),
                      onChange.mapToChecked --> { enabled => toggleScope(scope, enabled) }
                    ),
                    s"Custom: $scope"
                  )
                },
                input(
                  `type`          := "text",
                  width           := "100%",
                  boxSizing       := "border-box",
                  marginTop       := "3px",
                  padding         := "4px 6px",
                  fontSize        := "11px",
                  border          := "1px solid #e5e7eb",
                  borderRadius    := "4px",
                  placeholder     := "Custom pattern, e.g. web*",
                  onKeyDown --> { event =>
                    if (event.key == "Enter") {
                      event.preventDefault()
                      val field = event.currentTarget.asInstanceOf[dom.HTMLInputElement]
                      addScopePatterns(field.value)
                      field.value = ""
                    }
                  }
                )
              )
            )
          }
        }
      )
    }
    div(
      children <-- refreshOnOpen,
      topicCard(List(
        groupHeading(
          "Settings",
          "Log filtering settings",
          div(
            "Log level controls how much the extension writes: error/warn = failures only, info = one anchor line per turn, debug = verbose detail. ",
            "Log channels limits info/debug output to selected modules. ",
            "State docs/bytes/dirty describe the per-window storage shards.",
            div(
              marginTop := "4px",
              span("Now: ", color := "#374151"),
              span(
                child.text <-- stateStatsVar.signal,
                color             := "#374151",
                fontFamily        := "ui-monospace, SFMono-Regular, Menlo, monospace"
              )
            )
          )
        ),
        div(
          display.grid,
          styleProp("grid-template-columns") := "76px auto",
          alignItems.center,
          gap                                := "6px 8px",
          span("Log level", fontSize    := "12px", color := "#111827", whiteSpace.nowrap),
          select(
            levelOptions.map(level => option(value := level, level)),
            value <-- levelVar.signal,
            width                       := logControlWidth,
            height                      := "29px",
            padding                     := "4px 6px",
            boxSizing                   := "border-box",
            fontSize                    := "12px",
            border                      := "1px solid #e5e7eb",
            borderRadius                := "4px",
            onChange --> { event =>
              persistLevel(event.target.asInstanceOf[dom.HTMLSelectElement].value)
            }
          ),
          span("Log channels", fontSize := "12px", color := "#111827", whiteSpace.nowrap),
          scopesDropdown()
        )
      )),
      topicCard(List(
        sectionHeading(
          "Traces",
          s"Last ${TraceStore.MaxEntries} calls · bgTotal / pageTotal are totals",
          span(
            s"Last ${TraceStore.MaxEntries} calls (captured size / rule hits / marker result / phase timings). ",
            "Phases do not overlap; only bgTotal (whole SW run) and pageTotal (whole page-side run) are totals. ",
            "Durations ≥1s are shown in seconds. Click a row to load its trace index and full body; Copy all / Download include every stored body."
          )
        ),
        div(
          display.flex,
          alignItems.center,
          justifyContent.flexEnd,
          flexWrap.wrap,
          gap             := "8px",
          marginBottom    := "8px",
          actionButton("refresh", "Refresh", "Refreshed ✓")(() => loadTraces()),
          actionButton("copy", "Copy metadata", "Copied ✓")(() => copyText(traceJsonVar.now()))
            .amend(title := "Copy trace metadata without request or response bodies."),
          actionButton("copy-all", "Copy all", "Copied ✓")(() =>
            traceBundle().flatMap(copyText)
          ).amend(title  := "Copy trace metadata and all locally stored request/response bodies."),
          actionButton("download", "Download", "Downloaded ✓")(() =>
            traceBundle().map(bundle =>
              downloadJson(s"popcan-traces-${js.Date.now().toLong}.json", bundle)
            )
          ),
          child <-- confirmClearVar.signal.map { confirming =>
            if (!confirming)
              configButton(
                span("Clear"),
                () => confirmClearVar.set(true),
                textSize = "10px",
                spacing = "2px 8px"
              )
            else
              div(
                display.flex,
                alignItems.center,
                gap := "6px",
                span("Clear?", fontSize := "10px", color := "#b91c1c"),
                actionButton("clear", "Yes", "Cleared ✓")(() =>
                  clearTraces().map { _ =>
                    js.timers.setTimeout(1400)(confirmClearVar.set(false))
                    ()
                  }
                ),
                configButton(
                  span("No"),
                  () => confirmClearVar.set(false),
                  textSize = "10px",
                  spacing = "2px 8px"
                )
              )
          }
        ),
        div(
          children <-- traceEntriesVar.signal.combineWith(showIdleVar.signal).map { case (entries, showIdle) =>
            if (entries.isEmpty) List(div(fontSize := "10px", color := "#6b7280", "No traces yet."))
            else {
              val idleCount = entries.count(isIdle)
              val visible   = if (showIdle) entries else entries.filterNot(isIdle)
              val footer    =
                if (idleCount == 0) Nil
                else
                  List(
                    div(
                      marginTop := "4px",
                      padding   := "2px 4px",
                      fontSize  := "10px",
                      color     := "#6b7280",
                      cursor.pointer,
                      if (showIdle) s"hide $idleCount idle entries"
                      else s"$idleCount idle entries hidden · show",
                      onClick --> { _ => showIdleVar.update(!_) }
                    )
                  )
              visible.map(traceRow) ::: footer
            }
          },
          maxHeight       := "180px",
          overflow        := "auto",
          marginBottom    := "6px",
          padding         := "6px 8px",
          backgroundColor := "#ffffff",
          border          := "1px solid #e5e7eb",
          borderRadius    := "4px"
        ),
        div(
          display.flex,
          justifyContent.flexEnd,
          marginTop       := "4px",
          span(
            child.text <-- traceBodyVar.signal.map(text => if (text.isEmpty) "bodies -" else text),
            fontSize := "10px",
            color    := "#6b7280",
            whiteSpace.nowrap
          )
        )
      ))
    )
  }
  def renderNavigation(ctx: ConfigPanelContext): HtmlElement            = {
    val configSectionVar         = ctx.configSection
    def menuButton(
        key: String,
        label: String,
        dynamicLabel: Option[Signal[String]] = None
    ): HtmlElement = {
      val labelMod: Modifier[HtmlElement]     = dynamicLabel match {
        case Some(signal) => child.text <-- signal
        case None         => span(label)
      }
      button(
        labelMod,
        fontSize     := "12px",
        padding      := "5px 14px",
        cursor.pointer,
        whiteSpace.nowrap,
        flexShrink   := "0",
        backgroundColor <-- configSectionVar.signal.map(v => if (v == key) "#ffffff" else "transparent"),
        color <-- configSectionVar.signal.map(v => if (v == key) "#1d4ed8" else "#111827"),
        border       := "none",
        borderRadius := "4px",
        boxShadow <-- configSectionVar.signal.map(v =>
          if (v == key) "0 1px 2px rgba(0,0,0,0.08)" else "none"
        ),
        transition   := "background-color 0.15s ease, box-shadow 0.15s ease",
        onClick --> { _ =>
          configSectionVar.set(key)
          if (key == "providers") ctx.refreshParserProviders()
        }
      )
    }
    div(
      display         := "flex",
      alignItems.center,
      gap             := "2px",
      width           := "100%",
      padding         := "3px",
      backgroundColor := "#f3f4f6",
      borderRadius    := "6px",
      overflowX       := "auto",
      boxSizing.borderBox,
      menuButton("providers", "Providers"),
      menuButton("apiKeys", "API Keys"),
      menuButton("memory", "Memory"),
      menuButton("log", "Log")
    )
  }
  def renderContent(ctx: ConfigPanelContext): HtmlElement               = {
    val newApiKeyNameVar = Var("")
    val showCreateKeyVar = Var(false)
    val editingKeyIdVar  = Var("")

    /** 展开 Routing 编辑的 key id（新建 key 后自动展开）。 */
    /** 展开 `Advanced`（prompt mode + routing）的 key id；新建 key 后自动展开。 */
    val advancedOpenVar                                            = Var(Set.empty[String])
    var routeEditorStates                                          = Map.empty[String, ProviderRouteEditor.State]
    def routeEditorState(keyId: String): ProviderRouteEditor.State =
      routeEditorStates.getOrElse(
        keyId, {
          val state = ProviderRouteEditor.state()
          routeEditorStates += keyId -> state
          state
        }
      )

    val editNameVar                           = Var("")
    val configSectionVar                      = ctx.configSection
    val localApiUrl                           = LocalApiRuntimeStatus.Endpoint
    def formatTime(ts: Double): String        =
      if (ts <= 0) "-" else new js.Date(ts).toLocaleString()
    def formatLastUsed(ts: Double): String    =
      if (ts <= 0) "Never used" else new js.Date(ts).toLocaleString()
    def copyLocalApiUrl(): Future[Unit]       =
      try {
        val clipboard = JsBridge.navigator.clipboard
        if (js.isUndefined(clipboard) || clipboard == null)
          Future.failed(new RuntimeException("clipboard unavailable"))
        else
          JsBridge.asPromiseAny(clipboard.writeText(localApiUrl)).toFuture
            .map(_ => ())
      } catch {
        case e: Throwable =>
          Future.failed(e)
      }
    val localApiActive                        =
      ctx.serverStatus.signal.map(ctx.serverWorkerClient.isServerActive)
    val localApiColor                         =
      ctx.serverStatus.signal.map { status =>
        LocalApiStatus.controlColor(status, ctx.serverWorkerClient.isServerActive(status))
      }
    val localApiTooltip                       =
      ctx.serverStatus.signal.map { status =>
        val action =
          if (ctx.serverWorkerClient.isServerActive(status)) "stop" else "start"
        s"Local OpenAI API · $status · $localApiUrl · click to $action"
      }
    val localApiAriaLabel                     =
      ctx.serverStatus.signal.map { status =>
        val action =
          if (ctx.serverWorkerClient.isServerActive(status)) "Stop" else "Start"
        s"$action local OpenAI-compatible API"
      }
    val localApiSwitch                        =
      OnOffLabel.render(
        activeSignal = localApiActive,
        colorSignal = localApiColor,
        tooltipSignal = localApiTooltip,
        ariaLabelSignal = localApiAriaLabel
      ) { _ =>
        if (ctx.serverWorkerClient.isServerActive(ctx.serverStatus.now()))
          ctx.serverWorkerClient.stopServerWorker()
        else ctx.serverWorkerClient.startServerWorker()
      }
    div(
      width    := "100%",
      padding  := "12px",
      fontSize := "12px",
      boxSizing.borderBox,
      div(
        display <-- configSectionVar.signal.map(v => if (v == "providers") "block" else "none"),
        border          := "1px solid #e5e7eb",
        borderRadius    := "8px",
        backgroundColor := "#f8fafc",
        padding         := "12px",
        ParserPanel.render(ctx.refreshParserProviders)
      ),
      div(
        display <-- configSectionVar.signal.map(v => if (v == "memory") "block" else "none"),
        border          := "1px solid #e5e7eb",
        borderRadius    := "8px",
        backgroundColor := "#f8fafc",
        padding         := "12px",
        renderMemorySection(ctx)
      ),
      // API keys
      div(
        display <-- configSectionVar.signal.map(v => if (v == "apiKeys") "block" else "none"),
        border          := "1px solid #e5e7eb",
        borderRadius    := "8px",
        backgroundColor := "#f8fafc",
        padding         := "12px",
        topicCard(List(
          div(
            display.flex,
            flexDirection.column,
            gap := "6px",
            sectionHeading(
              "Local API",
              "Local API",
              div(
                "This switch opens or closes the local HTTP endpoint used by external API clients. ",
                "Stopping it pauses external access but keeps all keys and routing settings."
              )
            ),
            div(
              display.flex,
              alignItems.center,
              gap      := "8px",
              width    := "100%",
              minWidth := "0",
              button(
                `type`          := "button",
                padding         := "0",
                border          := "none",
                backgroundColor := "transparent",
                textAlign       := "left",
                fontFamily      := "ui-monospace, SFMono-Regular, Menlo, monospace",
                fontSize        := "10px",
                color           := "#94a3b8",
                flexShrink      := "1",
                minWidth        := "0",
                overflow.hidden,
                textOverflow.ellipsis,
                whiteSpace.nowrap,
                cursor          := "pointer",
                title           := "Click to copy Local API endpoint",
                aria.label      := "Copy Local API endpoint",
                onClick --> { _ =>
                  copyLocalApiUrl()
                    .map(_ => Toast.success("Copied Local API endpoint"))
                    .recover { case e =>
                      Toast.error(s"Copy Local API URL failed: ${Option(e.getMessage).getOrElse("unknown error")}")
                    }
                    .foreach(_ => ())
                },
                localApiUrl
              ),
              localApiSwitch
            )
          )
        )),
        topicCard(List(
          sectionHeading(
            "Access keys",
            "API keys",
            div(
              strong("System Default"),
              " is used by the plugin's own provider tabs. ",
              "User keys are independent credentials for external clients. ",
              "Each user key's Advanced section controls provider routing and prompt mode."
            )
          ),
          div(
            display <-- showCreateKeyVar.signal.map(open => if (open) "none" else "flex"),
            justifyContent.flexStart,
            marginTop    := "10px",
            marginBottom := "2px",
            configButton(
              span("+ New key"),
              () => showCreateKeyVar.set(true),
              spacing = "3px 8px"
            )
          ),
          div(
            display.flex,
            gap          := "8px",
            marginBottom := "6px",
            div(
              display <-- showCreateKeyVar.signal.map(open => if (open) "flex" else "none"),
              gap             := "8px",
              flexGrow        := "1",
              marginBottom    := "10px",
              padding         := "8px",
              backgroundColor := "#ffffff",
              border          := "1px solid #e5e7eb",
              borderRadius    := "6px",
              input(
                placeholder  := "Key name",
                flexGrow     := "1",
                padding      := "6px 8px",
                boxSizing.borderBox,
                fontSize     := "12px",
                border       := "1px solid #d1d5db",
                borderRadius := "4px",
                value <-- newApiKeyNameVar,
                onInput.mapToValue --> newApiKeyNameVar.set
              ),
              configButton(
                span("Create"),
                () => {
                  ctx.generateApiKey(newApiKeyNameVar.now())
                  // 新建后自动展开它的 Advanced（prompt mode / routing），创建时就能把候选配上。
                  ctx.apiKeys.now().lastOption.foreach(key => advancedOpenVar.update(_ + key.id))
                  newApiKeyNameVar.set("")
                  showCreateKeyVar.set(false)
                },
                textSize = "12px",
                spacing = "6px 10px"
              ),
              configButton(
                span("Cancel"),
                () => showCreateKeyVar.set(false),
                textSize = "12px",
                spacing = "6px 10px"
              )
            )
          ),
          div(
            children <-- ctx.apiKeys.signal.map { keys =>
              val activeKeys = keys.filterNot(_.revoked)
              if (activeKeys.isEmpty) {
                List(span(fontSize := "11px", color := "#6b7280", "No API keys yet"))
              } else {
                activeKeys.map { key =>
                  val systemKey         = key.id == ApiKeyStore.SystemApiKeyId
                  val keyLabel          = if (key.name.nonEmpty) key.name else "unnamed"
                  val advancedOpen      = advancedOpenVar.signal.map(_.contains(key.id))
                  div(
                    padding      := "10px 0",
                    borderBottom := "1px solid #eef2f7",
                    opacity      := (if (key.revoked) "0.5" else "1"),
                    // 第一行：名字 + secret + 右侧操作按钮。
                    div(
                      display.flex,
                      alignItems.center,
                      gap             := "8px",
                      div(
                        children <-- editingKeyIdVar.signal.map { editing =>
                          if (editing == key.id) {
                            List(
                              div(
                                display.flex,
                                gap := "4px",
                                input(
                                  value <-- editNameVar,
                                  placeholder  := "unnamed",
                                  padding      := "4px 6px",
                                  fontSize     := "11px",
                                  border       := "1px solid #e5e7eb",
                                  borderRadius := "4px",
                                  disabled     := key.revoked || systemKey,
                                  onInput.mapToValue --> editNameVar.set
                                ),
                                configButton(
                                  span("Save"),
                                  () => {
                                    ctx.renameApiKey(key.id, editNameVar.now())
                                    editingKeyIdVar.set("")
                                  },
                                  disabledSignal = Val(key.revoked || systemKey),
                                  textSize = "10px",
                                  spacing = "2px 6px"
                                ),
                                configButton(
                                  span("Cancel"),
                                  () => editingKeyIdVar.set(""),
                                  textSize = "12px",
                                  spacing = "2px 6px"
                                )
                              )
                            )
                          } else {
                            List(
                              div(
                                display.flex,
                                alignItems.center,
                                gap := "6px",
                                span(
                                  if (key.name.nonEmpty) key.name else "unnamed",
                                  overflow.hidden,
                                  textOverflow.ellipsis,
                                  whiteSpace.nowrap,
                                  minWidth := "0"
                                )
                              )
                            )
                          }
                        }
                      ),
                      button(
                        `type`          := "button",
                        padding         := "0",
                        border          := "none",
                        backgroundColor := "transparent",
                        textAlign       := "left",
                        fontSize        := "10px",
                        fontFamily      := "monospace",
                        color           := "#6b7280",
                        overflow.hidden,
                        textOverflow.ellipsis,
                        whiteSpace.nowrap,
                        minWidth        := "0",
                        flexShrink      := "1",
                        cursor          := (if (key.revoked) "default" else "pointer"),
                        disabled        := key.revoked,
                        title           := s"Click to copy $keyLabel API key",
                        aria.label      := s"Copy $keyLabel API key",
                        onClick --> { _ =>
                          ctx
                            .copyApiKey(key.id)
                            .map(_ => Toast.success(s"Copied API key: $keyLabel"))
                            .recover { case e =>
                              Toast.error(s"Copy API key failed: ${Option(e.getMessage).getOrElse("unknown error")}")
                            }
                            .foreach(_ => ())
                        },
                        key.secret
                      ),
                      span(flexGrow     := "1"),
                      // `last used` 只在下面那行 `Created: … · Last used: …` 里显示一次（这里原来重复了一遍）。
                      configButton(
                        span("Edit"),
                        () => {
                          editNameVar.set(key.name)
                          editingKeyIdVar.set(key.id)
                        },
                        disabledSignal = Val(key.revoked || systemKey),
                        spacing = "3px 8px"
                      ),
                      configButton(
                        span("Revoke"),
                        () => ctx.revokeApiKey(key.id),
                        disabledSignal = Val(key.revoked || systemKey),
                        spacing = "3px 8px"
                      )
                    ),
                    div(
                      fontSize        := "10px",
                      color           := "#6b7280",
                      marginTop       := "2px",
                      s"Created: ${formatTime(key.createdAt)} · Last used: ${formatLastUsed(key.lastUsedAt)}"
                    ),
                    // 第三行：纯展开入口（prompt mode + routing 都在下面，默认收起）。
                    // System Default 不参与：它的 prompt 模式是全局开关（插件页 `/prompt`、状态行、胶囊底部），这里不再暴露。
                    button(
                      `type`          := "button",
                      display         := (if (systemKey) "none" else "inline-flex"),
                      alignItems.center,
                      gap             := "6px",
                      marginTop       := "6px",
                      padding         := "0",
                      cursor.pointer,
                      backgroundColor := "transparent",
                      border          := "none",
                      textAlign       := "left",
                      aria.expanded <-- advancedOpen,
                      title <-- advancedOpen.map(open =>
                        if (open) "Collapse routing settings" else "Expand routing settings"
                      ),
                      span("Advanced", fontSize := "10px", color := "#6b7280", flexShrink := "0"),
                      span(
                        child.text <-- advancedOpen.map(open => if (open) "▴" else "▾"),
                        fontSize                := "10px",
                        color                   := "#9ca3af",
                        flexShrink              := "0"
                      ),
                      onClick --> { _ =>
                        advancedOpenVar.update(open =>
                          if (open.contains(key.id)) open - key.id else open + key.id
                        )
                      }
                    ),
                    // 展开：Routing 独立子卡片（Strategy / Failover / Providers）。
                    // prompt mode 不在这里 —— 它挂在每条候选路由上（见 renderRoutingBlock 的 route 编辑器）。
                    div(
                      display <-- advancedOpen.map(open => if (open && !systemKey) "block" else "none"),
                      marginTop       := "6px",
                      marginBottom    := "4px",
                      // System Default 是内部分组用的默认 key，不参与 Routing 配置。
                      children <-- Val(
                        if (systemKey) Nil
                        else List(renderRoutingBlock(ctx, key, routeEditorState(key.id)))
                      )
                    )
                  )
                }
              }
            }
          )
        ))
      ),
      // Log
      div(
        display <-- configSectionVar.signal.map(v => if (v == "log") "block" else "none"),
        border          := "1px solid #e5e7eb",
        borderRadius    := "8px",
        backgroundColor := "#f8fafc",
        padding         := "12px",
        renderLogSection(configSectionVar.signal)
      )
    )
  }
}
