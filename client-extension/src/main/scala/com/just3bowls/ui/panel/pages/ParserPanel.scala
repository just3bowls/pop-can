package com.just3bowls.ui.panel.pages

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.ChromeAPI
import com.just3bowls.facade.StorageChange
import com.just3bowls.model.provider.*

import com.just3bowls.ui.components.*

import com.just3bowls.api.provider.PresetApply
import com.just3bowls.model.provider.ParserProviderLocal
import com.just3bowls.model.provider.ProviderHostAccess
import com.just3bowls.model.provider.ProviderImportPlan
import com.just3bowls.model.provider.ProviderPresetState
import com.just3bowls.model.provider.ProviderPresets
import com.just3bowls.model.provider.ProviderPackClient
import com.just3bowls.model.provider.ProviderPackConfig
import com.just3bowls.model.provider.ProviderPackStatus
import com.raquo.laminar.api.L._
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * Config → Providers：**只读清单 + 开关**。
 *
 * 目录来自用户安装的签名 Provider Pack；应用安装包不再携带 provider 规则。在线下载和离线 Import 走同一套验签、 版本兼容和冲突逻辑，没有新建入口、没有 capture、也没有手改
 * rule。冲突口径（本地优先）见 [[com.just3bowls.api.provider.PresetApply]]。
 */
