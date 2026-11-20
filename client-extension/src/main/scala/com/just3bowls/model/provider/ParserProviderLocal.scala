package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.JsonValue
import com.just3bowls.api.provider.PresetApply
import com.just3bowls.facade.ChromeAPI
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
final case class ElementFingerprint(
    tag: String = "",
    id: String = "",
    classes: List[String] = Nil,
    role: String = "",
    ariaLabel: String = "",
    placeholder: String = "",
    name: String = "",
    dataTestId: String = "",
    text: String = "",
    editable: Boolean = false,
    clickable: Boolean = false,
    parentTag: String = "",
    parentClasses: List[String] = Nil,
    parentText: String = "",
    siblingTexts: List[String] = Nil,
    ancestorClasses: List[String] = Nil,
    ancestorTexts: List[String] = Nil,
    nearestText: String = "",
    nearInput: Boolean = false,
    capturedAt: Long = 0L,
    x: Double = 0.0,
    y: Double = 0.0,
    width: Double = 0.0,
    height: Double = 0.0,
    scrollX: Double = 0.0,
    scrollY: Double = 0.0,
    innerWidth: Double = 0.0,
    innerHeight: Double = 0.0,
    docX: Double = 0.0,
    docY: Double = 0.0,
    anchorDeltaX: Double = 0.0,
    anchorDeltaY: Double = 0.0
)
final case class ProviderSelectors(input: String = "", newChat: String = "")
final case class ProviderPage(
    url: String = "",
    selectors: ProviderSelectors = ProviderSelectors(),
    fingerprints: Map[String, ElementFingerprint] = Map.empty
)

/** API 响应和 follow-up 都属于网络层配置。 */
final case class ProviderNetwork(
    apiPatterns: List[String] = Nil,
    followUpPatterns: List[String] = Nil,
    followUpIdentityPath: String = ""
)

/** `input` / `paste` / `drop` 的目标配置。 */
final case class ProviderAttachment(strategy: String = "", selector: String = "")
final case class ParserProviderLocal(
    id: String,
    name: String,
    page: ProviderPage = ProviderPage(),
    network: ProviderNetwork = ProviderNetwork(),
    attachment: ProviderAttachment = ProviderAttachment(),
    status: String = "draft",
    /** 执行层使用字符串；持久化与来源配置使用嵌套 `parserRule` 对象。 */
    parserRule: String = "",
    /**
     * 这一家的血缘：`Some(owned=true)` = 由已安装 Provider Pack 应用而来，`owned=false` = 用户 import 接手过，`None` = 旧版遗留文档。 `version` /
     * `hash` 记的是**上次应用包时**的版本与行为指纹，用来判断"之后有没有被本地改动过"。
     */
    preset: Option[PresetMeta] = None
) {
  // Read-only semantic accessors keep runtime consumers independent from the persistence nesting.
  def pageUrl: String                               = page.url
  def inputSelector: String                         = page.selectors.input
  def newChatSelector: String                       = page.selectors.newChat
  def fingerprints: Map[String, ElementFingerprint] = page.fingerprints
  def apiPatterns: List[String]                     = network.apiPatterns
  def followUpPatterns: List[String]                = network.followUpPatterns
  def followUpIdentityPath: String                  = network.followUpIdentityPath
  def attachmentStrategy: String                    = attachment.strategy
  def attachmentSelector: String                    = attachment.selector
}

/** 见 [[ParserProviderLocal.preset]]。 */
final case class PresetMeta(owned: Boolean, version: Int, hash: String)
object ParserProviderLocal {
  private val prefix = "parserProvider:"

  /** 运行期缓存（面板与 SW 都会读）：`storage.onChanged` 命中前缀即失效，所以不会读到旧规则。 */
  private var cache: Option[Map[String, ParserProviderLocal]]                           = None
  private var listenerAttached: Boolean                                                 = false
  private def providerKey(id: String): String                                           = s"$prefix$id"
  private def ensureListener(): Unit                                                    =
    if (!listenerAttached) {
      listenerAttached = true
      ChromeAPI.storage.onChanged.addListener {
        (changes: js.Dictionary[com.just3bowls.facade.StorageChange], area: String) =>
          if (area == "local" && changes.keys.exists(_.startsWith(prefix))) {
            cache = None
            ()
          }
      }
    }
  private def fromProviderEntry(id: String, value: js.Any): Option[ParserProviderLocal] =
    Option(value).filter(v => !js.isUndefined(v) && v != null).map(_ => parse(id, value))
  def fingerprintToDynamic(fp: ElementFingerprint): js.Any                              =
    fingerprintToJs(fp)

