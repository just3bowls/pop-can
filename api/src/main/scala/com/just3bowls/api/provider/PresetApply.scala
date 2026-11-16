package com.just3bowls.api.provider
import com.just3bowls.api.protocol.*

/**
 * provider 预设包（`presets/bundle.json`）"该怎么落到本地"的纯逻辑：JVM + Scala.js 通用，`server/test` 里逐条单测。
 *
 * 背景：插件随包带一份 provider 目录（首批 9 家），SW 启动时把它应用到 `chrome.storage.local["parserProvider:<id>"]`；用户在面板里 import 新版本走的是同一段逻辑。
 * 配置文件的职责是"有哪些 provider、长什么样"，本地文档的职责是"这一家现在启用没有、有没有被本地接手"。
 *
 * 冲突口径（用户选定）：**本地优先**——只有当这一家仍是"从预设包原样应用、之后没被动过"时才升级；本地接手过（面板单条 import）或内容与包不一致时只保留，并回报"有更新"， 让用户点 `Reset to built-in`
 * 才覆盖。
 */
object PresetApply {

  /** 预设包拥有的**行为字段**：它们决定一份 provider "是不是被本地动过"。 */
  val BehaviorKeys: List[String] = List(
    "pageUrl",
    "inputSelector",
    "newChatSelector",
    "apiPatterns",
    "attachmentStrategy",
    "attachmentSelector",
    "followUpPatterns",
    "followUpIdentityPath",
    "fingerprints",
    "parserRule"
  )

  /**
   * 把字段值串成稳定文本：字段名排序后 `key\u001Fvalue` 连接。
   *
   * 调用方负责把每个字段先规范化成**稳定字符串**（对象键排序、数组保序、缺省的 `followUp*` 补成 `[]` / `""`），这里只保证"字段名顺序"不影响结果。
   * 缺字段按空串参与，所以"键省掉"与"显式空值"不会被算成两种内容——预设文件里省掉 `followUp*` 是常见写法。
   */
  def canonical(values: Map[String, String]): String =
    BehaviorKeys.sorted.map(key => s"$key\u001F${values.getOrElse(key, "")}").mkString("\u001E")

  /** 行为字段的指纹（小写 md5 十六进制）。 */
  def hash(values: Map[String, String]): String = Md5.hex(canonical(values))

  /** 预设包里的一个 provider（id + 所在包的版本 + 行为指纹）。 */
  final case class PresetEntry(id: String, version: Int, behaviorHash: String)

  /**
   * 本地 `parserProvider:<id>` 文档的摘要。
   *
   * @param presetOwned
   *   文档里 `preset.owned`：`None` = 从没有过 `preset` 字段（旧版留下的文档）；`Some(true)` = 由预设包应用而来；`Some(false)` = 用户接手过（单条 import）。
   * @param appliedHash
   *   上次应用预设包时的行为指纹（`preset.hash`），用于判断"应用之后有没有被本地改过"。
   * @param presetVersion
   *   上次应用预设包时的版本（`preset.version`）；本地比随包的包新时不允许被顶回去。
   */
  final case class LocalEntry(
      id: String,
      behaviorHash: String,
      presetOwned: Option[Boolean],
      appliedHash: String,
      presetVersion: Int
  )

  sealed trait Action { def id: String }

  object Action {

    /** 本地没有：按预设插入（`status=draft`，等用户启用）。 */
    final case class Insert(id: String, behaviorHash: String) extends Action

    /** 仍是"原样应用、没被动过"的预设文档：用包里的内容刷新（保留本地 status）。 */
    final case class Update(id: String, behaviorHash: String) extends Action

    /** 内容和包完全一致（含用户接手但改回一致的情况）：只补写 `preset` 元数据，认领回预设血缘。 */
    final case class Adopt(id: String, behaviorHash: String) extends Action

    /** 本地有内容且与包不一致、或已被用户接手：保留不动；`updateAvailable` 表示包里有更新可重置。 */
    final case class Keep(id: String, behaviorHash: String, updateAvailable: Boolean) extends Action

    /** 这一家在本地被删过（tombstone）：不再塞回来。 */
    final case class Skip(id: String) extends Action

    /** 本地有、包里没有：保留（可能是我把某家从包里下掉了，不该自动删用户的东西）。 */
    final case class Orphan(id: String) extends Action
  }

  /**
   * 给每一个预设条目 + 每一个本地文档定一个动作。
   *
   * 返回顺序：先按传入的预设顺序给 `Insert / Update / Adopt / Keep / Skip`，最后按 id 排序给 `Orphan`。
   */
  def decide(entries: List[PresetEntry], locals: List[LocalEntry], removed: Set[String]): List[Action] = {
    val localById = locals.map(entry => entry.id -> entry).toMap
    val presetIds = entries.map(_.id).toSet

    val main = entries.map { entry =>
      if (removed.contains(entry.id)) Action.Skip(entry.id)
      else
        localById.get(entry.id) match {
          case None                                                                                       =>
            Action.Insert(entry.id, entry.behaviorHash)
          case Some(local)
              if local.presetOwned.contains(true) && local.appliedHash == local.behaviorHash &&
                entry.version < local.presetVersion =>
            // 本地是"用户 import 过的更新版"：随包版本更旧，不许把内容顶回去（也不报"有更新"）。
            Action.Keep(entry.id, entry.behaviorHash, updateAvailable = false)
          case Some(local) if local.behaviorHash == entry.behaviorHash                                    =>
            // 内容已经和包一致（含"用户接手后又改回一致"）：补写 `preset` 元数据，认领回预设血缘。
            Action.Adopt(entry.id, entry.behaviorHash)
          case Some(local) if local.presetOwned.contains(true) && local.appliedHash == local.behaviorHash =>
            // 仍是"原样应用、没被动过"：用包里的内容刷新，保留本地 status。
            Action.Update(entry.id, entry.behaviorHash)
          case Some(_)                                                                                    =>
            Action.Keep(entry.id, entry.behaviorHash, updateAvailable = true)
        }
    }

    val orphans =
      locals.filterNot(local => presetIds.contains(local.id)).sortBy(_.id).map(local => Action.Orphan(local.id))
    main ++ orphans
  }
}
