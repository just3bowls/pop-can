package com.just3bowls.memory

import com.just3bowls.api.memory.MemoryCandidate
import com.just3bowls.api.memory.MemoryCandidateBatch
import com.just3bowls.api.memory.MemoryForgetSession
import com.just3bowls.api.memory.MemoryProviderRoute
import com.just3bowls.api.memory.MemoryQuery
import com.just3bowls.api.memory.MemorySettings
import com.just3bowls.api.memory.MemoryTurn
import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.api.provider.ServerRouting
import zio.json._
import zio.test._

import java.nio.file.Files
import java.util.Comparator

object MemoryStoreSpec extends ZIOSpecDefault {

  private def withStore[A](body: MemoryStore => A): A = {
    val directory = Files.createTempDirectory("pop-can-memory-test-")
    val store     = new MemoryStore(directory)
    try body(store)
    finally {
      store.close()
      Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    }
  }

  def spec = suite("MemoryStoreSpec")(
    test("global and session memories obey recall scope") {
      withStore { store =>
        store.remember("global", "preference", "The user prefers oolong tea", 8, pinned = true)
        store.remember(
          "session",
          "fact",
          "Today's task uses oolong test data",
          4,
          pinned = false,
          sourceSessionId = "s1"
        )
        val s1 = store.recall(MemoryQuery("s1", "oolong", budgetTokens = 1200))
        val s2 = store.recall(MemoryQuery("s2", "oolong", budgetTokens = 1200))
        assertTrue(
          s1.hits.exists(_.text.contains("prefers oolong")),
          s1.hits.exists(_.text.contains("test data")),
          s2.hits.exists(_.text.contains("prefers oolong")),
          !s2.hits.exists(_.text.contains("test data"))
        )
      }
    },
    test("automatic extraction queues and admits evidence-backed candidates") {
      withStore { store =>
        store.updateSettings(MemorySettings(
          autoExtract = true,
          extractionRoutes = List(MemoryProviderRoute("provider-a", 1))
        ))
        val turn        = MemoryTurn(
          turnId = "t1",
          sessionId = "s1",
          userMessageId = "u1",
          assistantMessageId = "a1",
          userText = "I prefer concise answers.",
          assistantText = "Understood."
        )
        val (_, queued) = store.ingestTurn(turn)
        val job         = store.claimNextJob()
        val accepted    = job.map(_.id).map(id =>
          store.completeJob(
            id,
            MemoryCandidateBatch(List(MemoryCandidate(
              text = "The user prefers concise answers.",
              scope = "global",
              kind = "preference",
              confidence = 0.94,
              importance = 8,
              evidenceQuote = "I prefer concise answers"
            ))).toJson
          )
        ).getOrElse(0)
        val recalled    = store.recall(MemoryQuery("s2", "concise answers"))
        assertTrue(queued.nonEmpty, accepted == 1, recalled.hits.exists(_.text.contains("concise answers")))
      }
    },
    test("extraction jobs use the selected route prompt mode across failover") {
      withStore { store =>
        store.updateSettings(MemorySettings(
          autoExtract = true,
          extractionRoutes = List(
            MemoryProviderRoute("provider-a", 1, RoutePromptMode.Inline),
            MemoryProviderRoute("provider-b", 1, RoutePromptMode.File)
          ),
          extractionStrategy = ServerRouting.StrategyWeighted,
          extractionFailover = ServerRouting.FailoverNext
        ))
        val turn        = MemoryTurn(
          turnId = "t-prompt-mode",
          sessionId = "s1",
          userMessageId = "u1",
          assistantMessageId = "a1",
          userText = "Remember the release channel.",
          assistantText = "It is stable."
        )
        val (_, queued) = store.ingestTurn(turn)
        val first       = store.claimNextJob()
        first.foreach(job => store.retryJob(job.id, "provider-a failed"))
        val second      = store.claimNextJob()

        assertTrue(
          queued.exists(_.promptMode == RoutePromptMode.Inline),
          first.exists(_.promptMode == RoutePromptMode.Inline),
          second.exists(_.promptMode == RoutePromptMode.File)
        )
      }
    },
    test("memory extraction failover moves to another untried provider") {
      withStore { store =>
        store.updateSettings(MemorySettings(
          autoExtract = true,
          extractionRoutes = List(
            MemoryProviderRoute("provider-a", 1),
            MemoryProviderRoute("provider-b", 1),
            MemoryProviderRoute("provider-c", 1)
          ),
          extractionStrategy = ServerRouting.StrategyWeighted,
          extractionFailover = ServerRouting.FailoverNext
        ))
        val turn        = MemoryTurn(
          turnId = "t-failover",
          sessionId = "s1",
          userMessageId = "u1",
          assistantMessageId = "a1",
          userText = "Remember the deployment target.",
          assistantText = "It is staging."
        )
        val (_, queued) = store.ingestTurn(turn)
        val first       = store.claimNextJob()
        first.foreach(job => store.retryJob(job.id, "provider-a failed"))
        val second      = store.claimNextJob()
        second.foreach(job => store.retryJob(job.id, "provider-b failed"))
        val third       = store.claimNextJob()
        val providers   = List(first, second, third).flatMap(_.map(_.provider))
        assertTrue(
          queued.nonEmpty,
          providers.headOption.contains("provider-a"),
          providers.distinct.size == 3,
          providers.toSet == Set("provider-a", "provider-b", "provider-c")
        )
      }
    },
    test("session deletion keeps global memory while source forgetting removes it") {
      withStore { store =>
        val global             = store.remember(
          "global",
          "fact",
          "The project uses SQLite",
          7,
          pinned = false,
          sourceSessionId = "s1",
          sourceTurnId = "t1"
        )
        store.remember("session", "fact", "Session-only note", 5, pinned = false, sourceSessionId = "s1")
        val afterSessionDelete = store.forget(MemoryForgetSession("s1", forgetSources = false))
        val stillGlobal        = store.list().items.exists(_.id == global.id)
        store.forget(MemoryForgetSession("s1", forgetSources = true))
        val afterSourceForget  = store.list().items.exists(_.id == global.id)
        assertTrue(afterSessionDelete == 1, stillGlobal, !afterSourceForget)
      }
    }
  )
}