  /**
   * 全部运行时 provider 配置。
   *
   * **不再用无 key 的 `storage.local.get()`**：那会把整个扩展存储（曾 21.5MB）反序列化一遍只为找配置； 现在用 `getKeys()` 定位 `parserProvider:*`
   * 只读这几条，并缓存到内存（`onChanged` 失效）。
   */
  def loadAll(): Future[Map[String, ParserProviderLocal]] =
    cache match {
      case Some(map) => Future.successful(map)
      case None      =>
        ensureListener()
        allKeys()
          .flatMap { keys =>
            val providerKeys = keys.filter(_.startsWith(prefix))
            if (providerKeys.isEmpty) Future.successful(Map.empty[String, ParserProviderLocal])
            else
              ChromeAPI.storage.local.get(js.Array(providerKeys*)).toFuture.map { values =>
                val supportedKeys = providerKeys.filter { key =>
                  values.get(key).forall(raw => JsonValue.intField(raw, "schemaVersion").contains(1))
                }
                providerKeys.filterNot(supportedKeys.contains).foreach { key =>
                  PopCanLog.warn(
                    "provider-catalog",
                    s"ignored incompatible provider document ${key.stripPrefix(prefix)} (expected schemaVersion=1)"
                  )
                }
                val entries       = supportedKeys.flatMap { key =>
                  val id = key.stripPrefix(prefix)
                  // 注意：`js.Dictionary.get` 已经返回 `Option`，别再包一层 —— 双包会拿到 `Some(Option(…))` 当文档解析，
                  // 结果是每家 provider 都退化成 `name=id / status=draft` 的空配置（真实踩过）。
                  values.get(key).flatMap(value => fromProviderEntry(id, value))
                }
                // 主键是 provider id（callers 按 id 取）；名字作为别名保留历史行为，不覆盖同 id 条目。
                entries.foldLeft(entries.map(local => local.id -> local).toMap) { case (acc, local) =>
                  val name = local.name.trim
                  if (name.nonEmpty && !acc.contains(name)) acc.updated(name, local) else acc
                }
              }
          }
          .map { map =>
            cache = Some(map)
            map
          }
          .recover { case _ => Map.empty[String, ParserProviderLocal] }
    }

  /** 热路径按 id 取单条（命中缓存；SW 每轮发送都用它，不再读 1MB 文档）。 */
  def getProvider(id: String): Future[Option[ParserProviderLocal]] =
    if (id.trim.isEmpty) Future.successful(None) else loadAll().map(_.get(id))
  private def allKeys(): Future[List[String]]                      = {
    val area       = ChromeAPI.storage.local
    val hasGetKeys = JsonValue.field(area, "getKeys").isDefined
    if (hasGetKeys) area.getKeys().toFuture.map(_.toList)
    else area.get().toFuture.map(_.keys.toList)
  }
  def save(config: ParserProviderLocal): Future[Unit]              =
    ChromeAPI.storage.local
      .set(js.Dictionary(
        providerKey(config.id) -> toJs(config)
      ))
      .toFuture
      .map { _ =>
        cache = None
        ()
      }
  def remove(id: String): Future[Unit]                             =
    ChromeAPI.storage.local.remove(providerKey(id)).toFuture.map { _ =>
      cache = None
      ()
    }
  def clear(): Future[Unit]                                        =
    allKeys().flatMap { all =>
      val keys = all.filter(_.startsWith(prefix))
      ChromeAPI.storage.local.remove(js.Array(keys*)).toFuture.map { _ =>
        cache = None
        ()
      }
    }
  def replaceAll(configs: Seq[ParserProviderLocal]): Future[Unit]  = {
    val dict = js.Dictionary.empty[js.Any]
    configs.foreach { config =>
      dict.update(providerKey(config.id), toJs(config))
    }
    ChromeAPI.storage.local.set(dict).toFuture.map { _ =>
      cache = None
      ()
    }
  }

