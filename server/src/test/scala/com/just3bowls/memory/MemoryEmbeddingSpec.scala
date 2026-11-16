package com.just3bowls.memory

import com.just3bowls.api.memory.MemoryQuery
import zio.test._

import java.nio.file.Files
import java.sql.DriverManager
import java.util.Comparator

object MemoryEmbeddingSpec extends ZIOSpecDefault {
  def spec = suite("MemoryEmbeddingSpec")(
    test("hash fallback is deterministic, normalized and 384-dimensional") {
      val provider = new HashingEmbeddingProvider()
      val first    = provider.embed("Flink checkpoint 机制")
      val second   = provider.embed("Flink checkpoint 机制")
      val norm     = math.sqrt(first.iterator.map(value => value.toDouble * value).sum)
      assertTrue(first.sameElements(second), first.length == 384, math.abs(norm - 1.0) < 0.0001)
    },
    test("SQLite survives index rebuild and reopen") {
      val directory = Files.createTempDirectory("pop-can-embedding-reopen-")
      try {
        val firstStore = new MemoryStore(directory)
        val item       = firstStore.remember("global", "fact", "The project uses SQLite", 8, pinned = true)
        firstStore.close()

        val raw      = DriverManager.getConnection(s"jdbc:sqlite:${directory.resolve("memory.sqlite").toAbsolutePath}")
        val rawCount =
          try {
            val rs = raw.createStatement().executeQuery("select count(*) from memory_nodes")
            if (rs.next()) rs.getInt(1) else 0
          } finally raw.close()
        val secondStore = new MemoryStore(directory)
        val (recalled, activeCount, listed, identity) =
          try {
            val recalled = secondStore.recall(MemoryQuery("new-session", "SQLite"))
            (
              recalled,
              secondStore.status().activeCount,
              secondStore.list().items,
              secondStore.status().embeddingIdentity
            )
          } finally secondStore.close()
        assertTrue(
          recalled.hits.exists(_.id == item.id),
          rawCount == 1,
          activeCount == 1,
          listed.exists(_.id == item.id),
          identity.nonEmpty
        )
      } finally {
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      }
    }
  )
}
