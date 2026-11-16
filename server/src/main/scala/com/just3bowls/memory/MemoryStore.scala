package com.just3bowls.memory

import com.just3bowls.api.memory.MemoryCandidate
import com.just3bowls.api.memory.MemoryCandidateBatch
import com.just3bowls.api.memory.MemoryForgetSession
import com.just3bowls.api.memory.MemoryHit
import com.just3bowls.api.memory.MemoryItem
import com.just3bowls.api.memory.MemoryList
import com.just3bowls.api.memory.MemoryMutation
import com.just3bowls.api.memory.MemoryProviderRoute
import com.just3bowls.api.memory.MemoryQuery
import com.just3bowls.api.memory.MemorySettings
import com.just3bowls.api.memory.MemoryStatus
import com.just3bowls.api.memory.MemoryTurn
import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.api.provider.ServerRouting
import zio.json._

import java.nio.file.{Files, Path}
import java.sql.{DriverManager, PreparedStatement, ResultSet}
import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.util.Using

final case class MemoryExtractionJob(
    id: String,
    turnId: String,
    provider: String,
    prompt: String,
    attempts: Int,
    promptMode: String = RoutePromptMode.Inline
)

/** SQLite is the canonical graph/evidence store; Lucene is always rebuildable from it. */
final class MemoryStore(dataDir: Path) extends AutoCloseable {
  Files.createDirectories(dataDir)
  Class.forName("org.sqlite.JDBC")

  private val dbPath            = dataDir.resolve("memory.sqlite")
  private val embeddingProvider = EmbeddingProvider.resolve(dataDir)
  private val index             = new MemoryIndex(dataDir.resolve("lucene"), embeddingProvider)
  private val conn              = DriverManager.getConnection(s"jdbc:sqlite:${dbPath.toAbsolutePath}")
  private var closed            = false
  private var extractionCursor  = 0

  initialize()

  private def initialize(): Unit = synchronized {
    MemorySchema.install(conn)
    val previousIdentity = query(
      "select embedding_identity, dimension from memory_index_meta where id=1"
    ) { rs =>
      if (rs.next()) Some(rs.getString(1) -> rs.getInt(2)) else None
    }.flatten
    val mustRebuild      =
      previousIdentity.forall { case (identity, dimension) =>
        identity != index.embeddingIdentity || dimension != embeddingProvider.dimension
      }
    if (mustRebuild || index.documentCount == 0) rebuildIndex()
    conn.setAutoCommit(false)
    conn.commit()
    conn.setAutoCommit(true)
  }

  def settings(): MemorySettings = synchronized {
    query(
      "select auto_extract, extraction_routes, extraction_strategy, extraction_failover, " +
        "context_budget_tokens from memory_settings where id=1"
    ) { rs =>
      if (!rs.next()) MemorySettings.Default
      else {
        val storedRoutes =
          Option(rs.getString(2)).getOrElse("[]").fromJson[List[MemoryProviderRoute]].getOrElse(Nil)
        MemorySettings.normalize(MemorySettings(
          autoExtract = rs.getInt(1) != 0,
          extractionRoutes = storedRoutes,
          extractionStrategy = rs.getString(3),
          extractionFailover = rs.getString(4),
          contextBudgetTokens = rs.getInt(5)
        ))
      }
    }.getOrElse(MemorySettings.Default)
  }

  def updateSettings(value: MemorySettings): MemorySettings = synchronized {
    val normalized = MemorySettings.normalize(value)
    update(
      "update memory_settings set auto_extract=?, extraction_routes=?, extraction_strategy=?, " +
        "extraction_failover=?, context_budget_tokens=? where id=1"
    ) { ps =>
      ps.setInt(1, if (normalized.autoExtract) 1 else 0)
      ps.setString(2, normalized.extractionRoutes.toJson)
      ps.setString(3, normalized.extractionStrategy)
      ps.setString(4, normalized.extractionFailover)
      ps.setInt(5, normalized.contextBudgetTokens)
    }
    normalized
  }

  def ingestTurn(turn: MemoryTurn): (MemoryTurn, Option[MemoryExtractionJob]) = synchronized {
    val now        = if (turn.completedAt > 0) turn.completedAt else Instant.now().toEpochMilli
    val normalized = turn.copy(
      turnId = turn.turnId.trim,
      sessionId = turn.sessionId.trim,
      userText = turn.userText.trim,
      assistantText = turn.assistantText.trim,
      completedAt = now
    )
    if (normalized.turnId.isEmpty || normalized.sessionId.isEmpty || isForgotten(normalized.sessionId))
      normalized -> None
    else {
      update(
        """insert or ignore into memory_episodes(
          |turn_id, session_id, user_message_id, assistant_message_id, attempt_id, provider, mode, origin,
          |user_text, assistant_text, untrusted, completed_at, created_at
          |) values(?,?,?,?,?,?,?,?,?,?,?,?,?)""".stripMargin
      ) { ps =>
        ps.setString(1, normalized.turnId)
        ps.setString(2, normalized.sessionId)
        ps.setString(3, normalized.userMessageId)
        ps.setString(4, normalized.assistantMessageId)
        ps.setString(5, normalized.attemptId)
        ps.setString(6, normalized.provider)
        ps.setString(7, normalized.mode)
        ps.setString(8, normalized.origin)
        ps.setString(9, normalized.userText)
        ps.setString(10, normalized.assistantText)
        ps.setInt(11, if (normalized.untrustedEvidence) 1 else 0)
        ps.setLong(12, now)
        ps.setLong(13, now)
      }
      val current = settings()
      val job     =
        if (current.autoExtract && current.extractionRoutes.nonEmpty)
          enqueueExtraction(normalized, current, now)
        else None
      normalized -> job
    }
  }

