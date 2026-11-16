package com.just3bowls.api
import com.just3bowls.api.provider.*

import com.just3bowls.api.provider.ServerRouting.Route
import zio.test._

/** 路由算法的金值测试：权重展开、轮询推进、随机边界、空候选。 */
object ServerRoutingSpec extends ZIOSpecDefault {

  private val ab  = List(Route("a", 1), Route("b", 1))
  private val a2b = List(Route("a", 2), Route("b", 1))

  def spec: Spec[Any, Any] = suite("ServerRouting")(
    test("加权展开：全 1 = 等权，2:1 = 按比例") {
      assertTrue(
        ServerRouting.expand(ab) == List("a", "b"),
        ServerRouting.expand(a2b) == List("a", "a", "b")
      )
    },
    test("归一化：空 provider 丢掉、权重至少 1") {
      assertTrue(
        ServerRouting.normalize(List(Route("", 3), Route("a", 0), Route(" b ", 2))) ==
          List(Route("a", 1), Route("b", 2))
      )
    },
    test("轮询：按序推进并回绕") {
      assertTrue(
        ServerRouting.nextRoundRobin(a2b, 0).contains(("a", 1)),
        ServerRouting.nextRoundRobin(a2b, 1).contains(("a", 2)),
        ServerRouting.nextRoundRobin(a2b, 2).contains(("b", 3)),
        // 游标是"序列下标"，越界回绕后归位到 0 的下一位。
        ServerRouting.nextRoundRobin(a2b, 3).contains(("a", 1)),
        ServerRouting.nextRoundRobin(a2b, -1).contains(("b", 3)),
        ServerRouting.nextRoundRobin(Nil, 7).isEmpty
      )
    },
    test("随机：按累计权重落桶，roll 越界被夹住") {
      assertTrue(
        ServerRouting.pickRandom(a2b, 0.0).contains("a"),
        ServerRouting.pickRandom(a2b, 0.5).contains("a"),
        ServerRouting.pickRandom(a2b, 0.7).contains("b"),
        ServerRouting.pickRandom(a2b, 0.999).contains("b"),
        ServerRouting.pickRandom(a2b, 1.0).contains("b"),
        ServerRouting.pickRandom(a2b, -1.0).contains("a"),
        ServerRouting.pickRandom(Nil, 0.5).isEmpty
      )
    },
    test("单候选：轮询与随机都只出它") {
      val one = List(Route("only", 3))
      assertTrue(
        ServerRouting.nextRoundRobin(one, 5).contains(("only", 3)),
        ServerRouting.pickRandom(one, 0.99).contains("only")
      )
    }
  )
}
