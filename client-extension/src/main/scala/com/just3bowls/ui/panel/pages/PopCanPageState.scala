package com.just3bowls.ui.panel.pages

import com.just3bowls.model.chat.{ChatMessage, ChatSession, SessionFolder}
import com.just3bowls.model.memory.MemoryClient
import com.just3bowls.model.provider.ParserProviderInfo
import com.raquo.laminar.api.L._

import scala.collection.mutable

/** Page-lifetime state shared by the render graph and its command/event controllers. */
private[pages] object PopCanPageState {
  val renderDisposers              = mutable.ArrayBuffer.empty[() => Unit]
  val chatMessagesVar              = Var(List.empty[ChatMessage])
  val liveMessagesVar              = Var(Map.empty[String, ChatMessage])
  val chatSessionsVar              = Var(List.empty[ChatSession])
  val chatFoldersVar               = Var(List.empty[SessionFolder])
  val chatRunningVar               = Var(false)
  val chatRunProgressVar           = Var(Option.empty[com.just3bowls.model.chat.RunProgress])
  val chatRunStartedAtVar          = Var(0.0)
  val chatRunElapsedVar            = Var("")
  val parserProvidersVar           = Var(List.empty[ParserProviderInfo])
  val configDrawerOpenVar          = Var(false)
  val configDrawerMountedVar       = Var(false)
  val configSectionVar             = Var("providers")
  val serverRunningVar             = Var(false)
  val serverStatusVar              = Var("Stopped")
  @volatile var activeMemoryClient = Option.empty[MemoryClient]
}
