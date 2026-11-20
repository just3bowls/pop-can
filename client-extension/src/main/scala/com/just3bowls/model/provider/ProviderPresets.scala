package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.JsonValue

import com.just3bowls.api.provider.PresetApply
import com.just3bowls.facade.ChromeAPI
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** 已验证 Provider Pack payload 的强类型视图。 */
final case class ProviderPresetBundle(version: Int, providers: List[ParserProviderLocal])

/** 一次应用 / 导入的结果摘要：面板预览与日志共用。 */
final case class ProviderPresetReport(
    version: Int,
    inserted: List[String] = Nil,
    updated: List[String] = Nil,
    adopted: List[String] = Nil,
    kept: List[String] = Nil,
    skipped: List[String] = Nil
) {
  def changed: Boolean = inserted.nonEmpty || updated.nonEmpty || adopted.nonEmpty
  def summary: String  =
    s"v$version · ${inserted.size} new · ${updated.size} updated · ${adopted.size} unchanged" +
      s" · ${kept.size} kept · ${skipped.size} skipped"
}

/** 这一家相对**随包预设**的状态（面板徽标用）。 */
sealed trait ProviderPresetState
object ProviderPresetState {

  /** 来自预设包，且内容与随包一致。 */
  case object Builtin extends ProviderPresetState

  /** 本地内容与当前 Provider Pack 不同。 */
  case object Modified extends ProviderPresetState

  /** 用户通过未签名 Import 主动接手的本地配置，不参与 signed pack 自动覆盖。 */
  case object LocalOverride extends ProviderPresetState

  /** 本地是比随包更新的 import 版本：保持不动，也不提示"有更新"。 */
  case object Imported extends ProviderPresetState

  /** 随包里没有这一家（我们下掉的、或历史遗留）。 */
  case object LocalOnly extends ProviderPresetState

  private[provider] def classify(
      localHash: String,
      presetHash: Option[String],
      meta: Option[PresetMeta],
      installedVersion: Int
  ): ProviderPresetState =
    presetHash match {
      case None                             => LocalOnly
      case Some(hash) if hash == localHash  => Builtin
      case Some(_) if meta.exists(!_.owned) => LocalOverride
      case Some(_)
          if meta.exists(value => value.owned && value.hash == localHash && value.version > installedVersion) =>
        Imported
      case Some(_)                          => Modified
    }
}

/** 导入解析结果：`owned` 决定这批内容算"预设血缘"还是"用户接手"。 */
final case class ProviderImportPlan(
    bundle: ProviderPresetBundle,
    owned: Boolean,
    actions: List[PresetApply.Action]
)

/**
 * provider 预设包的运行时入口：**把配置文件变成运行时真相**。
 *
 * 一句话职责：仓库里 `presets/providers/` 下的逐家 JSONC 是"有哪些 provider、长什么样"，签名 Provider Pack 是它在运行时的形态；本对象负责把已安装 pack 落到
 * `chrome.storage.local["parserProvider:<id>"]`（唯一运行时真相），并维护"删除过哪些"与"上次应用到哪个版本"。
 *
 * 冲突口径见 [[PresetApply]]：本地优先，只有"从预设包原样应用、之后没被动过"的文档才会被随包升级。
 */
object ProviderPresets {

  /** 上次应用到的预设包版本（随包应用与面板 import 共用这一个数：谁新算谁）。 */
  private val VersionKey = "thProviderPresetVersion"

  /** 用户删掉的 pack provider id：不再被塞回来，直到 `Restore removed`。 */
  private val RemovedKey                                   = "thProviderPresetRemoved"
  private var cached: Option[Option[ProviderPresetBundle]] = None

  /** 当前已安装的 Provider Pack；首次使用前必须先调用 `hydrate()`。 */
  def bundle(): Option[ProviderPresetBundle] = cached.getOrElse(None)

  /** 从扩展存储加载已安装的强类型 pack payload。 */
  def hydrate(): Future[Option[ProviderPresetBundle]] =
    ProviderPackStore.activeBundle().map { value =>
      cached = Some(value)
      value
    }

  /** 标签用的一句话：`v3 · 9 providers`。 */
  def label(): String =
    bundle().map(b => s"v${b.version} · ${b.providers.size} providers").getOrElse("no provider pack")

  /** 当前安装的 Provider Pack 版本（没有安装时 0）。 */
  def version: Int = bundle().map(_.version).getOrElse(0)

  /** SW 启动时调用：把已安装 Provider Pack 应用到本地（幂等；没有 pack 时保持本地配置）。 */
  def ensureApplied(): Future[ProviderPresetReport] =
    hydrate().flatMap {
      case None        => Future.successful(ProviderPresetReport(0))
      case Some(value) => applyBundle(value, owned = true, force = false)
    }

