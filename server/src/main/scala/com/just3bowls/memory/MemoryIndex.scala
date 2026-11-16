package com.just3bowls.memory

import com.just3bowls.api.memory.MemoryItem
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.{Document, Field, KnnFloatVectorField, StoredField, StringField, TextField}
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig, Term}
import org.apache.lucene.queryparser.classic.{QueryParser, QueryParserBase}
import org.apache.lucene.search.{
  BooleanClause,
  BooleanQuery,
  IndexSearcher,
  KnnFloatVectorQuery,
  MatchAllDocsQuery,
  Query,
  ScoreDoc,
  TermQuery
}
import org.apache.lucene.store.FSDirectory

import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
 * Lucene is a derived index only. SQLite remains authoritative and can rebuild this directory at any time. The local
 * embedding is intentionally deterministic and dependency-free; the index identity leaves room for a bundled ONNX
 * provider without changing storage semantics.
 */
final class MemoryIndex(root: Path, embeddingProvider: EmbeddingProvider) extends AutoCloseable {
  Files.createDirectories(root)

  private val analyzer  = new StandardAnalyzer()
  private val directory = FSDirectory.open(root)
  private val writer    = new IndexWriter(directory, new IndexWriterConfig(analyzer))

  def embeddingIdentity: String = embeddingProvider.identity
  def embeddingReady: Boolean   = embeddingProvider.available
  def embeddingDetail: String   = embeddingProvider.reason
  def documentCount: Int        = synchronized(writer.getDocStats.numDocs)

  def upsert(item: MemoryItem): Unit = synchronized {
    val document = new Document()
    document.add(new StringField("id", item.id, Field.Store.YES))
    document.add(new StringField("scope", item.scope, Field.Store.NO))
    document.add(new StringField("scopeId", item.sourceSessionId, Field.Store.NO))
    document.add(new StringField("kind", item.kind, Field.Store.NO))
    document.add(new StringField("status", item.status, Field.Store.NO))
    document.add(new StringField("pinned", item.pinned.toString, Field.Store.NO))
    document.add(new TextField("text", item.text, Field.Store.NO))
    document.add(new StoredField("importance", item.importance))
    document.add(new KnnFloatVectorField(
      "vector",
      embeddingProvider.embed(item.text),
      org.apache.lucene.index.VectorSimilarityFunction.COSINE
    ))
    writer.updateDocument(new Term("id", item.id), document)
    writer.commit()
  }

  def delete(id: String): Unit = synchronized {
    writer.deleteDocuments(new Term("id", id))
    writer.commit()
  }

  def clearAndRebuild(items: Iterable[MemoryItem]): Unit = synchronized {
    writer.deleteAll()
    items.foreach { item =>
      val document = new Document()
      document.add(new StringField("id", item.id, Field.Store.YES))
      document.add(new StringField("scope", item.scope, Field.Store.NO))
      document.add(new StringField("scopeId", item.sourceSessionId, Field.Store.NO))
      document.add(new StringField("kind", item.kind, Field.Store.NO))
      document.add(new StringField("status", item.status, Field.Store.NO))
      document.add(new StringField("pinned", item.pinned.toString, Field.Store.NO))
      document.add(new TextField("text", item.text, Field.Store.NO))
      document.add(new StoredField("importance", item.importance))
      document.add(new KnnFloatVectorField(
        "vector",
        embeddingProvider.embed(item.text),
        org.apache.lucene.index.VectorSimilarityFunction.COSINE
      ))
      writer.addDocument(document)
    }
    writer.commit()
  }

  /** Returns `(memoryId, reciprocal-rank-input-score)` for lexical and vector lanes. */
  def search(queryText: String, sessionId: String, limit: Int): List[(String, Double)] = synchronized {
    if (queryText.trim.isEmpty) Nil
    else {
      writer.commit()
      val reader = DirectoryReader.open(writer)
      try {
        val searcher    = new IndexSearcher(reader)
        val filter      = scopeFilter(sessionId)
        val lexical     = lexicalQuery(queryText, filter)
        val lexicalHits = searcher.search(lexical, limit).scoreDocs.toList
        val vectorHits  =
          try
            searcher.search(
              new KnnFloatVectorQuery("vector", embeddingProvider.embed(queryText), limit, filter),
              limit
            ).scoreDocs.toList
          catch { case _: Throwable => Nil }

        val ids = reader.numDocs()
        if (ids == 0) Nil
        else {
          val merged                            = mutable.LinkedHashMap.empty[String, Double]
          def add(ranked: List[ScoreDoc]): Unit =
            ranked.zipWithIndex.foreach { case (scoreDoc, rank) =>
              val id = searcher.storedFields().document(scoreDoc.doc).get("id")
              if (id != null) merged.update(id, merged.getOrElse(id, 0.0) + 1.0 / (60.0 + rank + 1.0))
            }
          add(lexicalHits)
          add(vectorHits)
          merged.toList.sortBy { case (_, score) => -score }.take(limit)
        }
      } finally reader.close()
    }
  }

  private def lexicalQuery(queryText: String, filter: Query): Query = {
    val parser  = new QueryParser("text", analyzer)
    val parsed  =
      try parser.parse(QueryParserBase.escape(queryText))
      catch { case _: Throwable => new MatchAllDocsQuery() }
    val builder = new BooleanQuery.Builder()
    builder.add(parsed, BooleanClause.Occur.MUST)
    builder.add(filter, BooleanClause.Occur.FILTER)
    builder.build()
  }

  private def scopeFilter(sessionId: String): Query = {
    val active = new TermQuery(new Term("status", MemoryPolicy.ActiveStatus))
    val scopes = new BooleanQuery.Builder()
    scopes.add(new TermQuery(new Term("scope", MemoryPolicy.GlobalScope)), BooleanClause.Occur.SHOULD)
    if (sessionId.nonEmpty)
      scopes.add(
        new BooleanQuery.Builder()
          .add(new TermQuery(new Term("scope", MemoryPolicy.SessionScope)), BooleanClause.Occur.MUST)
          .add(new TermQuery(new Term("scopeId", sessionId)), BooleanClause.Occur.MUST)
          .build(),
        BooleanClause.Occur.SHOULD
      )
    scopes.setMinimumNumberShouldMatch(1)
    new BooleanQuery.Builder()
      .add(active, BooleanClause.Occur.FILTER)
      .add(scopes.build(), BooleanClause.Occur.FILTER)
      .build()
  }

  override def close(): Unit = synchronized {
    writer.close()
    directory.close()
    analyzer.close()
    embeddingProvider.close()
  }
}
