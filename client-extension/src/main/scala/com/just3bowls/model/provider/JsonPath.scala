package com.just3bowls.model.provider

import com.just3bowls.facade.{JsBridge, JsonValue}

import scala.collection.mutable
import scala.scalajs.js

/**
 * 极简 JSON 路径读写，供"网页原生会话"在请求体里定位用户那句话用。
 *
 * 片段语法与 `LocalRawParser` 的规则路径保持一致：`$`、`.field`、`[0]`、`[-1]`，以及 `[?(@.nested.field=='v')]` 过滤。
 * 过滤片段与规则里一样产出**匹配数组**，所以实际写法通常是 `[?(@.nested.field=='v')][0]`，例如豆包：
 * `$.messages[-1].content_block[?(@.block_type=='10000')][0].content.text_block.text`
 *
 * 另有只给"事件扇出"用的 `[*]`：单值读写（`get` / `setString`）不认它，`expand` 用它把一条 payload 里的数组摊成多个事件。
 *
 * 解析不出（片段不支持、路径缺失）就返回 `None`：调用方按"这条 provider 不支持读写"处理，不做静默兜底。
 */
object JsonPath {
  sealed trait Segment
  final case class Field(name: String)                                        extends Segment
  final case class Index(index: Int)                                          extends Segment
  final case class Filter(path: List[String], value: String, negate: Boolean) extends Segment
  final case class Wildcard()                                                 extends Segment
  private val FilterPattern =
    """^\?\(@((?:\.[A-Za-z_][A-Za-z0-9_-]*)+)\s*(==|!=)\s*['"]([^'"]*)['"]\)$""".r

  /** 解析路径；遇到不支持的片段返回 None。 */
  def parse(path: String): Option[List[Segment]] = {
    val trimmed = path.trim
    if (!trimmed.startsWith("$")) None
    else {
      val segments = mutable.ListBuffer.empty[Segment]
      var i        = 1
      var ok       = true
      while (i < trimmed.length && ok) {
        trimmed.charAt(i) match {
          case '.' =>
            val start = i + 1
            var end   = start
            while (end < trimmed.length && trimmed.charAt(end) != '.' && trimmed.charAt(end) != '[') end += 1
            if (end == start) ok = false
            else {
              segments += Field(trimmed.substring(start, end))
              i = end
            }
          case '[' =>
            val close = trimmed.indexOf(']', i)
            if (close < 0) ok = false
            else {
              val body = trimmed.substring(i + 1, close).trim
              if (body == "*") segments += Wildcard()
              else
                body.toIntOption match {
                  case Some(index) => segments += Index(index)
                  case None        =>
                    body match {
                      case FilterPattern(fieldPath, op, value) =>
                        segments += Filter(
                          fieldPath.stripPrefix(".").split('.').toList,
                          value,
                          negate = op == "!="
                        )
                      case _                                   => ok = false
                    }
                }
              i = close + 1
            }
          case _   =>
            ok = false
        }
      }
      if (ok && segments.nonEmpty) Some(segments.toList) else None
    }
  }

  /** 取值；任一片段缺失返回 None。过滤片段产出匹配数组（与 `LocalRawParser` 一致）。 */
  def get(json: js.Any, path: String): Option[js.Any] =
    parse(path).flatMap { segments =>
      var current: js.Any = json
      var missing         = false
      segments.foreach {
        case Field(name)                      =>
          if (!missing) {
            if (current == null || js.isUndefined(current) || js.typeOf(current) != "object") missing = true
            else {
              val value = JsonValue.field(current, name).getOrElse(js.undefined)
              if (value == null || js.isUndefined(value)) missing = true else current = value
            }
          }
        case Index(index)                     =>
          if (!missing) {
            if (current == null || js.isUndefined(current) || !js.Array.isArray(current)) missing = true
            else {
              val array = JsBridge.asArray(current)
              val at    = if (index < 0) array.length + index else index
              if (at < 0 || at >= array.length) missing = true
              else {
                val value = array(at)
                if (value == null || js.isUndefined(value)) missing = true else current = value
              }
            }
          }
        case Filter(fieldPath, value, negate) =>
          if (!missing) {
            if (current == null || js.isUndefined(current) || !js.Array.isArray(current)) missing = true
            else {
              val array   = JsBridge.asArray(current)
              val matched = array.filter { item =>
                val itemValue = stringify(nestedField(item, fieldPath).getOrElse(js.undefined))
                if (negate) itemValue != value else itemValue == value
              }
              if (matched.isEmpty) missing = true else current = matched
            }
          }
        case Wildcard()                       =>
          // `[*]` 只服务 `expand`（事件扇出）；单值取值遇到它按"不支持"处理，避免静默取错。
          if (!missing) missing = true
      }
      if (missing) None else Some(current)
    }

