package com.just3bowls.api.marker

import scala.collection.mutable

/**
 * Marker tool-call 参数解析器。
 *
 * 参数标记是递归结构，必须按父子作用域消费；顺序 `indexOf` 扫描会把未闭合块里的元素误提升成顶层参数。 这里用 token 化 + 栈建立参数树，再生成 JSON：
 *   - BEG 和 END 都必须带类型，且名称、类型完全一致；
 *   - 数组元素名必须是数字；
 *   - 任何拼写或结构偏差都返回可读错误，由模型按严格协议重写。
 */
object MarkerArguments {

  private val OpenPrefix  = "@@BEG:"
  private val ClosePrefix = "@@END:"

  final private case class Token(
      isOpen: Boolean,
      name: String,
      paramType: String,
      start: Int,
      end: Int,
      raw: String
  )

  final private class Frame(
      var name: String,
      var paramType: String,
      val parentType: String,
      val openEnd: Int
  ) {
    val text     = new StringBuilder
    val children = mutable.ArrayBuffer.empty[Node]
  }

  final private case class Node(
      name: String,
      paramType: String,
      literal: String,
      children: List[Node]
  )

  def parse(argumentsBody: String): Either[List[String], String] = {
    val body = Option(argumentsBody).getOrElse("").trim
    if (body.isEmpty) Right("{}")
    else
      buildTree(body).flatMap(root =>
        renderObject(root.children, path = "arguments").left.map(List(_))
      )
  }

  private def buildTree(body: String): Either[List[String], Node] = {
    val root   = new Frame(name = "", paramType = "object", parentType = "", openEnd = 0)
    val stack  = mutable.ArrayBuffer(root)
    var from   = 0
    val errors = mutable.ArrayBuffer.empty[String]
    var done   = false

    while (!done) {
      nextToken(body, from) match {
        case Left(message)                  =>
          errors += message
          done = true
        case Right(None)                    =>
          stack.last.text.append(body.substring(from))
          from = body.length
          done = true
        case Right(Some((token, nextFrom))) =>
          val current = stack.last
          current.text.append(body.substring(from, token.start))
          if (token.isOpen) {
            stack += new Frame(
              name = token.name,
              paramType = token.paramType,
              parentType = current.paramType,
              openEnd = token.end
            )
          } else {
            val frame = stack.last
            if (frame eq root) errors += s"orphan closing marker ${token.raw}"
            else {
              val typeMismatch =
                frame.paramType.nonEmpty &&
                  token.paramType.nonEmpty &&
                  frame.paramType != token.paramType
              if (frame.name.isEmpty || frame.name != token.name)
                errors += s"closing marker ${token.raw} does not match open field '${frame.name}'"
              else if (frame.paramType.isEmpty || token.paramType.isEmpty)
                errors +=
                  s"closing marker ${token.raw} must include the same type as '${frame.paramType}'"
              else if (typeMismatch)
                errors +=
                  s"closing marker ${token.raw} has type '${token.paramType}', expected '${frame.paramType}'"
              if (frame.name.nonEmpty) {
                stack.remove(stack.length - 1)
                stack.last.children += Node(
                  if (token.name.nonEmpty) token.name else frame.name,
                  if (token.paramType.nonEmpty) token.paramType else frame.paramType,
                  frame.text.result().trim,
                  frame.children.toList
                )
              }
            }
          }
          from = nextFrom
      }
    }

    stack.tail.foreach(frame => errors += s"unclosed marker '${frame.name}'")
    val trailing = root.text.result().trim
    if (trailing.nonEmpty)
      errors += s"unexpected text outside argument markers: ${short(trailing)}"
    if (errors.nonEmpty) Left(errors.distinct.toList)
    else Right(Node(name = "", paramType = "object", literal = "", children = root.children.toList))
  }

  private def nextToken(text: String, from: Int): Either[String, Option[(Token, Int)]] = {
    val openAt  = text.indexOf(OpenPrefix, from)
    val closeAt = text.indexOf(ClosePrefix, from)
    val at      =
      if (openAt < 0) closeAt
      else if (closeAt < 0) openAt
      else math.min(openAt, closeAt)
    if (at < 0) Right(None)
    else {
      val isOpen = at == openAt
      val prefix = if (isOpen) OpenPrefix else ClosePrefix
      val bodyAt = at + prefix.length
      val endAt  = text.indexOf("@@", bodyAt)
      if (endAt < 0) Left(s"unterminated marker near '${short(text.substring(at))}'")
      else {
        val inside = text.substring(bodyAt, endAt)
        val colon  = inside.lastIndexOf(':')
        val name   = if (colon >= 0) inside.substring(0, colon) else inside
        val kind   = if (colon >= 0) inside.substring(colon + 1) else ""
        val end    = endAt + 2
        Right(Some((Token(isOpen, name, kind, at, end, text.substring(at, end)), end)))
      }
    }
  }

