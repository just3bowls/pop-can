package com.just3bowls.server.bootstrap

import java.io.{FileOutputStream, PrintStream}
import zio._

private[server] object ProcessLogging {

  /** native messaging 的 stdout 是协议通道，所以日志始终走 stderr。 */
  val stderrLogger: ZLogger[String, Any] = new ZLogger[String, Any] {
    def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
    ): Any = {
      val head = s"timestamp=${java.time.Instant.now()} level=${logLevel.label} fiber=$fiberId message=${message()}"
      java.lang.System.err.println(if (cause.isEmpty) head else s"$head cause=${cause.prettyPrint}")
    }
  }

  val LogDirProperty      = "pop-can.log.dir"
  val LogFileNameProperty = "pop-can.log.file"
  val LogFileName         = "host.log"

  /**
   * `--log` > `-Dpop-can.log.dir`（拼配置的文件名）> 不重定向。
   *
   * jpackage launcher 不能带 native manifest 参数，因此 Windows 只能靠系统属性把日志落盘。
   */
  def resolveLogPath(
      logFile: Option[String],
      logDir: Option[String],
      fileName: String = configuredFileName
  ): Option[String] =
    logFile.map(_.trim).filter(_.nonEmpty).orElse(
      logDir.map(_.trim).filter(_.nonEmpty).map(dir => s"${dir.replaceAll("[/\\\\]+$$", "")}/$fileName")
    )

  /** Native hosts never depend on a build-workspace path: default to the platform log directory. */
  def nativeHostDefaultLogPath(): String =
    AppPaths.defaultLogFile().toString

  private[server] def configuredFileName: String =
    Option(java.lang.System.getProperty(LogFileNameProperty))
      .map(_.trim)
      .filter(name => name.nonEmpty && !name.contains("/") && !name.contains("\\"))
      .getOrElse(LogFileName)

  /** 打开日志文件并创建父目录；写不出去时继续用 stderr，绝不让日志配置杀死服务。 */
  def openLogFile(path: String): Option[PrintStream] =
    try {
      val file = new java.io.File(path)
      Option(file.getParentFile).foreach(parent => if (!parent.exists()) parent.mkdirs())
      Some(new PrintStream(new FileOutputStream(file, true), true))
    } catch {
      case e: Throwable =>
        java.lang.System.err.println(s"cannot open log file $path (${e.getMessage}); keeping stderr")
        None
    }

  def redirectStderr(logPath: Option[String]): Unit =
    logPath.foreach(path => openLogFile(path).foreach(java.lang.System.setErr))
}
