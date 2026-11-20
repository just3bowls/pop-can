#!/usr/bin/env node
/**
 * Capture one desensitized request fixture through the official INPUT_PROMPT path.
 *
 * The script never types into a page itself: it asks the extension to run a normal capture task,
 * polls the full-body handoff written by ProviderTurnRunner, cancels the task, and writes a
 * request fixture accepted by replay-fixtures.mjs.
 *
 * Usage:
 *   node client-extension/scripts/capture-provider-request.mjs kimi wenxin
 *   node client-extension/scripts/capture-provider-request.mjs --out-dir /tmp/pop-can-requests kimi
 */
import {mkdirSync, writeFileSync} from 'node:fs'
import {createHash} from 'node:crypto'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {evalInTarget, usablePluginPage} from './lib/provider-cdp.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')
const args = process.argv.slice(2)
const outIndex = args.indexOf('--out-dir')
const outDir = resolve(
  outIndex >= 0 ? args.splice(outIndex, 2)[1] : join(extensionRoot, 'provider-catalog', 'fixtures')
)
const promptIndex = args.indexOf('--prompt')
const prompt =
  promptIndex >= 0
    ? args.splice(promptIndex, 2)[1]
    : 'Pop Can rule fixture check. Reply with exactly: fixture-ok'
const providers = args.filter(arg => !arg.startsWith('--'))
if (providers.length === 0) providers.push('kimi', 'wenxin')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const generationId = provider => `fixture-${provider}-${Date.now()}-${Math.random().toString(16).slice(2)}`

const sendMessage = (page, message) =>
  evalInTarget(
    page,
    `chrome.runtime.sendMessage(${JSON.stringify(message)}).then(r => JSON.stringify(r)).catch(e => 'ERR ' + e.message)`
  )

const readProviderConfig = async (page, provider) => {
  const raw = await evalInTarget(
    page,
    `chrome.storage.local.get(${JSON.stringify(`parserProvider:${provider}`)}).then(v => JSON.stringify(v[${JSON.stringify(
      `parserProvider:${provider}`
    )}] || null))`
  )
  const config = typeof raw === 'string' ? JSON.parse(raw) : raw
  if (!config) throw new Error(`${provider}: parserProvider:${provider} is not installed`)
  const inputXPath = config.page?.selectors?.input || ''
  if (!inputXPath) throw new Error(`${provider}: provider config has no page.selectors.input`)
  return config
}

const readCapture = async (page, key) => {
  const raw = await evalInTarget(
    page,
    `chrome.storage.local.get(${JSON.stringify(key)}).then(v => JSON.stringify(v[${JSON.stringify(key)}] || null))`
  )
  return typeof raw === 'string' ? JSON.parse(raw) : raw
}

const clearCapture = (page, key) =>
  evalInTarget(page, `chrome.storage.local.remove(${JSON.stringify(key)}).then(() => true)`)

const sensitiveKey =
  /^(?:auth|authorization|token|cookie|csrf|uid)$|(?:^|_)(?:auth|token|cookie|csrf|uid|user_?id|device_?id|session_?id|conversation_?id|chat_?id|message_?id|trace_?id|request_?id)(?:$|_)|(?:user|device|session|conversation|chat|message|trace|request|project)Id$/i

const redactStructuredToken = value => {
  const dash = value.lastIndexOf('-')
  if (dash <= 0 || !/^-\d+-\d+$/.test(value.slice(dash))) return '<redacted>'
  const core = value.slice(0, dash)
  let decoded
  try {
    decoded = Buffer.from(core, 'base64').toString('utf8')
  } catch (error) {
    return '<redacted>'
  }
  const parts = decoded.split('|')
  if (parts.length < 2) return '<redacted>'
  const safe = parts.map(part => {
    if (/^\d+$/.test(part)) return '0'
    if (/^[a-f0-9]{32}$/i.test(part)) return createHash('md5').update('').digest('hex')
    return 'redacted'
  })
  return `${Buffer.from(safe.join('|'), 'utf8').toString('base64')}${value.slice(core.length)}`
}

const sanitizeValue = value => {
  if (Array.isArray(value)) return value.map(sanitizeValue)
  if (!value || typeof value !== 'object') return value
  const out = {}
  for (const [key, child] of Object.entries(value)) {
    if (sensitiveKey.test(key) && typeof child === 'string')
      out[key] = /token$/i.test(key) ? redactStructuredToken(child) : '<redacted>'
    else if (sensitiveKey.test(key) && typeof child === 'number') out[key] = 0
    else out[key] = sanitizeValue(child)
  }
  return out
}

