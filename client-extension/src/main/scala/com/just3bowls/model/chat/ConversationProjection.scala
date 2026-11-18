package com.just3bowls.model.chat

/** Stored chat messages with running messages overlaid by id. */
final case class ConversationProjection(
    stored: List[ChatMessage],
    live: Map[String, ChatMessage]
) {
  lazy val visible: List[ChatMessage] = {
    val storedIds = stored.map(_.id).toSet
    val overlaid  = stored.map(message => live.getOrElse(message.id, message))
    val appended  = live.valuesIterator
      .filterNot(message => storedIds.contains(message.id))
      .toList
      .sortBy(message => (message.createdAt, message.id))
    overlaid ++ appended
  }

  def byId: Map[String, ChatMessage] =
    ChatMessageTree.byId(visible)

  def pathTo(leafId: Option[String]): List[ChatMessage] =
    ChatMessageTree.pathTo(visible, leafId)
}
