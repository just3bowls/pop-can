package com.just3bowls.server.bootstrap

import zio.test._

/**
 * 日志落盘的金值：`--log` > `-Dpop-can.log.dir` > 不重定向，以及"父目录自动创建 + 写不出去也不打死服务"。
 *
 * 为什么 Windows 那条路非有不可：jpackage 出来的 launcher 不能带参数（native messaging 的 manifest 不允许 args）， 所以 Windows 上只能靠
 * `--java-options "-Dpop-can.log.dir=…"` 把日志落出文件 —— 老代码只认 `--log`，那个属性设了也白设。
 */
object ServerLogPathSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("ServerMain 日志路径")(
    test("--log 显式给出时最高优先，dir 被忽略") {
      assertTrue(
        ProcessLogging.resolveLogPath(Some("/tmp/x.log"), Some("/tmp/dir")).contains("/tmp/x.log")
      )
    },
    test("只有 log.dir 时拼默认 host.log，并去掉目录尾部分隔符（含 Windows 反斜杠）") {
      assertTrue(
        ProcessLogging.resolveLogPath(None, Some("/home/u/pop-can/logs")).contains(
          "/home/u/pop-can/logs/host.log"
        ),
        ProcessLogging.resolveLogPath(None, Some("/home/u/pop-can/logs/")).contains(
          "/home/u/pop-can/logs/host.log"
        ),
        ProcessLogging.resolveLogPath(None, Some("""C:\Users\u\AppData\Local\PopCan\logs\"""))
          .contains("""C:\Users\u\AppData\Local\PopCan\logs/host.log""")
      )
    },
    test("平台可以指定不同的日志文件名") {
      assertTrue(
        ProcessLogging
          .resolveLogPath(None, Some("/home/u/pop-can/logs/"), "host-linux.log")
          .contains("/home/u/pop-can/logs/host-linux.log"),
        ProcessLogging
          .resolveLogPath(None, Some("""C:\Users\u\AppData\Local\PopCan\logs\"""), "host-windows.log")
          .contains("""C:\Users\u\AppData\Local\PopCan\logs/host-windows.log""")
      )
    },
    test("都没有 / 只给空白 时不重定向") {
      assertTrue(
        ProcessLogging.resolveLogPath(None, None).isEmpty,
        ProcessLogging.resolveLogPath(Some("  "), Some("  ")).isEmpty
      )
    },
    test("native host 默认日志落在平台日志目录，不依赖构建工作区") {
      val path = ProcessLogging.nativeHostDefaultLogPath()
      assertTrue(
        path.endsWith(s"logs${java.io.File.separator}${ProcessLogging.LogFileName}"),
        !path.contains("server/target"),
        !path.contains("server\\target")
      )
    },
    test("父目录不存在时自动创建，文件可写") {
      // 用 java.io.File（服务端也是这么写的）：`java.nio.file.Files` 在 Scala 3 的 test 体 lambda 里会编出一个
      // JVM 上不存在的 `Files$` 引用（NoClassDefFoundError），跟本用例要测的东西无关，绕开它。
      val base   = new java.io.File(System.getProperty("java.io.tmpdir"), s"th-log-spec-${java.lang.System.nanoTime()}")
      val path   = new java.io.File(new java.io.File(base, "a"), ProcessLogging.LogFileName)
      val opened = ProcessLogging.openLogFile(path.getPath)
      opened.foreach { stream =>
        stream.println("hello")
        stream.flush()
        stream.close()
      }
      assertTrue(
        opened.isDefined,
        path.isFile,
        path.length() > 0
      )
    },
    test("路径写不出去时返回 None（不抛给调用方）") {
      // 用一个"父路径是文件"的非法路径：mkdirs 必失败
      val dir  =
        new java.io.File(System.getProperty("java.io.tmpdir"), s"th-log-spec-file-${java.lang.System.nanoTime()}")
      dir.mkdirs()
      val file = new java.io.File(dir, "not-a-dir")
      file.createNewFile()
      assertTrue(ProcessLogging.openLogFile(new java.io.File(file, ProcessLogging.LogFileName).getPath).isEmpty)
    }
  )
}
