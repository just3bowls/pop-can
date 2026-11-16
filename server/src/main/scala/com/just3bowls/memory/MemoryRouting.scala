package com.just3bowls.memory

import com.just3bowls.api.memory.{MemoryProviderRoute, MemorySettings}
import com.just3bowls.api.provider.ServerRouting

/** Pure normalization and provider selection rules for Memory extraction jobs. */
private[memory] object MemoryRouting {
  def normalizeRoutes(routes: List[MemoryProviderRoute]): List[MemoryProviderRoute] =
    MemorySettings.normalizeRoutes(routes)

  def normalizeStrategy(value: String): String =
    if (Option(value).exists(_.trim.equalsIgnoreCase(ServerRouting.StrategyRandom)))
      ServerRouting.StrategyRandom
    else ServerRouting.StrategyWeighted

  def normalizeFailover(value: String): String =
    if (Option(value).exists(_.trim.equalsIgnoreCase(ServerRouting.FailoverNext)))
      ServerRouting.FailoverNext
    else ServerRouting.FailoverOff

  def select(
      routes: List[MemoryProviderRoute],
      strategy: String,
      excluded: Set[String],
      cursor: Int
  ): Option[(String, Int)] = {
    val available = normalizeRoutes(routes)
      .filterNot(route => excluded.contains(route.provider))
      .map(route => ServerRouting.Route(route.provider, route.weight))
    if (available.isEmpty) None
    else if (normalizeStrategy(strategy) == ServerRouting.StrategyRandom)
      ServerRouting.pickRandom(available, Math.random()).map(_ -> cursor)
    else ServerRouting.nextRoundRobin(available, cursor)
  }
}
