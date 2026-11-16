package com.just3bowls.background.automation.browser

/** Page-side JavaScript used by browser tools. Kept separate from CDP orchestration. */
private[background] object BrowserPageScripts {
  val LibraryProbe: String =
    "(function(){return (typeof window.Readability === 'function' ? 'r' : '') + " +
      "(typeof window.TurndownService === 'function' ? 't' : '');})()"

  val PageInfo: String =
    """(function(){
      |  var doc = document;
      |  var root = doc.documentElement;
      |  var token = window.__thDocumentToken;
      |  if (!token) {
      |    token = String(Date.now()) + "-" + Math.random();
      |    try { window.__thDocumentToken = token; } catch (e) { }
      |  }
      |  return {
      |    url: location.href || "",
      |    title: doc.title || "",
      |    token: token,
      |    scrollX: window.scrollX || 0,
      |    scrollY: window.scrollY || 0,
      |    viewportWidth: window.innerWidth || 0,
      |    viewportHeight: window.innerHeight || 0,
      |    documentHeight: root ? root.scrollHeight : 0
      |  };
      |})()""".stripMargin

  val ExtractPipeline: String =
    """
      |(function(){
      |  var out = { url: location.href, title: document.title || "", metadata: "", readability: "",
      |              readabilityTitle: "", readabilityLinkDensity: 0, full: "", note: "" };
      |  try {
      |    var clone = document.cloneNode(true);
      |
      |    // 1) 结构化元数据：JSON-LD articleBody / itemprop=articleBody
      |    var meta = (function(doc){
      |      var scripts = doc.querySelectorAll('script[type="application/ld+json"]');
      |      for (var i = 0; i < scripts.length; i++) {
      |        try {
      |          var parsed = JSON.parse(scripts[i].textContent || "{}");
      |          var queue = Array.isArray(parsed) ? parsed.slice() : [parsed];
      |          while (queue.length) {
      |            var item = queue.shift();
      |            if (!item || typeof item !== "object") continue;
      |            if (typeof item.articleBody === "string" && item.articleBody.trim().length >= 400) {
      |              return item.articleBody.trim();
      |            }
      |            var keys = Object.keys(item);
      |            for (var k = 0; k < keys.length; k++) {
      |              var value = item[keys[k]];
      |              if (value && typeof value === "object") queue.push(value);
      |            }
      |          }
      |        } catch (e) {}
      |      }
      |      var node = doc.querySelector('[itemprop="articleBody"]');
      |      if (node) {
      |        var text = (node.innerText || node.textContent || "").replace(/\s+/g, " ").trim();
      |        if (text.length >= 400) return text;
      |      }
      |      return "";
      |    })(clone);
      |    if (meta) out.metadata = meta.slice(0, 50000);
      |
      |    // 2) readability（Chrome 阅读模式同源算法），转 markdown
      |    if (!out.metadata && typeof window.Readability === "function") {
      |      try {
      |        var article = new window.Readability(clone, { charThreshold: 400, keepClasses: false }).parse();
      |        if (article && article.textContent && article.textContent.trim().length >= 400) {
      |          var body = article.textContent;
      |          try {
      |            if (typeof window.TurndownService === "function") {
      |              body = new window.TurndownService(
      |                { headingStyle: "atx", codeBlockStyle: "fenced", bulletListMarker: "-" }
      |              ).turndown(article.content);
      |            }
      |          } catch (e) { out.note = "turndown failed"; }
      |          out.readability = body.slice(0, 50000);
      |          out.readabilityTitle = article.title || "";
      |          try {
      |            var holder = document.createElement("div");
      |            holder.innerHTML = article.content || "";
      |            var anchors = holder.querySelectorAll("a");
      |            var linkChars = 0;
      |            for (var a = 0; a < anchors.length; a++) linkChars += (anchors[a].textContent || "").length;
      |            var allChars = (holder.textContent || "").length;
      |            out.readabilityLinkDensity = allChars > 0 ? linkChars / allChars : 0;
      |          } catch (e) {}
      |        }
      |      } catch (e) {
      |        out.note = "readability: " + (e && e.message ? e.message : String(e));
      |      }
      |    }
      |
      |    // 4) 全文兜底（同样在 clone 上做，不删活页面元素）
      |    var fullClone = document.cloneNode(true);
      |    var drop = fullClone.querySelectorAll("script, style, nav, footer, header, iframe");
      |    for (var d = 0; d < drop.length; d++) {
      |      if (drop[d].parentNode) drop[d].parentNode.removeChild(drop[d]);
      |    }
      |    var bodyEl = fullClone.body;
      |    var bodyText = bodyEl ? (bodyEl.innerText || bodyEl.textContent || "") : "";
      |    out.full = bodyText.replace(/\s+/g, " ").trim().slice(0, 50000);
      |  } catch (e) {
      |    out.note = "pipeline: " + (e && e.message ? e.message : String(e));
      |  }
      |  return out;
      |})()
      |""".stripMargin

  /** 视口滚动脚本：优先滚视口中心下最内层的可滚动容器（SPA 常见），否则滚 window。 */
  def scroll(deltaX: Double, deltaY: Double): String =
    s"""(function(dx, dy){
       |  function scrollable(el, axis) {
       |    if (!el || el.nodeType !== 1) return false;
       |    var style = window.getComputedStyle(el);
       |    var overflow = axis === "y" ? style.overflowY : style.overflowX;
       |    if (overflow !== "auto" && overflow !== "scroll" && overflow !== "overlay") return false;
       |    return axis === "y" ? (el.scrollHeight - el.clientHeight > 1) : (el.scrollWidth - el.clientWidth > 1);
       |  }
       |  function describe(el) {
       |    if (!el || el === document.body || el === document.documentElement) return "window";
       |    var tag = (el.tagName || "div").toLowerCase();
       |    return el.id ? tag + "#" + el.id : tag;
       |  }
       |  var cx = Math.max(1, Math.floor((window.innerWidth || 2) / 2));
       |  var cy = Math.max(1, Math.floor((window.innerHeight || 2) / 2));
       |  var node = document.elementFromPoint(cx, cy);
       |  while (node && node !== document.body && node !== document.documentElement) {
       |    if ((dy !== 0 && scrollable(node, "y")) || (dx !== 0 && scrollable(node, "x"))) {
       |      node.scrollBy({left: dx, top: dy, behavior: "auto"});
       |      return {
       |        target: describe(node),
       |        targetX: node.scrollLeft || 0,
       |        targetY: node.scrollTop || 0,
       |        windowX: window.scrollX || 0,
       |        windowY: window.scrollY || 0
       |      };
       |    }
       |    node = node.parentElement;
       |  }
       |  window.scrollBy({left: dx, top: dy, behavior: "auto"});
       |  return {
       |    target: "window",
       |    targetX: window.scrollX || 0,
       |    targetY: window.scrollY || 0,
       |    windowX: window.scrollX || 0,
       |    windowY: window.scrollY || 0
       |  };
       |})(${deltaX.toInt}, ${deltaY.toInt})""".stripMargin
}
