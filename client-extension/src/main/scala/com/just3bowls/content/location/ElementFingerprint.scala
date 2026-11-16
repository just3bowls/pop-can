package com.just3bowls.content.location

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.JsonValue
import scala.collection.mutable.ListBuffer
import scala.scalajs.js
import com.just3bowls.model.diagnostics.PopCanLog
object ElementFingerprint {
  private val Threshold                                                               = 0.68
  // Focus captured while recording changes the editor's transient classes (e.g. ProseMirror-focused).
  // A sole visible editable candidate with a still-strong fingerprint is safe to recover without
  // requiring the same normal match score; competing candidates still have to pass Threshold.
  private val SoloInputThreshold                                                      = 0.55
  private def attr(el: js.Any, name: String): String                                  =
    try {
      val raw = JsBridge.call(el, "getAttribute", name)
      if (raw == null || js.isUndefined(raw)) "" else raw.toString
    } catch {
      case _: Throwable => ""
    }
  private def classNameOf(el: js.Any): String                                         =
    try {
      val raw = JsBridge.field(el, "className")
      if (raw == null || js.isUndefined(raw)) ""
      else if (JsBridge.field(raw, "baseVal") != null && !js.isUndefined(JsBridge.field(raw, "baseVal")))
        JsBridge.field(raw, "baseVal").toString
      else raw.toString
    } catch {
      case _: Throwable => ""
    }
  private def tagOf(el: js.Any): String                                               =
    try {
      val raw = JsBridge.field(el, "tagName")
      if (raw == null || js.isUndefined(raw)) ""
      else raw.toString.toLowerCase
    } catch {
      case _: Throwable => ""
    }
  private def normalizeText(raw: String): String                                      =
    raw.trim.replaceAll("\\s+", " ").take(200)
  private def textOf(el: js.Any): String                                              =
    try {
      val raw = JsBridge.field(el, "textContent")
      if (raw == null || js.isUndefined(raw)) "" else normalizeText(raw.toString)
    } catch {
      case _: Throwable => ""
    }
  private def parentContext(el: js.Any): (String, List[String], String, List[String]) = {
    val parent = JsBridge.field(el, "parentElement")
    if (parent == null || js.isUndefined(parent)) {
      ("", Nil, "", Nil)
    } else {
      val direct   = new StringBuilder
      val siblings = ListBuffer.empty[String]
      try {
        val nodes  = JsBridge.field(parent, "childNodes")
        val length = JsBridge.asInt(JsBridge.field(nodes, "length"))
        var i      = 0
        while (i < length) {
          val node = JsBridge.call(nodes, "item", i)
          if (node != null && !js.isUndefined(node)) {
            val nodeType = JsBridge.asInt(JsBridge.field(node, "nodeType"))
            if (nodeType == 3) {
              val text = JsBridge.field(node, "textContent")
              if (text != null && !js.isUndefined(text)) direct.append(text.toString)
            } else if (nodeType == 1 && node != el) {
              val childText = textOf(node)
              if (childText.nonEmpty) siblings += childText
            }
          }
          i += 1
        }
      } catch {
        case _: Throwable => ()
      }
      (
        tagOf(parent),
        classNameOf(parent).split("\\s+").toList.filter(_.nonEmpty),
        normalizeText(direct.toString),
        siblings.toList
      )
    }
  }
  private def ancestorContext(el: js.Any): (List[String], List[String])               = {
    val classes = ListBuffer.empty[String]
    val texts   = ListBuffer.empty[String]
    var node    = JsBridge.field(el, "parentElement")
    var depth   = 0
    while (node != null && !js.isUndefined(node) && depth < 3) {
      classes ++= classNameOf(node).split("\\s+").filter(_.nonEmpty)
      val text = textOf(node)
      if (text.nonEmpty) texts += text.take(120)
      node = JsBridge.field(node, "parentElement")
      depth += 1
    }
    (classes.toList, texts.toList)
  }
  private def hasEditableNearby(el: js.Any): Boolean                                  = {
    var parent = JsBridge.field(el, "parentElement")
    var depth  = 0
    while (parent != null && !js.isUndefined(parent) && depth < 3) {
      try {
        val children = JsBridge.field(parent, "children")
        val count    = JsBridge.asInt(JsBridge.field(children, "length"))
        var i        = 0
        while (i < count) {
          val child = JsBridge.call(children, "item", i)
          if (child != null && !js.isUndefined(child) && child != el && editableOf(child)) {
            return true
          }
          i += 1
        }
      } catch {
        case _: Throwable => ()
      }
      parent = JsBridge.field(parent, "parentElement")
      depth += 1
    }
    false
  }
  private def editableOf(el: js.Any): Boolean                                         = {
    val tag = tagOf(el)
    if (tag == "textarea" || tag == "input") true
    else {
      val editable = attr(el, "contenteditable")
      editable.nonEmpty && editable != "false"
    }
  }
  private def clickableOf(el: js.Any): Boolean                                        = {
    val tag  = tagOf(el)
    val role = attr(el, "role")
    tag == "button" || tag == "a" || role == "button" || tag == "svg"
  }
  private def disabledOf(el: js.Any): Boolean                                         =
    try {
      val nativeDisabled = JsBridge.booleanField(el, "disabled").getOrElse(false)
      val ariaDisabled   = attr(el, "aria-disabled")
      val hasDisabled    = JsBridge.undefinedBoolean(JsBridge.call(el, "hasAttribute", "disabled")).getOrElse(false)
      nativeDisabled || ariaDisabled == "true" || hasDisabled
    } catch {
      case _: Throwable => false
    }
  private def visibleOf(el: js.Any): Boolean                                          =
    try {
      val rect = JsBridge.call(el, "getBoundingClientRect")
      if (
        JsBridge.asDouble(JsBridge.field(rect, "width")) <= 0 ||
        JsBridge.asDouble(JsBridge.field(rect, "height")) <= 0
      ) false
      else {
        val style      = JsBridge.getComputedStyle(el)
        val display    = JsBridge.undefinedString(style.display).getOrElse("")
        val visibility = JsBridge.undefinedString(style.visibility).getOrElse("")
        val ariaHidden = attr(el, "aria-hidden")
        display != "none" && visibility != "hidden" && ariaHidden != "true"
      }
    } catch {
      case _: Throwable => false
    }
  private def clickableWithin(el: js.Any): Boolean                                    =
    clickableOf(el) ||
      (try {
        JsBridge.call(
          el,
          "querySelector",
          "button, [role=\"button\"], a[href], input[type='submit'], input[type='button']"
        ) != null
      } catch {
        case _: Throwable => false
      })
  private def usableForRole(el: js.Any, role: String): Boolean                        =
    if (el == null || js.isUndefined(el) || !visibleOf(el) || disabledOf(el)) false
    else
      role match {
        case "input" => editableOf(el)
        case "send"  => clickableWithin(el)
        case _       => true
      }
  def extract(el: js.Any, role: String = ""): js.Any                                  = {
    if (el == null || js.isUndefined(el)) return null
    val (parentTag, parentClasses, parentText, siblingTexts)         = parentContext(el)
    val (ancestorClasses, ancestorTexts)                             = ancestorContext(el)
    val nearestText                                                  =
      siblingTexts.headOption.getOrElse(parentText).take(120)
    val rect                                                         = JsBridge.call(el, "getBoundingClientRect")
    val rectLeft                                                     = JsBridge.asDouble(JsBridge.field(rect, "left"))
    val rectTop                                                      = JsBridge.asDouble(JsBridge.field(rect, "top"))
    val rectWidth                                                    = JsBridge.asDouble(JsBridge.field(rect, "width"))
    val rectHeight                                                   = JsBridge.asDouble(JsBridge.field(rect, "height"))
    val x                                                            = rectLeft + rectWidth / 2
    val y                                                            = rectTop + rectHeight / 2
    val scrollX                                                      = JsBridge.asDouble(JsBridge.window.scrollX)
    val scrollY                                                      = JsBridge.asDouble(JsBridge.window.scrollY)
    val docX                                                         = x + scrollX
    val docY                                                         = y + scrollY
    var anchorDeltaX                                                 = 0.0
    var anchorDeltaY                                                 = 0.0
    if (role == "send") {
      val input = JsBridge.document.querySelector("[data-th-role=\"input\"]")
      if (input != null && !js.isUndefined(input)) {
        val inputRect = input.getBoundingClientRect()
        anchorDeltaX = x - (JsBridge.asDouble(inputRect.left) + JsBridge.asDouble(inputRect.width) / 2)
        anchorDeltaY = y - (JsBridge.asDouble(inputRect.top) + JsBridge.asDouble(inputRect.height) / 2)
      }
    }
    JsBridge.obj(
      "tag"         -> (tagOf(el)),
      "id"          -> (attr(el, "id")),
      "classes"     -> (js.Array(classNameOf(el).split("\\s+").filter(_.nonEmpty)*)),
      "role"        -> (attr(el, "role")),
      "ariaLabel"   -> (attr(el, "aria-label")),
      "placeholder" -> (attr(el, "placeholder")),
      "name"        -> (attr(el, "name")),
      "dataTestId"  -> (attr(el, "data-testid")),
      "text"        -> (textOf(el)),
      "editable"    -> (editableOf(el)),
      "clickable"   -> (clickableOf(el)),
      "parentTag"   -> (parentTag),
      "parentClasses"   -> (js.Array(parentClasses*)),
      "parentText"      -> (parentText),
      "siblingTexts"    -> (js.Array(siblingTexts*)),
      "ancestorClasses" -> (js.Array(ancestorClasses*)),
      "ancestorTexts"   -> (js.Array(ancestorTexts*)),
      "nearestText"     -> (nearestText),
      "nearInput"       -> (hasEditableNearby(el)),
      "capturedAt"      -> (js.Date.now()),
      "x"               -> (x),
      "y"               -> (y),
      "width"           -> (rectWidth),
      "height"          -> (rectHeight),
      "scrollX"         -> (scrollX),
      "scrollY"         -> (scrollY),
      "innerWidth"      -> (JsBridge.asDouble(JsBridge.window.innerWidth)),
      "innerHeight"     -> (JsBridge.asDouble(JsBridge.window.innerHeight)),
      "docX"            -> (docX),
      "docY"            -> (docY),
      "anchorDeltaX"    -> (anchorDeltaX),
      "anchorDeltaY"    -> (anchorDeltaY)
    )
  }
  private def levenshtein(a: String, b: String): Int                                  = {
    if (a == b) return 0
    val left  = a.take(200)
    val right = b.take(200)
    if (left.isEmpty) return right.length
    if (right.isEmpty) return left.length
    val prev  = Array.tabulate(right.length + 1)(identity)
    val curr  = new Array[Int](right.length + 1)
    var i     = 1
    while (i <= left.length) {
      curr(0) = i
      var j  = 1
      while (j <= right.length) {
        val cost = if (left.charAt(i - 1) == right.charAt(j - 1)) 0 else 1
        curr(j) = Math.min(
          Math.min(curr(j - 1) + 1, prev(j) + 1),
          prev(j - 1) + cost
        )
        j += 1
      }
      var j2 = 0
      while (j2 <= right.length) {
        prev(j2) = curr(j2)
        j2 += 1
      }
      i += 1
    }
    prev(right.length)
  }
  private def stringSimilarity(a: String, b: String): Double                          = {
    val left  = normalizeText(a).toLowerCase
    val right = normalizeText(b).toLowerCase
    if (left.isEmpty && right.isEmpty) 1.0
    else if (left.isEmpty || right.isEmpty) 0.0
    else {
      val longest = Math.max(left.length, right.length)
      1.0 - levenshtein(left, right).toDouble / longest.toDouble
    }
  }
  private def tokenize(value: String): Set[String]                                    =
    value.toLowerCase.split("[\\s\\-_/:.,]+").filter(_.nonEmpty).toSet
  private def jaccard(left: List[String], right: List[String]): Double                = {
    val a = left.flatMap(tokenize).toSet
    val b = right.flatMap(tokenize).toSet
    if (a.isEmpty && b.isEmpty) 1.0
    else if (a.isEmpty || b.isEmpty) 0.0
    else a.intersect(b).size.toDouble / a.union(b).size.toDouble
  }
  private def classSimilarity(expected: List[String], current: List[String]): Double  =
    jaccard(expected, current)
  private def attrSimilarity(meta: js.Any, el: js.Any): Double                        = {
    val pairs    = List(
      ("role", "role"),
      ("ariaLabel", "aria-label"),
      ("placeholder", "placeholder"),
      ("name", "name"),
      ("dataTestId", "data-testid")
    )
    val expected = pairs.map { case (metaKey, attrName) =>
      val value = JsonValue.stringField(meta, metaKey).getOrElse("")
      attrName -> value
    }.filter(_._2.nonEmpty)
    if (expected.isEmpty) 0.5
    else {
      val scores = expected.map { case (attrName, value) =>
        stringSimilarity(attr(el, attrName), value)
      }
      scores.sum / scores.length.toDouble
    }
  }
  private def anchorSimilarity(meta: js.Any, el: js.Any): Double                      = {
    val metaParentTag                                        = JsBridge.stringField(meta, "parentTag").getOrElse("")
    val metaParentClasses                                    = JsonValue.stringListField(meta, "parentClasses")
    val metaParentText                                       = JsBridge.stringField(meta, "parentText").getOrElse("")
    val metaSiblingTexts                                     = JsonValue.stringListField(meta, "siblingTexts")
    val (parentTag, parentClasses, parentText, siblingTexts) = parentContext(el)
    val parentTagScore                                       =
      if (metaParentTag.nonEmpty) {
        if (parentTag == metaParentTag) 1.0 else 0.2
      } else 0.5
    val parentClassScore                                     =
      if (metaParentClasses.nonEmpty) classSimilarity(metaParentClasses, parentClasses)
      else 0.5
    val parentTextScore                                      = stringSimilarity(metaParentText, parentText)
    val siblingScore                                         =
      if (metaSiblingTexts.nonEmpty) jaccard(metaSiblingTexts, siblingTexts)
      else 0.5
    parentTagScore * 0.25 +
      parentClassScore * 0.35 +
      parentTextScore * 0.15 +
      siblingScore * 0.25
  }
  private def contextSimilarity(meta: js.Any, el: js.Any): Double                     = {
    val metaAncestorClasses              = JsonValue.stringListField(meta, "ancestorClasses")
    val metaAncestorTexts                = JsonValue.stringListField(meta, "ancestorTexts")
    val metaNearestText                  = JsBridge.stringField(meta, "nearestText").getOrElse("")
    val metaNearInput                    = JsBridge.booleanField(meta, "nearInput").getOrElse(false)
    val (ancestorClasses, ancestorTexts) = ancestorContext(el)
    val ancestorClassScore               =
      if (metaAncestorClasses.nonEmpty) classSimilarity(metaAncestorClasses, ancestorClasses)
      else 0.5
    val ancestorTextScore                =
      if (metaAncestorTexts.nonEmpty) jaccard(metaAncestorTexts, ancestorTexts)
      else 0.5
    val nearestTextScore                 = stringSimilarity(metaNearestText, nearestTextOf(el))
    val nearInputScore                   =
      if (metaNearInput) {
        if (hasEditableNearby(el)) 1.0 else 0.0
      } else 0.5
    ancestorClassScore * 0.4 +
      ancestorTextScore * 0.3 +
      nearestTextScore * 0.2 +
      nearInputScore * 0.1
  }
  private def nearestTextOf(el: js.Any): String                                       = {
    val (_, _, parentText, siblingTexts) = parentContext(el)
    siblingTexts.headOption.getOrElse(parentText).take(120)
  }
  private def interactionSimilarity(meta: js.Any, el: js.Any): Double                 = {
    val metaEditable  = JsBridge.booleanField(meta, "editable").getOrElse(false)
    val metaClickable = JsBridge.booleanField(meta, "clickable").getOrElse(false)
    if (metaEditable) {
      if (editableOf(el)) 1.0 else 0.0
    } else if (metaClickable) {
      if (clickableOf(el)) 1.0 else 0.0
    } else {
      0.5
    }
  }
  private def geometrySimilarity(meta: js.Any, el: js.Any): Double                    = {
    val metaDocX = JsBridge.doubleField(meta, "docX").getOrElse(0.0)
    val metaDocY = JsBridge.doubleField(meta, "docY").getOrElse(0.0)
    val metaW    = JsBridge.doubleField(meta, "innerWidth").getOrElse(0.0)
    val metaH    = JsBridge.doubleField(meta, "innerHeight").getOrElse(0.0)
    if (metaW <= 0 || metaH <= 0) 0.5
    else {
      val rect   = JsBridge.call(el, "getBoundingClientRect")
      val curX   =
        JsBridge.asDouble(JsBridge.field(rect, "left")) + JsBridge.asDouble(JsBridge.field(rect, "width")) / 2
      val curY   =
        JsBridge.asDouble(JsBridge.field(rect, "top")) + JsBridge.asDouble(JsBridge.field(rect, "height")) / 2
      val docX   = curX + JsBridge.asDouble(JsBridge.window.scrollX)
      val docY   = curY + JsBridge.asDouble(JsBridge.window.scrollY)
      val dist   = Math.sqrt(Math.pow(docX - metaDocX, 2) + Math.pow(docY - metaDocY, 2))
      val maxDim = Math.max(metaW, metaH)
      Math.max(0.0, 1.0 - dist / maxDim)
    }
  }
  private def sendAnchorSimilarity(meta: js.Any, el: js.Any): Double                  = {
    val metaDeltaX = JsBridge.doubleField(meta, "anchorDeltaX").getOrElse(0.0)
    val metaDeltaY = JsBridge.doubleField(meta, "anchorDeltaY").getOrElse(0.0)
    val input      = JsBridge.document.querySelector("[data-th-role=\"input\"]")
    if (input == null || js.isUndefined(input) || (metaDeltaX == 0 && metaDeltaY == 0)) 0.5
    else {
      val inputRect = input.getBoundingClientRect()
      val inputX    = JsBridge.asDouble(inputRect.left) + JsBridge.asDouble(inputRect.width) / 2
      val inputY    = JsBridge.asDouble(inputRect.top) + JsBridge.asDouble(inputRect.height) / 2
      val rect      = JsBridge.call(el, "getBoundingClientRect")
      val curX      =
        JsBridge.asDouble(JsBridge.field(rect, "left")) + JsBridge.asDouble(JsBridge.field(rect, "width")) / 2
      val curY      =
        JsBridge.asDouble(JsBridge.field(rect, "top")) + JsBridge.asDouble(JsBridge.field(rect, "height")) / 2
      val expectedX = inputX + metaDeltaX
      val expectedY = inputY + metaDeltaY
      val dist      = Math.sqrt(Math.pow(curX - expectedX, 2) + Math.pow(curY - expectedY, 2))
      Math.max(0.0, 1.0 - dist / 250.0)
    }
  }
  def score(meta: js.Any, el: js.Any, role: String = ""): Double                      = {
    if (meta == null || js.isUndefined(meta) || el == null || js.isUndefined(el)) return 0.0
    val metaTag          = JsBridge.stringField(meta, "tag").getOrElse("")
    val metaClasses      = JsonValue.stringListField(meta, "classes")
    val metaText         = JsBridge.stringField(meta, "text").getOrElse("")
    val currentTag       = tagOf(el)
    val currentClasses   = classNameOf(el).split("\\s+").toList.filter(_.nonEmpty)
    val tagScore         = if (metaTag.nonEmpty && currentTag == metaTag) 1.0 else 0.35
    val textScore        = stringSimilarity(metaText, textOf(el))
    val attrScore        = attrSimilarity(meta, el)
    val classScore       =
      if (metaClasses.nonEmpty || currentClasses.nonEmpty) classSimilarity(metaClasses, currentClasses)
      else 1.0
    val domAnchorScore   = anchorSimilarity(meta, el) * 0.75 + contextSimilarity(meta, el) * 0.25
    val interactionScore = interactionSimilarity(meta, el)
    val sendAnchorScore  = if (role == "send") sendAnchorSimilarity(meta, el) else 0.5
    val geometryScore    = geometrySimilarity(meta, el)
    tagScore * 0.10 +
      textScore * 0.20 +
      attrScore * 0.10 +
      classScore * 0.10 +
      domAnchorScore * 0.20 +
      interactionScore * 0.10 +
      sendAnchorScore * 0.10 +
      geometryScore * 0.10
  }
  private def escapeCss(value: String): String                                        =
    value.replaceAll("([^a-zA-Z0-9_-])", "\\\\$1")
  private def candidateSelector(meta: js.Any, role: String): String                   = {
    if (role == "input") {
      return "textarea,input,[contenteditable],[contenteditable='plaintext-only']"
    }
    if (role == "send") {
      return List(
        "button",
        "[role='button']",
        "a[href]",
        "svg",
        "input[type='submit']",
        "input[type='button']",
        "[class*='send']",
        "[class*='submit']",
        "[aria-label]",
        "[title]"
      ).mkString(",")
    }
    val tag     = JsBridge.stringField(meta, "tag").getOrElse("")
    val classes = JsonValue.stringListField(meta, "classes")
    val parts   = ListBuffer.empty[String]
    parts ++= List(
      "button",
      "[role='button']",
      "a[href]",
      "svg",
      "input[type='submit']",
      "textarea",
      "input",
      "[contenteditable='true']",
      "[contenteditable='plaintext-only']",
      "[class*='button']",
      "[class*='send']",
      "[class*='submit']"
    )
    classes.take(3).filter(_.nonEmpty).foreach(c => parts += "." + escapeCss(c))
    if (tag.nonEmpty && tag != "html" && tag != "body") parts += tag
    parts.distinct.mkString(",")
  }
  private def findDirect(selector: String): js.Any                                    = {
    if (selector.trim.isEmpty) return null
    val document = JsBridge.document
    try {
      if (selector.trim.startsWith("/")) {
        document.evaluate(
          selector,
          document,
          null,
          JsBridge.xpathResult.FIRST_ORDERED_NODE_TYPE,
          null
        ).singleNodeValue
      } else {
        document.querySelector(selector)
      }
    } catch {
      case _: Throwable => null
    }
  }
  def findRole(selector: String, role: String, meta: js.Any = null): js.Any           = {
    val direct = findDirect(selector)
    if (direct != null && !js.isUndefined(direct) && usableForRole(direct, role)) {
      return direct
    }
    if (meta == null || js.isUndefined(meta)) null
    else findBestWithin(meta, role, JsBridge.document)
  }
  def findBestAnchored(meta: js.Any, role: String, selector: String): js.Any          =
    findRole(selector, role, meta)
  private def findBestWithin(meta: js.Any, role: String, root: js.Any): js.Any        = {
    val selector     = candidateSelector(meta, role)
    val nodes        =
      try JsBridge.call(root, "querySelectorAll", selector)
      catch { case _: Throwable => return null }
    val length       = JsBridge.asInt(JsBridge.field(nodes, "length"))
    var best: js.Any = null
    var bestScore    = 0.0
    var secondScore  = 0.0
    var i            = 0
    while (i < length) {
      val node = JsBridge.call(nodes, "item", i)
      if (node != null && !js.isUndefined(node)) {
        val existing      = JsBridge.call(node, "getAttribute", "data-th-role")
        val alreadyMarked =
          existing != null && !js.isUndefined(existing) && existing.toString.nonEmpty
        if (!alreadyMarked && usableForRole(node, role)) {
          val currentScore = score(meta, node, role)
          if (currentScore > bestScore) {
            secondScore = bestScore
            bestScore = currentScore
            best = node
          } else if (currentScore > secondScore) {
            secondScore = currentScore
          }
        }
      }
      i += 1
    }
    PopCanLog.debug(
      "location",
      s"heal candidates role=$role evaluated=$length best=$bestScore second=$secondScore threshold=$Threshold"
    )
    val normalMatch  = best != null && bestScore >= Threshold && bestScore - secondScore >= 0.03
    val soloInput    = role == "input" && best != null && secondScore == 0.0 && bestScore >= SoloInputThreshold
    if (normalMatch || soloInput) {
      best
    } else {
      if (best != null) {
        PopCanLog.debug(
          "location",
          s"heal rejected role=$role best=$bestScore second=$secondScore gap=${bestScore - secondScore} threshold=$Threshold"
        )
      }
      null
    }
  }
  def findBest(meta: js.Any, role: String): js.Any                                    =
    findBestWithin(meta, role, JsBridge.document)
}
