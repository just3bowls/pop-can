package com.just3bowls.api.marker

import scala.collection.mutable

/**
 * Single source of truth for marker extraction and streaming settlement.
 *
 * Streaming consumers can only append text, so a field value may be exposed only when no future chunk can turn its tail
 * into a structural marker. The final strict parse uses the same field and tool-call grammar.
 */
object MarkerSettlement {

  final case class ToolCall(name: String, arguments: String)
  final case class Parsed(content: String, reasoning: Option[String], toolCalls: List[ToolCall])
  final case class Settled(content: String, reasoning: String)

  private def openMarker(name: String): String = s"@@BEG:$name@@"

  private def closeMarker(name: String): String = s"@@END:$name@@"

  /** Values that are safe to append to a streaming consumer from the current raw prefix. */
  def settledValues(rawText: String): Settled =
    Settled(
      content = settledTagValue(Option(rawText).getOrElse(""), "content"),
      reasoning = settledTagValue(Option(rawText).getOrElse(""), "reasoning_content")
    )

  def strict(rawText: String): Either[List[String], Parsed] = {
    val text    = Option(rawText).getOrElse("")
    val opening = openMarker("response")
    val closing = closeMarker("response")
    val start   = text.indexOf(opening)
    if (text.trim.isEmpty) Left(List("Browser response contained no text at all"))
    else if (!text.contains("@@BEG:") && !text.contains("@@END:"))
      Left(List("Browser response did not use the required marker protocol"))
    else if (start < 0) Left(List("Browser response did not use the required @@BEG:response@@ marker"))
    else {
      val errors     = mutable.ArrayBuffer.empty[String]
      val bodyStart  = start + opening.length
      val close      = text.indexOf(closing, bodyStart)
      if (close < 0) errors += "Browser response is missing @@END:response@@"
      val body       = text.substring(bodyStart, if (close >= 0) close else text.length)
      val unpaired   =
        List("reasoning_content", "content", "tool_calls", "tool_call", "name", "arguments")
          .filter(name => isUnpairedMarker(body, name))
      if (unpaired.nonEmpty)
        errors += s"Browser response has unpaired marker(s): ${unpaired.mkString(", ")}"
      val duplicated =
        List("reasoning_content", "content", "tool_calls")
          .filter(name =>
            countOccurrences(body, openMarker(name)) > 1 ||
              countOccurrences(body, closeMarker(name)) > 1
          )
      if (duplicated.nonEmpty)
        errors += s"Browser response has duplicate marker pair(s): ${duplicated.mkString(", ")}"

      val hasToolCalls = hasCompleteMarker(body, "tool_calls")
      val hasToolCall  = hasCompleteMarker(body, "tool_call")
      val required     =
        if (hasToolCalls && hasToolCall) List("reasoning_content")
        else List("reasoning_content", "content")
      val missing      = required.filterNot(name => hasCompleteMarker(body, name))
      if (missing.nonEmpty)
        errors += s"Browser response is missing required marker(s): ${missing.mkString(", ")}"
      if (hasToolCall && !hasToolCalls)
        errors += "Browser response contains @@BEG:tool_call@@ outside @@BEG:tool_calls@@"
      if (hasToolCalls && !hasToolCall)
        errors += "Browser response @@BEG:tool_calls@@ does not contain a tool_call"
      if (
        tagBodies(body, "tool_call").exists(callBody =>
          !hasCompleteMarker(callBody, "name") || !hasCompleteMarker(callBody, "arguments")
        )
      )
        errors += "Browser response contains a malformed tool_call"

      val parsedContent   = tagSlice(body, "content").getOrElse("")
      val parsedReasoning = tagSlice(body, "reasoning_content")
      val parsedCalls     = parseToolCalls(text) match {
        case Left(error)  =>
          errors += error
          Nil
        case Right(calls) => calls
      }
      if (parsedCalls.nonEmpty && parsedContent.nonEmpty)
        errors += "Browser response cannot include content when tool_calls are present"
      if (parsedCalls.isEmpty && parsedContent.isEmpty)
        errors += "Browser response must include content or tool_calls"

      val distinct = errors.toList.distinct.take(20)
      if (distinct.nonEmpty) Left(distinct)
      else Right(Parsed(parsedContent, parsedReasoning, parsedCalls))
    }
  }

