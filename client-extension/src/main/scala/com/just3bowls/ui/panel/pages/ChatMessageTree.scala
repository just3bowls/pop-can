package com.just3bowls.ui.panel.pages

import com.just3bowls.model.chat.ChatMessage

/** Read-only projections over the append-only chat message tree. */
private[pages] object ChatMessageTree {
  def byId(messages: List[ChatMessage]): Map[String, ChatMessage] =
    messages.map(message => message.id -> message).toMap

  def pathTo(messages: List[ChatMessage], leafId: Option[String]): List[ChatMessage] = {
    val index                                                       = byId(messages)
    def loop(id: String, acc: List[ChatMessage]): List[ChatMessage] =
      index.get(id) match {
        case Some(message) =>
          message.parentId.filter(index.contains).filterNot(parent => acc.exists(_.id == parent)) match {
            case Some(parent) => loop(parent, message :: acc)
            case None         => message :: acc
          }
        case None          =>
          acc
      }
    leafId.flatMap(index.get).map(message => loop(message.id, Nil)).getOrElse(Nil)
  }

  def subtreeOf(messages: List[ChatMessage], messageId: String): List[ChatMessage] = {
    val childrenOf                                             = messages.groupBy(_.parentId)
    def loop(id: String, seen: Set[String]): List[ChatMessage] =
      if (seen.contains(id)) Nil
      else {
        val self = messages.find(_.id == id).toList
        val kids = childrenOf.getOrElse(Some(id), Nil).flatMap(child => loop(child.id, seen + id))
        self ++ kids
      }
    loop(messageId, Set.empty).sortBy(_.createdAt)
  }
}
