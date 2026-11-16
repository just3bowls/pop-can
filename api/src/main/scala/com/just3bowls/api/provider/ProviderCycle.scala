package com.just3bowls.api.provider

/**
 * 状态行「provider」那一枚点一下切下一个的规则（纯逻辑，JVM + Scala.js 通用，`server/test` 有金值）。
 *
 * 集合 = **全部已启用**的 provider，顺序取调用方给的列表序（插件里就是配置面板那份：`ParserProviderLocal.list()` 按 id 排序）； 到尾回绕；当前这家不在列表里（被禁用 /
 * 还没选）就从列表第一个开始。
 */
object ProviderCycle {

  /** @return 下一个要切的 provider id；没有可用的（空列表）返回 None。 */
  def next(activeIds: List[String], currentId: String): Option[String] = {
    val active = Option(activeIds).getOrElse(Nil).map(id => Option(id).getOrElse("").trim).filter(_.nonEmpty).distinct
    if (active.isEmpty) None
    else {
      val current = Option(currentId).getOrElse("").trim
      val at      = active.indexOf(current)
      Some(if (at < 0) active.head else active((at + 1) % active.length))
    }
  }
}