  /** 解析一份 provider 文档：本地 `parserProvider:<id>` 与 Provider Pack payload 里的条目共用同一套字段。 */
  def parse(id: String, raw: js.Any): ParserProviderLocal = {
    val page       = JsonValue.objectField(raw, "page").getOrElse(JsonValue.emptyObject)
    val selectors  = JsonValue.objectField(page, "selectors").getOrElse(JsonValue.emptyObject)
    val network    = JsonValue.objectField(raw, "network").getOrElse(JsonValue.emptyObject)
    val attachment = JsonValue.objectField(raw, "attachment").getOrElse(JsonValue.emptyObject)
    ParserProviderLocal(
      id = id,
      name = JsonValue.stringField(raw, "name").getOrElse(id),
      page = ProviderPage(
        url = JsonValue.stringField(page, "url").getOrElse(""),
        selectors = ProviderSelectors(
          input = JsonValue.stringField(selectors, "input").getOrElse(""),
          newChat = JsonValue.stringField(selectors, "new-chat").getOrElse("")
        ),
        fingerprints = JsonValue
          .objectField(page, "fingerprints")
          .map(JsonValue.dictionary)
          .getOrElse(Map.empty)
          .map { case (role, value) => role -> parseFingerprint(role, value) }
      ),
      network = ProviderNetwork(
        apiPatterns = JsonValue.stringListField(network, "apiPatterns"),
        followUpPatterns = JsonValue.stringListField(network, "followUpPatterns"),
        followUpIdentityPath = JsonValue.stringField(network, "followUpIdentityPath").getOrElse("")
      ),
      attachment = ProviderAttachment(
        strategy = JsonValue.stringField(attachment, "strategy").getOrElse(""),
        selector = JsonValue.stringField(attachment, "selector").getOrElse("")
      ),
      status = JsonValue.stringField(raw, "status").getOrElse("draft"),
      parserRule = ruleText(JsonValue.field(raw, "parserRule").getOrElse(js.undefined)),
      preset = JsonValue
        .objectField(raw, "preset")
        .map(value =>
          PresetMeta(
            owned = JsonValue.boolField(value, "owned").getOrElse(true),
            version = JsonValue.doubleField(value, "version").getOrElse(0.0).toInt,
            hash = JsonValue.stringField(value, "hash").getOrElse("")
          )
        )
    )
  }
  def toJs(config: ParserProviderLocal): js.Object        = {
    val doc = JsBridge.obj(
      "schemaVersion" -> (1),
      "id"            -> (config.id),
      "name"          -> (config.name),
      "page"          -> (JsBridge.obj(
        "url"          -> (config.page.url),
        "selectors"    -> (JsBridge.obj(
          "input"    -> (config.page.selectors.input),
          "new-chat" -> (config.page.selectors.newChat)
        )),
        "fingerprints" -> (js.Dictionary(config.page.fingerprints.map { case (role, fp) =>
          role -> fingerprintToJs(fp)
        }.toSeq*))
      )),
      "network"       -> (JsBridge.obj(
        "apiPatterns"          -> (js.Array(config.network.apiPatterns*)),
        "followUpPatterns"     -> (js.Array(config.network.followUpPatterns*)),
        "followUpIdentityPath" -> (config.network.followUpIdentityPath)
      )),
      "attachment"    -> (JsBridge.obj(
        "strategy" -> (config.attachment.strategy),
        "selector" -> (config.attachment.selector)
      )),
      "status"        -> (config.status),
      "parserRule"    -> (if (config.parserRule.trim.isEmpty) JsBridge.obj()
                       else
                         try js.JSON.parse(config.parserRule)
                         catch { case _: Throwable => JsBridge.asAny(config.parserRule) })
    )
    // 只在真有血缘时才挂 `preset`：值里带 `undefined` 的键在结构化克隆里行为不统一。
    config.preset.foreach { meta =>
      JsBridge.update(
        doc,
        "preset",
        JsBridge.asAny(
          JsBridge.obj("owned" -> (meta.owned), "version" -> (meta.version.toDouble), "hash" -> (meta.hash))
        )
      )
    }
    doc
  }

  /** 全部文档（按 id 去重；`loadAll()` 的返回里另有按 name 的别名，调用方要的是这一份）。 */
  def list(): Future[List[ParserProviderLocal]] =
    loadAll().map(_.values.toList.map(local => local.id -> local).toMap.values.toList.sortBy(_.id))