const grpcFrames = bytes => {
  const frames = []
  let offset = 0
  while (offset + 5 <= bytes.length) {
    const flag = bytes[offset]
    if (flag !== 0 && flag !== 0x80) return null
    const length = bytes.readUInt32BE(offset + 1)
    const start = offset + 5
    const end = start + length
    if (end > bytes.length) return null
    frames.push({flag, payload: bytes.subarray(start, end)})
    offset = end
  }
  return offset === bytes.length && frames.length > 0 ? frames : null
}

const sanitizeBody = bodyBase64 => {
  const bytes = Buffer.from(bodyBase64, 'base64')
  const frames = grpcFrames(bytes)
  if (frames) {
    const sanitizedFrames = []
    for (const frame of frames) {
      let json
      try {
        json = JSON.parse(frame.payload.toString('utf8'))
      } catch (error) {
        // Non-JSON gRPC frames can carry opaque account/session identifiers. The request replay only
        // needs JSON candidates, so omit them instead of committing unclassifiable private bytes.
        continue
      }
      const payload = Buffer.from(JSON.stringify(sanitizeValue(json)), 'utf8')
      const head = Buffer.alloc(5)
      head[0] = frame.flag
      head.writeUInt32BE(payload.length, 1)
      sanitizedFrames.push(head, payload)
    }
    if (sanitizedFrames.length === 0) throw new Error('gRPC-Web request contained no JSON frames to sanitize')
    return Buffer.concat(sanitizedFrames)
  }
  const text = bytes.toString('utf8')
  const brace = text.indexOf('{')
  if (brace < 0) throw new Error('request body is neither gRPC-Web nor JSON')
  const json = JSON.parse(text.slice(brace))
  return Buffer.concat([Buffer.from(text.slice(0, brace), 'utf8'), Buffer.from(JSON.stringify(sanitizeValue(json)), 'utf8')])
}

const captureOne = async (page, provider) => {
  const config = await readProviderConfig(page, provider)
  const gen = generationId(provider)
  const key = `thProviderRequestCapture:${gen}`
  const started = await sendMessage(page, {
    type: 'STATE_STATS'
  })
  if (typeof started !== 'string' || !started.startsWith('{"ok":true')) {
    throw new Error(`background is not reachable (${started}) — reload the extension and retry`)
  }

  console.log(`capture: ${provider} via official INPUT_PROMPT`)
  await evalInTarget(
    page,
    `globalThis.__popCanRequestCapture = {done:false,response:null,error:''};` +
      `chrome.runtime.sendMessage(${JSON.stringify({
        type: 'INPUT_PROMPT',
        provider,
        prompt,
        inputXPath: config.page.selectors.input,
        generationId: gen,
        stream: false,
        keyId: 'system-default',
        localTaskKind: 'capture',
        contextMode: 'new',
        conversation: 'new',
        inject: false
      })}).then(r => { globalThis.__popCanRequestCapture = {done:true,response:r,error:''} })` +
      `.catch(e => { globalThis.__popCanRequestCapture = {done:true,response:null,error:String(e && e.message || e)} }); true`
  )

  const deadline = Date.now() + 180000
  let capture = null
  while (Date.now() < deadline) {
    capture = await readCapture(page, key)
    if (capture?.bodyBase64) break
    const state = await evalInTarget(page, 'JSON.stringify(globalThis.__popCanRequestCapture || null)')
    const parsed = typeof state === 'string' ? JSON.parse(state) : state
    if (parsed?.done && !parsed.response?.ok) {
      throw new Error(`${provider}: capture task failed before request observation: ${parsed.error || parsed.response?.error || 'unknown error'}`)
    }
    await sleep(500)
  }
  if (!capture?.bodyBase64) throw new Error(`${provider}: timed out waiting for the request fixture`)

  await sendMessage(page, {type: 'CANCEL_TASK', generationId: gen})
  await clearCapture(page, key)
  const sanitized = sanitizeBody(capture.bodyBase64)
  const fixture = {
    url: capture.url,
    bodyBase64: sanitized.toString('base64'),
    expectedUserText: prompt,
    notes:
      'Captured through the official INPUT_PROMPT path. Sensitive identifier fields and non-JSON gRPC frames were removed.'
  }
  const dir = join(outDir, provider)
  mkdirSync(dir, {recursive: true})
  const path = join(dir, 'chat.request.json')
  writeFileSync(path, `${JSON.stringify(fixture, null, 2)}\n`)
  console.log(`capture: ${provider} -> ${path} (${sanitized.length} bytes)`)
}

const main = async () => {
  const page = await usablePluginPage()
  for (const provider of providers) await captureOne(page, provider)
}

main()
  .then(() => process.exit(0))
  .catch(error => {
    console.error(`capture failed: ${error.message}`)
    process.exit(1)
  })
