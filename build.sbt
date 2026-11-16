import org.scalajs.linker.interface.ModuleSplitStyle
import org.scalajs.linker.interface.ModuleSplitStyle.{FewestModules, SmallModulesFor, SmallestModules}
import org.scalajs.linker.interface.ESVersion
import sbtassembly.AssemblyPlugin.autoImport._

resolvers := Seq(
  "central" at "https://repo1.maven.org/maven2",
  "sonatype" at "https://oss.sonatype.org/content/repositories/releases"
)


val ZIO_VERSION = "2.1.14"
val ZIO_HTTP_VERSION = "3.3.3"
val ZIO_JSON_VERSION = "0.7.42"
val TAPIR_VERSION = "1.11.10"
val STTP_VERSION = "3.10.1"
val LAMINAR_VERSION = "17.2.1"
val SCALAJS_DOM_VERSION = "2.8.0"

ThisBuild / scalaVersion := "3.7.1"

ThisBuild / organization := "com.just3bowls"
Global / cancelable := true
Global / semanticdbEnabled := true

/**
 * 把 server 的编译期 classpath 写进
 * `server/target/native-host/<platform>/classpath/classpath.txt`。
 *
 * 同平台的 classpath launcher 读取它并直接运行编译产物，避免每次 assembly / jpackage。
 */
lazy val writeNativeHostClasspath =
  taskKey[Unit]("write the server compile classpath for the classpath native host")

// Resolve zio-json version conflicts
ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % "always"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-Wunused:all")


lazy val root =
  project
    .in(file("."))
    .settings(
      name := "pop-can",
    )
    .aggregate(apiJVM, apiJS, server, extension)

lazy val apiJVM = api.jvm
lazy val apiJS = api.js

// Shared API module (for both JVM and JS)
lazy val api = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("api"))
  .settings(
    name := "api-sources",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %%% "tapir-core" % TAPIR_VERSION,
      "com.softwaremill.sttp.tapir" %%% "tapir-json-zio" % TAPIR_VERSION,
      "dev.zio" %%% "zio-json" % ZIO_JSON_VERSION
    )
  )