  /** 取字符串值（非字符串一律 None）。 */
  def getString(json: js.Any, path: String): Option[String] =
    get(json, path).flatMap { value =>
      if (js.typeOf(value) == "string") Some(JsBridge.asString(value)) else None
    }

  /**
   * 展开取值：与 `get` 同一套语法，但 `[*]` 与过滤片段产出**多个具体值**（数组元素），而不是整段数组。
   *
   * 用来把"一条 payload 里装了 N 个事件"摊开：`$.data.messages[*]` → 逐条消息。
   */
  def expand(json: js.Any, path: String): List[js.Any]                              =
    parse(path).map(segments => expandAt(List(json), segments)).getOrElse(Nil)
  private def expandAt(values: List[js.Any], segments: List[Segment]): List[js.Any] =
    segments match {
      case Nil             => values
      case segment :: rest =>
        val next = values.flatMap(value => step(value, segment))
        if (next.isEmpty) Nil else expandAt(next, rest)
    }
  private def step(value: js.Any, segment: Segment): List[js.Any]                   =
    if (value == null || js.isUndefined(value)) Nil
    else
      segment match {
        case Field(name)                       =>
          if (js.typeOf(value) != "object") Nil
          else {
            val child = JsonValue.field(value, name).getOrElse(js.undefined)
            if (child == null || js.isUndefined(child)) Nil else List(child)
          }
        case Index(index)                      =>
          if (!js.Array.isArray(value)) Nil
          else {
            val array = JsBridge.asArray(value)
            val at    = if (index < 0) array.length + index else index
            if (at < 0 || at >= array.length) Nil else List(array(at))
          }
        case Wildcard()                        =>
          if (js.Array.isArray(value)) JsBridge.asArray(value).toList else Nil
        case Filter(fieldPath, value_, negate) =>
          if (!js.Array.isArray(value)) Nil
          else
            JsBridge
              .asArray(value)
              .toList
              .filter { item =>
                val itemValue = stringify(nestedField(item, fieldPath).getOrElse(js.undefined))
                if (negate) itemValue != value_ else itemValue == value_
              }
      }

  /**
   * 设值：走 JSON 往返复制，不改原对象；路径不可达返回 None。
   *
   * 只支持"父路径必须存在"的写入——恰好是我们要的语义：把已有字段的文本换掉，而不是造结构。
   */
  def setString(json: js.Any, path: String, value: String): Option[js.Any] =
    parse(path).flatMap { segments =>
      val copy = js.JSON.parse(js.JSON.stringify(json))
      resolve(copy, segments).flatMap { concrete =>
        if (writeAt(copy, concrete, value)) Some(copy) else None
      }
    }

