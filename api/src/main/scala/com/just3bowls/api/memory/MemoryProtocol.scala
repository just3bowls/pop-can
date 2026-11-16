package com.just3bowls.api.memory
import com.just3bowls.api.provider.*

import zio.json._

/**
 * Shared Memory wire model.
 *
 * `MemoryRequestEnvelope` / `MemoryResponseEnvelope` are used over native messaging. The `payload` field carries one of
 * the strongly typed JSON values below; keeping the envelope open-ended avoids a second JSON codec implementation in
 * Scala.js.
 */
final case class MemoryRequestEnvelope(
    requestId: String,
    action: String,
    payload: String = "{}"
)

object MemoryRequestEnvelope {
  given JsonCodec[MemoryRequestEnvelope] = DeriveJsonCodec.gen
}

final case class MemoryResponseEnvelope(
    requestId: String,
    ok: Boolean,
    payload: String = "{}",
    error: String = "",
    `type`: String = "memory_response"
)

object MemoryResponseEnvelope {
  given JsonCodec[MemoryResponseEnvelope] = DeriveJsonCodec.gen
}

final case class MemoryProviderRoute(
    provider: String,
    weight: Int = 1,
    promptMode: String = RoutePromptMode.Inline
)

object MemoryProviderRoute {
  given JsonCodec[MemoryProviderRoute] = DeriveJsonCodec.gen
}

final case class MemorySettings(
    autoExtract: Boolean = false,
    extractionRoutes: List[MemoryProviderRoute] = Nil,
    extractionStrategy: String = ServerRouting.StrategyWeighted,
    extractionFailover: String = ServerRouting.FailoverOff,
    contextBudgetTokens: Int = 1200
)

object MemorySettings {
  val Default: MemorySettings     = MemorySettings()
  val MaxExtractionProviders: Int = 3

  def normalizeRoutes(routes: List[MemoryProviderRoute]): List[MemoryProviderRoute] =
    routes
      .filter(route => Option(route.provider).exists(_.trim.nonEmpty))
      .map(route =>
        MemoryProviderRoute(
          provider = route.provider.trim,
          weight = math.max(1, route.weight),
          promptMode = RoutePromptMode.normalize(route.promptMode)
        )
      )
      .distinctBy(_.provider)
      .take(MaxExtractionProviders)

  def normalize(value: MemorySettings): MemorySettings = {
    val routes = normalizeRoutes(value.extractionRoutes)
    MemorySettings(
      autoExtract = value.autoExtract && routes.nonEmpty,
      extractionRoutes = routes,
      extractionStrategy =
        if (Option(value.extractionStrategy).exists(_.trim.equalsIgnoreCase(ServerRouting.StrategyRandom)))
          ServerRouting.StrategyRandom
        else ServerRouting.StrategyWeighted,
      extractionFailover =
        if (Option(value.extractionFailover).exists(_.trim.equalsIgnoreCase(ServerRouting.FailoverNext)))
          ServerRouting.FailoverNext
        else ServerRouting.FailoverOff,
      contextBudgetTokens = math.max(200, math.min(4000, value.contextBudgetTokens))
    )
  }

  given JsonCodec[MemorySettings] = DeriveJsonCodec.gen
}

/** One stable, user-visible turn. Hidden reasoning, errors and pending attempts never enter Memory. */
final case class MemoryTurn(
    turnId: String,
    sessionId: String,
    userMessageId: String,
    assistantMessageId: String,
    attemptId: String = "",
    provider: String = "",
    mode: String = "chat",
    origin: String = "plugin",
    userText: String = "",
    assistantText: String = "",
    untrustedEvidence: Boolean = false,
    completedAt: Long = 0L
)

object MemoryTurn {
  given JsonCodec[MemoryTurn] = DeriveJsonCodec.gen
}

final case class MemoryQuery(
    sessionId: String,
    query: String,
    recentContext: String = "",
    budgetTokens: Int = 1200
)

object MemoryQuery {
  given JsonCodec[MemoryQuery] = DeriveJsonCodec.gen
}

final case class MemoryHit(
    id: String,
    scope: String,
    kind: String,
    text: String,
    origin: String,
    importance: Int,
    pinned: Boolean,
    score: Double,
    sourceSessionId: String = ""
)

