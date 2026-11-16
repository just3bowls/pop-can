package com.just3bowls.memory

import java.sql.Connection
import scala.util.Using

/** Versioned SQLite schema bootstrap for the canonical graph/evidence store. */
private[memory] object MemorySchema {
  def install(conn: Connection): Unit = {
    Using.resource(conn.createStatement()) { st =>
      st.execute("pragma journal_mode=wal")
      st.execute("pragma synchronous=normal")
      st.execute("pragma foreign_keys=on")
      st.execute(
        """create table if not exists memory_nodes(
          |  id text primary key,
          |  scope_type text not null,
          |  scope_id text not null default '',
          |  kind text not null,
          |  text text not null,
          |  normalized_key text not null,
          |  status text not null,
          |  confidence real not null default 0,
          |  importance integer not null default 5,
          |  pinned integer not null default 0,
          |  origin text not null,
          |  source_session_id text not null default '',
          |  source_turn_id text not null default '',
          |  observed_at integer not null,
          |  valid_from integer not null,
          |  valid_to integer not null default 0,
          |  created_at integer not null,
          |  updated_at integer not null
          |)""".stripMargin
      )
      st.execute("create index if not exists memory_nodes_key on memory_nodes(normalized_key, status)")
      st.execute(
        """create table if not exists memory_edges(
          |  id text primary key,
          |  from_node_id text not null,
          |  to_node_id text not null,
          |  relation text not null,
          |  confidence real not null default 0,
          |  valid_from integer not null,
          |  valid_to integer not null default 0,
          |  supersedes_id text not null default '',
          |  source_turn_id text not null default ''
          |)""".stripMargin
      )
      st.execute("create index if not exists memory_edges_from on memory_edges(from_node_id)")
      st.execute(
        """create table if not exists memory_episodes(
          |  turn_id text primary key,
          |  session_id text not null,
          |  user_message_id text not null,
          |  assistant_message_id text not null,
          |  attempt_id text not null default '',
          |  provider text not null default '',
          |  mode text not null default 'chat',
          |  origin text not null default 'plugin',
          |  user_text text not null,
          |  assistant_text text not null,
          |  untrusted integer not null default 0,
          |  completed_at integer not null,
          |  created_at integer not null
          |)""".stripMargin
      )
      st.execute(
        """create table if not exists memory_sources(
          |  node_id text not null,
          |  turn_id text not null,
          |  evidence_quote text not null,
          |  origin text not null,
          |  created_at integer not null,
          |  primary key(node_id, turn_id, evidence_quote)
          |)""".stripMargin
      )
      st.execute(
        """create table if not exists memory_revisions(
          |  id text primary key,
          |  node_id text not null,
          |  action text not null,
          |  before_json text not null default '',
          |  after_json text not null default '',
          |  created_at integer not null
          |)""".stripMargin
      )
      st.execute(
        """create table if not exists memory_candidates(
          |  node_id text primary key,
          |  turn_id text not null,
          |  reason text not null default '',
          |  created_at integer not null
          |)""".stripMargin
      )
      st.execute(
        """create table if not exists memory_jobs(
          |  id text primary key,
          |  turn_id text not null unique,
          |  provider text not null,
          |  routes_json text not null default '[]',
          |  strategy text not null default 'weighted',
          |  failover text not null default 'off',
          |  tried_providers_json text not null default '[]',
          |  prompt text not null,
          |  state text not null,
          |  attempts integer not null default 0,
          |  last_error text not null default '',
          |  created_at integer not null,
          |  updated_at integer not null
          |)""".stripMargin
      )
      st.execute("create index if not exists memory_jobs_state on memory_jobs(state, created_at)")
      st.execute(
        """create table if not exists memory_forgotten_sessions(
          |  session_id text primary key,
          |  forgotten_at integer not null
          |)""".stripMargin
      )
      st.execute(
        """create table if not exists memory_settings(
          |  id integer primary key check(id=1),
          |  auto_extract integer not null default 0,
          |  extraction_routes text not null default '[]',
          |  extraction_strategy text not null default 'weighted',
          |  extraction_failover text not null default 'off',
          |  context_budget_tokens integer not null default 1200
          |)""".stripMargin
      )
      st.execute(
        "insert or ignore into memory_settings(id, auto_extract, context_budget_tokens) values(1, 0, 1200)"
      )
      st.execute(
        """create table if not exists memory_index_meta(
          |  id integer primary key check(id=1),
          |  embedding_identity text not null,
          |  dimension integer not null,
          |  model_hash text not null default '',
          |  schema_version integer not null
          |)""".stripMargin
      )
      st.execute("update memory_jobs set state='queued' where state='processing'")
    }
  }
}