  /**
   * 行为字段投影：**预设包条目与本地文档共用**——两边都先解析成 [[ParserProviderLocal]] 再调它，指纹才有可比性。
   *
   * 对象的键顺序、`parserRule` 的排版（compact / pretty）都不该算成"内容变了"，所以统一走 [[canonicalJson]]：对象键排序、数组保序、缺省字段补空值。
   */
  def behaviorFields(local: ParserProviderLocal): Map[String, String] =
    Map(
      "pageUrl"              -> local.pageUrl,
      "inputSelector"        -> local.inputSelector,
      "newChatSelector"      -> local.newChatSelector,
      "apiPatterns"          -> canonicalJson(js.Array(local.apiPatterns*)),
      "attachmentStrategy"   -> local.attachmentStrategy,
      "attachmentSelector"   -> local.attachmentSelector,
      "followUpPatterns"     -> canonicalJson(js.Array(local.followUpPatterns*)),
      "followUpIdentityPath" -> local.followUpIdentityPath,
      "fingerprints"         -> canonicalJson(fingerprintsToJs(local.fingerprints)),
      "parserRule"           -> canonicalRule(local.parserRule)
    )

  /** 行为指纹（md5）：判断"这一家相对预设包有没有被改过"，也是"有更新"徽标的口径。 */
  def behaviorHash(local: ParserProviderLocal): String = PresetApply.hash(behaviorFields(local))

  /** 用预设条目造一份新文档（只换内容与血缘；status 由调用方给，新装一律 draft）。 */
  def fromPreset(preset: ParserProviderLocal, id: String, status: String, meta: PresetMeta): ParserProviderLocal =
    preset.copy(
      id = id,
      name = if (preset.name.trim.nonEmpty) preset.name else id,
      status = status,
      preset = Some(meta)
    )

  /** 把预设条目的行为字段刷进已有文档：**保留**本地 status / setupStep，只换内容与血缘。 */
  def withPresetContent(
      local: ParserProviderLocal,
      preset: ParserProviderLocal,
      meta: PresetMeta
  ): ParserProviderLocal =
    local.copy(
      page = preset.page,
      network = preset.network,
      attachment = preset.attachment,
      parserRule = preset.parserRule,
      preset = Some(meta)
    )

  /** 指纹 map → JSON（写文档与算指纹共用同一份序列化，避免两处漂移）。 */
  def fingerprintsToJs(fingerprints: Map[String, ElementFingerprint]): js.Any =
    js.Dictionary(fingerprints.map { case (role, fp) =>
      role -> fingerprintToJs(fp)
    }.toSeq*)

  /**
   * 规则统一成"解析后重新序列化"的稳定形态；空串与 `{}` 等价（都表示没有规则）。旧版配置里的 `comment` 只是人读文档，递归剔除后再比较，升级到 JSONC 时不会被误判成行为变化。
   */
  private def canonicalRule(rule: String): String =
    if (rule.trim.isEmpty) "{}"
    else
      JsonValue.parse(rule).map(value => JsonValue.canonical(value, ignoredKeys = Set("comment"))).getOrElse(rule.trim)

  /** `parserRule` 两种形态：字符串（运行时文档）与对象（预设文件 / 导入 JSON）。 */
  private def ruleText(value: js.Any): String =
    if (value == null || js.isUndefined(value)) ""
    else {
      val raw: js.Any = if (js.typeOf(value) == "string") JsBridge.asString(value) else value
      val parsed      =
        if (js.typeOf(raw) == "string") JsonValue.parse(JsBridge.asString(raw)).toOption
        else Some(raw)
      parsed.map(value => JsonValue.canonical(value.asInstanceOf[JsonValue], ignoredKeys = Set("comment"))).getOrElse {
        if (js.typeOf(raw) == "string") JsBridge.asString(raw).trim else ""
      }
    }