  private def enqueueExtraction(
      turn: MemoryTurn,
      settings: MemorySettings,
      now: Long
  ): Option[MemoryExtractionJob] = {
    val existing = preparedQuery(
      "select id, provider, prompt, attempts, routes_json from memory_jobs " +
        "where turn_id=? and state in ('queued','processing','retry')"
    ) { ps =>
      ps.setString(1, turn.turnId)
    } { rs =>
      if (rs.next()) {
        val provider = rs.getString(2)
        val routes   =
          Option(rs.getString(5)).getOrElse("[]").fromJson[List[MemoryProviderRoute]].getOrElse(Nil)
        Some(MemoryExtractionJob(
          rs.getString(1),
          turn.turnId,
          provider,
          rs.getString(3),
          rs.getInt(4),
          promptModeFor(routes, provider)
        ))
      } else None
    }.flatten
    existing.orElse {
      val id     = UUID.randomUUID().toString
      val prompt = MemoryExtractionPrompt.build(turn)
      MemoryRouting.select(settings.extractionRoutes, settings.extractionStrategy, Set.empty, extractionCursor).map {
        case (provider, nextCursor) =>
          extractionCursor = nextCursor
          val tried      = List(provider)
          val promptMode =
            settings.extractionRoutes.find(_.provider == provider).map(_.promptMode).getOrElse(RoutePromptMode.Inline)
          update(
            """insert into memory_jobs(
              |id, turn_id, provider, routes_json, strategy, failover, tried_providers_json,
              |prompt, state, attempts, last_error, created_at, updated_at
              |) values(?,?,?,?,?,?,?,?, 'queued', 0, '', ?, ?)""".stripMargin
          ) { ps =>
            ps.setString(1, id)
            ps.setString(2, turn.turnId)
            ps.setString(3, provider)
            ps.setString(4, settings.extractionRoutes.toJson)
            ps.setString(5, settings.extractionStrategy)
            ps.setString(6, settings.extractionFailover)
            ps.setString(7, tried.toJson)
            ps.setString(8, prompt)
            ps.setLong(9, now)
            ps.setLong(10, now)
          }
          MemoryExtractionJob(id, turn.turnId, provider, prompt, 0, RoutePromptMode.normalize(promptMode))
      }
    }
  }

  def claimNextJob(): Option[MemoryExtractionJob] = synchronized {
    val next = query(
      "select id, turn_id, provider, prompt, attempts, routes_json from memory_jobs " +
        "where state in ('queued','retry') " +
        "order by created_at limit 1"
    ) { rs =>
      if (rs.next()) {
        val provider = rs.getString(3)
        val routes   =
          Option(rs.getString(6)).getOrElse("[]").fromJson[List[MemoryProviderRoute]].getOrElse(Nil)
        Some(MemoryExtractionJob(
          rs.getString(1),
          rs.getString(2),
          provider,
          rs.getString(4),
          rs.getInt(5),
          promptModeFor(routes, provider)
        ))
      } else None
    }.flatten
    next.foreach { job =>
      update("update memory_jobs set state='processing', attempts=attempts+1, updated_at=? where id=?") { ps =>
        ps.setLong(1, Instant.now().toEpochMilli)
        ps.setString(2, job.id)
      }
    }
    next
  }

