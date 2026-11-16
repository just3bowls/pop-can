package com.just3bowls.server.bootstrap

import java.nio.file.Paths

import zio.test._

object AppPathsSpec extends ZIOSpecDefault {
  private val home = Paths.get("/home/user")

  private def environment(
      os: String,
      xdgData: Option[String] = None,
      xdgState: Option[String] = None,
      localAppData: Option[String] = None
  ): AppPaths.Environment =
    AppPaths.Environment(
      osName = os,
      home = home,
      xdgDataHome = xdgData.map(Paths.get(_)),
      xdgStateHome = xdgState.map(Paths.get(_)),
      localAppData = localAppData.map(Paths.get(_))
    )

  def spec: Spec[Any, Any] = suite("AppPaths")(
    test("Linux uses XDG data/state directories") {
      val env = environment(
        "Linux",
        xdgData = Some("/xdg/data"),
        xdgState = Some("/xdg/state")
      )
      assertTrue(
        AppPaths.defaultDataDir(env) == Paths.get("/xdg/data/pop-can"),
        AppPaths.defaultLogDir(env) == Paths.get("/xdg/state/pop-can/logs")
      )
    },
    test("Linux falls back to home data/state directories") {
      val env = environment("Linux")
      assertTrue(
        AppPaths.defaultDataDir(env) == home.resolve(".local/share/pop-can"),
        AppPaths.defaultLogDir(env) == home.resolve(".local/state/pop-can/logs")
      )
    },
    test("macOS uses Application Support for data and Library/Logs for logs") {
      val env = environment("Mac OS X")
      assertTrue(
        AppPaths.defaultDataDir(env) == home.resolve("Library/Application Support/PopCan"),
        AppPaths.defaultLogDir(env) == home.resolve("Library/Logs/PopCan")
      )
    },
    test("Windows uses LocalAppData for data and logs") {
      val env = environment("Windows 11", localAppData = Some("/local"))
      assertTrue(
        AppPaths.defaultDataDir(env) == Paths.get("/local/PopCan"),
        AppPaths.defaultLogDir(env) == Paths.get("/local/PopCan/logs")
      )
    },
    test("explicit data and log overrides win over platform defaults") {
      val env = environment("Linux")
      assertTrue(
        AppPaths.resolveDataDir(env, Some(Paths.get("/override/data"))) == Paths.get("/override/data"),
        AppPaths.resolveLogDir(env, Some(Paths.get("/override/logs"))) == Paths.get("/override/logs")
      )
    }
  )
}
