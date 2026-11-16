package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge
import scala.scalajs.js
import com.just3bowls.api.protocol.Bytes
import com.just3bowls.api.protocol.GrpcWebFrames
import com.just3bowls.api.protocol.Md5

/**
 * 从 provider 的 `parserRule` 里取 `request` 段，用来在聊天请求体上读写"这一轮用户说了什么"。
 *
 * 规则形如：
 * {{{
 * {"request":{"userText":"$.messages[-1].content"}}          // 单路径
 * {"request":{"userText":["$.prompt","$.displayPrompt"]}}    // 多路径：读第一个命中，写全部命中
 * }}}
 *
 * 请求体可能是裸 JSON（doubao / mimo / wenxin / yuanbao…），也可能是 gRPC-Web 帧（kimi）：`00`/`80` + 4 字节大端长度 + JSON——后者按**形状**识别（第一个
 * `{` 落在第 5 字节、首字节是合法 flag），帧头原样保留、写回时按 UTF-8 重算长度；长度字段不参与校验。
 *
 * 有些站点还会在正文旁边放**完整性字段**（例如文心的 `chat_token` 里嵌着 `md5(输入框文本)`）：改写正文必须同步重算，否则服务端直接拒。这类字段由规则里的 `request.derive` 声明（见
 * [[DeriveOp]]），执行的是通用 op，代码里没有 provider 名字分支。
 *
 * 规则缺失、路径不支持、请求体解不出来一律返回 `None`：调用方按"这家 provider 只能旁听"处理。
 */
object ProviderRequestRule {

  /**
   * 规则里声明的一个"派生字段"：改写正文后要同步重算的字段。
   *
   * 两种形态：正文里的字段（`path` / `slot`）与请求头里的字段（`header` / `param` / `slice` / 分隔符）。
   */
  type DeriveOp = RequestDeriveOp

  /** 把形如 `base64(…|md5(文本)|…)-后缀` 的字段里第 `slot` 段重算成注入正文的 md5。 */
  val OpRefreshMd5Slot = "refreshMd5Slot"

  /**
   * 把请求头里"分隔符拼接"的参数换成注入正文（可截断）。
   *
   * 具体格式由规则声明：文心的 `X-Chat-Message` 是 `k:v,k:v`（`pairSep=","`, `kvSep=":"`）， 同页面另一套编码器 `gn()` 是 `k=v&k=v`（默认值）。旧的
   * `refreshUrlEncodedParam` 名字当别名继续认。
   */
  val OpRefreshHeaderParam   = "refreshHeaderParam"
  private val HeaderParamOps = Set(OpRefreshHeaderParam, "refreshUrlEncodedParam")
  private def definition(ruleJson: String): Option[ParserRuleDefinition] =
    ParserRuleDefinition.parse(ruleJson).toOption

  /** 规则里声明的用户文本路径（一个或多个）。 */
  def userTextPaths(ruleJson: String): List[String] =
    definition(ruleJson).map(_.request.userTextPaths).getOrElse(Nil)
  def hasRule(ruleJson: String): Boolean            = userTextPaths(ruleJson).nonEmpty

  /** 规则里声明的派生字段；没配或格式不对一律为空（老规则行为完全不变）。 */
  def deriveOps(ruleJson: String): List[DeriveOp] =
    definition(ruleJson).map(_.request.deriveOps).getOrElse(Nil)

  /**
   * body 里一段"能解析成 JSON"的候选。
   *
   * `start/end` 是它在 body **字节**里的区间；帧化时 `frameFlag` 记下这一帧的 flag（帧头就紧贴在 payload 前面 5 个字节）。
   */
  final private case class JsonCandidate(
      start: Int,
      end: Int,
      frameFlag: Option[Int],
      json: js.Any,
      label: String
  )

