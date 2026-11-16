package com.just3bowls.server.bootstrap

import java.nio.file.{Path, Paths}

/** Platform-owned runtime paths. Data and logs intentionally have different lifecycle rules. */
object AppPaths {
  private val DataDirEnv = "POP_CAN_DATA_DIR"
  private val LogDirEnv  = "POP_CAN_LOG_DIR"

  final private[bootstrap] case class Environment(
      osName: String,
      home: Path,
      xdgDataHome: Option[Path],
      xdgStateHome: Option[Path],
      localAppData: Option[Path]
  )

  def dataDir(): Path =
    resolveDataDir(currentEnvironment(), envPath(DataDirEnv))

  def memoryDir(): Path = dataDir().resolve("memory")

  def logDir(): Path =
    resolveLogDir(currentEnvironment(), envPath(LogDirEnv))

  def defaultLogFile(): Path = logDir().resolve("host.log")

  private[bootstrap] def resolveDataDir(environment: Environment, overrideDir: Option[Path]): Path =
    overrideDir.getOrElse(defaultDataDir(environment))

  private[bootstrap] def resolveLogDir(environment: Environment, overrideDir: Option[Path]): Path =
    overrideDir.getOrElse(defaultLogDir(environment))

  private[bootstrap] def defaultDataDir(environment: Environment): Path =
    platform(environment) match {
      case "windows" => environment.localAppData.getOrElse(environment.home).resolve("PopCan")
      case "macos"   => environment.home.resolve("Library").resolve("Application Support").resolve("PopCan")
      case _         =>
        environment.xdgDataHome.getOrElse(environment.home.resolve(".local").resolve("share")).resolve("pop-can")
    }

  private[bootstrap] def defaultLogDir(environment: Environment): Path =
    platform(environment) match {
      case "windows" => environment.localAppData.getOrElse(environment.home).resolve("PopCan").resolve("logs")
      case "macos"   => environment.home.resolve("Library").resolve("Logs").resolve("PopCan")
      case _         => environment.xdgStateHome.getOrElse(environment.home.resolve(".local").resolve("state")).resolve(
          "pop-can"
        ).resolve("logs")
    }

  private def platform(environment: Environment): String = {
    val os = environment.osName.toLowerCase
    if (os.contains("win")) "windows"
    else if (os.contains("mac")) "macos"
    else "linux"
  }

  private def envPath(name: String): Option[Path] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).map(Paths.get(_))

  private def currentEnvironment(): Environment =
    Environment(
      osName = Option(System.getProperty("os.name")).getOrElse(""),
      home = Paths.get(System.getProperty("user.home")),
      xdgDataHome = envPath("XDG_DATA_HOME"),
      xdgStateHome = envPath("XDG_STATE_HOME"),
      localAppData = envPath("LOCALAPPDATA")
    )
}
