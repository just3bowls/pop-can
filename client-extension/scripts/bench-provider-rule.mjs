#!/usr/bin/env node
import {existsSync, readFileSync} from 'node:fs'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseJsonc} from 'jsonc-parser'

import {evalInTarget, usablePluginPage} from './lib/provider-cdp.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')
const presetsDir = join(extensionRoot, 'presets')
const samples = [
  ['deepseek', 'thinking'],
  ['yuanbao', 'search-with-text'],
  ['wenxin', 'office-writing']
]

const providerRule = provider => {
  const path = join(presetsDir, 'providers', `${provider}.jsonc`)
  const doc = parseJsonc(readFileSync(path, 'utf8'), [], {allowTrailingComma: true, disallowComments: false})
  return JSON.stringify(doc.parserRule)
}

const main = async () => {
  const page = await usablePluginPage()
  for (const [provider, dialect] of samples) {
    const fixture = join(presetsDir, 'fixtures', provider, `${dialect}.raw.txt`)
    const expectPath = fixture.replace(/\.raw\.txt$/, '.expect.json')
    if (!existsSync(fixture)) throw new Error(`missing benchmark fixture: ${fixture}`)
    if (!existsSync(expectPath)) throw new Error(`missing benchmark expectation: ${expectPath}`)
    const expectation = JSON.parse(readFileSync(expectPath, 'utf8'))
    const payload = JSON.stringify({
      type: 'RULE_REPLAY',
      rule: providerRule(provider),
      raw: readFileSync(fixture, 'utf8'),
      sourceUrl: expectation.sourceUrl || ''
    })
    const started = performance.now()
    const raw = await evalInTarget(
      page,
      `chrome.runtime.sendMessage(${payload}).then(r => JSON.stringify(r)).catch(e => 'ERR ' + e.message)`
    )
    const elapsed = performance.now() - started
    const result = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!result || result.ok !== true) throw new Error(`${provider}/${dialect}: ${result?.error || 'replay failed'}`)
    const stats = result.stats || {}
    console.log(
      `${provider}/${dialect} ${elapsed.toFixed(1)}ms chars=${(result.content || '').length} ` +
        `scans=${stats.rawScans ?? '-'} frames=${stats.framesParsed ?? '-'} ` +
        `json=${stats.jsonParses ?? '-'} fanouts=${stats.handlerFanouts ?? '-'}`
    )
  }
}

main().catch(error => {
  console.error(error.message)
  process.exit(1)
})