  def retryJob(jobId: String, error: String): Unit = synchronized {
    val job = preparedQuery(
      "select provider, routes_json, strategy, failover, tried_providers_json, attempts " +
        "from memory_jobs where id=?"
    ) { ps =>
      ps.setString(1, jobId)
    } { rs =>
      if (rs.next())
        Some((
          rs.getString(1),
          Option(rs.getString(2)).getOrElse("[]").fromJson[List[MemoryProviderRoute]].getOrElse(Nil),
          MemoryRouting.normalizeStrategy(rs.getString(3)),
          MemoryRouting.normalizeFailover(rs.getString(4)),
          Option(rs.getString(5)).getOrElse("[]").fromJson[List[String]].getOrElse(Nil),
          rs.getInt(6)
        ))
      else None
    }.flatten
    job.foreach { case (currentProvider, storedRoutes, strategy, failover, tried, attempts) =>
      val routes   = if (storedRoutes.nonEmpty) storedRoutes else List(MemoryProviderRoute(currentProvider, 1))
      val triedNow = (tried :+ currentProvider).distinct
      val next     =
        if (failover == ServerRouting.FailoverNext)
          MemoryRouting
            .select(routes, strategy, triedNow.toSet, extractionCursor)
            .map { case (provider, nextCursor) =>
              extractionCursor = nextCursor
              provider
            }
        else Some(currentProvider)
      val state    =
        if (next.isEmpty || (failover == ServerRouting.FailoverOff && attempts >= 3)) "failed"
        else "retry"
      update(
        "update memory_jobs set provider=?, tried_providers_json=?, state=?, last_error=?, updated_at=? where id=?"
      ) { ps =>
        ps.setString(1, next.getOrElse(currentProvider))
        ps.setString(2, (if (next.nonEmpty) triedNow else tried).toJson)
        ps.setString(3, state)
        ps.setString(4, error.take(1000))
        ps.setLong(5, Instant.now().toEpochMilli)
        ps.setString(6, jobId)
      }
    }
  }

  def failJob(jobId: String, error: String): Unit = synchronized {
    update(
      "update memory_jobs set state='failed', last_error=?, updated_at=? where id=?"
    ) { ps =>
      ps.setString(1, error.take(1000))
      ps.setLong(2, Instant.now().toEpochMilli)
      ps.setString(3, jobId)
    }
  }

  def completeJob(jobId: String, body: String): Int = synchronized {
    val job  = jobById(jobId)
    val turn = job.flatMap(value => episode(value.turnId))
    turn match {
      case None         =>
        failJob(jobId, "source turn no longer exists")
        0
      case Some(source) =>
        val parsed = parseCandidateBody(body)
        parsed match {
          case Left(error)  =>
            retryJob(jobId, error)
            0
          case Right(batch) =>
            val accepted = batch.candidates.take(8).count(candidate => admitCandidate(source, candidate))
            update("update memory_jobs set state='done', last_error='', updated_at=? where id=?") { ps =>
              ps.setLong(1, Instant.now().toEpochMilli)
              ps.setString(2, jobId)
            }
            accepted
        }
    }
  }

  def remember(
      scope: String,
      kind: String,
      text: String,
      importance: Int,
      pinned: Boolean,
      sourceSessionId: String = "",
      sourceTurnId: String = ""
  ): MemoryItem = synchronized {
    val effectiveScope = MemoryPolicy.normalizeScope(scope)
    val effectiveKind  = MemoryPolicy.normalizeKind(kind)
    val cleanText      = Option(text).getOrElse("").trim.take(500)
    if (cleanText.isEmpty) throw new IllegalArgumentException("Memory text is empty")
    if (MemoryPolicy.containsSecret(cleanText))
      throw new IllegalArgumentException("Sensitive credentials cannot be stored in Memory")
    val now            = Instant.now().toEpochMilli
    val key            = MemoryPolicy.canonicalKey(effectiveScope, effectiveKind, cleanText)
    val existing       = findByKey(effectiveScope, sourceSessionId, key, activeOnly = true)
    val itemId         = existing.map(_.id).getOrElse(UUID.randomUUID().toString)
    if (existing.nonEmpty) {
      update(
        """update memory_nodes set text=?, importance=?, pinned=?, origin='explicit', status='active',
          |source_session_id=?, source_turn_id=?, observed_at=?, valid_from=?, valid_to=0, updated_at=? where id=?""".stripMargin
      ) { ps =>
        ps.setString(1, cleanText)
        ps.setInt(2, math.max(1, math.min(10, importance)))
        ps.setInt(3, if (pinned) 1 else 0)
        ps.setString(4, sourceSessionId)
        ps.setString(5, sourceTurnId)
        ps.setLong(6, now)
        ps.setLong(7, now)
        ps.setLong(8, now)
        ps.setString(9, itemId)
      }
    } else {
      insertNode(
        id = itemId,
        scope = effectiveScope,
        sessionId = sourceSessionId,
        kind = effectiveKind,
        text = cleanText,
        key = key,
        status = MemoryPolicy.ActiveStatus,
        confidence = 1.0,
        importance = importance,
        pinned = pinned,
        origin = MemoryPolicy.ExplicitOrigin,
        sourceSessionId = sourceSessionId,
        sourceTurnId = sourceTurnId,
        now = now
      )
    }
    val item           = itemById(itemId).getOrElse(throw new IllegalStateException("Memory write disappeared"))
    index.upsert(item)
    revision(itemId, "remember", "", Some(item))
    item
  }

