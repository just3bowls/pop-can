package com.just3bowls.api
import com.just3bowls.api.provider.*

import zio.test._

/** 状态行 provider 标记"点一下切下一个"的金值。 */
object ProviderCycleSpec extends ZIOSpecDefault {

  private val active = List("deepseek", "doubao", "gml", "kimi")

  def spec: Spec[Any, Any] = suite("ProviderCycle")(
    test("列表中间：下一个") {
      assertTrue(ProviderCycle.next(active, "doubao").contains("gml"))
    },
    test("到末尾：回绕到第一个") {
      assertTrue(ProviderCycle.next(active, "kimi").contains("deepseek"))
    },
    test("当前这家不在列表里（被禁用 / 还没选）：从第一个开始") {
      assertTrue(
        ProviderCycle.next(active, "yuanbao").contains("deepseek"),
        ProviderCycle.next(active, "").contains("deepseek")
      )
    },
    test("只有一个可用：点它等于没变化（不报错）") {
      assertTrue(ProviderCycle.next(List("gml"), "gml").contains("gml"))
    },
    test("空列表：没有下一个") {
      assertTrue(ProviderCycle.next(Nil, "gml").isEmpty, ProviderCycle.next(List("", "  "), "gml").isEmpty)
    },
    test("重复 id 去重，不会原地打转") {
      assertTrue(ProviderCycle.next(List("gml", "gml", "kimi"), "gml").contains("kimi"))
    }
  )
}
