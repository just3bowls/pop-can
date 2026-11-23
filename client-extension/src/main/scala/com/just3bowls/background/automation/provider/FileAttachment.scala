package com.just3bowls.background.automation.provider

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/**
 * Prompt `file` 模式的附件投递：在**页面上下文**里把 request body 造成一个 `File` 交给页面。
 *
 * 怎么交给页面由 **provider 配置**决定（`attachmentStrategy` = `input` / `paste` / `drop`，`attachmentSelector` 是目标选择器）：
 *
 *   - `input`：塞进 `input[type=file]` 再派发 `input` + `change`；选择器留空时按 accept 打分在页面内挑一个 file input。
 *   - `paste`：朝可编辑区派发带 `clipboardData` 的 `paste`（yuanbao / wenxin 走这条）。
 *   - `drop`：朝容器派发 `dragenter` / `dragover` / `drop`（qwen 走这条）。
 *
 * 只用 `Runtime.evaluate` + `File` / `DataTransfer`（不落盘、不新增权限）。**不做跨机制回退**：配置哪条就用哪条， 失败直接抛错（选择器找不到 / 页面没收下），由调用方按失败收口。
 */
private[background] object FileAttachment {
  val StrategyInput = "input"
  val StrategyPaste = "paste"
  val StrategyDrop  = "drop"

  /** 附件交出去后，最多等多久在页面上看到文件名。 */
  val VerifyWaitMs                          = 10000
  private val PollGapMs                     = 500
  private def literal(text: String): String = js.JSON.stringify(text)

  /**
   * `input` 策略且**没有配选择器**时的挑框规则：页面里常常不止一个 `input[type=file]`（例如 gml 的 composer 附件框和头像上传框）。 `accept` 只在用户手选时生效，程序赋值照塞不误
   * —— 但组件自己会按 `accept` 过滤，塞错框等于白塞。 所以按优先级挑：**任意类型（accept 为空）> accept 里含文本类后缀 > 其余第一个**；同档保持 DOM 顺序。
   */
  private val pickInputJs: String                                                   =
    """
      |  var TextLike = ['text/plain', '.txt', '.md', '.markdown', 'json', 'csv', 'yaml', 'yml'];
      |  function inputScore(node){
      |    var accept = (node.accept || '').toLowerCase();
      |    var score = 1;
      |    if (!accept.trim()) score = 3;
      |    for (var i = 0; i < TextLike.length; i++) {
      |      if (accept.indexOf(TextLike[i]) >= 0) { score = 2; break; }
      |    }
      |    return node.disabled ? score - 10 : score;
      |  }
      |  var candidates = [].slice.call(document.querySelectorAll('input[type=file]'));
      |  if (!candidates.length) return 'none';
      |  var best = candidates.slice().sort(function(a, b){ return inputScore(b) - inputScore(a); })[0];
      |""".stripMargin
  private def setInputExpr(name: String, content: String, selector: String): String = {
    val trimmed = selector.trim
    val pick    =
      if (trimmed.isEmpty) pickInputJs
      else
        s"""
       |  var best = document.querySelector(${literal(trimmed)});
       |  if (!best || best.type !== 'file') return 'missing';
       |""".stripMargin
    s"""
       |(function(){
       |  var name = ${literal(name)}, text = ${literal(content)};
       |$pick
       |  if (!best) return 'none';
       |  if (best.disabled || best.getAttribute('aria-disabled') === 'true') return 'disabled';
       |  var control = best;
       |  while (control && control !== document.body) {
       |    var ariaDisabled = control.getAttribute('aria-disabled');
       |    var className = (control.className && control.className.baseVal != null
       |      ? control.className.baseVal
       |      : control.className || '').toString().toLowerCase();
       |    if (ariaDisabled === 'true') return 'disabled';
       |    if ((className.indexOf('upload') >= 0 ||
       |         className.indexOf('attach') >= 0 ||
       |         className.indexOf('file') >= 0) &&
       |        className.indexOf('disabled') >= 0) return 'disabled';
       |    control = control.parentElement;
       |  }
       |  var file;
       |  try {
       |    file = new File([text], name, {type: 'text/plain'});
       |    var dt = new DataTransfer();
       |    dt.items.add(file);
       |    best.files = dt.files;
       |  } catch (e) { return 'none'; }
       |  best.dispatchEvent(new Event('input', {bubbles: true}));
       |  best.dispatchEvent(new Event('change', {bubbles: true}));
       |  return 'input';
       |})()
       |""".stripMargin
  }
  private def pasteExpr(name: String, content: String, selector: String): String    = {
    val trimmed = selector.trim
    val target  =
      if (trimmed.isEmpty)
        """
          |  var target = document.querySelector('[contenteditable=true]')
          |    || document.querySelector('textarea')
          |    || document.activeElement
          |    || document.body;
          |""".stripMargin
      else
        s"""
           |  var target = document.querySelector(${literal(trimmed)});
           |  if (!target) return 'missing';
           |""".stripMargin
    s"""
       |(function(){
       |  var name = ${literal(name)}, text = ${literal(content)};
       |$target
       |  var dt = new DataTransfer();
       |  dt.items.add(new File([text], name, {type: 'text/plain'}));
       |  try {
       |    target.dispatchEvent(new ClipboardEvent('paste', {bubbles: true, cancelable: true, clipboardData: dt}));
       |  } catch (e) { return 'none'; }
       |  return 'paste';
       |})()
       |""".stripMargin
  }
  private def dropExpr(name: String, content: String, selector: String): String     = {
    val trimmed = selector.trim
    val target  =
      if (trimmed.isEmpty)
        """
          |  var target = document.querySelector('form')
          |    || document.querySelector('[class*=composer]')
          |    || document.querySelector('[class*=editor]')
          |    || document.querySelector('[class*=upload]')
          |    || document.body;
          |""".stripMargin
      else
        s"""
           |  var target = document.querySelector(${literal(trimmed)});
           |  if (!target) return 'missing';
           |""".stripMargin
    s"""
       |(function(){
       |  var name = ${literal(name)}, text = ${literal(content)};
       |$target
       |  var dt = new DataTransfer();
       |  dt.items.add(new File([text], name, {type: 'text/plain'}));
       |  ['dragenter', 'dragover', 'drop'].forEach(function(type){
       |    target.dispatchEvent(new DragEvent(type, {bubbles: true, cancelable: true, dataTransfer: dt}));
       |  });
       |  return 'drop';
       |})()
       |""".stripMargin
  }

  /**
   * Count attachment-name evidence in page text.
   *
   * `input` strategy ignores editable/prompt text, because the thin file prompt already contains the generated
   * filename. Only a new occurrence outside the editor can prove that the page mounted an attachment.
   */
  private def evidenceCountExpr(name: String, strategy: String): String = {
    val stem         = {
      val dot = name.lastIndexOf('.')
      if (dot > 0) name.take(dot) else name
    }
    val ignoreEditor = strategy == StrategyInput
    s"""
       |(function(){
       |  var name = ${literal(name.toLowerCase)}, stem = ${literal(stem.toLowerCase)};
       |  function occurrences(text, needle) {
       |    if (!needle) return 0;
       |    var count = 0, at = 0;
       |    while ((at = text.indexOf(needle, at)) >= 0) {
       |      count += 1;
       |      at += needle.length;
       |    }
       |    return count;
       |  }
       |  function inEditable(node) {
       |    return !!(node.closest('input,textarea,[contenteditable="true"],[contenteditable="plaintext-only"]'));
       |  }
       |  var body = document.body;
       |  if (!body) return 0;
       |  var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, null, false);
       |  var count = 0, node;
       |  while ((node = walker.nextNode())) {
       |    var parent = node.parentElement;
       |    if (!parent) continue;
       |    var tag = parent.tagName;
       |    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') continue;
       |    if (${ignoreEditor.toString} && inEditable(parent)) continue;
       |    var text = (node.nodeValue || '').toLowerCase();
       |    count += occurrences(text, name);
       |    if (stem !== name) count += occurrences(text, stem);
       |  }
       |  return count;
       |})()
       |""".stripMargin
  }
  private def countOf(raw: js.Any): Int                                 =
    try raw.toString.toDouble.toInt
    catch { case _: Throwable => -1 }
  private def waitUntilEvidence(
      evaluate: String => Future[js.Any],
      delay: Int => Future[Unit],
      name: String,
      strategy: String,
      baseline: Int,
      deadline: Double
  ): Future[Boolean] =
    evaluate(evidenceCountExpr(name, strategy))
      .map(count => countOf(count) > baseline)
      .recover { case _: Throwable => false }
      .flatMap { increased =>
        if (increased) Future.successful(true)
        else if (js.Date.now() >= deadline) Future.successful(false)
        else delay(PollGapMs).flatMap(_ => waitUntilEvidence(evaluate, delay, name, strategy, baseline, deadline))
      }

  /**
   * 按 provider 配置把附件交给页面，并用"提示词之外新增文件名证据"确认它真的挂上了。
   *
   * @return
   *   成功时返回用到的策略（`input` / `paste` / `drop`）；配置缺失、选择器找不到、页面没收下都直接失败（不回退）。
   */
  def attach(
      evaluate: String => Future[js.Any],
      delay: Int => Future[Unit],
      provider: String,
      name: String,
      content: String,
      strategy: String,
      selector: String
  ): Future[String] = {
    val trimmed = Option(strategy).map(_.trim).getOrElse("")
    val expr    = trimmed match {
      case StrategyInput => setInputExpr(name, content, selector)
      case StrategyPaste => pasteExpr(name, content, selector)
      case StrategyDrop  => dropExpr(name, content, selector)
      case _             => ""
    }
    if (expr.isEmpty)
      Future.failed(new Exception(
        s"Attachment is not configured for this provider (provider-catalog/providers/$provider.jsonc → attachmentStrategy)"
      ))
    else
      evaluate(evidenceCountExpr(name, trimmed))
        .map(countOf)
        .recover { case _: Throwable => -1 }
        .flatMap { baseline =>
          if (baseline < 0)
            Future.failed(new Exception(s"Could not inspect attachment state for $provider"))
          else
            evaluate(expr)
              .map(_.toString)
              .recover { case _: Throwable => "none" }
              .flatMap {
                case tag if tag == StrategyInput || tag == StrategyPaste || tag == StrategyDrop =>
                  waitUntilEvidence(
                    evaluate,
                    delay,
                    name,
                    trimmed,
                    baseline,
                    js.Date.now() + VerifyWaitMs
                  ).flatMap { accepted =>
                    if (accepted) Future.successful(tag)
                    else
                      Future.failed(new Exception(
                        s"File prompt mode: $provider did not mount the attachment ($name)"
                      ))
                  }
                case "disabled"                                                                 =>
                  Future.failed(new Exception(
                    s"File prompt mode: $provider attachment upload is disabled for the current page settings"
                  ))
                case "missing"                                                                  =>
                  Future.failed(new Exception(s"Attachment selector not found for $provider: $selector"))
                case _                                                                          =>
                  Future.failed(new Exception(s"File prompt mode: $provider did not accept the attachment ($name)"))
              }
        }
  }
}