  /**
   * 把过滤片段落地成具体下标，之后的写操作按普通路径走。
   *
   * 规则作者的习惯是 `[?(@.k=='v')][0]`——这里把过滤解析成"第一个匹配项在父数组里的下标"，语义与之吻合。
   */
  private def resolve(json: js.Any, segments: List[Segment]): Option[List[Segment]] = {
    val out     = mutable.ListBuffer.empty[Segment]
    var current = json
    var ok      = true
    var rest    = segments
    while (rest.nonEmpty && ok) {
      rest.head match {
        case field: Field                     =>
          out += field
          if (current == null || js.isUndefined(current) || js.typeOf(current) != "object") ok = false
          else {
            val next = JsonValue.field(current, field.name).getOrElse(js.undefined)
            if (next == null || js.isUndefined(next)) ok = false else current = next
          }
          rest = rest.tail
        case Index(index)                     =>
          out += Index(index)
          if (current == null || js.isUndefined(current) || !js.Array.isArray(current)) ok = false
          else {
            val array = JsBridge.asArray(current)
            val at    = if (index < 0) array.length + index else index
            if (at < 0 || at >= array.length) ok = false else current = array(at)
          }
          rest = rest.tail
        case Filter(fieldPath, value, negate) =>
          if (current == null || js.isUndefined(current) || !js.Array.isArray(current)) ok = false
          else {
            val array                  = JsBridge.asArray(current)
            val matches                = array.zipWithIndex.filter { case (item, _) =>
              val itemValue = stringify(nestedField(item, fieldPath).getOrElse(js.undefined))
              if (negate) itemValue != value else itemValue == value
            }
            // 规则里习惯写 `[?(@.k=='v')][0]`：过滤先选出一组匹配，紧跟的下标是**从匹配里**取第几个。
            // （写入时把它落成具体下标；如果不消费这个下标，就会拿它去索引已经取出来的单个元素 → 解析失败。）
            val (pickIndex, remaining) = rest.tail match {
              case Index(k) :: tail => (Some(k), tail)
              case tail             => (None, tail)
            }
            if (matches.isEmpty) ok = false
            else {
              val pick    = pickIndex.getOrElse(0)
              val matchAt = if (pick < 0) matches.length + pick else pick
              if (matchAt < 0 || matchAt >= matches.length) ok = false
              else {
                val (element, absoluteIndex) = matches(matchAt)
                out += Index(absoluteIndex)
                current = element
                rest = remaining
              }
            }
          }
        case Wildcard()                       =>
          ok = false
      }
    }
    if (ok) Some(out.toList) else None
  }
  private def stringify(value: js.Any): String                                      =
    if (value == null || js.isUndefined(value)) ""
    else if (js.typeOf(value) == "string") JsBridge.asString(value)
    else value.toString
  private def nestedField(value: js.Any, path: List[String]): Option[js.Any]        =
    if (value == null || js.isUndefined(value) || js.typeOf(value) != "object") None
    else
      path.foldLeft(Option(value)) { (current, field) =>
        current.flatMap { item =>
          if (item == null || js.isUndefined(item) || js.typeOf(item) != "object") None
          else JsonValue.field(item, field).filter(next => next != null && !js.isUndefined(next))
        }
      }
  private def writeAt(root: js.Any, segments: List[Segment], value: String): Boolean =
    // 注意：路径通常是多段的（`$.a.b[0].c`），别只处理单段。
    segments.lastOption match {
      case None       => false
      case Some(last) =>
        parentOf(root, segments) match {
          case None         => false
          case Some(parent) =>
            last match {
              case Field(name)  =>
                if (parent == null || js.isUndefined(parent) || js.typeOf(parent) != "object") false
                else {
                  JsonValue.setStringField(parent, name, value)
                  true
                }
              case Index(index) =>
                if (!js.Array.isArray(parent)) false
                else {
                  val array = JsBridge.asArray(parent)
                  val at    = if (index < 0) array.length + index else index
                  if (at < 0 || at >= array.length) false
                  else JsonValue.setArrayItem(parent, index, value.asInstanceOf[JsonValue])
                }
              case _            => false
            }
        }
    }
  private def parentOf(root: js.Any, segments: List[Segment]): Option[js.Any]       = {
    val parents = segments.dropRight(1)
    var current = root
    var ok      = true
    parents.foreach {
      case Field(name)     =>
        if (!ok) ()
        else if (current == null || js.isUndefined(current) || js.typeOf(current) != "object") ok = false
        else {
          val value = JsonValue.field(current, name).getOrElse(js.undefined)
          if (value == null || js.isUndefined(value)) ok = false else current = value
        }
      case Index(index)    =>
        if (!ok) ()
        else if (!js.Array.isArray(current)) ok = false
        else {
          val array = JsBridge.asArray(current)
          val at    = if (index < 0) array.length + index else index
          if (at < 0 || at >= array.length) ok = false else current = array(at)
        }
      case Filter(_, _, _) =>
        ok = false
      case Wildcard()      =>
        ok = false
    }
    if (ok) Some(current) else None
  }
}
