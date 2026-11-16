package com.just3bowls.background.automation.cdp

import scala.scalajs.js

/** Browser-side expressions used by the provider CDP flow. */
private[background] object CdpFlowExpressions {

  /**
   * 通用"安全取点"表达式：标记优先（`[data-th-role=…]`），其次 XPath / CSS 选择器。
   *
   * 先把元素滚进视口，再取 `rect ∩ viewport` 的中心，越界 / 被遮挡都显式报错。
   */
  def safePoint(role: String, locator: String, metaPrefix: String = ""): String =
    s"""
       |(function(){
       |  var role = ${js.JSON.stringify(role)};
       |  var locator = ${js.JSON.stringify(locator)};
       |  var el = role ? document.querySelector('[data-th-role="' + role + '"]') : null;
       |  if (!el && locator.trim().length > 0) {
       |    el = locator.trim().indexOf('/') === 0
       |      ? document.evaluate(locator, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue
       |      : document.querySelector(locator);
       |  }
       |  if (!el) return JSON.stringify({${metaPrefix}status: 'notfound'});
       |  var style = getComputedStyle(el);
       |  if (style.display === 'none' || style.visibility === 'hidden')
       |    return JSON.stringify({${metaPrefix}status: 'hidden'});
       |  var rect = el.getBoundingClientRect();
       |  if (rect.width <= 0 || rect.height <= 0) return JSON.stringify({${metaPrefix}status: 'hidden'});
       |  try { el.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'instant'}); }
       |  catch (e) { try { el.scrollIntoView(); } catch (e2) {} }
       |  rect = el.getBoundingClientRect();
       |  var vw = window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0;
       |  var vh = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0;
       |  var rectInfo = {t: Math.round(rect.top), l: Math.round(rect.left), w: Math.round(rect.width),
       |    h: Math.round(rect.height), vw: vw, vh: vh};
       |  var left = Math.max(rect.left, 0), top = Math.max(rect.top, 0);
       |  var right = Math.min(rect.right, vw), bottom = Math.min(rect.bottom, vh);
       |  if (right - left < 2 || bottom - top < 2)
       |    return JSON.stringify({${metaPrefix}status: 'offscreen', rect: rectInfo});
       |  var cx = left + (right - left) / 2;
       |  var cy = top + (bottom - top) / 2;
       |  var hit = document.elementFromPoint(cx, cy);
       |  if (hit && hit !== el && !el.contains(hit))
       |    return JSON.stringify({${metaPrefix}status: 'covered', rect: rectInfo});
       |  return JSON.stringify({${metaPrefix}status: 'found', x: cx, y: cy, rect: rectInfo});
       |})()
       |""".stripMargin

  def markerTarget(role: String, selector: String): String =
    safePoint(role, selector, metaPrefix = "role: role, locator: locator, ")

  def inputState(prompt: String): String =
    s"""
       |(function(){
       |  var input = document.querySelector('[data-th-role="input"]');
       |  var active = document.activeElement;
       |  if (!input) return JSON.stringify({ok:false, value:'', active:'', reason:'notfound'});
       |  var tag = input.tagName.toLowerCase();
       |  var raw = (tag === 'input' || tag === 'textarea') ? (input.value || '') : (input.innerText || input.textContent || '');
       |  var inside = active && (active === input || (input.contains && input.contains(active)));
       |  var expected = ${js.JSON.stringify(prompt)}.replace(/\\s+/g, ' ').trim();
       |  var normalized = raw.replace(/\\s+/g, ' ').trim();
       |  var doubled = expected.length > 0 && normalized.indexOf(expected + expected) >= 0;
       |  var ok = inside && normalized.indexOf(expected) >= 0 && !doubled;
       |  return JSON.stringify({
       |    ok: ok,
       |    value: raw,
       |    normalized: normalized,
       |    expected: expected,
       |    activeTag: active ? active.tagName.toLowerCase() : '',
       |    activeRole: active ? (active.getAttribute('role') || '') : '',
       |    inside: !!inside
       |  });
       |})()
       |""".stripMargin

  /** 往输入框写文本的唯一底层表达式；调用方按元素类型选择它或 CDP Input。 */
  def fillInput(text: String): String =
    s"""
       |(function(){
       |  var input = document.querySelector('[data-th-role="input"]');
       |  if (!input) return JSON.stringify({ok:false, reason:'notfound', text:''});
       |  var value = ${js.JSON.stringify(text)};
       |  var tag = input.tagName.toLowerCase();
       |  try { input.focus(); } catch (e) {}
       |  if (tag === 'input' || tag === 'textarea') {
       |    var proto  = (tag === 'textarea') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
       |    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
       |    setter.call(input, value);
       |    input.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
       |    input.dispatchEvent(new Event('change', {bubbles: true}));
       |    return JSON.stringify({ok:true, method:'value', text: input.value || ''});
       |  }
       |  var sel = window.getSelection ? window.getSelection() : null;
       |  var range = document.createRange();
       |  range.selectNodeContents(input);
       |  if (sel) { sel.removeAllRanges(); sel.addRange(range); }
       |  var inserted = false;
       |  try { inserted = document.execCommand('insertText', false, value); } catch (e) { inserted = false; }
       |  return JSON.stringify({ok: inserted, method:'execCommand', text: (input.innerText || input.textContent || '')});
       |})()
       |""".stripMargin

  val inputKind: String =
    """
      |(function(){
      |  var el = document.querySelector('[data-th-role="input"]');
      |  if (!el) return 'none';
      |  var tag = el.tagName.toLowerCase();
      |  return (tag === 'input' || tag === 'textarea') ? 'text' : 'contenteditable';
      |})()
      |""".stripMargin

  val selectAllInInput: String =
    """
      |(function(){
      |  var el = document.querySelector('[data-th-role="input"]');
      |  if (!el) return 'none';
      |  try { el.focus(); } catch (e) {}
      |  var sel = window.getSelection ? window.getSelection() : null;
      |  var range = document.createRange();
      |  range.selectNodeContents(el);
      |  if (sel) { sel.removeAllRanges(); sel.addRange(range); }
      |  return 'selected';
      |})()
      |""".stripMargin

  def clearComposer(prompt: String): String =
    s"""
       |(function(){
       |  var input = document.querySelector('[data-th-role="input"]');
       |  if (!input) return 'notfound';
       |  var tag = input.tagName.toLowerCase();
       |  if (tag === 'input' || tag === 'textarea') {
       |    if (!input.value) return 'empty';
       |    var proto  = (tag === 'textarea') ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
       |    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
       |    setter.call(input, '');
       |    input.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
       |    input.dispatchEvent(new Event('change', {bubbles: true}));
       |    return 'value';
       |  }
       |  var expected = ${js.JSON.stringify(prompt)}.replace(/\\s+/g, ' ').trim();
       |  var raw = (input.innerText || input.textContent || '').replace(/\\s+/g, ' ').trim();
       |  if (!expected || raw.indexOf(expected) < 0) return 'notours';
       |  var sel = window.getSelection ? window.getSelection() : null;
       |  var range = document.createRange();
       |  range.selectNodeContents(input);
       |  if (sel) { sel.removeAllRanges(); sel.addRange(range); }
       |  var deleted = false;
       |  try { deleted = document.execCommand('delete'); } catch (e) { deleted = false; }
       |  return deleted ? 'delete' : 'none';
       |})()
       |""".stripMargin
}