private[ui] object ParserPanel {
  def render(
      onProvidersChanged: () => Unit = () => ()
  ): HtmlElement = {
    val providersVar                          = Var(List.empty[ParserProviderLocal])
    val readinessVar                          = Var(Map.empty[String, Boolean])
    val importOpenVar                         = Var(false)
    val importSourceVar                       = Var("online")
    val importFileTextVar                     = Var("")
    val importPasteTextVar                    = Var("")
    val importForceVar                        = Var(false)
    val importPlanVar                         = Var(Option.empty[ProviderImportPlan])
    val importNoteVar                         = Var("")
    val importErrorVar                        = Var("")
    val importFileInputVar                    = Var(Option.empty[dom.HTMLInputElement])
    val importFileNameVar                     = Var("")
    val confirmDeleteVar                      = Var("")
    val expandedProviderVar                   = Var("")
    val packStatusVar                         = Var(ProviderPackStatus())
    val packBusyVar                           = Var(false)
    val autoPreviewDelayMs                    = 250
    var autoPreviewTimer                      = 0
    var importReadGeneration                  = 0
    def reload(notify: Boolean = false): Unit =
      ProviderPackClient
        .status()
        .recover { case e =>
          ProviderPackStatus(error = Option(e.getMessage).getOrElse("Provider pack status failed"))
        }
        .flatMap { packStatus =>
          packStatusVar.set(packStatus)
          ProviderPresets.hydrate().flatMap { _ =>
            ParserProviderLocal
              .list()
              .flatMap { list =>
                ProviderHostAccess.readiness(list).map { ready =>
                  providersVar.set(list)
                  readinessVar.set(ready)
                  expandedProviderVar.update(id => if (list.exists(_.id == id)) id else "")
                  if (notify) onProvidersChanged()
                  ()
                }
              }
          }
        }
        .recover { case e =>
          Toast.error(s"Provider permission check failed: ${e.getMessage}")
        }
        .foreach(_ => ())
    reload()
    // ---------------------------------------------------------------- 小工具
    def chip(
        text: String,
        fgColor: String,
        bgColor: String,
        tooltip: String
    ): HtmlElement        =
      span(
        text,
        fontSize        := "10px",
        lineHeight      := "16px",
        padding         := "0 6px",
        borderRadius    := "999px",
        color           := fgColor,
        backgroundColor := bgColor,
        title           := tooltip,
        whiteSpace.nowrap
      )
    def panelButton(
        labelText: String,
        onClickAction: () => Unit,
        disabledSignal: Signal[Boolean] = Val(false)
    ): HtmlElement =
      ConfigPanel.configButton(span(labelText), onClickAction, disabledSignal)
    def actionIcon(label: String, tooltip: String, onClickAction: () => Unit): HtmlElement =
      button(
        `type`          := "button",
        width           := "20px",
        height          := "20px",
        padding         := "0",
        cursor.pointer,
        fontSize        := "11px",
        lineHeight      := "1",
        color           := "#94a3b8",
        backgroundColor := "transparent",
        border          := "none",
        title           := tooltip,
        aria.label      := tooltip,
        onMouseEnter --> { event =>
          event.currentTarget.asInstanceOf[dom.HTMLElement].style.color = "#475569"
        },
        onMouseLeave --> { event =>
          event.currentTarget.asInstanceOf[dom.HTMLElement].style.color = "#94a3b8"
        },
        onClick --> { event =>
          event.stopPropagation()
          onClickAction()
        },
        label
      )

    /**
     * 行内按钮的"原地反馈"状态：`<providerId>:<action>` → `done | failed`，面板级一份。
     *
     * 放面板级而不是按钮实例里，是因为动作成功后列表会整表重渲染（徽标要跟着变），实例级状态一渲染就丢。 与 Config 里 `Copy` → `Copied`、API keys 的 Copy 同一套写法：成功就地反馈、不弹
     * toast；失败才弹 toast（按钮同时短暂变红 `Failed`，让点击有个落点）。
     */
    val flashVar                                    = Var(Map.empty[String, String])
    val flashTokens                                 = scala.collection.mutable.Map.empty[String, Int]
    def markFlash(key: String, state: String): Unit = {
      val token = flashTokens.getOrElse(key, 0) + 1
      flashTokens.update(key, token)
      flashVar.update(_ + (key -> state))
      // 失败留久一点，够看清按钮变红（原因在 toast 里，能留 8s）。
      js.timers.setTimeout(if (state == "failed") 2500 else 1500) {
        if (flashTokens.getOrElse(key, 0) == token) flashVar.update(_ - key)
      }
    }

    /** 带原地反馈的按钮：文案与配色都跟着 `flashVar` 里这个 key 的状态走。 */
    def flashButton(
        key: String,
        idleLabel: String,
        doneLabel: String,
        onClickAction: () => Unit
    ): HtmlElement = {
      val state = flashVar.signal.map(_.getOrElse(key, ""))
      ConfigPanel.configButton(
        child.text <-- state.map {
          case "done"   => doneLabel
          case "failed" => "Failed"
          case _        => idleLabel
        },
        onClickAction
      )
    }

    /** 复制到剪贴板：成功由调用方决定怎么反馈（行内按钮 = 翻文案，面板级 = toast），失败统一弹 toast。 */
    def copyToClipboard(text: String, onSuccess: () => Unit): Unit              =
      try {
        val clipboard = JsBridge.navigator.clipboard
        if (!js.isUndefined(clipboard) && clipboard != null) {
          JsBridge.asPromiseAny(clipboard.writeText(text)).toFuture
            .map(_ => onSuccess())
            .recover { case _ => Toast.error("Copy failed — clipboard unavailable") }
            .foreach(_ => ())
        } else Toast.error("Copy failed — clipboard unavailable")
      } catch { case _: Throwable => Toast.error("Copy failed — clipboard unavailable") }
    def looksLikePackEnvelope(text: String): Boolean                            =
      try {
        val parsed = JsBridge.jsonParse(text)
        JsBridge.intField(parsed, "formatVersion").contains(1) &&
        JsBridge.stringField(parsed, "payload").exists(_.nonEmpty) &&
        JsBridge.stringField(parsed, "signature").exists(_.nonEmpty)
      } catch { case _: Throwable => false }
    def setPackBusy(value: Boolean): Unit                                       =
      packBusyVar.set(value)
    def requestPackPermission(): Future[Boolean]                                =
      ProviderPackConfig.requestPermission()
    def downloadPack(closeImport: Boolean = false): Unit                        =
      setPackBusy(true)
      requestPackPermission()
        .flatMap { granted =>
          if (granted) ProviderPackClient.installOnline()
          else Future.failed(new RuntimeException("Provider download permission was not granted"))
        }
        .map { status =>
          packStatusVar.set(status)
          Toast.success(s"Provider rules v${status.bundleVersion} installed")
          if (closeImport) importOpenVar.set(false)
          reload(notify = true)
        }
        .recover { case e =>
          Toast.error(Option(e.getMessage).getOrElse("Provider download failed"))
          reload()
        }
        .foreach(_ => setPackBusy(false))
    def checkPack(): Unit                                                       =
      setPackBusy(true)
      requestPackPermission()
        .flatMap { granted =>
          if (granted) ProviderPackClient.check()
          else Future.failed(new RuntimeException("Provider update permission was not granted"))
        }
        .map { status =>
          packStatusVar.set(status)
          if (status.updateAvailableVersion > 0)
            Toast.info(s"Provider rules v${status.updateAvailableVersion} are available")
          else Toast.success("Provider rules are up to date")
          reload()
        }
        .recover { case e =>
          Toast.error(Option(e.getMessage).getOrElse("Provider update check failed"))
          reload()
        }
        .foreach(_ => setPackBusy(false))
    def updateAutoUpdate(enabled: Boolean): Unit                                =
      val action =
        if (enabled)
          requestPackPermission().flatMap { granted =>
            if (granted) ProviderPackClient.setAutoUpdate(true)
            else Future.failed(new RuntimeException("Provider auto-update permission was not granted"))
          }
        else ProviderPackClient.setAutoUpdate(false)
      action
        .map { status =>
          packStatusVar.set(status)
          Toast.success(s"Provider auto-update ${if (status.autoUpdate) "enabled" else "disabled"}")
        }
        .recover { case e =>
          Toast.error(Option(e.getMessage).getOrElse("Provider auto-update setting failed"))
          reload()
        }
        .foreach(_ => ())
    def providerJson(local: ParserProviderLocal): String                        =
      JsBridge.asString(js.JSON.stringify(ParserProviderLocal.toJs(local), null, 2))
    def presetBadges(local: ParserProviderLocal): List[HtmlElement]             =
      ProviderPresets.stateOf(local) match {
        case ProviderPresetState.Builtin   => Nil
        case ProviderPresetState.Modified  =>
          List(
            chip(
              "modified",
              "#b45309",
              "#fef3c7",
              "Content differs from the installed provider pack"
            )
          )
        case ProviderPresetState.Imported  =>
          List(chip("imported", "#6d28d9", "#ede9fe", "Imported version is newer than the installed provider pack"))
        case ProviderPresetState.LocalOnly =>
          List(chip("local", "#4b5563", "#f3f4f6", "Local provider configuration, not present in the installed pack"))
      }
    def denied(pageUrl: String): Future[Unit]                                   =
      Future.failed(new Exception(
        s"Host access was not granted for ${ProviderHostAccess.hostOf(pageUrl)}; the provider was not enabled"
      ))
    def setEnabled(id: String, enabled: Boolean, pageUrl: String): Unit         = {
      val action =
        if (enabled)
          ProviderHostAccess
            .request(pageUrl)
            .flatMap(granted => if (granted) ParserApiClient.setEnabled(id, enabled = true) else denied(pageUrl))
        else ParserApiClient.setEnabled(id, enabled = false)
      action
        .map { _ =>
          reload(notify = true)
        }
        .recover { case e =>
          reload()
          Toast.error(s"${if (enabled) "Enable" else "Disable"} $id failed: ${e.getMessage}")
        }
        .foreach(_ => ())
    }
    def providerToggle(local: ParserProviderLocal, ready: Boolean): HtmlElement =
      val active  = local.status == "active"
      val tooltip =
        if (!active) s"Enable ${local.name}"
        else if (ready) s"Disable ${local.name}"
        else s"Disable ${local.name}; it is enabled but needs host access"
      val color   =
        if (!active) "#6b7280"
        else if (ready) "#16a34a"
        else "#b45309"
      OnOffLabel.render(
        activeSignal = Val(active),
        colorSignal = Val(color),
        tooltipSignal = Val(tooltip),
        ariaLabelSignal = Val(tooltip)
      )(_ => setEnabled(local.id, !active, local.pageUrl))
    def grantAccess(local: ParserProviderLocal): Unit                           =
      ProviderHostAccess
        .request(local.pageUrl)
        .flatMap(granted => if (granted) Future.successful(()) else denied(local.pageUrl))
        .flatMap(_ => ParserApiClient.refreshProviderContent())
        .map { _ =>
          markFlash(s"${local.id}:access", "done")
          reload(notify = true)
        }
        .recover { case e =>
          markFlash(s"${local.id}:access", "failed")
          Toast.error(s"Grant access for ${local.name} failed: ${e.getMessage}")
        }
        .foreach(_ => ())

    /** 删掉靠行消失就是反馈；只有失败才弹 toast。 */
    def deleteProvider(id: String): Unit                                        =
      ParserApiClient
        .delete(id)
        .map { _ =>
          confirmDeleteVar.set("")
          reload(notify = true)
        }
        .recover { case e =>
          markFlash(s"$id:delete", "failed")
          Toast.error(s"Delete $id failed: ${e.getMessage}")
        }
        .foreach(_ => ())
    def restoreBuiltins(): Unit                                                 =
      ProviderPresets
        .restoreBuiltins()
        .map { _ =>
          markFlash("providers:restore-builtins", "done")
          reload(notify = true)
        }
        .recover { case e =>
          markFlash("providers:restore-builtins", "failed")
          Toast.error(s"Restore removed providers failed: ${e.getMessage}")
        }
        .foreach(_ => ())
    // ---------------------------------------------------------------- 行
    def renderRow(local: ParserProviderLocal, ready: Boolean): HtmlElement      =
      val active     = local.status == "active"
      val expanded   = expandedProviderVar.signal.map(_ == local.id)
      div(
        padding   := "8px 0",
        borderTop := "1px solid #eef2f7",
        div(
          display.flex,
          alignItems.center,
          gap := "8px",
          div(
            flexGrow := "1",
            minWidth := "0",
            cursor.pointer,
            onClick --> { _ =>
              expandedProviderVar.update(current => if (current == local.id) "" else local.id)
            },
            display.flex,
            alignItems.center,
            gap      := "8px",
            span(
              flexShrink := "0",
              width      := "10px",
              color      := "#94a3b8",
              child.text <-- expanded.map(open => if (open) "▾" else "▸")
            ),
            div(
              flexGrow   := "1",
              minWidth   := "0",
              div(
                display.flex,
                alignItems.center,
                gap := "6px",
                flexWrap.wrap,
                span(
                  local.name,
                  fontSize       := "12px",
                  fontWeight.bold,
                  color          := (if (active) "#111827" else "#6b7280"),
                  textDecoration := (if (active) "none" else "line-through")
                ),
                children <-- Val(presetBadges(local)),
                providerToggle(local, ready),
                if (active && !ready)
                  chip(
                    "needs access",
                    "#b45309",
                    "#fef3c7",
                    s"${local.name} is enabled but needs host access"
                  )
                else emptyNode
              )
            )
          ),
          child <-- confirmDeleteVar.signal.map { confirmingId =>
            if (confirmingId == local.id)
              div(
                display.flex,
                alignItems.center,
                gap        := "6px",
                span("Delete?", fontSize := "11px", color := "#b91c1c"),
                flashButton(
                  key = s"${local.id}:delete",
                  idleLabel = "Yes",
                  doneLabel = "Deleted ✓",
                  onClickAction = () => deleteProvider(local.id)
                ),
                panelButton("No", () => confirmDeleteVar.set(""))
              )
            else
              div(
                display.flex,
                alignItems.center,
                gap        := "6px",
                flexShrink := "0",
                if (local.status == "active" && !ready)
                  flashButton(
                    key = s"${local.id}:access",
                    idleLabel = "Grant access",
                    doneLabel = "Granted ✓",
                    onClickAction = () => grantAccess(local)
                  )
                else emptyNode,
                actionIcon("×", s"Delete ${local.name}", () => confirmDeleteVar.set(local.id))
              )
          }
        ),
        child <-- expanded.map { open =>
          if (!open) emptyNode
          else
            div(
              marginTop       := "6px",
              marginLeft      := "18px",
              marginBottom    := "2px",
              padding         := "10px 12px",
              backgroundColor := "#f8fafc",
              border          := "1px solid #e2e8f0",
              borderLeft      := "2px solid #dbeafe",
              borderRadius    := "0 6px 6px 0",
              div(
                display.flex,
                alignItems.center,
                gap             := "8px",
                marginBottom    := "6px",
                span(
                  "Raw JSON",
                  fontSize    := "11px",
                  fontWeight  := "600",
                  color       := "#475569"
                ),
                span(flexGrow := "1"),
                flashButton(
                  key = s"${local.id}:copy",
                  idleLabel = "Copy",
                  doneLabel = "Copied ✓",
                  onClickAction = () =>
                    copyToClipboard(providerJson(local), () => markFlash(s"${local.id}:copy", "done"))
                )
              ),
              pre(
                providerJson(local),
                margin          := "0",
                maxHeight       := "420px",
                overflow        := "auto",
                whiteSpace      := "pre",
                fontFamily      := "ui-monospace, SFMono-Regular, Menlo, monospace",
                fontSize        := "10px",
                lineHeight      := "16px",
                color           := "#334155",
                backgroundColor := "#ffffff",
                border          := "1px solid #e2e8f0",
                borderRadius    := "4px",
                padding         := "8px"
              )
            )
        }
      )
    // ---------------------------------------------------------------- 导入
    def actionLabel(action: PresetApply.Action): (String, String)               =
      action match {
        case PresetApply.Action.Insert(id, _)      => (s"+ $id", "#047857")
        case PresetApply.Action.Update(id, _)      => (s"~ $id", "#1d4ed8")
        case PresetApply.Action.Adopt(id, _)       => (s"= $id", "#6b7280")
        case PresetApply.Action.Keep(id, _, true)  => (s"! $id (kept · locally modified)", "#b45309")
        case PresetApply.Action.Keep(id, _, false) => (s"! $id (kept · local is newer)", "#6b7280")
        case PresetApply.Action.Skip(id)           => (s"× $id (deleted earlier)", "#b91c1c")
        case PresetApply.Action.Orphan(id)         => (s"· $id (not in this file · kept)", "#6b7280")
      }
    def previewText(plan: ProviderImportPlan): String                           =
      val lines = plan.actions.map(actionLabel)
      val head  = s"${plan.bundle.providers.size} provider(s) · version ${plan.bundle
          .version} · ${if (plan.owned) "preset" else "local configuration (you take it over)"}"
      head :: lines.map { case (text, _) => s"  $text" } mkString "\n"
    def renderPreview(plan: ProviderImportPlan): HtmlElement                    =
      div(
        marginTop       := "8px",
        padding         := "8px",
        backgroundColor := "#f9fafb",
        borderRadius    := "4px",
        fontSize        := "11px",
        fontFamily      := "ui-monospace, SFMono-Regular, Menlo, monospace",
        whiteSpace      := "pre-wrap",
        color           := "#374151",
        previewText(plan)
      )
    def importSourceTab(id: String, label: String): HtmlElement                 =
      button(
        `type`       := "button",
        padding      := "5px 10px",
        fontSize     := "11px",
        cursor.pointer,
        border       := "none",
        borderRadius := "5px",
        backgroundColor <-- importSourceVar.signal.map(source => if (source == id) "#ffffff" else "transparent"),
        color <-- importSourceVar.signal.map(source => if (source == id) "#1d4ed8" else "#475569"),
        fontWeight <-- importSourceVar.signal.map(source => if (source == id) "600" else "400"),
        boxShadow <-- importSourceVar.signal.map(source =>
          if (source == id) "0 1px 2px rgba(15,23,42,0.08)" else "none"
        ),
        onClick --> { _ =>
          if (autoPreviewTimer != 0) dom.window.clearTimeout(autoPreviewTimer)
          importReadGeneration += 1
          importSourceVar.set(id)
          importPlanVar.set(None)
          importNoteVar.set("")
          importErrorVar.set("")
          id match {
            case "file"  =>
              val text = importFileTextVar.now()
              if (text.nonEmpty) previewImportedRules(text)
            case "paste" =>
              schedulePreview(importPasteTextVar.now())
            case _       => ()
          }
        },
        label
      )
    def readImportFile(event: dom.Event): Unit                                  = {
      val target     = event.target.asInstanceOf[dom.HTMLInputElement]
      val file       = Option(target.files).flatMap(files => Option(files.item(0)))
      importReadGeneration += 1
      val generation = importReadGeneration
      file match {
        case Some(selected) =>
          importFileNameVar.set(selected.name)
          importFileTextVar.set("")
          importPlanVar.set(None)
          importNoteVar.set(s"Reading ${selected.name}…")
          importErrorVar.set("")
          val reader = new dom.FileReader()
          reader.onload = { _ =>
            if (generation == importReadGeneration && importSourceVar.now() == "file") {
              val text = JsBridge.asString(reader.result)
              if (text.trim.isEmpty) {
                importFileTextVar.set("")
                importNoteVar.set("")
                importErrorVar.set("")
                importFileNameVar.set("")
                Toast.error(s"${selected.name} is empty")
              } else {
                importFileTextVar.set(text)
                importNoteVar.set(s"Loaded ${selected.name} · ${text.length} chars")
                importErrorVar.set("")
                previewImportedRules(text)
              }
            }
          }
          reader.onerror = { _ =>
            if (generation == importReadGeneration && importSourceVar.now() == "file") {
              importFileTextVar.set("")
              importPlanVar.set(None)
              importNoteVar.set("")
              importErrorVar.set("")
              importFileNameVar.set("")
              Toast.error(s"Read ${selected.name} failed")
            }
          }
          reader.readAsText(selected)
          target.value = ""
        case None           =>
          importFileTextVar.set("")
          importPlanVar.set(None)
          importNoteVar.set("")
          importErrorVar.set("")
          importFileNameVar.set("")
      }
    }
    def previewImportedRules(text: String): Unit                                = {
      val source             = importSourceVar.now()
      def isCurrent: Boolean =
        importSourceVar.now() == source &&
          (if (source == "file") importFileTextVar.now() else importPasteTextVar.now()) == text
      importPlanVar.set(None)
      importNoteVar.set("")
      importErrorVar.set("")
      if (text.trim.isEmpty) return
      if (looksLikePackEnvelope(text)) {
        importNoteVar.set("Signed Provider Pack detected — Apply will verify and install it.")
      } else
        ProviderPresets
          .planImport(text)
          .map {
            case Left(error) if isCurrent =>
              importPlanVar.set(None)
              importErrorVar.set(error)
            case Right(plan) if isCurrent =>
              importPlanVar.set(Some(plan))
              importErrorVar.set("")
            case _                        => ()
          }
          .recover { case e =>
            if (isCurrent)
              importErrorVar.set(Option(e.getMessage).getOrElse("Provider config preview failed"))
          }
          .foreach(_ => ())
    }
    def schedulePreview(text: String): Unit                                     = {
      if (autoPreviewTimer != 0) dom.window.clearTimeout(autoPreviewTimer)
      autoPreviewTimer =
        if (text.trim.isEmpty) {
          importPlanVar.set(None)
          importNoteVar.set("")
          importErrorVar.set("")
          0
        } else
          dom.window.setTimeout(
            () => {
              autoPreviewTimer = 0
              previewImportedRules(text)
            },
            autoPreviewDelayMs
          )
    }
    def applyImportedRules(): Unit                                              = {
      val text =
        if (importSourceVar.now() == "file") importFileTextVar.now() else importPasteTextVar.now()
      if (looksLikePackEnvelope(text)) {
        setPackBusy(true)
        ProviderPackClient
          .importText(text)
          .map { status =>
            packStatusVar.set(status)
            importNoteVar.set(s"Installed signed provider rules v${status.bundleVersion}")
            Toast.success(s"Installed provider rules v${status.bundleVersion}")
            importOpenVar.set(false)
            reload(notify = true)
          }
          .recover { case e =>
            Toast.error(s"Import failed: ${Option(e.getMessage).getOrElse("unknown error")}")
          }
          .foreach(_ => setPackBusy(false))
      } else
        importPlanVar.now().foreach { plan =>
          ProviderPresets
            .applyImport(plan, importForceVar.now())
            .map { report =>
              importNoteVar.set(s"Applied ${report.summary}")
              Toast.success(s"Imported provider config: ${report.summary}")
              importPlanVar.set(None)
              reload(notify = true)
            }
            .recover { case e =>
              Toast.error(s"Apply failed: ${Option(e.getMessage).getOrElse("unknown error")}")
            }
            .foreach(_ => ())
        }
    }
    def renderImportNote(): HtmlElement                                         =
      div(
        display <-- importNoteVar.signal
          .combineWith(importErrorVar.signal)
          .map { case (note, error) => if (note.isEmpty && error.isEmpty) "none" else "block" },
        marginTop := "6px",
        span(
          child.text <-- importNoteVar.signal
            .combineWith(importErrorVar.signal)
            .map { case (note, error) => if (error.nonEmpty) error else note },
          fontSize := "11px",
          color <-- importErrorVar.signal.map(error => if (error.nonEmpty) "#b91c1c" else "#047857")
        )
      )
    def renderImportedRulesFooter(): HtmlElement                                =
      div(
        display.flex,
        alignItems.center,
        flexWrap.wrap,
        gap        := "8px",
        marginTop  := "8px",
        paddingTop := "8px",
        borderTop  := "1px solid #eef2f7",
        span("Overwrite locally modified", fontSize := "11px", color := "#374151"),
        input(
          `type`                                    := "checkbox",
          checked <-- importForceVar.signal,
          onChange.mapToChecked --> importForceVar.set
        ),
        span(flexGrow                               := "1"),
        panelButton(
          "Apply",
          applyImportedRules,
          Signal
            .combine(importPlanVar.signal, importSourceVar.signal, importFileTextVar.signal, importPasteTextVar.signal)
            .map { case (plan, source, fileText, pasteText) =>
              val text = if (source == "file") fileText else pasteText
              plan.isEmpty && !looksLikePackEnvelope(text)
            }
        )
      )
    def renderImportDialog(): HtmlElement                                       =
      div(
        border          := "1px solid #e5e7eb",
        borderRadius    := "6px",
        padding         := "12px",
        marginBottom    := "10px",
        backgroundColor := "#fafafa",
        div(
          display.flex,
          alignItems.center,
          gap             := "2px",
          padding         := "3px",
          backgroundColor := "#f3f4f6",
          borderRadius    := "6px",
          importSourceTab("online", "Online"),
          importSourceTab("file", "File"),
          importSourceTab("paste", "Paste"),
          div(
            display.flex,
            alignItems.center,
            flexShrink := "0",
            marginLeft := "auto",
            actionIcon(
              "×",
              "Close import",
              () => {
                if (autoPreviewTimer != 0) dom.window.clearTimeout(autoPreviewTimer)
                importOpenVar.set(false)
                importNoteVar.set("")
                importErrorVar.set("")
              }
            )
          )
        ),
        child <-- importSourceVar.signal.map {
          case "online" =>
            div(
              marginTop       := "8px",
              padding         := "10px",
              backgroundColor := "#ffffff",
              border          := "1px solid #e5e7eb",
              borderRadius    := "6px",
              div("Online catalogue", fontSize := "12px", fontWeight.bold, color := "#111827"),
              div(
                "Download the latest signed Provider Pack from the configured catalogue.",
                marginTop                      := "3px",
                fontSize                       := "10px",
                color                          := "#6b7280"
              ),
              div(
                display.flex,
                justifyContent.flexEnd,
                marginTop                      := "8px",
                paddingTop                     := "8px",
                borderTop                      := "1px solid #eef2f7",
                ConfigPanel.configButton(
                  child.text <-- packBusyVar.signal.map(busy => if (busy) "Downloading…" else "Download rules"),
                  () => downloadPack(closeImport = true),
                  packBusyVar.signal
                )
              )
            )
          case "file"   =>
            div(
              marginTop       := "8px",
              padding         := "10px",
              backgroundColor := "#ffffff",
              border          := "1px solid #e5e7eb",
              borderRadius    := "6px",
              div("Choose a file", fontSize := "12px", fontWeight.bold, color := "#111827"),
              div(
                display.flex,
                alignItems.center,
                gap                         := "10px",
                marginTop                   := "8px",
                ConfigPanel.configButton(
                  "Choose file",
                  () => importFileInputVar.now().foreach(_.click())
                ),
                span(
                  child.text <-- importFileNameVar.signal.map(name =>
                    if (name.isEmpty) "No file selected" else name
                  ),
                  minWidth     := "0",
                  flexGrow     := "1",
                  overflow     := "hidden",
                  textOverflow := "ellipsis",
                  whiteSpace.nowrap,
                  fontSize     := "11px",
                  color        := "#6b7280"
                ),
                input(
                  display.none,
                  `type`       := "file",
                  accept       := ".popcan.json,.json,.jsonc,application/json,application/jsonc",
                  onChange --> readImportFile,
                  onMountCallback(ctx => importFileInputVar.set(Some(ctx.thisNode.ref))),
                  onUnmountCallback(_ => importFileInputVar.set(None))
                )
              ),
              renderImportNote(),
              renderImportedRulesFooter()
            )
          case _        =>
            div(
              marginTop       := "8px",
              padding         := "10px",
              backgroundColor := "#ffffff",
              border          := "1px solid #e5e7eb",
              borderRadius    := "6px",
              div("Paste configuration", fontSize := "12px", fontWeight.bold, color := "#111827"),
              div(
                marginTop                         := "6px",
                textArea(
                  value <-- importPasteTextVar,
                  onInput.mapToValue --> { text =>
                    importPasteTextVar.set(text)
                    schedulePreview(text)
                  },
                  rows         := 8,
                  width        := "100%",
                  boxSizing    := "border-box",
                  fontSize     := "11px",
                  fontFamily   := "ui-monospace, SFMono-Regular, Menlo, monospace",
                  border       := "1px solid #e5e7eb",
                  borderRadius := "4px",
                  padding      := "6px",
                  placeholder  := """// Signed Provider Pack envelope, bundle, or single provider
{ "formatVersion": 1, "payload": "…", "signature": "…" }"""
                )
              ),
              renderImportNote(),
              renderImportedRulesFooter()
            )
        },
        child <-- importPlanVar.signal.map {
          case Some(plan) => renderPreview(plan)
          case None       => emptyNode
        }
      )
    val providerSummary: Signal[String]                                         =
      Signal
        .combine(providersVar.signal, readinessVar.signal, packStatusVar.signal)
        .map { case (list, ready, status) =>
          if (list.isEmpty)
            if (status.installed) "Provider pack is installed but no providers are available."
            else "No provider rules installed — open Import to download or import offline."
          else {
            val active = list.filter(_.status == "active")
            val need   = active.count(provider => !ready.getOrElse(provider.id, false))
            if (need > 0) s"${active.size - need} ready · $need need access · ${list.size} total"
            else s"${active.size} enabled · ${list.size} total"
          }
        }
    def renderPackActions(): HtmlElement                                        =
      div(
        marginBottom := "10px",
        div(
          display.flex,
          alignItems.center,
          flexWrap.wrap,
          gap       := "14px",
          rowGap    := "8px",
          div(
            display.flex,
            alignItems.center,
            flexWrap.wrap,
            gap        := "6px",
            panelButton(
              "+ Import",
              () => {
                importOpenVar.set(true)
                importSourceVar.set("online")
                importPlanVar.set(None)
                importNoteVar.set("")
                importErrorVar.set("")
                importFileNameVar.set("")
              }
            )
          ),
          child <-- packStatusVar.signal.map { status =>
            if (!status.installed) emptyNode
            else
              div(
                display.flex,
                alignItems.center,
                gap := "6px",
                flashButton(
                  key = "providers:restore-builtins",
                  idleLabel = "Restore removed",
                  doneLabel = "Restored ✓",
                  onClickAction = () => restoreBuiltins()
                )
              )
          },
          div(
            display.flex,
            alignItems.center,
            flexWrap.wrap,
            gap        := "6px",
            marginLeft := "auto",
            child <-- packStatusVar.signal.map { status =>
              if (status.installed)
                ConfigPanel.configButton(
                  child.text <-- packBusyVar.signal.map(busy => if (busy) "Checking…" else "Check now"),
                  () => checkPack(),
                  packBusyVar.signal
                )
              else emptyNode
            },
            child <-- packStatusVar.signal.map { status =>
              if (status.updateAvailableVersion <= 0) emptyNode
              else
                ConfigPanel.configButton(
                  s"Update to v${status.updateAvailableVersion}",
                  () => downloadPack(),
                  packBusyVar.signal
                )
            },
            child <-- packStatusVar.signal.map { status =>
              if (!status.installed) emptyNode
              else
                label(
                  display.flex,
                  alignItems.center,
                  gap      := "4px",
                  fontSize := "11px",
                  color    := "#475569",
                  input(
                    `type`  := "checkbox",
                    checked := status.autoUpdate,
                    onChange.mapToChecked --> { enabled => updateAutoUpdate(enabled) }
                  ),
                  "auto-update"
                )
            }
          )
        ),
        div(
          display.flex,
          alignItems.center,
          justifyContent <-- providersVar.signal.map(list => if (list.isEmpty) "flex-start" else "flex-end"),
          gap       := "8px",
          marginTop := "8px",
          span(
            child.text <-- providerSummary,
            fontSize := "11px",
            color    := "#6b7280"
          ),
          child <-- packStatusVar.signal.map { status =>
            if (status.installed)
              chip(
                s"v${status.bundleVersion}",
                "#1d4ed8",
                "#eef2ff",
                "Installed Provider Pack version"
              )
            else emptyNode
          }
        )
      )
    // ---------------------------------------------------------------- 组装
    val onPackChanged: js.Function2[js.Dictionary[StorageChange], String, Unit] = (changes, area) => {
      if (area == "local" && changes.keys.exists(_.startsWith("thProviderPack:"))) reload(notify = true)
    }
    div(
      ConfigPanel.sectionHeading(
        "Provider catalogue",
        "Provider catalogue",
        div(
          "Enable the providers you want available to Chat and API routes. ",
          "Each enabled provider may require host access before it can be automated. ",
          "Provider rules are installed independently from the application. ",
          "Import accepts signed packs or local unsigned configuration. Provider details are read-only."
        )
      ),
      renderPackActions(),
      child <-- importOpenVar.signal.map(open => if (open) renderImportDialog() else emptyNode),
      children <-- providersVar.signal.combineWith(readinessVar.signal).map { case (list, ready) =>
        list.map(provider => renderRow(provider, ready.getOrElse(provider.id, false)))
      }
    ).amend(
      onMountCallback(_ => ChromeAPI.storage.onChanged.addListener(onPackChanged)),
      onUnmountCallback(_ => ChromeAPI.storage.onChanged.removeListener(onPackChanged))
    )
  }
}