  private def settledTagValue(rawText: String, name: String): String = {
    val opening   = openMarker(name)
    val openIndex = rawText.indexOf(opening)
    if (openIndex < 0) ""
    else {
      val valueStart = openIndex + opening.length
      val closing    = closeMarker(name)
      val closeIndex = rawText.indexOf(closing, valueStart)
      if (closeIndex >= 0) rawText.substring(valueStart, closeIndex).trim
      else {
        val value    = rawText.substring(valueStart)
        val safeSize =
          StreamSettlement.settledLength(value, List(StreamSettlement.AtPrefix(closing, Nil)))
        value.substring(0, safeSize).trim
      }
    }
  }

  private def tagSlice(rawText: String, name: String): Option[String] = {
    val opening   = openMarker(name)
    val closing   = closeMarker(name)
    val openIndex = rawText.indexOf(opening)
    if (openIndex < 0) None
    else {
      val closeIndex = rawText.indexOf(closing, openIndex + opening.length)
      val value      =
        if (closeIndex < 0) ""
        else rawText.substring(openIndex + opening.length, closeIndex).trim
      if (value.nonEmpty) Some(value) else None
    }
  }

  private def hasCompleteMarker(rawText: String, name: String): Boolean = {
    val opening   = openMarker(name)
    val closing   = closeMarker(name)
    val openIndex = rawText.indexOf(opening)
    openIndex >= 0 && rawText.indexOf(closing, openIndex + opening.length) >= 0
  }

  private def hasMarkerOpening(rawText: String, name: String): Boolean =
    rawText.indexOf(openMarker(name)) >= 0

  private def hasMarkerClosing(rawText: String, name: String): Boolean =
    rawText.indexOf(closeMarker(name)) >= 0

  private def countOccurrences(rawText: String, needle: String): Int = {
    var count = 0
    var from  = 0
    var index = rawText.indexOf(needle, from)
    while (index >= 0) {
      count += 1
      from = index + needle.length
      index = rawText.indexOf(needle, from)
    }
    count
  }

  /** Strict pairing: if either half appears, both must appear. */
  private def isUnpairedMarker(rawText: String, name: String): Boolean =
    hasMarkerOpening(rawText, name) != hasMarkerClosing(rawText, name)

  private def tagBodies(rawText: String, name: String): List[String] = {
    val bodies  = mutable.ArrayBuffer.empty[String]
    val opening = openMarker(name)
    val closing = closeMarker(name)
    var from    = 0
    var running = true
    while (running) {
      val openIndex = rawText.indexOf(opening, from)
      if (openIndex < 0) running = false
      else {
        val closeIndex = rawText.indexOf(closing, openIndex + opening.length)
        val valueEnd   = if (closeIndex < 0) rawText.length else closeIndex
        bodies.append(rawText.substring(openIndex + opening.length, valueEnd).trim)
        if (closeIndex < 0) running = false
        else from = closeIndex + closing.length
      }
    }
    bodies.toList
  }

  private def parseToolCalls(rawText: String): Either[String, List[ToolCall]] =
    tagBodies(rawText, "tool_call")
      .foldLeft[Either[String, List[ToolCall]]](Right(Nil)) { case (acc, body) =>
        for {
          calls     <- acc
          toolName  <- tagSlice(body, "name").filter(_.nonEmpty).toRight("tool_call is missing its name")
          arguments <- MarkerArguments
            .parse(tagSlice(body, "arguments").getOrElse(""))
            .left
            .map(errors => s"$toolName arguments: ${errors.take(20).mkString(" | ")}")
        } yield calls :+ ToolCall(toolName, arguments)
      }
}