  /** Provider Pack 的 payload 可以复用现有 provider 文档解析器，但必须声明正确的 pack 元数据。 */
  def parsePackPayload(text: String): Either[String, ProviderPresetBundle] =
    try {
      val raw            = JsBridge.jsonParse(text)
      val kind           = JsBridge.stringField(raw, "kind").getOrElse("")
      val packSchema     = JsBridge.intField(raw, "packSchemaVersion").getOrElse(0)
      val providerSchema = JsBridge.intField(raw, "providerSchemaVersion").getOrElse(0)
      if (kind != "provider-pack") Left("Provider payload has the wrong kind")
      else if (packSchema != 1) Left(s"Unsupported provider pack schema: $packSchema")
      else if (providerSchema != 2) Left(s"Unsupported provider schema: $providerSchema")
      else parseBundle(raw, fallbackId = false).map(_.bundle)
    } catch { case e: Throwable => Left(s"Invalid provider pack payload: ${e.getMessage}") }

  /** 解析 import 的文本：bundle / 单个 provider / provider 数组都收，先出预览不落库。 */
  def planImport(text: String): Future[Either[String, ProviderImportPlan]] =
    parseImport(text) match {
      case Left(error)  => Future.successful(Left(error))
      case Right(plan0) =>
        currentEntries().map { case (locals, removed) =>
          val entries    = plan0.bundle.providers.map(entryOf(plan0.bundle.version, _))
          val localItems = locals.map(localEntryOf)
          Right(plan0.copy(actions = PresetApply.decide(entries, localItems, removed.toSet)))
        }
    }

  /** 执行导入计划：`force` 时连"本地改过 / 用户接手过"的也一起覆盖（tombstone 仍然跳过）。 */
  def applyImport(plan: ProviderImportPlan, force: Boolean): Future[ProviderPresetReport] =
    applyBundle(plan.bundle, owned = plan.owned, force = force)

  /** 删掉一家：内置的记进 tombstone（下次启动不再塞回来），本地独有的直接删。 */
  def deleteProvider(id: String): Future[Unit] =
    ParserProviderLocal.getProvider(id).flatMap { local =>
      ParserProviderLocal.remove(id).flatMap { _ =>
        val wasPresetOwned = local.exists(_.preset.exists(_.owned))
        val isBuiltin      = bundle().exists(_.providers.exists(_.id == id))
        if (isBuiltin || wasPresetOwned) addTombstone(id) else Future.successful(())
      }
    }

  /** 把删掉的内置 provider 全部找回来（清 tombstone 后重跑一次应用）。 */
  def restoreBuiltins(): Future[ProviderPresetReport] =
    writeRemoved(Nil).flatMap(_ => ensureApplied())

