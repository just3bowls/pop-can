package com.just3bowls.api.webchat

/**
 * 一段 provider 对话的 URL 归属判定（JVM + Scala.js 通用，`server/test` 有金值单测）。
 *
 * 整个网页原生归属只依赖**一条不变式**：一段对话的 URL 只会"**只增不减**"——路径 / hash 前缀延伸，query 里已有的键值原样保留、只允许追加。 真实站点上这几种形态都算同一段对话：
 *
 *   - `/chat` → `/chat/<id>`（补上对话 id）；
 *   - `…?t=…&lang=zh` → `…?t=…&lang=zh&cid=<id>`（query 里补 id）；
 *   - `/?chat_enter_method=new_chat` → `/chat/<id>?chat_enter_method=new_chat`（kimi 的"新对话页 → 会话路由"：query 原样保留，只换路由形态）；
 *   - `#/c` → `#/chat/<id>`（hash 路由补 id）。
 *
 * 反过来一律算**新对话**，不做任何模糊匹配：
 *
 *   - 换 id / 换路径（`/chat/<id1>` → `/chat/<id2>`、`cid=A` → `cid=B`、换 host）；
 *   - 变短（回首页 `/`、只丢参数而路径没变长、去掉 hash 路由）；
 *   - 参数被就地改写（`t=A` → `t=B`，gml 的 new chat 就是这种）。
 *
 * 唯一一处放宽（2026-10）：**允许旧 query 里"新 URL 不再出现"的键消失**——页面把一次性参数消费掉不算新对话。 典型是 wenxin：
 * `/search?enter_type=sidebar_dialog&internal=1` （新对话页）→
 * `/search/1217…?enter_type=sidebar_dialog`（首条消息之后的服务页，`internal=1` 被丢掉），路径变长、保留的键值没变 → 同一段。 为了不把"变短"也放进来，仍然要求
 * **至少一处增长**（path/hash 变长，或 query 变多）。
 *
 * 之前用过"去掉 query 后比较"这类启发式，被真实站点打脸过：gml 的对话 id 在 query 里（`cid=`），去掉 query 后所有对话长得一模一样 → 会把"手动 new
 * chat"误判成同一段对话，把绑定和水位线搬过去。所以 query 必须参与比较，而且只认"旧值原样保留"这一种变化。
 */
object ConversationUrl {

  /**
   * 当前 URL 是否仍然是 `bound` 所代表的同一段对话。
   *
   * 两种合法变化：
   *   - URL 只增不减的细化（见 [[isExtensionOf]]）；
   *   - 同一路由上只清理 query 参数。provider 常把一次性的追踪参数补在地址里，刷新后又去掉； 这类变化不能要求 URL 变长，但 path / hash 必须完全相同。
   *
   * 因此 `.../chat/<id>` → `.../chat`、回首页、换 path / hash 或换对话 id 都**不是**同一段对话。
   */
  def sameConversation(bound: String, current: String): Boolean =
    bound == current || isExtensionOf(bound, current) || sameRouteWithQueryCleanup(bound, current)

  /**
   * `current` 是不是 `bound` 的延伸（或完全相等）。
   *
   * 判据：同 origin；路径 / hash 前缀延伸；旧 query 里**仍然出现的键**值必须相同（键可以消失）；且至少一处增长（path/hash 变长，或 query 变多）。
   */
  def isExtensionOf(bound: String, current: String): Boolean =
    if (bound.isEmpty || current.isEmpty) false
    else if (bound == current) true
    else
      (parse(bound), parse(current)) match {
        case (Some(b), Some(c)) =>
          val sameOrigin  = b.origin == c.origin
          val pathExtends = c.path.startsWith(b.path)
          val hashExtends = c.hash.startsWith(b.hash)
          // 旧键"还在"就必须同值；键消失允许（页面把一次性参数消费掉）。
          val queryKeeps  = b.query.forall { pair =>
            val key       = queryKey(pair)
            val sameKeyIn = c.query.filter(queryKey(_) == key)
            sameKeyIn.isEmpty || sameKeyIn.contains(pair)
          }
          // 至少一处增长，防止"只丢参数 / 回旧地址"这种变短被当成延伸。
          val grew        =
            c.path.length > b.path.length || c.hash.length > b.hash.length || c.query.size > b.query.size
          sameOrigin && pathExtends && hashExtends && queryKeeps && grew
        // 不是标准带 scheme 的 URL：退回最保守的整串前缀，至少不会把两段无关的字符串认成同一段。
        case _                  => current.startsWith(bound)
      }

  /**
   * 同一路由在 query 参数被清理后的形态。
   *
   * 只允许 `current` 删除 `bound` 里已有的参数；同名参数的值必须完全相同，path / hash 不能变化。 反方向“新增参数”已经由 [[isExtensionOf]] 覆盖。
   */
  private def sameRouteWithQueryCleanup(bound: String, current: String): Boolean =
    (parse(bound), parse(current)) match {
      case (Some(b), Some(c)) =>
        b.origin == c.origin &&
        b.path == c.path &&
        b.hash == c.hash &&
        // 至少要保留一个相同的 query 参数；清空整个 query 与"回到根页 / New Chat"无法区分，
        // 不能冒险继承旧对话的绑定和水位线。
        c.query.nonEmpty &&
        c.query.forall(b.query.contains)
      case _                  => false
    }

  private def queryKey(pair: String): String =
    val eq = pair.indexOf('=')
    if (eq < 0) pair else pair.substring(0, eq)

  /** origin / path / query 键值对（原始文本，顺序无关）/ hash（含 `#`）。 */
  final private case class Parts(origin: String, path: String, query: List[String], hash: String)

  private def parse(url: String): Option[Parts] = {
    val scheme = url.indexOf("://")
    if (scheme < 0) None
    else {
      val hostStart = scheme + 3
      val hostEnd   = firstIndex(url, hostStart, '/', '?', '#').getOrElse(url.length)
      if (hostEnd <= hostStart) None
      else {
        val queryIdx = Some(url.indexOf('?', hostEnd)).filter(_ >= 0)
        val hashIdx  = Some(url.indexOf('#', hostEnd)).filter(_ >= 0)
        val pathEnd  = (queryIdx.toList ++ hashIdx.toList).minOption.getOrElse(url.length)
        val queryEnd = hashIdx.getOrElse(url.length)
        Some(
          Parts(
            origin = url.substring(0, hostEnd),
            path = url.substring(hostEnd, pathEnd),
            query = queryIdx.filter(_ < queryEnd).map(i => splitQuery(url.substring(i + 1, queryEnd))).getOrElse(Nil),
            hash = hashIdx.map(url.substring(_)).getOrElse("")
          )
        )
      }
    }
  }

  private def firstIndex(url: String, from: Int, chars: Char*): Option[Int] =
    chars.map(url.indexOf(_, from)).filter(_ >= 0).minOption

  private def splitQuery(query: String): List[String] =
    query.split('&').toList.filter(_.nonEmpty)
}