  def recall(queryValue: MemoryQuery): com.just3bowls.api.memory.MemoryContext = synchronized {
    val current       = settings()
    val budget        = math.max(
      200,
      math.min(4000, Option(queryValue.budgetTokens).filter(_ > 0).getOrElse(current.contextBudgetTokens))
    )
    val pinned        = activeForScope(queryValue.sessionId).filter(_.pinned)
    val indexed       =
      try index.search(queryValue.query + "\n" + queryValue.recentContext, queryValue.sessionId, 80)
      catch { case _: Throwable => Nil }
    val byId          = activeForScope(queryValue.sessionId).map(item => item.id -> item).toMap
    val candidates    = indexed.flatMap { case (id, score) => byId.get(id).map(item => item -> score) }
    val graphExpanded = relatedItems(candidates.take(12).map(_._1.id).toSet)
      .filter(item => !candidates.exists(_._1.id == item.id))
      .map(_ -> 0.08)
    val fallback      =
      if (candidates.nonEmpty) Nil
      else lexicalFallback(queryValue.query, queryValue.sessionId).map(item => item -> 0.1)
    val ordered       = diversify(
      (candidates ++ fallback ++ graphExpanded).groupMapReduce(_._1.id)(identity)((left, right) =>
        if (left._2 >= right._2) left else right
      ).values.toList,
      budget
    )
    val withPinned    = (pinned.map(item => item -> Double.MaxValue) ++ ordered).distinctBy(_._1.id)
    val hits          = withPinned
      .foldLeft((List.empty[MemoryHit], 0)) { case ((acc, chars), (item, score)) =>
        val nextChars = chars + item.text.length
        if (acc.nonEmpty && nextChars > budget * 4) (acc, chars)
        else (
          acc :+ MemoryHit(
            id = item.id,
            scope = item.scope,
            kind = item.kind,
            text = item.text,
            origin = item.origin,
            importance = item.importance,
            pinned = item.pinned,
            score = score,
            sourceSessionId = if (item.scope == MemoryPolicy.SessionScope) item.sourceSessionId else ""
          ),
          nextChars
        )
      }
      ._1
    com.just3bowls.api.memory.MemoryContext(
      text = MemoryPolicy.renderContext(hits),
      hits = hits,
      degraded = indexed.isEmpty && candidates.isEmpty && queryValue.query.trim.nonEmpty
    )
  }

  def list(scope: String = "", status: String = "", limit: Int = 200): MemoryList = synchronized {
    val where   = mutable.ArrayBuffer.empty[String]
    val values  = mutable.ArrayBuffer.empty[String]
    if (scope.trim.nonEmpty) {
      where += "n.scope_type=?"
      values += MemoryPolicy.normalizeScope(scope)
    }
    if (status.trim.nonEmpty) {
      where += "n.status=?"
      values += status.trim
    }
    val clause  = if (where.isEmpty) "" else s" where ${where.mkString(" and ")}"
    val bounded = math.max(1, math.min(1000, limit))
    val items   = preparedQuery(
      s"select ${nodeColumns} from memory_nodes n$clause order by n.pinned desc, n.updated_at desc limit ?"
    ) { ps =>
      values.zipWithIndex.foreach { case (value, index) => ps.setString(index + 1, value) }
      ps.setInt(values.size + 1, bounded)
    } { rs =>
      val builder = List.newBuilder[MemoryItem]
      while (rs.next()) builder += readNode(rs)
      builder.result()
    }.getOrElse(Nil)
    MemoryList(items, items.size)
  }

  def mutate(value: MemoryMutation): Option[MemoryItem] = synchronized {
    Option(value.decision).getOrElse("").trim.toLowerCase match {
      case "approve"       =>
        val item = itemById(value.id)
        item.foreach(current => setStatus(current.id, MemoryPolicy.ActiveStatus))
        itemById(value.id)
      case "reject"        =>
        itemById(value.id).foreach(current => setStatus(current.id, MemoryPolicy.RejectedStatus))
        itemById(value.id)
      case "delete"        =>
        itemById(value.id).foreach { current =>
          index.delete(current.id)
          update("delete from memory_nodes where id=?")(ps => ps.setString(1, current.id))
          update("delete from memory_candidates where node_id=?")(ps => ps.setString(1, current.id))
          revision(current.id, "delete", current.toJson, None)
        }
        None
      case "pin" | "unpin" =>
        val item = itemById(value.id)
        item.foreach { current =>
          update("update memory_nodes set pinned=?, updated_at=? where id=?") { ps =>
            ps.setInt(1, if (value.decision.equalsIgnoreCase("pin")) 1 else 0)
            ps.setLong(2, Instant.now().toEpochMilli)
            ps.setString(3, current.id)
          }
          itemById(current.id).foreach(index.upsert)
        }
        itemById(value.id)
      case "edit"          =>
        itemById(value.id).map { current =>
          val text = Option(value.text).getOrElse("").trim.take(500)
          if (text.isEmpty) throw new IllegalArgumentException("Memory text is empty")
          if (MemoryPolicy.containsSecret(text))
            throw new IllegalArgumentException("Sensitive credentials cannot be stored in Memory")
          val key  = MemoryPolicy.canonicalKey(current.scope, current.kind, text)
          update(
            "update memory_nodes set text=?, normalized_key=?, importance=?, updated_at=? where id=?"
          ) { ps =>
            ps.setString(1, text)
            ps.setString(2, key)
            ps.setInt(3, math.max(1, math.min(10, value.importance)))
            ps.setLong(4, Instant.now().toEpochMilli)
            ps.setString(5, current.id)
          }
          val next = itemById(current.id).get
          index.upsert(next)
          revision(current.id, "edit", current, next)
          next
        }
      case _               => itemById(value.id)
    }
  }

