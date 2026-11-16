package com.just3bowls.background.bootstrap
import com.just3bowls.background.automation.provider.*

import scala.collection.mutable
import scala.concurrent.Promise
import com.just3bowls.model.chat.RawChunkStreamDecoder
private[background] object BackgroundState {
  val activeCdpTasks   = mutable.Map.empty[String, Int]
  val localRawParsers  = mutable.Map.empty[String, Option[LocalRawParser]]
  val rawChunkDecoders = mutable.Map.empty[String, RawChunkStreamDecoder]

  /** 规则判定"这一轮结束"却一个字都没解析出来时的可读原因（key = generationId），供任务链路失败收口用。 */
  val emptyTurnByTask     = mutable.Map.empty[String, String]
  val normalizedSeqByTask = mutable.Map.empty[String, Int]

  /** 每次任务累计收到的原始字符数，透传给前台用于判断"是否捕获到内容"。 */
  val rawCharsByTask                                                   = mutable.Map.empty[String, Int]
  val cancelledCdpTasks                                                = mutable.Set.empty[String]
  var testCancelRequested                                              = false
  var activeTestTabId                                                  = -1
  var activeTestGenerationId                                           = ""
  val tabLocks                                                         = mutable.Map.empty[Int, Promise[Unit]]
  val releasedKeyIds                                                   = mutable.Set.empty[String]
  val markerRoleStates                                                 = mutable.Map.empty[Int, Map[String, Boolean]]
  def reportMarkerRoles(tabId: Int, roles: Map[String, Boolean]): Unit =
    markerRoleStates.update(tabId, roles)
  def invalidateMarkerRoles(tabId: Int, roles: List[String]): Unit     =
    markerRoleStates.get(tabId).foreach { states =>
      markerRoleStates.update(tabId, roles.foldLeft(states)((current, role) => current.updated(role, false)))
    }
  def clearChunkParsingState(generationId: String): Unit               = {
    localRawParsers.remove(generationId)
    rawChunkDecoders.remove(generationId)
    emptyTurnByTask.remove(generationId)
    normalizedSeqByTask.remove(generationId)
    rawCharsByTask.remove(generationId)
  }
}
