package com.just3bowls.facade

import scala.scalajs.js

type JsonValue = js.Any

@js.native
trait JsoncParseError extends js.Object:
  def offset: Double = js.native
  def error: Int     = js.native

/** Typed access to open JSON values without exposing dynamic member access to business layers. */
object JsonValue {
  def parse(text: String): Either[String, JsonValue] =
    try Right(js.JSON.parse(text))
    catch { case e: Throwable => Left(Option(e.getMessage).getOrElse("invalid JSON")) }

  def stringify(value: JsonValue): String =
    js.JSON.stringify(value)

  def emptyObject: JsonValue =
    JsBridge.obj()

  def obj(fields: (String, js.Any)*): JsonValue =
    JsBridge.obj(fields*)

  def array(values: JsonValue*): js.Array[JsonValue] =
    js.Array(values*)

  def isObject(value: JsonValue): Boolean =
    value != null && !js.isUndefined(value) && js.typeOf(value) == "object" && !js.Array.isArray(value)

  def isArray(value: JsonValue): Boolean =
    value != null && !js.isUndefined(value) && js.Array.isArray(value)

  def field(value: JsonValue, name: String): Option[JsonValue] =
    Option(JsBridge.field(value, name)).filter(item => item != null && !js.isUndefined(item))

  def stringField(value: JsonValue, name: String): Option[String] =
    field(value, name).filter(item => js.typeOf(item) == "string").map(_.asInstanceOf[String])

  def boolField(value: JsonValue, name: String): Option[Boolean] =
    field(value, name).filter(item => js.typeOf(item) == "boolean").map(_.asInstanceOf[Boolean])

  def intField(value: JsonValue, name: String): Option[Int] =
    doubleField(value, name).map(_.toInt)

  def doubleField(value: JsonValue, name: String): Option[Double] =
    field(value, name).filter(item => js.typeOf(item) == "number").map(_.asInstanceOf[Double])

  def arrayField(value: JsonValue, name: String): List[JsonValue] =
    field(value, name).filter(isArray).map(_.asInstanceOf[js.Array[JsonValue]].toList).getOrElse(Nil)

  def stringListField(value: JsonValue, name: String): List[String] =
    arrayField(value, name).filter(item => js.typeOf(item) == "string").map(_.asInstanceOf[String])

  def objectField(value: JsonValue, name: String): Option[JsonValue] =
    field(value, name).filter(isObject)

  def dictionary(value: JsonValue): Map[String, JsonValue] =
    if (!isObject(value)) Map.empty
    else
      js.Object
        .keys(value.asInstanceOf[js.Object])
        .toList
        .flatMap(key => field(value, key).map(key -> _))
        .toMap

  def item(value: JsonValue, index: Int): Option[JsonValue] =
    if (!isArray(value)) None
    else {
      val values = value.asInstanceOf[js.Array[JsonValue]]
      val at     = if (index < 0) values.length + index else index
      if (at < 0 || at >= values.length) None
      else Option(values(at)).filter(item => item != null && !js.isUndefined(item))
    }

  def stringValue(value: JsonValue): Option[String] =
    Option(value).filter(item => item != null && !js.isUndefined(item) && js.typeOf(item) == "string")
      .map(_.asInstanceOf[String])

  def display(value: JsonValue): String =
    if (value == null || js.isUndefined(value)) ""
    else if (js.typeOf(value) == "string") value.asInstanceOf[String]
    else js.JSON.stringify(value)

  def cloneValue(value: JsonValue): JsonValue =
    js.JSON.parse(js.JSON.stringify(value))

  def setField(value: JsonValue, name: String, next: JsonValue): Unit =
    JsBridge.update(value, name, next)

  def setStringField(value: JsonValue, name: String, next: String): Unit =
    setField(value, name, next.asInstanceOf[JsonValue])

  def setArrayItem(value: JsonValue, index: Int, next: JsonValue): Boolean =
    if (!isArray(value)) false
    else {
      val values = value.asInstanceOf[js.Array[JsonValue]]
      val at     = if (index < 0) values.length + index else index
      if (at < 0 || at >= values.length) false
      else {
        values.update(at, next)
        true
      }
    }

  def canonical(value: JsonValue, ignoredKeys: Set[String] = Set.empty): String =
    if (value == null || js.isUndefined(value)) "null"
    else if (isArray(value))
      value.asInstanceOf[js.Array[JsonValue]].map(item => canonical(item, ignoredKeys)).mkString("[", ",", "]")
    else if (isObject(value))
      js.Object
        .keys(value.asInstanceOf[js.Object])
        .toList
        .filterNot(ignoredKeys.contains)
        .sorted
        .map(key =>
          js.JSON.stringify(key).asInstanceOf[String] + ":" +
            canonical(JsBridge.field(value, key), ignoredKeys)
        )
        .mkString("{", ",", "}")
    else if (js.typeOf(value) == "string") js.JSON.stringify(value).asInstanceOf[String]
    else String.valueOf(value)
}