  def forget(value: MemoryForgetSession): Int = synchronized {
    val sessionId = value.sessionId.trim
    if (sessionId.isEmpty) 0
    else {
      val ids =
        if (value.forgetSources)
          preparedQuery(
            "select id from memory_nodes where (scope_type='session' and source_session_id=?) " +
              "or (scope_type='global' and source_session_id=?)"
          ) { ps =>
            ps.setString(1, sessionId)
            ps.setString(2, sessionId)
          } { rs =>
            val out = List.newBuilder[String]
            while (rs.next()) out += rs.getString(1)
            out.result()
          }.getOrElse(Nil)
        else
          preparedQuery("select id from memory_nodes where scope_type='session' and source_session_id=?") { ps =>
            ps.setString(1, sessionId)
          } { rs =>
            val out = List.newBuilder[String]
            while (rs.next()) out += rs.getString(1)
            out.result()
          }.getOrElse(Nil)
      ids.foreach { id =>
        index.delete(id)
        update("delete from memory_nodes where id=?")(ps => ps.setString(1, id))
        update("delete from memory_candidates where node_id=?")(ps => ps.setString(1, id))
      }
      update("delete from memory_jobs where turn_id in (select turn_id from memory_episodes where session_id=?)") {
        ps =>
          ps.setString(1, sessionId)
      }
      if (value.forgetSources)
        update("insert or replace into memory_forgotten_sessions(session_id, forgotten_at) values(?, ?)") { ps =>
          ps.setString(1, sessionId)
          ps.setLong(2, Instant.now().toEpochMilli)
        }
      ids.size
    }
  }

  def status(): MemoryStatus = synchronized {
    val current = settings()
    val counts  = query(
      """select
        |sum(case when status='active' then 1 else 0 end),
        |sum(case when status='pending' then 1 else 0 end),
        |(select count(*) from memory_jobs where state in ('queued','processing','retry'))
        |from memory_nodes""".stripMargin
    ) { rs =>
      if (rs.next()) (rs.getInt(1), rs.getInt(2), rs.getInt(3)) else (0, 0, 0)
    }.getOrElse((0, 0, 0))
    MemoryStatus(
      enabled = true,
      autoExtract = current.autoExtract && current.extractionRoutes.nonEmpty,
      extractionRoutes = current.extractionRoutes,
      extractionStrategy = current.extractionStrategy,
      extractionFailover = current.extractionFailover,
      activeCount = counts._1,
      pendingCount = counts._2,
      queuedJobs = counts._3,
      indexHealthy = true,
      embeddingIdentity = index.embeddingIdentity,
      embeddingReady = index.embeddingReady,
      embeddingDetail = index.embeddingDetail,
      message = ""
    )
  }

  def rebuildIndex(): Int = synchronized {
    val items = query(s"select $nodeColumns from memory_nodes n") { rs =>
      val out = List.newBuilder[MemoryItem]
      while (rs.next()) out += readNode(rs)
      out.result()
    }.getOrElse(Nil)
    index.clearAndRebuild(items)
    update(
      """insert into memory_index_meta(id, embedding_identity, dimension, model_hash, schema_version)
        |values(1, ?, ?, '', 1)
        |on conflict(id) do update set embedding_identity=excluded.embedding_identity,
        |dimension=excluded.dimension, model_hash=excluded.model_hash,
        |schema_version=excluded.schema_version""".stripMargin
    ) { ps =>
      ps.setString(1, index.embeddingIdentity)
      ps.setInt(2, embeddingProvider.dimension)
    }
    items.size
  }

