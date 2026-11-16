package com.just3bowls.background.automation.browser

import scala.collection.mutable

/**
 * 本地相关性过滤：把"无损采集到的 AX 节点树"切成区域，用多路信号打分，选出一部分交给模型。
 *
 * 设计要点（与采集/渲染分层）：
 *   - 输入是采集阶段保留的**全量**节点树（本对象不做任何裁剪）；
 *   - 区域 = 「子树文本 ≥ 40 字符 或 有可交互后代」且「子节点 ≥ 2 或 有可交互后代」的**极小**节点， 再把"同父 + 同 role 签名 + ≥3
 *     个相邻兄弟"合并成一个列表区域（避免一条结果被拆成标题/摘要/操作三块）；
 *   - 打分只排序与取舍，不做站点特判；向量语义只是其中一路信号（由调用方注入，缺失时按 0 参与）。
 */
private[background] object AxFilter {

  /** 采集阶段产出的原始节点（无损；这里只做统计字段的补充）。 */
  final case class RawNode(
      uid: Int,
      parentUid: Option[Int],
      role: String,
      name: String,
      url: String,
      text: String,
      backendNodeId: Option[Int],
      interactive: Boolean,
      states: List[String],
      children: List[RawNode],
      /** 自身文本 + 全部后代文本的字符数。 */
      subtreeTextLen: Int,
      /** 子树里可交互节点的个数。 */
      interactiveCount: Int,
      /** 同层同 role 的兄弟个数。 */
      siblingRepeat: Int,
      /** 从根到本节点的 role 路径（用于位置信号与指纹）。 */
      pathRoles: List[String]
  )
  final case class Region(
      node: RawNode,
      /** 用于打分的长文本（截 2000）。 */
      scoreText: String,
      /** 用于展示的短文本（截 300）。 */
      displayText: String,
      fingerprint: String,
      ancestorUids: List[Int]
  )
  final case class Scored(region: Region, signals: Map[String, Double], score: Double)

  /** 默认阈值与上限（都是可调常量）。 */
  private val MinRegionText      = 40
  private val KeptFloorScore     = 0.30
  private val KeptRelativeToBest = 0.55
  private val MaxClimb           = 3
  private val MaxScoreText       = 2000
  private val MaxRegionsRendered = 12

  /** kept 区域内容合计低于这个值时补足"最长文本区域"，避免文章页只回一行标题。 */
  private val MinKeptContentChars = 800
  private val MaxQueryTokens      = 24
  private val FormRoles = Set("textbox", "searchbox", "combobox", "checkbox", "radio", "switch", "slider", "spinbutton")
  private val ChromeRoles = Set("navigation", "banner", "complementary", "contentinfo")
  private val ActionVerbs = List(
    "提交",
    "搜索",
    "查询",
    "下一页",
    "上一页",
    "下页",
    "登录",
    "登入",
    "注册",
    "筛选",
    "过滤",
    "排序",
    "关闭",
    "确定",
    "确认",
    "取消",
    "更多",
    "展开",
    "显示",
    "跳转",
    "submit",
    "search",
    "next",
    "prev",
    "previous",
    "login",
    "sign in",
    "sign up",
    "filter",
    "sort",
    "close",
    "ok",
    "cancel",
    "more",
    "expand",
    "show",
    "go"
  )

  /** 场景权重：按工具上下文自动切换（调用方决定传哪个）。 */
  private val SearchWeights                             = Map(
    "bm25"       -> 2.0,
    "structure"  -> 0.6,
    "position"   -> 0.3,
    "memory"     -> 0.5,
    "semantic"   -> 0.6,
    "affordance" -> 0.2,
    "trajectory" -> 0.1
  )
  private val OperateWeights                            = Map(
    "affordance" -> 1.0,
    "trajectory" -> 0.8,
    "bm25"       -> 0.4,
    "structure"  -> 0.3,
    "position"   -> 0.3,
    "memory"     -> 0.4,
    "semantic"   -> 0.2
  )
  def weightsFor(scenario: String): Map[String, Double] =
    if (scenario == "operate") OperateWeights else SearchWeights
  // ---------------------------------------------------------------- 区域切分
  private def qualifies(node: RawNode): Boolean         =
    (node.subtreeTextLen >= MinRegionText || node.interactiveCount >= 1) &&
      (node.children.size >= 2 || node.interactiveCount >= 1)
  private def roleSignature(node: RawNode): String      =
    node.role + "/" + node.children.map(_.role).distinct.sorted.mkString(",")

  /**
   * 切分区域：极小满足区域 → 兄弟列表合并 → **上溯到最近的"内容命中"祖先** → 排除查询回显 → 按 uid 去重。
   *
   * 上溯这一步是实测得出的：像天气卡片那样"标题含查询词、数值块不含"的结构，取极小区域会把卡片拆碎， 不含查询词的那半块会被判为无关；上溯到最近一个含查询词的祖先才能拿到完整条目。
   */
  def segment(roots: List[RawNode], goal: String): List[Region] = {
    val byUid                                                 = mutable.Map.empty[Int, RawNode]
    def index(n: RawNode): Unit                               = { byUid.update(n.uid, n); n.children.foreach(index) }
    roots.foreach(index)
    val goalTokens                                            = tokens(goal).toSet
    def matches(n: RawNode): Boolean                          =
      goalTokens.nonEmpty && {
        val own = tokens(textOf(n, MaxScoreText))
        own.exists(goalTokens.contains)
      }
    def climb(node: RawNode): RawNode                         = {
      var current = node
      var steps   = 0
      while (!matches(current) && steps < MaxClimb) {
        current.parentUid.flatMap(byUid.get) match {
          case Some(parent) => current = parent
          case None         => steps = MaxClimb
        }
        steps += 1
      }
      current
    }
    val collected                                             = mutable.ArrayBuffer.empty[(RawNode, Option[RawNode])]
    def collect(node: RawNode, parent: Option[RawNode]): Unit =
      if (qualifies(node)) {
        val inner = mutable.ArrayBuffer.empty[(RawNode, Option[RawNode])]
        node.children.foreach(child => collectInto(child, Some(node), inner))
        if (inner.isEmpty) collected += ((node, parent))
        else collected ++= inner
      } else node.children.foreach(child => collect(child, Some(node)))
    def collectInto(
        node: RawNode,
        parent: Option[RawNode],
        sink: mutable.ArrayBuffer[(RawNode, Option[RawNode])]
    ): Unit =
      if (qualifies(node)) {
        val inner = mutable.ArrayBuffer.empty[(RawNode, Option[RawNode])]
        node.children.foreach(child => collectInto(child, Some(node), inner))
        if (inner.isEmpty) sink += ((node, parent))
        else sink ++= inner
      } else node.children.foreach(child => collectInto(child, parent, sink))
    roots.foreach(root => collect(root, None))
    // 列表合并：同一父节点下 ≥3 个同 role 签名的兄弟 → 用父节点代表整块
    val merged                                                = mutable.ArrayBuffer.empty[Region]
    val skipped                                               = mutable.Set.empty[RawNode]
    val byParent                                              = collected.groupBy(_._2).toList
    byParent.foreach {
      case (Some(parent), group) =>
        val sameRole = group.groupBy { case (node, _) => roleSignature(node) }.filter(_._2.size >= 3)
        if (sameRole.nonEmpty) {
          sameRole.values.foreach(items => items.foreach { case (node, _) => skipped += node })
          merged += toRegion(parent, byUid.toMap)
        } else group.foreach { case (node, _) => merged += toRegion(node, byUid.toMap) }
      case (None, group)         =>
        group.foreach { case (node, _) => merged += toRegion(node, byUid.toMap) }
    }
    // 上溯 → 排除查询回显 → 去重
    val climbed = merged.map(region => toRegion(climb(region.node), byUid.toMap))
    val filtered                                              = climbed.filterNot(region => isEcho(region, goal))
    val result                                                =
      if (filtered.nonEmpty) filtered.toList
      else roots.flatMap(_.children.sortBy(-_.subtreeTextLen).take(3)).map(node => toRegion(node, byUid.toMap))
    result.distinctBy(_.node.uid)
  }

  /** 区域文本："真正的内容"——文本叶子正文 + 可交互节点标签；容器 name 是子树的重复，不参与。 */
  private def textOf(node: RawNode, cap: Int): String                   = {
    val sb                     = new StringBuilder
    def walk(n: RawNode): Unit =
      if (sb.length < cap) {
        val piece = if (n.text.nonEmpty) n.text else if (n.interactive) n.name else ""
        if (piece.nonEmpty) {
          if (sb.nonEmpty) sb.append(' ')
          sb.append(piece)
        }
        n.children.foreach(walk)
      }
    walk(node)
    sb.toString.replaceAll("\\s+", " ").trim.take(cap)
  }
  private def toRegion(node: RawNode, byUid: Map[Int, RawNode]): Region = {
    val ancestors = mutable.ListBuffer.empty[Int]
    var current   = node.parentUid
    while (current.exists(byUid.contains)) {
      val uid = current.get
      ancestors.prepend(uid)
      current = byUid.get(uid).flatMap(_.parentUid)
    }
    Region(
      node = node,
      scoreText = textOf(node, MaxScoreText),
      displayText = textOf(node, 300),
      fingerprint = fingerprintOf(node),
      ancestorUids = ancestors.toList
    )
  }

  /** 区域文本 = 子树文本（压空白、截 300 字符），用于打分与展示。 */
  def subtreeText(node: RawNode): String = textOf(node, 300)

  /** 区域是否只是"查询回显"（搜索框里的输入值 / 只含控件的短区域），不作为内容候选。 */
  private def isEcho(region: Region, goal: String): Boolean                        = {
    val normalized = normalizeForCompare(region.displayText)
    val goalNorm   = normalizeForCompare(goal)
    val pureEcho   = normalized.nonEmpty && (normalized == goalNorm || goalNorm.contains(normalized))
    val formOnly   = countRoles(region.node, FormRoles) > 0 && region.node.subtreeTextLen < 200
    pureEcho || formOnly
  }
  private def normalizeForCompare(text: String): String                            =
    text.toLowerCase.replaceAll("\\s+", "")
  private def fingerprintOf(node: RawNode): String                                 = {
    val roles = node.pathRoles.takeRight(4).mkString("/")
    val text  = subtreeText(node).take(80)
    hash(s"$roles|${node.role}|$text")
  }
  // ---------------------------------------------------------------- 信号
  private def isCjk(ch: Char): Boolean                                             =
    (ch >= '\u3400' && ch <= '\u4dbf') || (ch >= '\u4e00' && ch <= '\u9fff') || (ch >= '\uf900' && ch <= '\ufaff')
  private def tokens(text: String): List[String]                                   = {
    val lowered       = text.toLowerCase
    // 不用 \p{L} 之类的 Unicode 属性转义：Scala.js 的正则引擎要求 ES2018+，这里用 isLetterOrDigit 手工切词更稳
    val words         = mutable.ListBuffer.empty[String]
    val current       = new StringBuilder
    def flush(): Unit = {
      if (current.length >= 2) words += current.toString
      current.clear()
    }
    lowered.foreach { ch =>
      if (ch.isLetterOrDigit && !isCjk(ch)) current.append(ch) else flush()
    }
    flush()
    val cjk           = lowered.filter(isCjk)
    val bigrams       =
      if (cjk.length < 2) Nil else cjk.zip(cjk.drop(1)).map { case (left, right) => s"$left$right" }.toList
    (words.toList ++ bigrams).distinct
  }
  private def linkDensity(node: RawNode): Double                                   =
    if (node.subtreeTextLen <= 0) 0.0
    else {
      def interactiveText(n: RawNode): Int =
        (if (n.interactive) n.name.length + n.text.length else n.text.length) + n.children.map(interactiveText).sum
      math.min(1.0, interactiveText(node).toDouble / node.subtreeTextLen)
    }
  private def countRoles(node: RawNode, roles: Set[String]): Int                   =
    (if (roles.contains(node.role)) 1 else 0) + node.children.map(countRoles(_, roles)).sum
  private def bm25Scores(regions: List[Region], goal: String): Map[String, Double] = {
    val queryTokens = tokens(goal).take(MaxQueryTokens)
    if (queryTokens.isEmpty) regions.map(r => r.fingerprint -> 0.0).toMap
    else {
      val docs     = regions.map(region => region.fingerprint -> tokens(region.scoreText))
      val lengths  = docs.map { case (fp, ts) => fp -> ts.size }.toMap
      val avgLen   = math.max(1.0, lengths.values.sum.toDouble / math.max(1, lengths.size))
      val dfCounts = queryTokens.map(token => token -> docs.count { case (_, ts) => ts.contains(token) }).toMap
      val n        = math.max(1, docs.size)
      val k1       = 1.2
      val b        = 0.75
      docs.map { case (fp, ts) =>
        val tf    = ts.groupBy(identity).map { case (token, list) => token -> list.size }
        val len   = math.max(1, lengths.getOrElse(fp, 1))
        val score = queryTokens.map { token =>
          val df  = dfCounts.getOrElse(token, 0)
          val idf = math.log(1 + (n - df + 0.5) / (df + 0.5))
          val f   = tf.getOrElse(token, 0).toDouble
          if (f <= 0) 0.0
          else idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len / avgLen))
        }.sum
        fp -> score
      }.toMap
    }
  }
  private def structureScore(region: Region): Double                               = {
    // 内容系数：没有内容（空链接/空容器）即使"重复块"再多也不算结构分
    val contentFactor = math.min(1.0, region.node.subtreeTextLen / 120.0)
    val contextLen    = math.min(2000, math.max(0, region.node.subtreeTextLen - region.node.name.length))
    val repeat        = math.min(6, region.node.siblingRepeat)
    contentFactor * (0.6 * (contextLen / 2000.0) + 0.4 * (repeat / 6.0)) - 0.2 * linkDensity(region.node)
  }
  private def positionScore(region: Region): Double                                = {
    val roles  = region.node.pathRoles
    val inMain = roles.contains("main")
    val chrome = roles.exists(ChromeRoles.contains)
    val layout = region.node.role.startsWith("LayoutTable")
    val raw    = 0.4 + (if (inMain) 0.6 else 0.0) + (if (chrome) -0.5 else 0.0) + (if (layout) -0.3 else 0.0)
    math.max(0.0, math.min(1.0, raw))
  }
  private def affordanceScore(region: Region): Double                              = {
    val forms = math.min(3, countRoles(region.node, FormRoles)) / 3.0
    val verbs = if (ActionVerbs.exists(verb => region.scoreText.toLowerCase.contains(verb))) 1.0 else 0.0
    0.5 * forms + 0.5 * verbs
  }
  private def trajectoryScore(region: Region, lastAction: String): Double          =
    lastAction match {
      case "type"                => if (affordanceScore(region) > 0.5) 1.0 else 0.0
      case "search" | "navigate" => if (region.node.interactiveCount >= 1 && structureScore(region) > 0.3) 0.7 else 0.2
      case "click"               => 0.3
      case "scroll"              => 0.2
      case _                     => 0.0
    }
  private def memoryScore(hits: Int): Double                                       =
    if (hits <= 0) 0.0 else math.min(1.0, 0.5 + 0.15 * hits)
  private def hash(text: String): String                                           = {
    var h = 5381
    var i = 0
    while (i < text.length) {
      h = (h << 5) + h + text.charAt(i)
      i += 1
    }
    h.toHexString
  }
  // ---------------------------------------------------------------- 主流程
  /** 区域选择结果：命中的区域（按分数降序）、最高分、是否退化（无候选 / 无过阈区域）。 */
  final case class Selection(kept: List[Scored], best: Double, degenerate: Boolean)

  /**
   * 打分 → 阈值筛选 → 祖先去重 → 表单保底 → 内容量兜底。观察视图与 `browser_extract` 的区域兜底共用这一份， 保证两个工具的"什么算相关"完全一致。
   */
  private def selectForGoal(
      regions: List[Region],
      goal: String,
      scenario: String,
      lastAction: String = "",
      memoryHits: Map[String, Int] = Map.empty,
      semantic: Map[String, Double] = Map.empty
  ): Selection =
    if (regions.isEmpty) Selection(Nil, 0.0, degenerate = true)
    else {
      val bm25       = bm25Scores(regions, goal)
      // 候选资格：search 场景必须字面命中（没有命中的区域不是"结果"）；operate 场景还要收进控件区域。
      val candidates = regions.filter { region =>
        val lexical = bm25.getOrElse(region.fingerprint, 0.0) > 0
        if (scenario == "operate") lexical || affordanceScore(region) > 0 || hasFormControl(region) else lexical
      }
      val weights    = weightsFor(scenario)
      val rawSignals = candidates.map { region =>
        region -> Map(
          "bm25"       -> bm25.getOrElse(region.fingerprint, 0.0),
          "structure"  -> structureScore(region),
          "position"   -> positionScore(region),
          "affordance" -> affordanceScore(region),
          "trajectory" -> trajectoryScore(region, lastAction),
          "memory"     -> memoryScore(memoryHits.getOrElse(region.fingerprint, 0)),
          "semantic"   -> semantic.getOrElse(region.fingerprint, 0.0)
        )
      }
      // 每路信号在页面内做 min-max 归一化，避免量纲差异
      val normalized = rawSignals.map { case (region, signals) =>
        region -> signals.map { case (key, _) =>
          key -> normalize(signals(key), rawSignals.map(_._2.getOrElse(key, 0.0)))
        }
      }
      val scored     = normalized.map { case (region, norm) =>
        Scored(region, norm, norm.map { case (key, value) => weights.getOrElse(key, 0.0) * value }.sum)
      }.sortBy(-_.score)
      val best       = scored.headOption.map(_.score).getOrElse(0.0)
      val threshold  = math.max(KeptFloorScore, KeptRelativeToBest * best)
      // 阈值筛选 + 祖先关系去重（同一块内容的父子区域只保留一个，避免预算被同一个东西吃掉）
      val kept       = mutable.ArrayBuffer.empty[Scored]
      scored.foreach { candidate =>
        val isAncestorOrDescendant = kept.exists(existing =>
          isAncestor(candidate.region, existing.region) || isAncestor(existing.region, candidate.region)
        )
        val isFloor                = hasFormControl(candidate)
        if ((candidate.score >= threshold || isFloor) && !isAncestorOrDescendant && kept.size < MaxRegionsRendered)
          kept += candidate
      }
      // 内容量兜底：命中区域合计太短时，把页面上文本最长的区域补进来（文章页常见：正文不含查询原词）
      if (kept.map(_.region.scoreText.length).sum < MinKeptContentChars) {
        val extras = scored
          .filterNot(candidate => kept.exists(_.region.node.uid == candidate.region.node.uid))
          .sortBy(-_.region.node.subtreeTextLen)
          .filter(candidate => candidate.region.node.subtreeTextLen >= MinKeptContentChars / 2)
          .take(math.max(0, MaxRegionsRendered - kept.size))
        kept ++= extras
      }
      if (best <= 0.0 || kept.isEmpty) Selection(Nil, best, degenerate = true)
      else Selection(kept.toList, best, degenerate = false)
    }

  /**
   * 给 `browser_extract` 的兜底路径用：按相关性挑若干区域并拼成文本。
   *
   * 返回 (命中区域数, 文本)。区域数用来判断"这是列表页还是文章页"：文章页只有 1~2 个聚焦区域， 结果列表页会有多个竞争力相当的区域。
   */
  def regionSelection(roots: List[RawNode], goal: String, maxChars: Int): (Int, String) = {
    val regions   = segment(roots, goal)
    val selection = selectForGoal(regions, goal, "search")
    val sb        = new StringBuilder
    selection.kept.foreach { scored =>
      if (sb.length < maxChars && scored.region.scoreText.length >= 120) {
        if (sb.nonEmpty) sb.append("\n\n")
        sb.append(scored.region.scoreText.take(math.max(200, maxChars - sb.length)))
      }
    }
    (selection.kept.size, sb.toString.take(maxChars))
  }
  private def normalize(value: Double, values: List[Double]): Double                    = {
    val min = values.minOption.getOrElse(0.0)
    val max = values.maxOption.getOrElse(0.0)
    if (max - min < 1e-9) (if (max > 0) 1.0 else 0.0) else (value - min) / (max - min)
  }

  /** a 是否是 b 的祖先（按 uid 链判断）。 */
  private def isAncestor(a: Region, b: Region): Boolean =
    b.ancestorUids.contains(a.node.uid)
  private def hasFormControl(scored: Scored): Boolean   = countRoles(scored.region.node, FormRoles) > 0
  private def hasFormControl(region: Region): Boolean   = countRoles(region.node, FormRoles) > 0
}