  /** 这一家相对随包预设的状态（内容一致 / 有差别 / 本地更新 / 不在包里）。 */
  def stateOf(local: ParserProviderLocal): ProviderPresetState                                  =
    ProviderPresetState.classify(
      localHash = ParserProviderLocal.behaviorHash(local),
      presetHash = bundle().flatMap(_.providers.find(_.id == local.id)).map(ParserProviderLocal.behaviorHash),
      meta = local.preset,
      installedVersion = version
    )
  // ---------------------------------------------------------------- 应用
  /**
   * 落一次包。`owned=true`（随包应用）时把内容记为预设血缘；`owned=false`（单条 import）时记为"用户接手"。 无论哪种，`force=true` 都会覆盖"本地改过"的文档（仍然跳过
   * tombstone）。
   */
  private def applyBundle(
      bundle: ProviderPresetBundle,
      owned: Boolean,
      force: Boolean
  ): Future[ProviderPresetReport] =
    for {
      locals  <- ParserProviderLocal.list()
      removed <- readRemoved()
      actions   = PresetApply.decide(
        bundle.providers.map(entryOf(bundle.version, _)),
        locals.map(localEntryOf),
        removed.toSet
      )
      byId      = bundle.providers.map(provider => provider.id -> provider).toMap
      localById = locals.map(local => local.id -> local).toMap
      report <- applyActions(bundle.version, owned, force, actions, byId, localById)
      _      <- writeVersion(bundle.version)
    } yield report
  private def applyActions(
      bundleVersion: Int,
      owned: Boolean,
      force: Boolean,
      actions: List[PresetApply.Action],
      byId: Map[String, ParserProviderLocal],
      localById: Map[String, ParserProviderLocal]
  ): Future[ProviderPresetReport] = {
    val inserted                                                       = List.newBuilder[String]
    val updated                                                        = List.newBuilder[String]
    val adopted                                                        = List.newBuilder[String]
    val kept                                                           = List.newBuilder[String]
    val skipped                                                        = List.newBuilder[String]
    def metaFor(preset: ParserProviderLocal, version: Int): PresetMeta =
      PresetMeta(owned = owned, version = version, hash = ParserProviderLocal.behaviorHash(preset))
    val writes                                                         = actions.map {
      case PresetApply.Action.Skip(id)                     =>
        skipped += id
        Future.successful(())
      case PresetApply.Action.Orphan(id)                   =>
        kept += id
        Future.successful(())
      case PresetApply.Action.Insert(id, _)                =>
        byId.get(id) match {
          case None        => Future.successful(())
          case Some(value) =>
            inserted += id
            ParserProviderLocal.save(
              ParserProviderLocal.fromPreset(value, id, status = "draft", metaFor(value, bundleVersion))
            )
        }
      case PresetApply.Action.Update(id, _)                =>
        byId.get(id) match {
          case None        => Future.successful(())
          case Some(value) =>
            updated += id
            val meta =
              metaFor(value, math.max(localById.get(id).flatMap(_.preset).map(_.version).getOrElse(0), bundleVersion))
            ParserProviderLocal.save(
              localById.get(id).map(local => ParserProviderLocal.withPresetContent(local, value, meta)) match {
                case Some(doc) => doc
                case None      => ParserProviderLocal.fromPreset(value, id, status = "draft", meta)
              }
            )
        }
      case PresetApply.Action.Adopt(id, _)                 =>
        byId.get(id) match {
          case None        => Future.successful(())
          case Some(value) =>
            adopted += id
            val meta =
              metaFor(value, math.max(localById.get(id).flatMap(_.preset).map(_.version).getOrElse(0), bundleVersion))
            ParserProviderLocal.save(
              localById.get(id).map(local => local.copy(preset = Some(meta))) match {
                case Some(doc) => doc
                case None      => ParserProviderLocal.fromPreset(value, id, status = "draft", meta)
              }
            )
        }
      case PresetApply.Action.Keep(id, _, updateAvailable) =>
        if (force && updateAvailable) {
          byId.get(id) match {
            case None        => Future.successful(())
            case Some(value) =>
              updated += id
              val meta = metaFor(value, bundleVersion)
              ParserProviderLocal.save(
                localById.get(id).map(local => ParserProviderLocal.withPresetContent(local, value, meta)) match {
                  case Some(doc) => doc
                  case None      => ParserProviderLocal.fromPreset(value, id, status = "draft", meta)
                }
              )
          }
        } else {
          kept += id
          Future.successful(())
        }
    }
    writes.foldLeft(Future.successful(()))((acc, next) => acc.flatMap(_ => next)).map { _ =>
      ProviderPresetReport(
        version = bundleVersion,
        inserted = inserted.result(),
        updated = updated.result(),
        adopted = adopted.result(),
        kept = kept.result(),
        skipped = skipped.result()
      )
    }
  }
  // ---------------------------------------------------------------- 解析
  private def entryOf(bundleVersion: Int, preset: ParserProviderLocal): PresetApply.PresetEntry =
    PresetApply.PresetEntry(preset.id, bundleVersion, ParserProviderLocal.behaviorHash(preset))
  private def localEntryOf(local: ParserProviderLocal): PresetApply.LocalEntry                  =
    PresetApply.LocalEntry(
      id = local.id,
      behaviorHash = ParserProviderLocal.behaviorHash(local),
      presetOwned = local.preset.map(_.owned),
      appliedHash = local.preset.map(_.hash).getOrElse(""),
      presetVersion = local.preset.map(_.version).getOrElse(0)
    )
  private def currentEntries(): Future[(List[ParserProviderLocal], List[String])]               =
    ParserProviderLocal.list().flatMap(locals => readRemoved().map(removed => (locals, removed)))

