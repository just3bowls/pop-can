package com.just3bowls.server.bootstrap

import zio.test._

/**
 * native host 模式的开关解析（金值）。
 *
 * 起因是一条实测事故：jpackage app-image 的 cfg 里 `[ArgOptions] arguments=--native-host` 在 **Windows 上没到达 JVM** （日志连着 20 多次
 * `nativeHost=false` → stdio 通道没建 → 端口绑不上 → 扩展按退避不停重连，滚出一串 JVM）， 而同一份 cfg 的 `-D` 选项是生效的（日志目录就来自
 * `-Dpop-can.log.dir`）。所以这里两条通道都认，并且对参数做 `trim` （cfg 是 CRLF，防某个平台把 `\r` 带进参数值）。
 */
object ServerNativeHostSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Any] = suite("native host 开关解析")(
    test("命令行 --native-host 生效") {
      assertTrue(ServerOptions.resolveNativeHost(List("--native-host"), None))
    },
    test("参数带 CR/空白也认（Windows cfg 的 CRLF 防御）") {
      assertTrue(ServerOptions.resolveNativeHost(List("--native-host\r"), None))
    },
    test("命令行没给时看 -Dpop-can.native-host=true（launcher 的第二条通道）") {
      assertTrue(
        ServerOptions.resolveNativeHost(Nil, Some("true")),
        ServerOptions.resolveNativeHost(Nil, Some("TRUE")),
        !ServerOptions.resolveNativeHost(Nil, Some("false")),
        !ServerOptions.resolveNativeHost(Nil, None)
      )
    },
    test("别的参数不会误判") {
      assertTrue(
        !ServerOptions.resolveNativeHost(List("--log", "x.log", "--port", "8080"), None),
        !ServerOptions.resolveNativeHost(List("native-host"), None)
      )
    },
    test("属性名就是 build 脚本里发出的那个") {
      assertTrue(ServerOptions.NativeHostProperty == "pop-can.native-host")
    }
  )
}