  private def admitCandidate(turn: MemoryTurn, candidate: MemoryCandidate): Boolean = {
    val preliminaryKey = MemoryPolicy.canonicalKey(candidate.scope, candidate.kind, candidate.text)
    val conflict       = hasConflict(candidate.scope, turn.sessionId, preliminaryKey, candidate.text)
    val admission      = MemoryPolicy.admit(turn, candidate, conflict)
    if (!admission.accepted) return false
    val existing       = findByKey(admission.scope, turn.sessionId, admission.normalizedKey, activeOnly = false)
    if (existing.exists(item => item.status == MemoryPolicy.ActiveStatus && item.text == admission.text)) {
      mergeSource(existing.get.id, turn.turnId, admission.evidenceQuote, admission.origin)
      return false
    }
    if (conflict && admission.status == MemoryPolicy.ActiveStatus) {
      val id = UUID.randomUUID().toString
      insertNode(
        id,
        admission.scope,
        turn.sessionId,
        admission.kind,
        admission.text,
        admission.normalizedKey,
        MemoryPolicy.PendingStatus,
        admission.confidence,
        admission.importance,
        pinned = false,
        admission.origin,
        turn.sessionId,
        turn.turnId,
        Instant.now().toEpochMilli
      )
      recordCandidate(id, turn.turnId, admission.reason)
      mergeSource(id, turn.turnId, admission.evidenceQuote, admission.origin)
      itemById(id).foreach(index.upsert)
      false
    } else {
      val id   = UUID.randomUUID().toString
      insertNode(
        id,
        admission.scope,
        turn.sessionId,
        admission.kind,
        admission.text,
        admission.normalizedKey,
        admission.status,
        admission.confidence,
        admission.importance,
        pinned = false,
        admission.origin,
        turn.sessionId,
        turn.turnId,
        Instant.now().toEpochMilli
      )
      if (admission.status == MemoryPolicy.PendingStatus) recordCandidate(id, turn.turnId, admission.reason)
      mergeSource(id, turn.turnId, admission.evidenceQuote, admission.origin)
      val item = itemById(id)
      item.foreach(index.upsert)
      item.foreach { value =>
        linkSameTurn(value.id, turn.turnId)
        revision(value.id, "extract", "", Some(value))
      }
      true
    }
  }

  private def linkSameTurn(nodeId: String, turnId: String): Unit = {
    val related = preparedQuery(
      "select id from memory_nodes where source_turn_id=? and id<>? and status='active' and valid_to=0"
    ) { ps =>
      ps.setString(1, turnId)
      ps.setString(2, nodeId)
    } { rs =>
      val out = List.newBuilder[String]
      while (rs.next()) out += rs.getString(1)
      out.result()
    }.getOrElse(Nil)
    related.foreach { peer =>
      List(nodeId -> peer, peer -> nodeId).foreach { case (from, to) =>
        update(
          "insert or ignore into memory_edges(id, from_node_id, to_node_id, relation, confidence, valid_from, valid_to, source_turn_id) " +
            "values(?,?,?,?,?,?,0,?)"
        ) { ps =>
          ps.setString(1, s"$from:co-occurs:$to")
          ps.setString(2, from)
          ps.setString(3, to)
          ps.setString(4, "co_occurs")
          ps.setDouble(5, 0.75)
          ps.setLong(6, Instant.now().toEpochMilli)
          ps.setString(7, turnId)
        }
      }
    }
  }

  private def relatedItems(nodeIds: Set[String]): List[MemoryItem] =
    if (nodeIds.isEmpty) Nil
    else {
      val placeholders = List.fill(nodeIds.size)("?").mkString(",")
      preparedQuery(
        s"select distinct $nodeColumns from memory_edges e join memory_nodes n on n.id=e.to_node_id " +
          s"where e.from_node_id in ($placeholders) and e.valid_to=0 and n.status='active' and n.valid_to=0"
      ) { ps =>
        nodeIds.toList.zipWithIndex.foreach { case (id, index) => ps.setString(index + 1, id) }
      } { rs =>
        val out = List.newBuilder[MemoryItem]
        while (rs.next()) out += readNode(rs)
        out.result()
      }.getOrElse(Nil)
    }

  private def hasConflict(scope: String, sessionId: String, key: String, text: String): Boolean =
    findByKey(MemoryPolicy.normalizeScope(scope), sessionId, key, activeOnly = true)
      .exists(existing => MemoryPolicy.normalizeText(existing.text) != MemoryPolicy.normalizeText(text))

  private def parseCandidateBody(body: String): Either[String, MemoryCandidateBatch] = {
    val trimmed  = Option(body).getOrElse("").trim
    val unfenced =
      if (trimmed.startsWith("```")) {
        trimmed.replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("(?s)\\s*```$", "").trim
      } else trimmed
    unfenced.fromJson[MemoryCandidateBatch].left.map(error => s"Invalid extraction JSON: $error")
  }

  private def episode(turnId: String): Option[MemoryTurn] =
    preparedQuery(
      """select turn_id, session_id, user_message_id, assistant_message_id, attempt_id, provider, mode, origin,
        |user_text, assistant_text, untrusted, completed_at from memory_episodes where turn_id=?""".stripMargin
    ) { ps =>
      ps.setString(1, turnId)
    } { rs =>
      if (rs.next())
        Some(
          MemoryTurn(
            turnId = rs.getString(1),
            sessionId = rs.getString(2),
            userMessageId = rs.getString(3),
            assistantMessageId = rs.getString(4),
            attemptId = rs.getString(5),
            provider = rs.getString(6),
            mode = rs.getString(7),
            origin = rs.getString(8),
            userText = rs.getString(9),
            assistantText = rs.getString(10),
            untrustedEvidence = rs.getInt(11) != 0,
            completedAt = rs.getLong(12)
          )
        )
      else None
    }.flatten

