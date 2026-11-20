package com.just3bowls.model.diagnostics

import com.just3bowls.facade.{ChromeAPI, DebugModule, StorageChange}
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.scalajs.js

/**
 * 统一日志口：debug 负责命名空间开关与上色，PopCanLog 负责级别、上下文、截断与错误栈。
 *
 * 命名空间约定：链路锚点 `th:<scope>`、细节 `thv:<scope>`。 （不用 `th:<scope>:verbose`，因为 debug 的 `*` 通配会一并匹配到 verbose，级别就分不开了。）
 */
object PopCanLog {
  val LevelKey                      = "popCanLogLevel"
  val ScopesKey                     = "popCanLogScopes"
  val AllScopes                     = "*"
  val KnownScopes                   = List(
    "agent",
    "background",
    "chat",
    "cdp",
    "location",
    "memory",
    "page",
    "parser",
    "permissions",
    "provider-catalog",
    "rewrite",
    "server",
    "tabs",
    "tool",
    "webchat"
  )
  val MaxField                      = 120
  private val levelOrder            = List("off", "error", "warn", "info", "debug")
  @volatile private var level       = "info"
  @volatile private var scopes      = "*"
  @volatile private var initialized = false
  def init(): Unit                  = {
    if (initialized) {
      refresh()
      return
    }
    initialized = true
    // 先用默认值（info/*）同步生效；否则 storage 读取完成前的 `.enabled` 会读到 undefined。
    applyNamespaces()
    refresh()
    ChromeAPI.storage.onChanged.addListener(onChanged)
  }
  private val onChanged: js.Function2[js.Dictionary[StorageChange], String, Unit] =
    (changes, area) => {
      if (area == "local" && (changes.contains(LevelKey) || changes.contains(ScopesKey))) refresh()
    }
  private def refresh(): Unit                                                     =
    ChromeAPI.storage.local.get(js.Array(LevelKey, ScopesKey)).toFuture.foreach { values =>
      val nextLevel  = values
        .get(LevelKey)
        .flatMap(value => Option(value))
        .map(_.toString.trim.toLowerCase)
        .filter(levelOrder.contains)
        .getOrElse("info")
      val nextScopes = values
        .get(ScopesKey)
        .flatMap(value => Option(value))
        .map(_.toString.trim)
        .filter(_.nonEmpty)
        .getOrElse("*")
      level = nextLevel
      scopes = nextScopes
      applyNamespaces()
    }
  private def applyNamespaces(): Unit                                             = {
    val patterns   = scopes.split(",").map(_.trim).filter(_.nonEmpty) match {
      case Array()  => List("*")
      case nonEmpty => nonEmpty.toList
    }
    val namespaces = level match {
      case "info"  => patterns.map(pattern => s"th:$pattern")
      case "debug" => patterns.flatMap(pattern => List(s"th:$pattern", s"thv:$pattern"))
      case _       => Nil
    }
    if (namespaces.isEmpty) DebugModule.disable() else DebugModule.enable(namespaces.mkString(","))
  }
  def currentLevel: String                                                        = level
  def currentScopes: String                                                       = scopes
  def scopePatterns(raw: String): List[String]                                    =
    Option(raw)
      .getOrElse("")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList match {
      case Nil => List(AllScopes)
      case xs  => xs.distinct
    }
  def toggleScope(raw: String, scope: String, enabled: Boolean): String           = {
    val current = scopePatterns(raw).filterNot(_ == AllScopes)
    val next    = if (enabled) (current :+ scope).distinct else current.filterNot(_ == scope)
    if (next.isEmpty) AllScopes else next.mkString(",")
  }
  def allows(min: String): Boolean                                                = {
    val current = levelOrder.indexOf(level)
    val wanted  = levelOrder.indexOf(min)
    current >= 0 && wanted >= 0 && current >= wanted
  }
  def short(text: String, max: Int = MaxField): String                            =
    if (text == null) "" else if (text.length <= max) text else text.take(max) + "…"
  def logger(scope: String): PopCanLog                                            = new PopCanLog(scope, "")
  def withContext(scope: String, context: (String, String)*): PopCanLog           =
    new PopCanLog(scope, renderContext(context))
  private[model] def renderContext(context: Seq[(String, String)]): String        =
    context.filter(_._2 != null).filter(_._2.nonEmpty).map { case (key, value) => s"$key=$value" }.mkString(" ")
  private[model] def emit(levelName: String, line: String): Unit                  =
    levelName match {
      case "warn"  => dom.console.warn(line)
      case "error" => dom.console.error(line)
      case _       => dom.console.log(line)
    }

  /** 快捷方式：`PopCanLog.info("cdp", msg)`，等价于 `logger("cdp").info(msg)`。 */
  def debug(scope: String, msg: => String): Unit                          = logger(scope).debug(msg)
  def info(scope: String, msg: => String): Unit                           = logger(scope).info(msg)
  def warn(scope: String, msg: => String): Unit                           = logger(scope).warn(msg)
  def error(scope: String, msg: => String, cause: Throwable = null): Unit = logger(scope).error(msg, cause)
}

/** 某个 scope + 上下文的 logger；`info` 走锚点命名空间，`debug` 走细节命名空间。 */
final class PopCanLog private[model] (scope: String, context: String) {
  private val anchor                                   = DebugModule(s"th:$scope")
  private val verbose                                  = DebugModule(s"thv:$scope")
  def withContext(extra: (String, String)*): PopCanLog = {
    val merged = List(context, PopCanLog.renderContext(extra)).filter(_.nonEmpty).mkString(" ")
    new PopCanLog(scope, merged)
  }
  private def prefix: String                           = if (context.isEmpty) s"[$scope]" else s"[$scope] $context"
  def info(msg: => String): Unit  = if (anchor.enabled.getOrElse(false)) PopCanLog.emit("info", s"$prefix $msg")
  def debug(msg: => String): Unit = if (verbose.enabled.getOrElse(false)) PopCanLog.emit("debug", s"$prefix $msg")
  def warn(msg: => String): Unit  = if (PopCanLog.allows("warn")) PopCanLog.emit("warn", s"$prefix $msg")
  def error(msg: => String, cause: Throwable = null): Unit =
    if (PopCanLog.allows("error")) {
      val detail =
        if (cause == null) ""
        else Option(cause.getStackTrace).map(_.take(6).mkString("\n    ", "\n    ", "")).getOrElse("")
      val head   =
        if (cause == null) ""
        else s" (${cause.getClass.getSimpleName}: ${PopCanLog.short(Option(cause.getMessage).getOrElse(""), 200)})"
      PopCanLog.emit("error", s"$prefix $msg$head$detail")
    }
}
