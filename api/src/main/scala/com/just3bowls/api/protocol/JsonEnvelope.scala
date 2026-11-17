package com.just3bowls.api.protocol

import zio.json.*
import zio.json.ast.Json

/** Reads fields from a top-level JSON object without depending on serialization whitespace or key order. */
object JsonEnvelope {

  def json(raw: String): Option[Json] =
    Option(raw).flatMap(_.fromJson[Json].toOption)

  def fields(raw: String): Option[Map[String, Json]] =
    json(raw)
      .collect { case Json.Obj(values) => values.toMap }

  def typeOf(raw: String): Option[String] =
    stringField(raw, "type")

  def stringField(raw: String, name: String): Option[String] =
    fields(raw).flatMap(_.get(name)).collect { case Json.Str(value) => value }
}