  private def renderObject(nodes: List[Node], path: String): Either[String, String] =
    duplicateName(nodes).map(name => Left(s"$path contains duplicate field '$name'")).getOrElse {
      nodes
        .foldLeft[Either[String, List[String]]](Right(Nil)) { case (acc, node) =>
          for {
            values <- acc
            value  <- renderNode(node, s"$path.${node.name}")
          } yield values :+ s"${jsonString(node.name)}:$value"
        }
        .map(values => values.mkString("{", ",", "}"))
    }

  private def renderNode(node: Node, path: String): Either[String, String] = {
    val kind = node.paramType.trim.toLowerCase
    if (kind == "array" || (kind.isEmpty && node.children.nonEmpty && node.children.forall(ch => isIndex(ch.name))))
      renderArray(node, path)
    else if (kind == "object" || (kind.isEmpty && node.children.nonEmpty))
      renderObject(node.children, path)
    else if (node.children.nonEmpty)
      Left(s"$path is '$kind' but contains nested markers")
    else
      kind match {
        case "number" | "integer" =>
          val value = node.literal.trim
          if (value.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) Right(value)
          else Right(jsonString(node.literal))
        case "boolean"            =>
          node.literal.trim match {
            case "true" | "\"true\""   => Right("true")
            case "false" | "\"false\"" => Right("false")
            case _                     => Right(jsonString(node.literal))
          }
        case "null"               => Right("null")
        case "array"              =>
          val value = node.literal.trim
          if (value.startsWith("[") && value.endsWith("]")) Right(value)
          else Right(jsonString(node.literal))
        case "object"             =>
          val value = node.literal.trim
          if (value.startsWith("{") && value.endsWith("}")) Right(value)
          else Right(jsonString(node.literal))
        case _                    => Right(jsonString(node.literal))
      }
  }

  private def renderArray(node: Node, path: String): Either[String, String] =
    if (node.children.isEmpty) {
      val value = node.literal.trim
      if (value.startsWith("[") && value.endsWith("]")) Right(value)
      else if (value.isEmpty) Right("[]")
      else Right(jsonString(node.literal))
    } else {
      val indexed = node.children.map(child => child.name.toIntOption.map(_ -> child))
      if (indexed.exists(_.isEmpty)) Left(s"$path contains a non-numeric array index")
      else {
        val items = indexed.flatten
        duplicateIndex(items).map(index => Left(s"$path contains duplicate index $index")).getOrElse {
          val sorted = items.sortBy(_._1)
          if (sorted.map(_._1) != sorted.indices.toList)
            Left(s"$path indexes must be contiguous from 0, got ${sorted.map(_._1).mkString(",")}")
          else
            sorted
              .foldLeft[Either[String, List[String]]](Right(Nil)) { case (acc, (index, child)) =>
                for {
                  values <- acc
                  value  <- renderNode(child, s"$path[$index]")
                } yield values :+ value
              }
              .map(values => values.mkString("[", ",", "]"))
        }
      }
    }

  private def duplicateName(nodes: List[Node]): Option[String] = {
    val names = mutable.Set.empty[String]
    nodes.iterator.map(_.name).find(name => !names.add(name))
  }

  private def duplicateIndex(items: List[(Int, Node)]): Option[Int] = {
    val seen = mutable.Set.empty[Int]
    items.iterator.map(_._1).find(index => !seen.add(index))
  }

  private def isIndex(value: String): Boolean =
    value.nonEmpty && value.forall(_.isDigit)

  private def short(value: String): String =
    value.replaceAll("\\s+", " ").trim.take(80)

  private def jsonString(value: String): String = {
    val sb = new StringBuilder("\"")
    value.foreach {
      case '"'          => sb.append("\\\"")
      case '\\'         => sb.append("\\\\")
      case '\n'         => sb.append("\\n")
      case '\r'         => sb.append("\\r")
      case '\t'         => sb.append("\\t")
      case '\b'         => sb.append("\\b")
      case '\f'         => sb.append("\\f")
      case c if c < ' ' =>
        val hex = c.toInt.toHexString
        sb.append("\\u").append("0" * (4 - hex.length)).append(hex)
      case c            => sb.append(c)
    }
    sb.append('"').result()
  }
}
