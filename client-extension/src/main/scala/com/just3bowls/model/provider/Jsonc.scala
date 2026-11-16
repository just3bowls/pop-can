package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.{JsonValue, JsoncParseError}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * 面板导入使用的 JSONC 解析器。
 *
 * 源文件允许行注释、块注释和尾逗号；这里返回的是已经去掉注释的普通 JS 值，运行时不会继续携带 JSONC 语法。
 */
object Jsonc {
  @js.native
  @JSImport("jsonc-parser", "parse")
  private object Parse               extends js.Object {
    def apply(text: String, errors: js.Array[js.Any], options: js.UndefOr[js.Object]): js.Any = js.native
  }
  @js.native
  @JSImport("jsonc-parser", "printParseErrorCode")
  private object PrintParseErrorCode extends js.Object {
    def apply(code: Int): String = js.native
  }
  def parse(text: String, label: String): Either[String, JsonValue] = {
    val errors = js.Array[JsoncParseError]()
    val value  = Parse(
      text,
      JsBridge.asArray(errors),
      JsBridge.obj("allowTrailingComma" -> (true), "disallowComments" -> (false))
    )
    errors.headOption match {
      case None        => Right(value)
      case Some(error) =>
        val offset = error.offset.toInt
        val before = text.take(offset)
        val line   = before.count(_ == '\n') + 1
        val column = offset - before.lastIndexOf('\n')
        val code   = error.error
        Left(s"$label:$line:$column: ${PrintParseErrorCode(code)}")
    }
  }
}