  /**
   * 把请求体拆成"可编辑的 JSON 候选"。
   *
   * 优先按 gRPC-Web 帧序列解析（kimi 实测会出现**多帧**：前面一个非 JSON 帧 + 后面才是 JSON 帧，也随时可能再换形）， 挑出所有能 `JSON.parse` 的帧；不是帧序列时退回"裸
   * JSON（可能带非帧前缀）"的老行为。失败原因里带帧信息与前 32 字节十六进制， 下次任何 provider 换体形都能直接从日志看出结构。
   */
  private def jsonCandidates(body: Array[Byte]): Either[String, List[JsonCandidate]]     =
    if (body.isEmpty) Left("request body is empty")
    else
      GrpcWebFrames.parse(body) match {
        case Some(frames) =>
          val candidates = scala.collection.mutable.ListBuffer.empty[JsonCandidate]
          val notes      = scala.collection.mutable.ListBuffer.empty[String]
          var offset     = 0
          frames.zipWithIndex.foreach { case (frame, index) =>
            val start = offset + 5
            val end   = start + frame.payload.length
            offset = end
            parseJson(frame.payload) match {
              case Some(json) => candidates += JsonCandidate(start, end, Some(frame.flag), json, s"frame#$index")
              case None       => notes += s"frame#$index not JSON"
            }
          }
          if (candidates.nonEmpty) Right(candidates.toList)
          else Left(
            s"none of the ${frames.size} gRPC-Web frames is JSON (${notes.mkString(", ")}; ${frameSummary(frames, body)})"
          )
        case None         =>
          val braceAt = indexOfByte(body, '{')
          if (braceAt < 0) Left(s"request body has no '{' and is not a gRPC-Web frame (head=${Bytes.hexPreview(body)})")
          else
            parseJson(java.util.Arrays.copyOfRange(body, braceAt, body.length)) match {
              case Some(json) => Right(List(JsonCandidate(braceAt, body.length, None, json, "body")))
              case None       => Left(s"request body is not parseable JSON (head=${Bytes.hexPreview(body)})")
            }
      }
  private def parseJson(bytes: Array[Byte]): Option[js.Any]                              =
    try {
      val parsed = js.JSON.parse(Bytes.text(bytes))
      if (parsed == null || js.isUndefined(parsed)) None else Some(parsed)
    } catch {
      case _: Throwable => None
    }
  private def indexOfByte(bytes: Array[Byte], ch: Char): Int                             = {
    val target = ch.toInt
    var i      = 0
    while (i < bytes.length) {
      if ((bytes(i) & 0xff) == target) return i
      i += 1
    }
    -1
  }
  private def frameSummary(frames: List[GrpcWebFrames.Frame], body: Array[Byte]): String =
    s"frames=${frames.size} lens=${frames.map(_.payload.length).mkString(",")} head=${Bytes.hexPreview(body)}"

  /** 把改好的 JSON 拼回 body：帧化时只替换该帧（长度按真实字节重算），裸 JSON 时保留 `{` 之前的前缀。 */
  private def splice(body: Array[Byte], candidate: JsonCandidate, jsonText: String): Option[Array[Byte]] = {
    val payload = Bytes.utf8(jsonText)
    candidate.frameFlag match {
      case None       =>
        val out = new Array[Byte](candidate.start + payload.length)
        System.arraycopy(body, 0, out, 0, candidate.start)
        System.arraycopy(payload, 0, out, candidate.start, payload.length)
        Some(out)
      case Some(flag) =>
        val frameStart = candidate.start - 5
        if (frameStart < 0) None
        else {
          val head = GrpcWebFrames.frame(flag, payload)
          val tail = body.length - candidate.end
          val out  = new Array[Byte](frameStart + head.length + tail)
          System.arraycopy(body, 0, out, 0, frameStart)
          System.arraycopy(head, 0, out, frameStart, head.length)
          System.arraycopy(body, candidate.end, out, frameStart + head.length, tail)
          Some(out)
        }
    }
  }

  /** 读出这一轮用户说的那句话（多路径时取第一个命中的）。 */
  def readUserText(ruleJson: String, body: Array[Byte]): Option[String] =
    for {
      paths      <- Option(userTextPaths(ruleJson)).filter(_.nonEmpty)
      candidates <- jsonCandidates(body).toOption
      text       <-
        candidates.flatMap(candidate => paths.flatMap(path => JsonPath.getString(candidate.json, path))).headOption
    } yield text

  /**
   * 把用户那句话替换成整段模版文本，返回改写后的请求体。
   *
   * 多路径时全部替换（例如元宝的 `prompt` 与 `displayPrompt` 要保持一致）；只动这些路径，其它字段原样保留。
   */
  def writeUserText(ruleJson: String, body: Array[Byte], template: String): Option[Array[Byte]] =
    (for {
      paths      <- Option(userTextPaths(ruleJson)).filter(_.nonEmpty)
      candidates <- jsonCandidates(body).toOption
      // 选"第一个能让规则里所有 userText 路径都可写"的候选（多帧时就是那个 JSON 帧）。
      candidate  <- candidates.find(c => paths.forall(path => JsonPath.setString(c.json, path, "x").isDefined))
    } yield {
      val rewritten = paths.foldLeft(Option(candidate.json)) { (acc, path) =>
        acc.flatMap(current => JsonPath.setString(current, path, template))
      }
      // 完整性字段（如文心的 chat_token）在正文改完后同步重算；任何一步失败都宁可整次改写失败，也不发出明知会被拒的请求。
      val derived   = rewritten.flatMap(next => applyDerives(bodyDerives(ruleJson), next, template))
      derived.flatMap(next => splice(body, candidate, js.JSON.stringify(next)))
    }).flatten