  /**
   * 稳定 JSON 文本：对象键排序、数组保序、字符串按 JSON 转义。
   *
   * 只服务内容比较（[[behaviorFields]]），不参与任何喂给页面的载荷。
   */
  private def canonicalJson(value: js.Any, ignoredKeys: Set[String] = Set.empty): String =
    JsonValue.canonical(value, ignoredKeys)
  private def parseFingerprint(role: String, raw: js.Any): ElementFingerprint            =
    ElementFingerprint(
      tag = JsonValue.stringField(raw, "tag").getOrElse(""),
      id = JsonValue.stringField(raw, "id").getOrElse(""),
      classes = JsonValue.stringListField(raw, "classes"),
      role = role,
      ariaLabel = JsonValue.stringField(raw, "ariaLabel").getOrElse(""),
      placeholder = JsonValue.stringField(raw, "placeholder").getOrElse(""),
      name = JsonValue.stringField(raw, "name").getOrElse(""),
      dataTestId = JsonValue.stringField(raw, "dataTestId").getOrElse(""),
      text = JsonValue.stringField(raw, "text").getOrElse(""),
      editable = JsonValue.boolField(raw, "editable").getOrElse(false),
      clickable = JsonValue.boolField(raw, "clickable").getOrElse(false),
      parentTag = JsonValue.stringField(raw, "parentTag").getOrElse(""),
      parentClasses = JsonValue.stringListField(raw, "parentClasses"),
      parentText = JsonValue.stringField(raw, "parentText").getOrElse(""),
      siblingTexts = JsonValue.stringListField(raw, "siblingTexts"),
      ancestorClasses = JsonValue.stringListField(raw, "ancestorClasses"),
      ancestorTexts = JsonValue.stringListField(raw, "ancestorTexts"),
      nearestText = JsonValue.stringField(raw, "nearestText").getOrElse(""),
      nearInput = JsonValue.boolField(raw, "nearInput").getOrElse(false),
      capturedAt = JsonValue.doubleField(raw, "capturedAt").getOrElse(0.0).toLong,
      x = JsonValue.doubleField(raw, "x").getOrElse(0.0),
      y = JsonValue.doubleField(raw, "y").getOrElse(0.0),
      width = JsonValue.doubleField(raw, "width").getOrElse(0.0),
      height = JsonValue.doubleField(raw, "height").getOrElse(0.0),
      scrollX = JsonValue.doubleField(raw, "scrollX").getOrElse(0.0),
      scrollY = JsonValue.doubleField(raw, "scrollY").getOrElse(0.0),
      innerWidth = JsonValue.doubleField(raw, "innerWidth").getOrElse(0.0),
      innerHeight = JsonValue.doubleField(raw, "innerHeight").getOrElse(0.0),
      docX = JsonValue.doubleField(raw, "docX").getOrElse(0.0),
      docY = JsonValue.doubleField(raw, "docY").getOrElse(0.0),
      anchorDeltaX = JsonValue.doubleField(raw, "anchorDeltaX").getOrElse(0.0),
      anchorDeltaY = JsonValue.doubleField(raw, "anchorDeltaY").getOrElse(0.0)
    )
  private def fingerprintToJs(fp: ElementFingerprint): js.Object                         =
    JsBridge.obj(
      "tag"             -> (fp.tag),
      "id"              -> (fp.id),
      "classes"         -> (js.Array(fp.classes*)),
      "role"            -> (fp.role),
      "ariaLabel"       -> (fp.ariaLabel),
      "placeholder"     -> (fp.placeholder),
      "name"            -> (fp.name),
      "dataTestId"      -> (fp.dataTestId),
      "text"            -> (fp.text),
      "editable"        -> (fp.editable),
      "clickable"       -> (fp.clickable),
      "parentTag"       -> (fp.parentTag),
      "parentClasses"   -> (js.Array(fp.parentClasses*)),
      "parentText"      -> (fp.parentText),
      "siblingTexts"    -> (js.Array(fp.siblingTexts*)),
      "ancestorClasses" -> (js.Array(fp.ancestorClasses*)),
      "ancestorTexts"   -> (js.Array(fp.ancestorTexts*)),
      "nearestText"     -> (fp.nearestText),
      "nearInput"       -> (fp.nearInput),
      "capturedAt"      -> (fp.capturedAt.toDouble),
      "x"               -> (fp.x),
      "y"               -> (fp.y),
      "width"           -> (fp.width),
      "height"          -> (fp.height),
      "scrollX"         -> (fp.scrollX),
      "scrollY"         -> (fp.scrollY),
      "innerWidth"      -> (fp.innerWidth),
      "innerHeight"     -> (fp.innerHeight),
      "docX"            -> (fp.docX),
      "docY"            -> (fp.docY),
      "anchorDeltaX"    -> (fp.anchorDeltaX),
      "anchorDeltaY"    -> (fp.anchorDeltaY)
    )
}