  /** 文本 → bundle；单个 provider / provider 数组 / 完整 bundle 三种形态都收。 */
  private[model] def parseImport(text: String): Either[String, ProviderImportPlan]              = {
    val trimmed = Option(text).getOrElse("").trim
    if (trimmed.isEmpty) Left("Empty input")
    else
      Jsonc.parse(trimmed, "Import") match {
        case Left(error)                                                             => Left(s"Invalid JSONC: $error")
        case Right(value) if !JsonValue.isObject(value) && !JsonValue.isArray(value) =>
          Left("Unrecognized JSONC: expected a preset bundle, a provider object, or an array of providers")
        case Right(value)                                                            =>
          try {
            val parsed = value
            if (JsonValue.isArray(parsed)) {
              if (JsBridge.asArray(parsed).isEmpty) Left("Empty provider array")
              else parseBundle(JsBridge.obj("providers" -> (parsed), "bundleVersion" -> (0)), fallbackId = true).map(
                plan =>
                  plan.copy(owned = false)
              )
            } else if (JsonValue.field(parsed, "providers").nonEmpty) {
              parseBundle(parsed, fallbackId = false).map(plan => plan.copy(owned = false))
            } else if (JsonValue.field(parsed, "page").nonEmpty && JsonValue.field(parsed, "parserRule").nonEmpty) {
              parseBundle(JsBridge.obj("providers" -> (js.Array(parsed)), "bundleVersion" -> (0)), fallbackId = true).map(
                plan =>
                  plan.copy(owned = false)
              )
            } else Left("Unrecognized JSONC: expected a preset bundle, a provider object, or an array of providers")
          } catch { case e: Throwable => Left(s"Invalid JSONC: ${e.getMessage}") }
      }
  }
  private def parseBundle(raw: js.Any, fallbackId: Boolean): Either[String, ProviderImportPlan] = {
    val versionRaw = JsonValue.doubleField(raw, "bundleVersion").getOrElse(0.0)
    val version    = if (versionRaw > 0) versionRaw.toInt else 0
    val list       =
      JsonValue.arrayField(raw, "providers")
    if (list.isEmpty) Left("Bundle contains no providers")
    else {
      val parsed = list.toList.zipWithIndex.map { case (doc, index) =>
        val id = JsonValue.stringField(doc, "id")
          .filter(_.trim.nonEmpty)
          .orElse(JsonValue.stringField(doc, "name").filter(_.trim.nonEmpty))
          .orElse(if (fallbackId) Some(s"provider-$index") else None)
        id match {
          case None             => Left(s"Provider #$index has no id / name")
          case Some(providerId) => validateProvider(providerId.trim, doc)
        }
      }
      parsed.collectFirst { case Left(error) => error } match {
        case Some(error) => Left(error)
        case None        =>
          val providers = parsed.collect { case Right(provider) => provider }
          val dupe      = providers.groupBy(_.id).collectFirst { case (id, items) if items.size > 1 => id }
          dupe match {
            case Some(id) => Left(s"Duplicate provider id '$id' in bundle")
            case None     => Right(ProviderImportPlan(ProviderPresetBundle(version, providers), owned = true, Nil))
          }
      }
    }
  }
  private def validateProvider(id: String, doc: js.Any): Either[String, ParserProviderLocal]    = {
    val provider = ParserProviderLocal.parse(id, doc)
    val rule     = parseRule(provider.parserRule)
    val schema   = JsonValue.intField(doc, "schemaVersion").getOrElse(0)
    if (schema != 2) Left(s"$id: schemaVersion must be 2")
    else if (provider.pageUrl.trim.isEmpty) Left(s"$id: page.url is required")
    else if (provider.inputSelector.trim.isEmpty) Left(s"$id: page.selectors.input is required")
    else if (provider.apiPatterns.isEmpty) Left(s"$id: network.apiPatterns is required")
    else if (js.isUndefined(rule) || rule == null) Left(s"$id: parserRule is required")
    else if (ruleSchema(rule) != 2) Left(s"$id: parserRule.schemaVersion must be 2")
    else Right(provider.copy(name = if (provider.name.trim.nonEmpty) provider.name else id))
  }
  private def ruleSchema(rule: js.Any): Int                                                     =
    JsonValue.intField(rule, "schemaVersion").getOrElse(0)
  private def parseRule(rule: String): js.Any                                                   =
    if (Option(rule).getOrElse("").trim.isEmpty) js.undefined
    else JsonValue.parse(rule).toOption.getOrElse(js.undefined)
  // ---------------------------------------------------------------- 存储
  private def writeVersion(version: Int): Future[Unit]                                          =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](VersionKey -> version.toDouble))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }
  private def readRemoved(): Future[List[String]]                                               =
    ChromeAPI.storage.local
      .get(js.Array(RemovedKey))
      .toFuture
      .map(values =>
        values
          .get(RemovedKey)
          .flatMap(value => Option(value).map(JsBridge.asArray(_).toList.map(_.toString)))
          .getOrElse(Nil)
      )
      .recover { case _ => Nil }
  private def writeRemoved(ids: List[String]): Future[Unit]                                     =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](RemovedKey -> js.Array(ids.map(JsBridge.asObject(_))*)))
      .toFuture
      .map(_ => ())
      .recover { case _ => () }
  private def addTombstone(id: String): Future[Unit]                                            =
    readRemoved().flatMap(ids => if (ids.contains(id)) Future.successful(()) else writeRemoved((ids :+ id).distinct))
}