  /** 正文侧的派生 op（md5 槽位 + 未知 op 的兜底）；请求头侧的 op 由 [[rewriteHeaders]] 处理。 */
  private def bodyDerives(ruleJson: String): List[DeriveOp] =
    deriveOps(ruleJson).filter(op => !HeaderParamOps.contains(op.op))

  /**
   * 改写请求头里的派生字段（分隔符由规则声明：文心是 `,` + `:`，`gn()` 那类是默认的 `&` + `=`）。
   *
   * 返回**整份**新头（调用方按覆盖语义回传给 `Fetch.continueRequest`）；没有该头 / 没有该参数一律 `Left` 让改写失败， 避免发出"头里说 A、正文是 B"的请求。
   */
  def hasHeaderDerives(ruleJson: String): Boolean                                         =
    deriveOps(ruleJson).exists(op => HeaderParamOps.contains(op.op))
  def rewriteHeaders(
      ruleJson: String,
      headers: js.Dictionary[String],
      injected: String
  ): Either[String, js.Dictionary[String]] = {
    val ops = deriveOps(ruleJson).filter(op => HeaderParamOps.contains(op.op))
    ops.flatMap(op => headerProblem(headers, op)).headOption match {
      case Some(reason) => Left(reason)
      case None         => Right(ops.foldLeft(headers)((acc, op) => applyHeaderDerive(acc, op, injected)))
    }
  }
  private def applyHeaderDerive(
      headers: js.Dictionary[String],
      op: DeriveOp,
      injected: String
  ): js.Dictionary[String] =
    headers.get(op.header) match {
      case None        => headers
      case Some(value) =>
        refreshHeaderParam(value, op, injected) match {
          case None          => headers
          case Some(patched) =>
            val next = js.Dictionary[String]()
            headers.foreach { case (name, current) => next.update(name, current) }
            next.update(op.header, patched)
            next
        }
    }
  private def headerProblem(headers: js.Dictionary[String], op: DeriveOp): Option[String] =
    if (op.header.isEmpty || op.param.isEmpty) Some(s"header derive needs both header and param (op=${op.op})")
    else
      headers.get(op.header) match {
        case None        => Some(s"header not found: ${op.header}")
        case Some(value) =>
          if (paramIndex(value, op) < 0)
            Some(
              s"param '${op.param}' not found in header ${op.header} " +
                s"(pairSep='${op.pairSep}' kvSep='${op.kvSep}')"
            )
          else None
      }

  /** 把 `k<kvSep>v<pairSep>…` 里某个参数换成注入正文（`slice > 0` 时先截断）；key 原样保留、其它参数逐字不动。 */
  private def refreshHeaderParam(current: String, op: DeriveOp, injected: String): Option[String] =
    paramIndex(current, op) match {
      case index if index < 0 => None
      case index              =>
        val parts = splitLiteral(current, op.pairSep)
        val key   = parts(index).takeWhile(_ != op.kvSep.charAt(0))
        val value = if (op.slice > 0) injected.take(op.slice) else injected
        Some(parts.updated(index, s"$key${op.kvSep}${encodeUriComponent(value)}").mkString(op.pairSep))
    }
  private def paramIndex(value: String, op: DeriveOp): Int                                        =
    splitLiteral(value, op.pairSep).indexWhere { part =>
      val name = part.takeWhile(_ != op.kvSep.charAt(0))
      decodeUriComponent(name).contains(op.param)
    }

  /** 按**字面**分隔符切分（不做正则解释）。 */
  private def splitLiteral(value: String, separator: String): List[String]                      =
    if (separator.isEmpty) List(value)
    else {
      val parts = scala.collection.mutable.ListBuffer.empty[String]
      var start = 0
      var at    = value.indexOf(separator, start)
      while (at >= 0) {
        parts += value.substring(start, at)
        start = at + separator.length
        at = value.indexOf(separator, start)
      }
      parts += value.substring(start)
      parts.toList
    }
  private def encodeUriComponent(value: String): String                                         =
    try JsBridge.asString(JsBridge.encodeURIComponent(value))
    catch { case _: Throwable => value }
  private def decodeUriComponent(value: String): Option[String]                                 =
    try Some(JsBridge.asString(JsBridge.decodeURIComponent(value)))
    catch { case _: Throwable => None }
  private def applyDerives(ops: List[DeriveOp], body: js.Any, injected: String): Option[js.Any] =
    ops.foldLeft(Option(body)) { (acc, op) =>
      acc.flatMap(current => applyDerive(current, op, injected))
    }
  private def applyDerive(body: js.Any, op: DeriveOp, injected: String): Option[js.Any]         =
    op.op match {
      case OpRefreshMd5Slot => refreshMd5Slot(body, op, injected)
      case _                => None
    }

