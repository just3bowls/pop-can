#!/usr/bin/env node
/**
 * 用**真引擎**回放 `provider-catalog/fixtures/` 里的每个响应方言与请求体样本，逐条断言。
 *
 * 为什么走 CDP 而不是在 node 里重写一遍解析：规则引擎只有一份实现（`background/LocalRawParser`），
 * 回归网必须打在同一份实现上，否则"fixtures 全绿"不能说明线上会成。
 *
 * 前提：浏览器开着 `--remote-debugging-port=9222`，扩展已加载（会自动开一个插件页当消息入口）。
 *
 * 用法：
 *   node client-extension/scripts/replay-fixtures.mjs                 # 全部
 *   node client-extension/scripts/replay-fixtures.mjs --rule yuanbao  # 单家
 */
import { readdirSync, readFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseJsonc, printParseErrorCode } from 'jsonc-parser'
import { evalInTarget, reloadPage, usablePluginPage } from './lib/provider-cdp.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const catalogDir = join(here, '..', 'provider-catalog')
const fixturesDir = join(catalogDir, 'fixtures')
const onlyRule = process.argv.includes('--rule') ? process.argv[process.argv.indexOf('--rule') + 1] : ''

const readFixtures = () => {
  if (!existsSync(fixturesDir)) return []
  const out = []
  for (const provider of readdirSync(fixturesDir).sort()) {
    if (onlyRule && provider !== onlyRule) continue
    const dir = join(fixturesDir, provider)
    for (const file of readdirSync(dir).sort()) {
      if (file.endsWith('.raw.txt')) {
        const dialect = file.replace(/\.raw\.txt$/, '')
        const expectPath = join(dir, `${dialect}.expect.json`)
        if (!existsSync(expectPath)) throw new Error(`${provider}/${dialect}: missing .expect.json`)
        out.push({
          provider,
          dialect,
          raw: readFileSync(join(dir, file), 'utf8'),
          spec: JSON.parse(readFileSync(expectPath, 'utf8'))
        })
      } else if (file.endsWith('.request.json')) {
        const spec = JSON.parse(readFileSync(join(dir, file), 'utf8'))
        out.push({
          provider,
          dialect: file,
          request: spec,
          spec
        })
      }
    }
  }
  return out
}

const compactionFixture = () => {
  const event = (seq, component, value) => {
    const payload = {
      status: 0,
      qid: 'synthetic',
      sessionId: 'synthetic',
      seq_id: seq,
      data: {
        message: {
          content: {
            generator: {
              component,
              isFinished: false,
              data: { value }
            }
          }
        }
      }
    }
    return `event:message\ndata:${JSON.stringify(payload)}\n\n`
  }
  let seq = 1
  let raw = ''
  for (let i = 0; i < 72; i += 1) {
    raw += event(seq++, 'pad', `${i}:${'x'.repeat(1000)}`)
  }
  const values = [
    '@@BEG:response@@\n',
    '@@BEG:reasoning_content@@\n',
    'compaction boundary reasoning\n',
    '@@END:reasoning_content',
    '@@',
    '\n\n',
    '@@',
    'BEG:content',
    '@@\n',
    'boundary answer\n',
    '@@END:content',
    '@@',
    '\n',
    '@@END:response@@'
  ]
  for (const value of values) raw += event(seq++, 'markdown-yiyan', value)
  return {
    provider: 'wenxin',
    dialect: 'synthetic-compaction',
    raw,
    spec: {
      sourceUrl: 'https://chat.baidu.com/aichat/api/conversation',
      expect: {
        markerOk: true,
        contentContains: ['compaction boundary reasoning', 'boundary answer'],
        handlers: ['wenxinConversationAnswer']
      }
    }
  }
}

const ruleOf = provider => {
  const path = join(catalogDir, 'providers', `${provider}.jsonc`)
  if (!existsSync(path)) throw new Error(`missing rule file: ${path}`)
  const text = readFileSync(path, 'utf8')
  const errors = []
  const doc = parseJsonc(text, errors, { allowTrailingComma: true, disallowComments: false })
  if (errors.length > 0) {
    const first = errors[0]
    const before = text.slice(0, first.offset)
    const line = before.split('\n').length
    const column = before.length - before.lastIndexOf('\n')
    throw new Error(`${path}:${line}:${column}: ${printParseErrorCode(first.error)}`)
  }
  return JSON.stringify(doc.parserRule)
}

const markerOk = content => {
  const text = (content || '').trim()
  return text.startsWith('@@BEG:response@@') && text.endsWith('@@END:response@@')
}

