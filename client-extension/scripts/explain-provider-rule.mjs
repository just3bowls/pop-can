#!/usr/bin/env node
import {execFileSync} from 'node:child_process'
import {existsSync, readFileSync} from 'node:fs'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseJsonc, printParseErrorCode} from 'jsonc-parser'

import {evalInTarget, reloadPage, usablePluginPage} from './lib/provider-cdp.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')
const repoRoot = resolve(extensionRoot, '..')

const valueAfter = name => {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] : ''
}

export const sourceText = (source, provider) => {
  if (existsSync(source)) return readFileSync(source, 'utf8')
  const relative = `client-extension/provider-catalog/providers/${provider}.jsonc`
  return execFileSync('git', ['show', `${source}:${relative}`], {
    cwd: repoRoot,
    encoding: 'utf8'
  })
}

export const ruleFromProviderText = (text, label) => {
  const errors = []
  const doc = parseJsonc(text, errors, {allowTrailingComma: true, disallowComments: false})
  if (errors.length > 0) {
    const first = errors[0]
    const before = text.slice(0, first.offset)
    const line = before.split('\n').length
    const column = before.length - before.lastIndexOf('\n')
    throw new Error(`${label}:${line}:${column}: ${printParseErrorCode(first.error)}`)
  }
  if (!doc || typeof doc !== 'object' || !doc.parserRule || typeof doc.parserRule !== 'object')
    throw new Error(`${label}: missing parserRule`)
  return JSON.stringify(doc.parserRule)
}

const explainRule = async rule => {
  let page = await usablePluginPage()
  const payload = JSON.stringify({type: 'RULE_EXPLAIN', rule})
  const send = target =>
    evalInTarget(
      target,
      `chrome.runtime.sendMessage(${payload}).then(r => JSON.stringify(r)).catch(e => 'ERR ' + e.message)`
    )
  let raw
  try {
    raw = await send(page)
  } catch {
    await reloadPage(page)
    raw = await send(page)
  }
  const response = typeof raw === 'string' ? JSON.parse(raw) : raw
  if (!response || response.ok !== true) throw new Error(response?.error || 'RULE_EXPLAIN failed')
  return response.rule
}

export const diffValues = (before, after, path = '$', out = []) => {
  if (JSON.stringify(before) === JSON.stringify(after)) return out
  if (
    before === null ||
    after === null ||
    typeof before !== 'object' ||
    typeof after !== 'object' ||
    Array.isArray(before) !== Array.isArray(after)
  ) {
    out.push(`- ${path}: ${JSON.stringify(before)}`)
    out.push(`+ ${path}: ${JSON.stringify(after)}`)
    return out
  }
  if (Array.isArray(before)) {
    const length = Math.max(before.length, after.length)
    for (let index = 0; index < length; index += 1) {
      if (index >= before.length) out.push(`+ ${path}[${index}]: ${JSON.stringify(after[index])}`)
      else if (index >= after.length) out.push(`- ${path}[${index}]: ${JSON.stringify(before[index])}`)
      else diffValues(before[index], after[index], `${path}[${index}]`, out)
    }
    return out
  }
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()
  for (const key of keys) {
    if (!(key in before)) out.push(`+ ${path}.${key}: ${JSON.stringify(after[key])}`)
    else if (!(key in after)) out.push(`- ${path}.${key}: ${JSON.stringify(before[key])}`)
    else diffValues(before[key], after[key], `${path}.${key}`, out)
  }
  return out
}

const main = async () => {
  const provider = valueAfter('--provider')
  const currentSource = valueAfter('--file') || valueAfter('--to')
  const previousSource = valueAfter('--from')
  const jsonMode = process.argv.includes('--json')
  const compare = process.argv.includes('--compare') || previousSource !== ''
  if (!provider) {
    console.error(
      'usage: explain-provider-rule.mjs --provider <id> [--file <jsonc>] [--from <file|git-ref>] [--json]'
    )
    process.exit(2)
  }
  const currentText = sourceText(
    currentSource || join(extensionRoot, 'provider-catalog', 'providers', `${provider}.jsonc`),
    provider
  )
  const effective = await explainRule(ruleFromProviderText(currentText, provider))
  if (!compare) {
    if (jsonMode) console.log(JSON.stringify(effective, null, 2))
    else {
      console.log(`${provider} · schema v${effective.schemaVersion} · ${effective.handlers.length} handlers`)
      console.log(JSON.stringify(effective, null, 2))
    }
    return
  }

  const previous = await explainRule(ruleFromProviderText(sourceText(previousSource, provider), previousSource))
  const changes = diffValues(previous, effective)
  if (changes.length === 0) console.log(`${provider}: no effective rule changes`)
  else console.log(`${provider}: effective rule diff (${previousSource} -> current)\n${changes.join('\n')}`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error(error.message)
    process.exit(1)
  })
}
