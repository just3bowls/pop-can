package com.just3bowls.api.provider

/**
 * Server 转发链路的固定模型名 + 路由算法（JVM + Scala.js 通用，`server/test` 有金值单测）。
 *
 * 客户端（opencode 等）永远只配 `ModelName` 一个模型名；真正落到哪个 provider 由**这个 API key 配的候选列表**决定： 一条或多条 `Route(provider, weight)` +
 * 一个策略（加权轮询 / 按权重随机）+ 一个 failover 开关。
 */
object ServerRouting {

  /** `/v1/models` 唯一暴露的名字，请求里的 model 也用这个名字（只回显，不参与路由）。 */
  val ModelName = "pop-can"

  /** 加权轮询：全 1 = 等权轮询，2:1 = 按比例。 */
  val StrategyWeighted = "weighted"

  /** 按权重随机抽。 */
  val StrategyRandom = "random"

  /** failover：本轮还没给客户端写任何内容就失败时，换下一个候选重试。 */
  val FailoverOff  = "off"
  val FailoverNext = "next"

  final case class Route(provider: String, weight: Int)

  /** 归一化：丢掉空 provider，权重至少 1，保持原顺序。 */
  def normalize(routes: List[Route]): List[Route] =
    routes.filter(route => route.provider.trim.nonEmpty).map(route =>
      Route(route.provider.trim, math.max(1, route.weight))
    )

  /** 按权重展开成轮询序列（`A×2 + B×1` → `[A, A, B]`）。 */
  def expand(routes: List[Route]): List[String] =
    normalize(routes).flatMap(route => List.fill(route.weight)(route.provider))

  /**
   * 加权轮询：取 `cursor` 位置那一个，并给出下一个游标。
   *
   * 游标是**展开序列的下标**：越界（含负数）自动回绕，返回的下一个游标范围是 `1..length`（回绕后从 1 继续）。空候选 → None。
   */
  def nextRoundRobin(routes: List[Route], cursor: Int): Option[(String, Int)] = {
    val sequence = expand(routes)
    if (sequence.isEmpty) None
    else {
      val index = ((cursor % sequence.length) + sequence.length) % sequence.length
      Some((sequence(index), index + 1))
    }
  }

  /** 按权重随机：`roll` ∈ [0, 1)（越界会被夹到合法范围）。空候选 → None。 */
  def pickRandom(routes: List[Route], roll: Double): Option[String] = {
    val normalized = normalize(routes)
    val total      = normalized.map(_.weight).sum
    if (total <= 0) None
    else {
      val target                 = math.min(0.999999999, math.max(0.0, roll)) * total
      var accumulated            = 0.0
      var picked: Option[String] = None
      normalized.foreach { route =>
        if (picked.isEmpty) {
          accumulated += route.weight
          if (target < accumulated) picked = Some(route.provider)
        }
      }
      picked.orElse(normalized.lastOption.map(_.provider))
    }
  }
}
