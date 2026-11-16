package com.just3bowls.api
import com.just3bowls.api.provider.*

import zio.test._

/**
 * 预设包应用逻辑的金值测试。
 *
 * 这张表就是"配置文件 vs 本地文档"的唯一裁决口径，所以每条分支都要有明确期望：装不装、升不升、留不留、能不能被覆盖。 任何改动先改这里再看实现。
 */
object PresetApplySpec extends ZIOSpecDefault {

  private def fields(
      pageUrl: String = "https://a.example/chat",
      rule: String = "{\"handlers\":[1]}"
  ): Map[String, String]              = Map(
    "pageUrl"              -> pageUrl,
    "inputSelector"        -> "//input",
    "newChatSelector"      -> "//button",
    "apiPatterns"          -> "[\"https://a.example/api\"]",
    "attachmentStrategy"   -> "",
    "attachmentSelector"   -> "",
    "followUpPatterns"     -> "[]",
    "followUpIdentityPath" -> "",
    "fingerprints"         -> "{}",
    "parserRule"           -> rule
  )

  private val hashA = PresetApply.hash(fields())
  private val hashB = PresetApply.hash(fields(rule = "{\"handlers\":[2]}"))

  private val entryA = PresetApply.PresetEntry("a", version = 3, hashA)

  def spec: Spec[Any, Any] = suite("PresetApply")(
    suite("字段指纹")(
      test("字段名顺序不影响结果") {
        val shuffled = fields().toList.reverse.toMap
        assertTrue(PresetApply.hash(shuffled) == hashA)
      },
      test("缺省键按空串参与，与显式空值等价") {
        assertTrue(PresetApply.hash(Map("pageUrl" -> "https://a.example/chat")) == PresetApply.hash(Map(
          "pageUrl"          -> "https://a.example/chat",
          "followUpPatterns" -> ""
        )))
      },
      test("内容变一个字节，指纹就变") {
        assertTrue(hashA != hashB)
      }
    ),
    suite("应用决策")(
      test("本地没有 → Insert（等用户启用）") {
        val actions = PresetApply.decide(List(entryA), Nil, Set.empty)
        assertTrue(actions == List(PresetApply.Action.Insert("a", hashA)))
      },
      test("本地删过（tombstone）→ Skip，不塞回来") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashB, Some(true), hashB, presetVersion = 1)),
          Set("a")
        )
        assertTrue(actions == List(PresetApply.Action.Skip("a")))
      },
      test("预设血缘且原样未改 → Update，包里的改动直接落地") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashB, Some(true), hashB, presetVersion = 1)),
          Set.empty
        )
        assertTrue(actions == List(PresetApply.Action.Update("a", hashA)))
      },
      test("预设血缘且内容已与包一致 → Adopt（只刷元数据）") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashA, Some(true), hashA, presetVersion = 1)),
          Set.empty
        )
        assertTrue(actions == List(PresetApply.Action.Adopt("a", hashA)))
      },
      test("旧版遗留文档（没有 preset 字段）内容与包一致 → Adopt") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashA, None, "", presetVersion = 0)),
          Set.empty
        )
        assertTrue(actions == List(PresetApply.Action.Adopt("a", hashA)))
      },
      test("旧版遗留文档内容与包不同 → Keep + 有更新（本地优先，不静默覆盖）") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashB, None, "", presetVersion = 0)),
          Set.empty
        )
        assertTrue(actions == List(PresetApply.Action.Keep("a", hashA, updateAvailable = true)))
      },
      test("用户接手过（单条 import）且内容与包不同 → Keep + 有更新") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashB, Some(false), hashA, presetVersion = 0)),
          Set.empty
        )
        assertTrue(actions == List(PresetApply.Action.Keep("a", hashA, updateAvailable = true)))
      },
      test("用户接手过但内容恰好改回与包一致 → Adopt") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashA, Some(false), hashB, presetVersion = 0)),
          Set.empty
        )
        assertTrue(actions == List(PresetApply.Action.Adopt("a", hashA)))
      },
      test("本地是 import 过的更新版（版本更高）→ Keep，不回退也不报更新") {
        val actions = PresetApply.decide(
          List(entryA),
          List(PresetApply.LocalEntry("a", hashB, Some(true), hashB, presetVersion = 7)),
          Set.empty
        )
        assertTrue(actions == List(PresetApply.Action.Keep("a", hashA, updateAvailable = false)))
      },
      test("本地有、包里没有 → Orphan，排在最后且按 id 排序") {
        val actions = PresetApply.decide(
          List(entryA),
          List(
            PresetApply.LocalEntry("z", hashA, Some(true), hashA, presetVersion = 1),
            PresetApply.LocalEntry("b", hashA, Some(true), hashA, presetVersion = 1)
          ),
          Set.empty
        )
        assertTrue(
          actions == List(
            PresetApply.Action.Insert("a", hashA),
            PresetApply.Action.Orphan("b"),
            PresetApply.Action.Orphan("z")
          )
        )
      },
      test("多个条目保持传入顺序") {
        val actions = PresetApply.decide(
          List(entryA, PresetApply.PresetEntry("b", version = 3, hashA)),
          Nil,
          Set("b")
        )
        assertTrue(actions == List(PresetApply.Action.Insert("a", hashA), PresetApply.Action.Skip("b")))
      }
    )
  )
}
