package com.just3bowls.api.memory

object MemoryLibraryPolicy {

  val ScopeAll            = "all"
  val ScopeGlobal         = "global"
  val ScopeCurrentSession = "current-session"

  val StatusAll     = "all"
  val StatusActive  = "active"
  val StatusPending = "pending"

  final case class Filters(
      query: String = "",
      scope: String = ScopeAll,
      status: String = StatusAll,
      currentSessionId: String = ""
  )

  def apply(items: List[MemoryItem], filters: Filters): List[MemoryItem] =
    items
      .filter(matchesScope(_, filters.scope, filters.currentSessionId))
      .filter(matchesStatus(_, filters.status))
      .filter(matchesQuery(_, filters.query))
      .sortBy(item => (if (item.status == StatusPending) 0 else 1, -item.observedAt, item.id))

  private def matchesScope(item: MemoryItem, scope: String, currentSessionId: String): Boolean =
    scope match {
      case ScopeGlobal         => item.scope == "global"
      case ScopeCurrentSession => item.scope == "session" && item.sourceSessionId == currentSessionId
      case _                   => true
    }

  private def matchesStatus(item: MemoryItem, status: String): Boolean =
    status == StatusAll || item.status == status

  private def matchesQuery(item: MemoryItem, rawQuery: String): Boolean = {
    val query = Option(rawQuery).getOrElse("").trim.toLowerCase
    query.isEmpty || List(item.text, item.normalizedKey, item.kind, item.origin)
      .exists(value => Option(value).getOrElse("").toLowerCase.contains(query))
  }
}
