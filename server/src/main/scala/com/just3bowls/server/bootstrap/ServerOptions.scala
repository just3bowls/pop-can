package com.just3bowls.server.bootstrap

private[server] object ServerOptions {

  /** 命令行开关：`--native-host`（native messaging 模式）/ `--port` / `--bind` / `--log <file>`。 */
  final case class Options(
      nativeHost: Boolean = false,
      port: Int = 8080,
      bind: String = "127.0.0.1",
      logFile: Option[String] = None
  )

  /**
   * native host 模式的**两条通道**（纯逻辑，`server/test` 有金值）。
   *
   * jpackage app-image 的 cfg 参数在 Windows 上实测没有到达 JVM，因此构建脚本同时设置 `-Dpop-can.native-host=true`。 参数比较前先 trim：cfg 是
   * CRLF，避免把尾随 `\r` 带进精确比较。
   */
  val NativeHostProperty = "pop-can.native-host"

  def resolveNativeHost(args: List[String], prop: Option[String]): Boolean =
    args.exists(_.trim == "--native-host") || prop.map(_.trim.toLowerCase).contains("true")

  def parse(args: List[String]): Options = {
    def valueAfter(flags: List[String]): Option[String] =
      args.sliding(2).collectFirst { case List(flag, value) if flags.contains(flag) => value }
    Options(
      nativeHost = resolveNativeHost(
        args,
        Option(java.lang.System.getProperty(NativeHostProperty))
      ),
      port = valueAfter(List("--port", "-p"))
        .flatMap(_.toIntOption)
        .orElse(sys.env.get("POP_CAN_PORT").flatMap(_.toIntOption))
        .filter(port => port > 0 && port <= 65535)
        .getOrElse(8080),
      bind = valueAfter(List("--bind", "-b"))
        .orElse(sys.env.get("POP_CAN_BIND"))
        .filter(_.nonEmpty)
        .getOrElse("127.0.0.1"),
      logFile = valueAfter(List("--log")).filter(_.nonEmpty)
    )
  }
}