const main = async () => {
  const fixtures = readFixtures()
  if (!onlyRule || onlyRule === 'wenxin') fixtures.push(compactionFixture())
  if (fixtures.length === 0) throw new Error('no fixtures found')
  const page = await usablePluginPage()
  const preflight = await evalInTarget(page, "chrome.runtime.sendMessage({type:'STATE_STATS'}).then(r => JSON.stringify(r)).catch(e => 'ERR ' + e.message)")
  if (typeof preflight !== 'string' || !preflight.startsWith('{"ok":true')) {
    throw new Error(`background did not answer STATE_STATS (${preflight}) — reload the extension and retry`)
  }
  let failed = 0
  console.log(`replay: ${fixtures.length} fixture(s) via ${page.url}`)
  for (const fixture of fixtures) {
    const rule = ruleOf(fixture.provider)
    const requestTemplate = fixture.request?.requestTemplate || 'Pop Can request replay'
    const payload = JSON.stringify(
      fixture.request
        ? {
            type: 'RULE_REPLAY',
            rule,
            requestBodyBase64: fixture.request.bodyBase64,
            requestTemplate
          }
        : {
            type: 'RULE_REPLAY',
            rule,
            raw: fixture.raw,
            sourceUrl: fixture.spec.sourceUrl || ''
          }
    )
    const sendOnce = target =>
      evalInTarget(target, `chrome.runtime.sendMessage(${payload}).then(r => JSON.stringify(r)).catch(e => 'ERR ' + e.message)`)
    let raw
    try {
      raw = await sendOnce(page)
    } catch (error) {
      // 单条 fixture 超时/端口死了：重载一次页面再试（老页面在扩展重载后就会出现这种"消息石沉大海"）。
      console.log(`  ..   ${fixture.provider}/${fixture.dialect}: ${error.message}; reloading page and retrying`)
      await reloadPage(page)
      raw = await sendOnce(page)
    }
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (parsed === undefined)
      throw new Error(
        'background did not answer RULE_REPLAY — the running service worker has no such handler (reload the extension / restart the dev server)'
      )
    const expect = fixture.spec.expect || {}
    const problems = []
    if (!parsed || parsed.ok !== true) problems.push(`engine rejected: ${parsed && parsed.error}`)
    else if (fixture.request) {
      if (parsed.requestText !== fixture.request.expectedUserText)
        problems.push(
          `request text ${JSON.stringify(parsed.requestText || '')} != ${JSON.stringify(fixture.request.expectedUserText)}`
        )
      if (parsed.requestRewritten !== true)
        problems.push(`request rewrite failed: ${parsed.requestError || 'unknown error'}`)
      if (parsed.requestRewriteText !== requestTemplate)
        problems.push(
          `request rewrite text ${JSON.stringify(parsed.requestRewriteText || '')} != ${JSON.stringify(requestTemplate)}`
        )
    }
    else {
      const content = parsed.content || ''
      if (expect.markerOk === true && !markerOk(content)) problems.push('content is not a complete marker block')
      if (expect.mode && parsed.mode !== expect.mode) problems.push(`mode ${parsed.mode} != ${expect.mode}`)
      if (expect.finishReason && parsed.finishReason !== expect.finishReason)
        problems.push(`finishReason ${parsed.finishReason} != ${expect.finishReason}`)
      for (const needle of expect.errorContains || [])
        if (!(parsed.error || '').includes(needle))
          problems.push(`error ${JSON.stringify(parsed.error || '')} does not contain ${JSON.stringify(needle)}`)
      for (const needle of [].concat(expect.contentContains || []))
        if (!content.includes(needle)) problems.push(`content does not contain ${JSON.stringify(needle)}`)
      for (const needle of expect.mustNotContain || [])
        if (content.includes(needle)) problems.push(`content leaked ${JSON.stringify(needle)}`)
      // `streamed` = 按 SSE 事件边界分帧回放后，消费者实际累积到的那份正文。
      // 默认不变量：分帧回放出来的正文必须与"整段一次过滤"的结果**逐字相同** —— 正文过滤的结算 bug（长块被删时把恢复点推错）
      // 只在分帧时暴露，整段喂永远看不出来。
      const streamed = parsed.streamed
      if (typeof streamed !== 'string')
        console.log(`  note ${fixture.provider}/${fixture.dialect}: engine did not report streamed text (reload the extension)`)
      for (const needle of [].concat(expect.streamedContains || []))
        if (typeof streamed === 'string' && !streamed.includes(needle))
          problems.push(`streamed text does not contain ${JSON.stringify(needle)}`)
      for (const needle of [].concat(expect.streamedMustNotContain || []))
        if (typeof streamed === 'string' && streamed.includes(needle))
          problems.push(`streamed text leaked ${JSON.stringify(needle)}`)
      if (typeof streamed === 'string' && expect.allowStreamedDivergence !== true && streamed !== content)
        problems.push(`streamed text diverged from the filtered content (chars ${streamed.length} vs ${content.length})`)
      const plans = parsed.streamedPlans || {}
      for (const [name, value] of Object.entries(plans)) {
        const planText = Array.isArray(value) ? value[0] : value
        if (typeof planText === 'string' && planText !== content)
          problems.push(`${name} replay diverged from filtered content (chars ${planText.length} vs ${content.length})`)
      }
      for (const handler of expect.handlers || [])
        if (!(parsed.hits || []).includes(handler)) problems.push(`handler not hit: ${handler}`)
    }
    const label = `${fixture.provider}/${fixture.dialect}`
    if (problems.length > 0) {
      failed++
      console.log(`  FAIL ${label}`)
      for (const problem of problems) console.log(`       - ${problem}`)
      if (parsed && parsed.ok) console.log(`       content=${JSON.stringify((parsed.content || '').slice(0, 140))}`)
      console.log(`       census=${parsed && parsed.census}`)
    } else {
      console.log(
        fixture.request
          ? `  ok   ${label.padEnd(32)} request chars=${String((parsed.requestText || '').length).padStart(5)}`
          : `  ok   ${label.padEnd(32)} mode=${(parsed.mode || '-').padEnd(18)} chars=${String(
              (parsed.content || '').length
            ).padStart(5)} hits=${(parsed.hits || []).join(',')}`
      )
    }
  }
  console.log(failed === 0 ? `all ${fixtures.length} fixtures passed` : `${failed} fixture(s) failed`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch(error => {
  console.error(`replay failed: ${error.message}`)
  process.exit(2)
})