  private def jobById(id: String): Option[MemoryExtractionJob] =
    preparedQuery(
      "select id, turn_id, provider, prompt, attempts, routes_json from memory_jobs where id=?"
    ) { ps =>
      ps.setString(1, id)
    } { rs =>
      if (rs.next()) {
        val provider = rs.getString(3)
        val routes   =
          Option(rs.getString(6)).getOrElse("[]").fromJson[List[MemoryProviderRoute]].getOrElse(Nil)
        Some(MemoryExtractionJob(
          rs.getString(1),
          rs.getString(2),
          provider,
          rs.getString(4),
          rs.getInt(5),
          promptModeFor(routes, provider)
        ))
      } else None
    }.flatten

  private def promptModeFor(routes: List[MemoryProviderRoute], provider: String): String =
    routes.find(_.provider == provider).map(_.promptMode).map(RoutePromptMode.normalize).getOrElse(
      RoutePromptMode.Inline
    )

  private def activeForScope(sessionId: String): List[MemoryItem] = {
    val sql =
      if (sessionId.nonEmpty)
        s"select $nodeColumns from memory_nodes n where n.status='active' and n.valid_to=0 " +
          "and (n.scope_type='global' or (n.scope_type='session' and n.source_session_id=?))"
      else s"select $nodeColumns from memory_nodes n where n.status='active' and n.valid_to=0 and n.scope_type='global'"
    preparedQuery(sql) { ps =>
      if (sessionId.nonEmpty) ps.setString(1, sessionId)
    } { rs =>
      val out = List.newBuilder[MemoryItem]
      while (rs.next()) out += readNode(rs)
      out.result()
    }.getOrElse(Nil)
  }

  private def lexicalFallback(queryText: String, sessionId: String): List[MemoryItem] = {
    val needle = MemoryPolicy.normalizeText(queryText).take(80)
    if (needle.isEmpty) Nil
    else
      activeForScope(sessionId)
        .map(item => item -> textOverlap(needle, MemoryPolicy.normalizeText(item.text)))
        .filter(_._2 > 0)
        .sortBy { case (_, score) => -score }
        .take(40)
        .map(_._1)
  }

  private def diversify(items: List[(MemoryItem, Double)], budgetTokens: Int): List[(MemoryItem, Double)] = {
    val ordered   = items.sortBy { case (item, score) =>
      -(
        score + item.importance.toDouble / 100.0 + (if (item.pinned) 10.0 else 0.0)
      )
    }
    val selected  = mutable.ArrayBuffer.empty[(MemoryItem, Double)]
    val remaining = mutable.ArrayBuffer.from(ordered)
    while (remaining.nonEmpty && selected.size < 40) {
      val best = remaining.maxBy { case (item, score) =>
        val redundancy =
          selected.map { case (existing, _) =>
            textOverlap(MemoryPolicy.normalizeText(item.text), MemoryPolicy.normalizeText(existing.text))
          }.maxOption.getOrElse(0.0)
        score - redundancy * 0.25
      }
      selected += best
      remaining -= best
      if (selected.map(_._1.text.length).sum > budgetTokens * 4) remaining.clear()
    }
    selected.toList
  }

  private def textOverlap(left: String, right: String): Double = {
    val a = left.split("\\s+").filter(_.nonEmpty).toSet
    val b = right.split("\\s+").filter(_.nonEmpty).toSet
    if (a.isEmpty || b.isEmpty) 0.0
    else a.intersect(b).size.toDouble / math.max(a.size, b.size)
  }

  private def findByKey(scope: String, sessionId: String, key: String, activeOnly: Boolean): Option[MemoryItem] = {
    val scopeClause  =
      if (scope == MemoryPolicy.SessionScope) "and source_session_id=?" else ""
    val activeClause = if (activeOnly) "and status='active' and valid_to=0" else ""
    preparedQuery(
      s"select $nodeColumns from memory_nodes n where normalized_key=? and scope_type=? $scopeClause $activeClause " +
        "order by pinned desc, updated_at desc limit 1"
    ) { ps =>
      var index = 1
      ps.setString(index, key); index += 1
      ps.setString(index, scope); index += 1
      if (scope == MemoryPolicy.SessionScope) ps.setString(index, sessionId)
    } { rs =>
      if (rs.next()) Some(readNode(rs)) else None
    }.flatten
  }

  private def itemById(id: String): Option[MemoryItem] =
    preparedQuery(s"select $nodeColumns from memory_nodes n where id=?") { ps =>
      ps.setString(1, id)
    } { rs =>
      if (rs.next()) Some(readNode(rs)) else None
    }.flatten

  private def setStatus(id: String, status: String): Unit = {
    val before = itemById(id)
    update("update memory_nodes set status=?, updated_at=? where id=?") { ps =>
      ps.setString(1, status)
      ps.setLong(2, Instant.now().toEpochMilli)
      ps.setString(3, id)
    }
    if (status == MemoryPolicy.RejectedStatus)
      update("delete from memory_candidates where node_id=?")(ps => ps.setString(1, id))
    itemById(id).foreach { after =>
      index.upsert(after)
      before.foreach(value => revision(id, status, value, after))
    }
  }

