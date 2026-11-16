package com.just3bowls.background.automation.browser

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.{ChromeAPI, JsonValue, SearchDetectResult}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSON

/**
 * 搜索结果记录检测（MDR 式，纯 DOM 算法，无站点选择器）。
 *
 * 页面侧脚本负责"找重复记录 + 字段对齐 + 记录级形态过滤 + 算指标"，Scala 侧负责"打分选优 + 校验 + 渲染 + 固化"： 把权重与阈值留在 Scala 里，调参不用改注入的 JS。
 *
 * 记录级过滤只用**形态判据**（一条结果 = 一个主链接 + 一段连续正文；聚合块/加载态天然不满足）， 不依赖任何站点或语言的词表；唯一的词表是广告标记，而它只标不删（业界通用抽取器也这么做）。
 */
private[background] object SearchResults {
  final case class Record(title: String, url: String, snippet: String, date: String, ad: Boolean)
  final case class Candidate(
      path: String,
      sig: String,
      via: String,
      records: List[Record],
      recordCount: Int,
      blockCount: Int,
      distinctUrlRatio: Double,
      snippetCoverage: Double,
      linkDensity: Double,
      inMain: Boolean
  )
  final case class Detected(
      url: String,
      title: String,
      profileApplied: Boolean,
      lists: List[Candidate],
      bestSigHits: Int = 0,
      bestSigTotal: Int = 0,
      bestSigRecords: Int = 0
  ) {

    /** 失败时的诊断摘要：候选容器数 + "最接近合格"的那个容器（同签名兄弟数 / 容器子节点数 / 通过形态判据的记录数）。 */
    def diagnostic: String =
      s"candidates=${lists.size} bestSig=${bestSigHits}/${bestSigTotal} records=$bestSigRecords"
  }

  /** 固化档案：容器路径 + 记录签名（字段规则是通用的，不需要逐字段存）。 */
  final case class Profile(containerPath: String, recordSig: String, hits: Int, failures: Int, updatedAt: Double)
  private val ProfilesKey        = "thSearchListProfiles"
  private val MaxHosts           = 50
  private val MaxProfilesPerHost = 5
  private val MaxProfileFailures = 3
  private val MaxRecordsRendered = 20
  private val MaxResultChars     = 8000
  private val MaxQueryTokens     = 24
  private val MinRecords         = 3
  private val MinDistinctUrls    = 0.6
  private val MinTitleRatio      = 0.8
  private val MaxRecordLinkDens  = 0.5

  /** 结果列表尾部提示：结果只是索引，需要细节/引用时还得打开并抽取（和动作类工具的提示行同风格）。 */
  private val PreviewHint                                       =
    "[these are previews: use browser_navigate to open a result and browser_extract to read it before citing it]"
  // ---------------------------------------------------------------- 页面侧脚本
  /** 检测脚本：query 只用于回传（打分在 Scala 侧），profile 可选（命中则优先按档案抽）。 */
  def detectJs(query: String, profile: Option[Profile]): String = {
    val queryJson   = JSON.stringify(query)
    val profileJson =
      profile
        .map(p =>
          JSON.stringify(JsBridge.obj("containerPath" -> (p.containerPath), "recordSig" -> (p.recordSig)))
        )
        .getOrElse("null")
    DetectTemplate.replace("__QUERY__", queryJson).replace("__PROFILE__", profileJson)
  }
  private val DetectTemplate                                    =
    """
      |(function(){
      |  var QUERY = __QUERY__;
      |  var PROFILE = __PROFILE__;
      |  var HTTP = /^https?:/i;
      |  var out = { url: location.href, title: document.title || "", profileApplied: false, lists: [],
      |              bestSigHits: 0, bestSigTotal: 0, bestSigRecords: 0 };
      |
      |  function clean(s){ return (s || "").replace(/\s+/g, " ").trim(); }
      |  function visible(el){
      |    if (!el || el.nodeType !== 1) return false;
      |    if (el.hidden) return false;
      |    try {
      |      var st = getComputedStyle(el);
      |      if (st.display === "none" || st.visibility === "hidden") return false;
      |    } catch (e) {}
      |    return true;
      |  }
      |  function sigOf(el){
      |    var tags = [], prev = null, n = 0, kids = el.children || [];
      |    for (var i = 0; i < kids.length; i++){
      |      if (!visible(kids[i])) continue;
      |      var t = (kids[i].tagName || "").toLowerCase();
      |      if (t === prev) n++;
      |      else { if (prev) tags.push(n > 1 ? prev + "x" + n : prev); prev = t; n = 1; }
      |    }
      |    if (prev) tags.push(n > 1 ? prev + "x" + n : prev);
      |    return (el.tagName || "").toLowerCase() + ">" + tags.slice(0, 12).join(",");
      |  }
      |  function pathOf(el){
      |    var parts = [], node = el, depth = 0;
      |    while (node && node.nodeType === 1 && node !== document.body && depth < 12){
      |      var tag = (node.tagName || "").toLowerCase();
      |      var idx = 1, sib = node;
      |      while ((sib = sib.previousElementSibling)) if ((sib.tagName || "").toLowerCase() === tag) idx++;
      |      parts.unshift(tag + ":nth-of-type(" + idx + ")");
      |      node = node.parentElement; depth++;
      |    }
      |    return parts.join(">");
      |  }
      |  function byPath(path){
      |    if (!path) return null;
      |    var parts = path.split(">"), node = document.body;
      |    for (var i = 0; i < parts.length && node; i++){
      |      var m = parts[i].match(/^([a-z0-9]+):nth-of-type\((\d+)\)$/);
      |      if (!m) return null;
      |      var tag = m[1], idx = parseInt(m[2], 10), count = 0, found = null, kids = node.children || [];
      |      for (var j = 0; j < kids.length; j++){
      |        if ((kids[j].tagName || "").toLowerCase() === tag){
      |          count++;
      |          if (count === idx) { found = kids[j]; break; }
      |        }
      |      }
      |      if (!found) return null;
      |      node = found;
      |    }
      |    return node;
      |  }
      |  function recordOf(rec){
      |    var anchors = rec.querySelectorAll("a[href]"), best = null;
      |    var linkCount = 0, linkChars = 0;
      |    for (var i = 0; i < anchors.length; i++){
      |      var href = anchors[i].href || "";
      |      var text = clean(anchors[i].innerText || anchors[i].textContent);
      |      if (!HTTP.test(href) || text.length < 2) continue;
      |      linkCount++;
      |      linkChars += text.length;
      |      if (!best || text.length > best.title.length) best = { title: text, url: href };
      |    }
      |    var full = clean(rec.innerText || rec.textContent || "");
      |    // 记录内"连续正文"：链接文字不算，被 <a> 打断就重新起算（真结果是"一个主链接 + 一段正文"，
      |    // "大家还在搜/精选笔记"这类聚合块是一堆短链接拼起来的，凑不出连续正文）。
      |    // 不能只看单个文本节点：引擎常用 <em>/<span> 高亮把摘要切碎，单节点长度会严重偏小。
      |    function longestTextRun(root){
      |      var best = 0;
      |      function walk(node){
      |        var run = 0, kids = node.childNodes || [];
      |        for (var i = 0; i < kids.length; i++){
      |          var child = kids[i];
      |          if (child.nodeType === 3){
      |            var chunk = clean(child.nodeValue || "");
      |            if (chunk.length) run += chunk.length + 1;
      |            if (run > best) best = run;
      |          } else if (child.nodeType === 1){
      |            if (child.tagName === "A"){
      |              if (run > best) best = run;
      |              run = 0;
      |            } else {
      |              run += walk(child);
      |              if (run > best) best = run;
      |            }
      |          }
      |        }
      |        return run;
      |      }
      |      walk(root);
      |      return best;
      |    }
      |    var longestTextRunValue = longestTextRun(rec);
      |    var title = best ? best.title : clean((rec.querySelector("h1,h2,h3,h4") || {}).innerText || "");
      |    var snippet = full;
      |    if (title) snippet = snippet.replace(title, " ");
      |    var date = (full.match(/\d{4}[-/年]\d{1,2}([-/月]\d{1,2}日?)?/) || [""])[0];
      |    if (!date) date = (full.match(/\d+\s*(小时|分钟|天)前/) || [""])[0];
      |    return {
      |      title: title.slice(0, 200),
      |      url: best ? best.url : "",
      |      snippet: clean(snippet).slice(0, 300),
      |      date: date,
      |      ad: /(广告|推广|Sponsored|赞助)/i.test(full),
      |      linkCount: linkCount,
      |      linkChars: linkChars,
      |      textChars: full.length,
      |      longestTextRun: longestTextRunValue
      |    };
      |  }
      |  // 形态判据：一条结果记录 = 一个主链接 + 一段连续正文；聚合块/加载回显会被这里挡掉
      |  function isResultRecord(r){
      |    if (!r.url || r.title.length < 2) return false;
      |    if (r.linkCount > 6) return false;
      |    if (r.textChars > 0 && r.linkChars / r.textChars > 0.6) return false;
      |    return r.longestTextRun >= 40;
      |  }
      |  function metricsOf(listEl, records){
      |    var urls = [], withSnippet = 0, linkChars = 0;
      |    for (var i = 0; i < records.length; i++){
      |      if (records[i].url) urls.push(records[i].url);
      |      if (records[i].snippet && records[i].snippet.length >= 20) withSnippet++;
      |    }
      |    var seen = {}, distinct = 0;
      |    for (var u = 0; u < urls.length; u++){
      |      var key = urls[u].split("#")[0];
      |      if (!seen[key]) { seen[key] = 1; distinct++; }
      |    }
      |    var anchors = listEl.querySelectorAll("a");
      |    for (var a = 0; a < anchors.length; a++) linkChars += clean(anchors[a].innerText || "").length;
      |    var textChars = clean(listEl.innerText || "").length;
      |    return {
      |      recordCount: records.length,
      |      distinctUrlRatio: urls.length ? distinct / urls.length : 0,
      |      snippetCoverage: records.length ? withSnippet / records.length : 0,
      |      linkDensity: textChars > 0 ? linkChars / textChars : 1,
      |      inMain: !!(listEl.closest && listEl.closest('main,[role="main"],article')),
      |      path: pathOf(listEl),
      |      sig: sigOf(listEl)
      |    };
      |  }
      |  function pushList(listEl, via, sigFilter){
      |    var kids = listEl.children || [], records = [], matched = 0;
      |    for (var i = 0; i < kids.length; i++){
      |      if (!visible(kids[i])) continue;
      |      if (sigFilter && sigOf(kids[i]) !== sigFilter) continue;
      |      matched++;
      |      var record = recordOf(kids[i]);
      |      if (isResultRecord(record)) records.push(record);
      |    }
      |    if (records.length < 3) {
      |      // 诊断：记下"最接近合格"的那个容器（同签名兄弟数 / 容器子节点数 / 通过形态判据的记录数），
      |      // 失败时用来区分"没有重复容器"、"容器门没过"还是"记录被滤光"。
      |      if (records.length > out.bestSigRecords) {
      |        out.bestSigRecords = records.length;
      |        out.bestSigHits = matched;
      |        out.bestSigTotal = kids.length;
      |      }
      |      return false;
      |    }
      |    var metrics = metricsOf(listEl, records);
      |    metrics.via = via;
      |    metrics.blockCount = matched;
      |    metrics.records = records.slice(0, 40);
      |    out.lists.push(metrics);
      |    return true;
      |  }
      |
      |  // 1) 优先尝试固化档案
      |  if (PROFILE && PROFILE.containerPath){
      |    var saved = byPath(PROFILE.containerPath);
      |    if (saved && pushList(saved, "profile", PROFILE.recordSig || null)) out.profileApplied = true;
      |  }
      |
      |  // 2) 全量检测：父节点下存在 ≥3 个同签名的兄弟，取该容器的"主导签名"。
      |  //    不再要求"同签名占比 ≥60%"：引擎会把结果卡/答案卡/分页混在同一个容器里（实测 Bing 主导组只有
      |  //    7/14 = 50%），占比只是弱代理；真正的过滤交给记录形态判据 + valid() + 多候选打分。
      |  var all = document.body ? document.body.querySelectorAll("*") : [];
      |  for (var e = 0; e < all.length; e++){
      |    var el = all[e];
      |    if (!visible(el)) continue;
      |    var kids = el.children || [];
      |    if (kids.length < 3) continue;
      |    var groups = {}, order = [];
      |    for (var k = 0; k < kids.length; k++){
      |      if (!visible(kids[k])) continue;
      |      var s = sigOf(kids[k]);
      |      if (!groups[s]) { groups[s] = 0; order.push(s); }
      |      groups[s]++;
      |    }
      |    var maxSig = "", maxHits = 0;
      |    for (var g0 = 0; g0 < order.length; g0++){
      |      var s0 = order[g0];
      |      if (groups[s0] > maxHits) { maxHits = groups[s0]; maxSig = s0; }
      |    }
      |    for (var g = 0; g < order.length; g++){
      |      var sig = order[g], hits = groups[sig];
      |      if (hits < 3 || hits !== maxHits) continue;
      |      pushList(el, "detect", sig);
      |    }
      |  }
      |
      |  // 3) 去掉重复的容器（同 path 只留第一个）
      |  var seenPath = {}, unique = [];
      |  for (var l = 0; l < out.lists.length; l++){
      |    var p = out.lists[l].path || ("#" + l);
      |    if (seenPath[p]) continue;
      |    seenPath[p] = 1;
      |    unique.push(out.lists[l]);
      |  }
      |  out.lists = unique;
      |  return out;
      |})()
      |""".stripMargin
  // ---------------------------------------------------------------- 打分与校验
  private def isCjk(ch: Char): Boolean                          =
    (ch >= '\u3400' && ch <= '\u4dbf') || (ch >= '\u4e00' && ch <= '\u9fff') || (ch >= '\uf900' && ch <= '\ufaff')
  private def tokens(text: String): List[String]                = {
    val lowered       = text.toLowerCase
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
      if (cjk.length < 2) Nil else cjk.zip(cjk.drop(1)).map { case (l, r) => s"$l$r" }.toList
    (words.toList ++ bigrams).distinct
  }

  /** 命中查询词的记录比例（标题 + 摘要）。 */
  private def queryHits(candidate: Candidate, query: String): Double                       = {
    val queryTokens = tokens(query).take(MaxQueryTokens)
    if (queryTokens.isEmpty || candidate.records.isEmpty) 0.0
    else {
      val hit = candidate.records.count { record =>
        val haystack = s"${record.title} ${record.snippet}".toLowerCase
        queryTokens.exists(haystack.contains)
      }
      hit.toDouble / candidate.records.size
    }
  }
  def score(candidate: Candidate, query: String): Double                                   = {
    val hits   = queryHits(candidate, query)
    val count  = math.min(1.0, candidate.recordCount / 10.0)
    val inMain = if (candidate.inMain) 0.5 else 0.0
    val dense  = if (candidate.linkDensity > MaxRecordLinkDens) 0.5 else 0.0
    hits * 3.0 + count * 1.0 + candidate.snippetCoverage * 0.8 + inMain - dense
  }
  def valid(candidate: Candidate): Boolean                                                 = {
    val titled = if (candidate.records.isEmpty) 0.0
    else candidate.records.count(_.title.trim.nonEmpty).toDouble / candidate.records.size
    candidate.recordCount >= MinRecords &&
    candidate.distinctUrlRatio >= MinDistinctUrls &&
    titled >= MinTitleRatio
  }
  def parse(raw: js.Any): Detected                                                         = {
    val result = raw.asInstanceOf[SearchDetectResult]
    val lists  = result.lists.toOption.filter(_ != null).map(_.toList).getOrElse(Nil).map { list =>
      val records = list.records.toOption.filter(_ != null).map(_.toList).getOrElse(Nil).map { record =>
        Record(
          title = record.title.getOrElse(""),
          url = record.url.getOrElse(""),
          snippet = record.snippet.getOrElse(""),
          date = record.date.getOrElse(""),
          ad = record.ad.getOrElse(false)
        )
      }
      Candidate(
        path = list.path.getOrElse(""),
        sig = list.sig.getOrElse(""),
        via = list.via.getOrElse("detect"),
        records = records,
        recordCount = list.recordCount.toOption.getOrElse(records.size),
        blockCount = list.blockCount.toOption.getOrElse(records.size),
        distinctUrlRatio = list.distinctUrlRatio.getOrElse(0.0),
        snippetCoverage = list.snippetCoverage.getOrElse(0.0),
        linkDensity = list.linkDensity.getOrElse(1.0),
        inMain = list.inMain.getOrElse(false)
      )
    }
    Detected(
      url = result.url.getOrElse(""),
      title = result.title.getOrElse(""),
      profileApplied = result.profileApplied.getOrElse(false),
      lists = lists,
      bestSigHits = result.bestSigHits.toOption.getOrElse(0),
      bestSigTotal = result.bestSigTotal.toOption.getOrElse(0),
      bestSigRecords = result.bestSigRecords.toOption.getOrElse(0)
    )
  }
  def render(detected: Detected, best: Candidate, bestScore: Double, ignored: Int): String = {
    val header =
      s"URL: ${detected.url}\n" +
        s"Title: ${detected.title}\n" +
        s"[search-results: ${best.records.size} records of ${best.blockCount} blocks · " +
        s"score ${"%.2f".format(bestScore)} · " +
        s"$ignored other candidate list(s) ignored · via ${best.via}]\n\n"
    val body   = best.records.take(MaxRecordsRendered).zipWithIndex.map { case (record, index) =>
      val ad       = if (record.ad) "[ad] " else ""
      val headline = s"${index + 1}. $ad\"${record.title}\" url=${record.url}"
      val lines    = mutable.ListBuffer(headline)
      if (record.snippet.nonEmpty) lines += s"   snippet: ${record.snippet}"
      if (record.date.nonEmpty) lines += s"   date: ${record.date}"
      lines.mkString("\n")
    }.mkString("\n")
    val text   = header + body + "\n"
    val capped = if (text.length <= MaxResultChars) text
    else text.take(MaxResultChars) + s"\n[search-results truncated at $MaxResultChars chars]\n"
    s"$capped$PreviewHint\n"
  }
  // ---------------------------------------------------------------- 固化档案
  def loadProfile(host: String): Future[Option[Profile]]                                   =
    if (host.isEmpty) Future.successful(None)
    else
      ChromeAPI.storage.local.get(ProfilesKey).toFuture
        .map(values => profilesOf(values.get(ProfilesKey)).getOrElse(host, Nil).sortBy(-_.hits).headOption)
        .recover { case _ => None }
  def saveProfile(host: String, containerPath: String, recordSig: String): Future[Unit]    =
    if (host.isEmpty || containerPath.isEmpty) Future.successful(())
    else
      ChromeAPI.storage.local.get(ProfilesKey).toFuture
        .map(values => profilesOf(values.get(ProfilesKey)))
        .flatMap { all =>
          val current  = all.getOrElse(host, Nil)
          val existing = current.find(profile => profile.containerPath == containerPath)
          val updated  = Profile(
            containerPath = containerPath,
            recordSig = recordSig,
            hits = existing.map(_.hits + 1).getOrElse(1),
            failures = 0,
            updatedAt = js.Date.now()
          )
          val merged   = (updated :: current.filterNot(_.containerPath == containerPath))
            .sortBy(-_.updatedAt)
            .take(MaxProfilesPerHost)
          val next     = (all - host).take(MaxHosts - 1) + (host -> merged)
          ChromeAPI.storage.local
            .set(js.Dictionary[js.Any](ProfilesKey -> toJs(next)))
            .toFuture
            .map(_ => ())
        }
        .recover { case _ => () }

  /** 档案失效计数：连续失败到阈值就把它删掉，下次走全量检测（只影响这次实际用到的那份档案）。 */
  def failProfile(host: String, containerPath: String): Future[Unit]       =
    if (host.isEmpty || containerPath.isEmpty) Future.successful(())
    else
      ChromeAPI.storage.local.get(ProfilesKey).toFuture
        .map(values => profilesOf(values.get(ProfilesKey)))
        .flatMap { all =>
          val current = all.getOrElse(host, Nil)
          val next    = current
            .map(profile =>
              if (profile.containerPath == containerPath) profile.copy(failures = profile.failures + 1) else profile
            )
            .filter(_.failures < MaxProfileFailures)
          val updated = if (next.isEmpty) all - host else all.updated(host, next)
          ChromeAPI.storage.local
            .set(js.Dictionary[js.Any](ProfilesKey -> toJs(updated)))
            .toFuture
            .map(_ => ())
            .recover { case _ => () }
        }
        .recover { case _ => () }
  private def profilesOf(raw: Option[js.Any]): Map[String, List[Profile]]  =
    raw.filter(value => value != null && !js.isUndefined(value)) match {
      case None        => Map.empty
      case Some(value) =>
        JsBridge.asDictionary(value).toMap.map { case (host, entriesRaw) =>
          val entries = JsBridge.asArray(entriesRaw).toList.map { entry =>
            Profile(
              containerPath = JsonValue.stringField(entry, "containerPath").getOrElse(""),
              recordSig = JsonValue.stringField(entry, "recordSig").getOrElse(""),
              hits = JsonValue.intField(entry, "hits").getOrElse(0),
              failures = JsonValue.intField(entry, "failures").getOrElse(0),
              updatedAt = JsonValue.doubleField(entry, "updatedAt").getOrElse(0.0)
            )
          }.filter(_.containerPath.nonEmpty)
          host -> entries
        }
    }
  private def toJs(all: Map[String, List[Profile]]): js.Dictionary[js.Any] =
    js.Dictionary(all.toSeq.map { case (host, entries) =>
      host -> JsBridge.asObject(js.Array(entries.map { entry =>
        JsBridge.obj(
          "containerPath" -> (entry.containerPath),
          "recordSig"     -> (entry.recordSig),
          "hits"          -> (entry.hits),
          "failures"      -> (entry.failures),
          "updatedAt"     -> (entry.updatedAt)
        )
      }*))
    }*)

  /** 校验失败的日志（保持可排查，不静默）。 */
  def logNoResult(host: String, detected: Detected): Unit =
    PopCanLog.warn(
      "tool",
      s"search: no valid result list on $host (title='${detected.title.take(60)}', ${detected.diagnostic})"
    )
}