  /**
   * `base64(…|md5(文本)|…)-后缀`：把第 `slot` 段换成注入正文的 md5，其余（uid / 时间戳 / counter / 后缀）原样保留。
   *
   * 实测结构（文心）：`base64(uid|md5(输入框文本)|时间戳|counter)-counter-3`；输入框为空时那一段就是 `md5("")`。
   */
  private def refreshMd5Slot(body: js.Any, op: DeriveOp, injected: String): Option[js.Any] =
    JsonPath.getString(body, op.path).flatMap { current =>
      val dashAt = current.indexOf('-')
      if (dashAt <= 0 || op.slot < 0) None
      else {
        val core   = current.take(dashAt)
        val suffix = current.drop(dashAt)
        decodeBase64(core).flatMap { decoded =>
          val parts = decoded.split("\\|", -1).toList
          if (op.slot >= parts.length) None
          else
            encodeBase64(parts.updated(op.slot, Md5.hex(injected)).mkString("|"), keepPadding = core.endsWith("="))
              .flatMap(encoded => JsonPath.setString(body, op.path, encoded + suffix))
        }
      }
    }
  private def decodeBase64(value: String): Option[String]                                  =
    try {
      val padded = value + ("=" * ((4 - value.length % 4) % 4))
      Some(JsBridge.asString(JsBridge.atob(padded)))
    } catch {
      case _: Throwable => None
    }
  private def encodeBase64(value: String, keepPadding: Boolean): Option[String]            =
    try {
      val encoded = JsBridge.asString(JsBridge.btoa(value))
      Some(if (keepPadding) encoded else encoded.replaceAll("=+$", ""))
    } catch {
      case _: Throwable => None
    }

  /** 派生字段能不能应用；只做结构检查（不写回），给 `diagnose` 报"卡在哪一步"。 */
  private def deriveProblem(body: js.Any, op: DeriveOp): Option[String] =
    if (op.op != OpRefreshMd5Slot) Some(s"unknown derive op '${op.op}' on ${op.path}")
    else
      JsonPath.getString(body, op.path) match {
        case None          => Some(s"derive path not found: ${op.path}")
        case Some(current) =>
          val dashAt = current.indexOf('-')
          if (dashAt <= 0) Some(s"derive value has no '-' separator: ${op.path}")
          else
            decodeBase64(current.take(dashAt)) match {
              case None          => Some(s"derive value is not base64: ${op.path}")
              case Some(decoded) =>
                val parts = decoded.split("\\|", -1).length
                if (op.slot < 0 || op.slot >= parts)
                  Some(s"derive slot ${op.slot} out of range (parts=$parts): ${op.path}")
                else None
            }
      }

  /** 改写失败时给出"卡在哪一步"，省得只看到一句笼统的报错。 */
  def diagnose(ruleJson: String, body: Array[Byte]): String = {
    val paths = userTextPaths(ruleJson)
    if (paths.isEmpty) "request.userText not configured"
    else
      jsonCandidates(body) match {
        case Left(reason)      => reason
        case Right(candidates) =>
          candidates.find(candidate => paths.exists(path => JsonPath.getString(candidate.json, path).isDefined)) match {
            case None            =>
              val detail =
                candidates.map(candidate => s"${candidate.label}: path not found: ${paths.mkString(", ")}").mkString(
                  "; "
                )
              s"path not found in any JSON candidate ($detail)"
            case Some(candidate) =>
              val readable = paths.filter(path => JsonPath.getString(candidate.json, path).isDefined)
              val writable = readable.filter(path => JsonPath.setString(candidate.json, path, "x").isDefined)
              if (writable.isEmpty) s"path readable but not writable: ${readable.mkString(", ")}"
              else
                // 有 derive 段时，先报派生字段的问题，比笼统的"改写不合法"好定位。
                bodyDerives(ruleJson).flatMap(op => deriveProblem(candidate.json, op)).headOption
                  .getOrElse("rewrite produced an invalid body")
          }
      }
  }
}