  private def insertNode(
      id: String,
      scope: String,
      sessionId: String,
      kind: String,
      text: String,
      key: String,
      status: String,
      confidence: Double,
      importance: Int,
      pinned: Boolean,
      origin: String,
      sourceSessionId: String,
      sourceTurnId: String,
      now: Long
  ): Unit =
    update(
      """insert into memory_nodes(
        |id, scope_type, scope_id, kind, text, normalized_key, status, confidence, importance, pinned, origin,
        |source_session_id, source_turn_id, observed_at, valid_from, valid_to, created_at, updated_at
        |) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)""".stripMargin
    ) { ps =>
      ps.setString(1, id)
      ps.setString(2, scope)
      ps.setString(3, if (scope == MemoryPolicy.SessionScope) sessionId else "")
      ps.setString(4, kind)
      ps.setString(5, text)
      ps.setString(6, key)
      ps.setString(7, status)
      ps.setDouble(8, confidence)
      ps.setInt(9, math.max(1, math.min(10, importance)))
      ps.setInt(10, if (pinned) 1 else 0)
      ps.setString(11, origin)
      ps.setString(12, sourceSessionId)
      ps.setString(13, sourceTurnId)
      ps.setLong(14, now)
      ps.setLong(15, now)
      ps.setLong(16, now)
      ps.setLong(17, now)
    }

  private def mergeSource(nodeId: String, turnId: String, quote: String, origin: String): Unit =
    update(
      "insert or ignore into memory_sources(node_id, turn_id, evidence_quote, origin, created_at) values(?,?,?,?,?)"
    ) { ps =>
      ps.setString(1, nodeId)
      ps.setString(2, turnId)
      ps.setString(3, quote)
      ps.setString(4, origin)
      ps.setLong(5, Instant.now().toEpochMilli)
    }

  private def recordCandidate(nodeId: String, turnId: String, reason: String): Unit =
    update("insert or replace into memory_candidates(node_id, turn_id, reason, created_at) values(?,?,?,?)") { ps =>
      ps.setString(1, nodeId)
      ps.setString(2, turnId)
      ps.setString(3, reason)
      ps.setLong(4, Instant.now().toEpochMilli)
    }

  private def revision(nodeId: String, action: String, before: MemoryItem, after: MemoryItem): Unit =
    revision(nodeId, action, before.toJson, Some(after))

  private def revision(nodeId: String, action: String, beforeJson: String, after: Option[MemoryItem]): Unit =
    update(
      "insert into memory_revisions(id, node_id, action, before_json, after_json, created_at) values(?,?,?,?,?,?)"
    ) { ps =>
      ps.setString(1, UUID.randomUUID().toString)
      ps.setString(2, nodeId)
      ps.setString(3, action)
      ps.setString(4, beforeJson)
      ps.setString(5, after.map(_.toJson).getOrElse(""))
      ps.setLong(6, Instant.now().toEpochMilli)
    }

  private def isForgotten(sessionId: String): Boolean =
    preparedQuery("select 1 from memory_forgotten_sessions where session_id=?") { ps =>
      ps.setString(1, sessionId)
    } { rs =>
      rs.next()
    }.getOrElse(false)

  private val nodeColumns =
    """n.id, n.scope_type, n.scope_id, n.kind, n.text, n.normalized_key, n.status, n.confidence, n.importance,
      |n.pinned, n.origin, n.source_session_id, n.source_turn_id, n.observed_at, n.valid_from, n.valid_to""".stripMargin

  private def readNode(rs: ResultSet): MemoryItem =
    MemoryItem(
      id = rs.getString(1),
      scope = rs.getString(2),
      kind = rs.getString(4),
      text = rs.getString(5),
      normalizedKey = rs.getString(6),
      status = rs.getString(7),
      confidence = rs.getDouble(8),
      importance = rs.getInt(9),
      pinned = rs.getInt(10) != 0,
      origin = rs.getString(11),
      sourceSessionId = rs.getString(12),
      sourceTurnId = rs.getString(13),
      observedAt = rs.getLong(14),
      validFrom = rs.getLong(15),
      validTo = rs.getLong(16)
    )

  private def query[A](sql: String)(read: ResultSet => A): Option[A] =
    try
      Some(Using.resource(conn.prepareStatement(sql)) { ps =>
        Using.resource(ps.executeQuery())(read)
      })
    catch { case _: Throwable => None }

  private def preparedQuery[A](sql: String)(bind: PreparedStatement => Unit)(read: ResultSet => A): Option[A] =
    try
      Some(Using.resource(conn.prepareStatement(sql)) { ps =>
        bind(ps)
        Using.resource(ps.executeQuery())(read)
      })
    catch { case _: Throwable => None }

  private def update(sql: String)(bind: PreparedStatement => Unit): Int =
    Using.resource(conn.prepareStatement(sql)) { ps =>
      bind(ps)
      ps.executeUpdate()
    }

  override def close(): Unit = synchronized {
    if (!closed) {
      closed = true
      index.close()
      conn.close()
    }
  }
}