object MemoryHit {
  given JsonCodec[MemoryHit] = DeriveJsonCodec.gen
}

final case class MemoryContext(
    text: String = "",
    hits: List[MemoryHit] = Nil,
    degraded: Boolean = false,
    reason: String = ""
)

object MemoryContext {
  val Empty: MemoryContext = MemoryContext()

  given JsonCodec[MemoryContext] = DeriveJsonCodec.gen
}

final case class MemoryItem(
    id: String,
    scope: String,
    kind: String,
    text: String,
    normalizedKey: String,
    status: String,
    confidence: Double,
    importance: Int,
    pinned: Boolean,
    origin: String,
    sourceSessionId: String = "",
    sourceTurnId: String = "",
    observedAt: Long = 0L,
    validFrom: Long = 0L,
    validTo: Long = 0L
)

object MemoryItem {
  given JsonCodec[MemoryItem] = DeriveJsonCodec.gen
}

final case class MemoryList(
    items: List[MemoryItem] = Nil,
    total: Int = 0
)

object MemoryList {
  given JsonCodec[MemoryList] = DeriveJsonCodec.gen
}

final case class MemoryListRequest(
    scope: String = "",
    status: String = "",
    limit: Int = 200
)

object MemoryListRequest {
  given JsonCodec[MemoryListRequest] = DeriveJsonCodec.gen
}

final case class MemoryStatus(
    enabled: Boolean = true,
    autoExtract: Boolean = false,
    extractionRoutes: List[MemoryProviderRoute] = Nil,
    extractionStrategy: String = ServerRouting.StrategyWeighted,
    extractionFailover: String = ServerRouting.FailoverOff,
    activeCount: Int = 0,
    pendingCount: Int = 0,
    queuedJobs: Int = 0,
    indexHealthy: Boolean = true,
    embeddingIdentity: String = "",
    embeddingReady: Boolean = false,
    embeddingDetail: String = "",
    message: String = ""
)

object MemoryStatus {
  given JsonCodec[MemoryStatus] = DeriveJsonCodec.gen
}

final case class MemoryAck(ok: Boolean = true, message: String = "", count: Int = 0)

object MemoryAck {
  given JsonCodec[MemoryAck] = DeriveJsonCodec.gen
}

/**
 * A candidate proposed by the dedicated extraction model. The engine owns IDs, scope promotion, conflict handling and
 * status; the model cannot set any of those.
 */
final case class MemoryCandidate(
    text: String,
    scope: String = "session",
    kind: String = "fact",
    confidence: Double = 0.0,
    importance: Int = 5,
    evidenceQuote: String = "",
    sensitive: Boolean = false
)

object MemoryCandidate {
  given JsonCodec[MemoryCandidate] = DeriveJsonCodec.gen
}

final case class MemoryCandidateBatch(candidates: List[MemoryCandidate] = Nil)

object MemoryCandidateBatch {
  given JsonCodec[MemoryCandidateBatch] = DeriveJsonCodec.gen
}

final case class MemoryMutation(
    id: String = "",
    scope: String = "global",
    sourceSessionId: String = "",
    kind: String = "fact",
    text: String = "",
    importance: Int = 5,
    pinned: Boolean = false,
    decision: String = ""
)

object MemoryMutation {
  given JsonCodec[MemoryMutation] = DeriveJsonCodec.gen
}

/** Host -> extension: ask the configured provider for a candidate batch. */
final case class MemoryExtractionRequest(
    requestId: String,
    jobId: String,
    provider: String,
    prompt: String,
    promptMode: String = RoutePromptMode.Inline,
    `type`: String = "memory_extract_request"
)

object MemoryExtractionRequest {
  given JsonCodec[MemoryExtractionRequest] = DeriveJsonCodec.gen
}

/** Extension -> host: marker-validated JSON body produced by the extraction provider. */
final case class MemoryExtractionResult(
    requestId: String,
    jobId: String,
    ok: Boolean,
    body: String = "",
    error: String = "",
    `type`: String = "memory_extract_result"
)

object MemoryExtractionResult {
  given JsonCodec[MemoryExtractionResult] = DeriveJsonCodec.gen
}

final case class MemoryForgetSession(sessionId: String, forgetSources: Boolean = false)

object MemoryForgetSession {
  given JsonCodec[MemoryForgetSession] = DeriveJsonCodec.gen
}