// Server module (JVM only)
lazy val server = project
  .in(file("server"))
  .settings(
    name := "server",
    Compile / mainClass := Some("com.just3bowls.server.bootstrap.ServerMain"),
    Compile / run / fork := true,
    Compile / run / connectInput := true,
    // 测试跑在**独立 JVM**里：zio-test 每次跑用的是自己的 runtime，而 zio-http 的 netty runtime（`DateHeaderEncoder`
    // 的缓存日期 ticker、event loop 等）不会随用例结束回收 —— 跑在 sbt server 那种常驻 JVM 里就是**每跑一次攒一组线程**
    // （实测：一个活了两天的 sbt server 里攒了 4 组），久了会拖慢甚至拖垮 sbt server。独立进程跑完即销毁，sbt server 保持干净。
    Test / fork := true,
    assembly / mainClass := Some("com.just3bowls.server.bootstrap.ServerMain"),
    assembly / assemblyJarName := "pop-can-server.jar",
    // Lucene (and other MR jars) ship Java-version-specific implementations below
    // META-INF/versions. The generic META-INF discard below must not remove them.
    packageOptions += Package.ManifestAttributes("Multi-Release" -> "true"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", _*) => MergeStrategy.first
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "deriving.conf" => MergeStrategy.first
      case PathList("module-info.class") => MergeStrategy.discard
      // scala3-library 与 lihaoyi 的 unroll-annotation 都带 scala/annotation/unroll.*（内容不同）：
      // 两者 API 一致，取任意一份即可（否则 fat jar 合不出来，jpackage 也就无从谈起）。
      case PathList("scala", "annotation", "unroll.class") => MergeStrategy.first
      case PathList("scala", "annotation", "unroll.tasty") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % ZIO_VERSION,
      "dev.zio" %% "zio-http" % ZIO_HTTP_VERSION,
      "dev.zio" %% "zio-json" % ZIO_JSON_VERSION,
      "org.xerial" % "sqlite-jdbc" % "3.50.3.0",
      "org.apache.lucene" % "lucene-core" % "9.12.2",
      "org.apache.lucene" % "lucene-analysis-common" % "9.12.2",
      "org.apache.lucene" % "lucene-queryparser" % "9.12.2",
      "com.microsoft.onnxruntime" % "onnxruntime" % "1.31.1",
      "ai.djl.huggingface" % "tokenizers" % "0.39.0",
      "org.slf4j" % "slf4j-nop" % "2.0.16",
      "dev.zio" %% "zio-test" % ZIO_VERSION % Test,
      "dev.zio" %% "zio-test-sbt" % ZIO_VERSION % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    writeNativeHostClasspath := {
      val classpath = (Compile / fullClasspath).value.files.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val osName    = java.lang.System.getProperty("os.name", "").toLowerCase
      val platform  = if (osName.contains("mac")) "macos" else if (osName.contains("win")) "windows" else "linux"
      val target    = baseDirectory.value / "target" / "native-host" / platform / "classpath" / "classpath.txt"
      IO.createDirectory(target.getParentFile)
      IO.write(target, classpath)
      streams.value.log.info(s"native-host classpath written: $target")
    }
  )
  .dependsOn(api.jvm)


// Scala.js extension module
lazy val extension = project
  .in(file("client-extension"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "client-extension",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %%% "core" % STTP_VERSION,
      "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % TAPIR_VERSION,
      "org.scala-js" %%% "scalajs-dom" % SCALAJS_DOM_VERSION,
      "com.raquo" %%% "laminar" % LAMINAR_VERSION,
      "org.scalameta" %%% "munit" % "1.3.6" % Test
    ),

    scalaJSUseMainModuleInitializer := false,
    // Configure Scala.js linker for better source map support
    scalaJSLinkerConfig ~= {
      // 目前vite-plugin-scalajs source map issue没解决，先禁用
      // 浏览器 DevTools 实际上能正确解析 source map 并调试 Scala 源码
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(
          // SmallestModules
           FewestModules
          // 指定包名
          // ModuleSplitStyle.SmallModulesFor(List("com.just3bowls"))
        )
        // 过滤器用到了 \p{L} 之类的 Unicode 属性转义，需要 ES2018 的正则特性
        .withESFeatures(_.withESVersion(ESVersion.ES2018))
        .withSourceMap(true) // 是否生成map文件


    },


    /**
     * vite-plugin-scalajs 生成的 .js 文件带有自己的 Source Map
     * 这些 Map 文件里的确切路径（例如 file:///Users/.../ExtensionEntrypoints.scala）在 Vite/Rollup 处理阶段是无法被其文件系统解析器找到的
     * 尽管我们通过在vite.config.js中自定义中间件解决了浏览器运行时的调试问题
     * 但 Vite 构建时的警告依然会抛出，先忍着吧
     */
    scalacOptions ++= {
      // 在LocalRootProject作用域下的baseDirectory即pop-can项目
      // toURI加上file:协议
      val localBase = (LocalRootProject / baseDirectory).value.toURI.toString
      // 在vite.config.js中配置 Vite 运行在 localhost:3000
      // 注意：如果你的 Vite 端口变了，这里也要变
      val devServerBase = "http://localhost:3000/"

      // 只有在开发阶段 (非生产发布) 我们才需要映射到本地文件
      // 可以直接在 target/scala-3.3.6/client-extension-fastopt/ 中的 map 文件查看到效果
      if (scalaJSLinkerConfig.value.moduleKind == ModuleKind.ESModule) {
        Seq(
          // 格式: -scalajs-mapSourceURI:原始URI->映射后的URI
          // 这里的映射目的是告诉浏览器：
          // 当你在 map 里看到相对路径时，去哪里找真正的 scala 文件
          s"-scalajs-mapSourceURI:$localBase->$devServerBase"
        )
      } else Seq.empty
    }
  )
  .dependsOn(api.js)
