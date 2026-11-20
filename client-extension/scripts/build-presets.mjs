#!/usr/bin/env node
/**
 * 把 `presets/providers/*.jsonc`（逐家权威文件，人可读、可手改）打成 `presets/bundle.json`
 * —— 这份 bundle 才是插件运行时要读、用户要 import 的东西。
 *
 * 用法：`node client-extension/scripts/build-presets.mjs`
 *
 * 约定：
 * - 文件名（去掉 .jsonc）= provider id，也就是 `parserProvider:<id>` 的 key；文件里不写 id，避免两处漂移。
 * - 源文件允许行注释、块注释和尾逗号；产物始终是无注释的严格 JSON。
 * - `presets/VERSION` 是单行整数，发布时手动 +1；bundleVersion 直接取自它。
 * - 幂等：providers 内容与版本都没变时不重写文件（generatedAt 也不会变），所以重跑不会污染 git diff。
 */
import { readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, printParseErrorCode } from 'jsonc-parser'

const here = dirname(fileURLToPath(import.meta.url))
const presetsDir = join(here, '..', 'presets')
const providersDir = join(presetsDir, 'providers')
const versionPath = join(presetsDir, 'VERSION')
const bundlePath = join(presetsDir, 'bundle.json')
const args = new Set(process.argv.slice(2))
const checkMode = args.has('--check')
const strictMode = args.has('--strict')
const unknownArgs = [...args].filter(arg => arg !== '--check' && arg !== '--strict')
if (unknownArgs.length > 0) {
  console.error(`build-presets: unknown argument(s): ${unknownArgs.join(', ')}`)
  console.error('usage: build-presets.mjs [--check] [--strict]')
  process.exit(2)
}

/** 运行时读的字段全集；多一个少一个都在这里拦下来（bundle 是线上配置，不允许悄悄漂移）。 */
const REQUIRED_KEYS = [
  'schemaVersion',
  'name',
  'page',
  'network',
  'attachment',
  'parserRule'
]
const DOCUMENT_KEYS = new Set([...REQUIRED_KEYS, 'buildEvidence'])
const PAGE_KEYS = new Set(['url', 'selectors', 'fingerprints'])
const NETWORK_KEYS = new Set(['apiPatterns', 'followUpPatterns', 'followUpIdentityPath'])
const ATTACHMENT_KEYS = new Set(['strategy', 'selector'])
const BUILD_EVIDENCE_KEYS = new Set(['fixedIndexes'])
const FIXED_INDEX_EVIDENCE_KEYS = new Set(['path', 'kind', 'reason', 'fixture'])

const fail = message => {
  console.error(`build-presets: ${message}`)
  process.exit(1)
}

const lineColumn = (text, offset) => {
  const before = text.slice(0, offset)
  const line = before.split('\n').length
  const column = before.length - before.lastIndexOf('\n')
  return { line, column }
}

const readJsonc = (path, label) => {
  const text = readFileSync(path, 'utf8')
  const errors = []
  const parsed = parse(text, errors, { allowTrailingComma: true, disallowComments: false })
  if (errors.length > 0) {
    const first = errors[0]
    const { line, column } = lineColumn(text, first.offset)
    fail(`${label}:${line}:${column}: ${printParseErrorCode(first.error)}`)
  }
  return parsed
}

const readVersion = () => {
  if (!existsSync(versionPath)) fail(`missing ${versionPath}`)
  const raw = readFileSync(versionPath, 'utf8').trim()
  const version = Number.parseInt(raw, 10)
  if (!Number.isInteger(version) || version <= 0) fail(`presets/VERSION must be a positive integer, got ${JSON.stringify(raw)}`)
  return version
}

/**
 * 规则校验：只认已知的 match 键 / action op，并要求引用闭合（mode / memory / fixtures）。
 *
 * 这次踩过的两类坑都在这里挡住：① `match.mode` 写了但规则根没有 `mode.fromStream`；
 * ② 某个 handler 依赖 `memoryKey`，但规则里没人 `setMemory` 同一个键（阶段门永远打不开）。
 */
const MATCH_KEYS = new Set([
  'transport', 'rawRegex', 'jsonGroup', 'eventsPath', 'path', 'regex',
  'equals', 'contains', 'startsWith', 'notStartsWith', 'urlRegex', 'mode',
  'memoryKey', 'memoryEquals', 'memoryNotEquals', 'memoryPath', 'allOf', 'anyOf'
])
const TEXT_MATCH_KEYS = ['equals', 'contains', 'startsWith', 'notStartsWith']
const TRANSPORT_KINDS = new Set(['sse-json-line'])
const SSE_JSON_LINE_RAW_REGEX = String.raw`(?m)^data:\s*(\{.*\})\s*$`
const HANDLER_KEYS = new Set(['id', 'match', 'actions', 'content', 'phase', 'startsPhase'])
const ACTION_OPS = new Set([
  'appendContent',
  'appendDelta',
  'setContent',
  'resetContent',
  'truncateContent',
  'setFinishReason',
  'setMemory',
  'setError'
])
const ACTION_KEYS = new Set(['op', 'path', 'value', 'key'])
const RULE_KEYS = new Set([
  'schemaVersion', 'contentTransforms', 'memory', 'phases', 'mode',
  'handlers', 'finalActions', 'request'
])
const TRANSFORM_KEYS = new Set(['id', 'regex', 'replacement', 'settle'])
const SETTLE_KEYS = new Set(['candidatePrefix', 'holdback'])
const MEMORY_MATCH_KEYS = ['memoryKey', 'memoryEquals', 'memoryNotEquals', 'memoryPath']

const collectConditions = match => {
  const out = [match]
  for (const key of ['allOf', 'anyOf']) for (const sub of match[key] || []) out.push(...collectConditions(sub))
  return out
}

const hasUnsafeFixedIndex = path => {
  if (typeof path !== 'string' || !path) return false
  // `[?(@.type=='text')][0]` means "first match", which is semantic; plain [0] assumes array order.
  const withoutFilteredFirst = path.replace(/\[\?\(@\.[^)]*\)\]\[\d+\]/g, '')
  return /\[\d+\]/.test(withoutFilteredFirst)
}

const compileRuleRegex = pattern => {
  let source = pattern
  const flags = new Set()
  while (source.startsWith('(?') && source.indexOf(')') > 2) {
    const close = source.indexOf(')')
    const body = source.slice(2, close)
    if (!body || !/^[ims]+$/.test(body)) break
    for (const flag of body) flags.add(flag)
    source = source.slice(close + 1)
  }
  new RegExp(source, [...flags].join(''))
}

const validateContentTransforms = (id, transforms) => {
  if (transforms === undefined) return
  if (!Array.isArray(transforms)) fail(`${id}/contentTransforms: must be an array`)
  const seen = new Set()
  transforms.forEach((transform, index) => {
    const where = `${id}/contentTransforms[${index}]`
    if (typeof transform !== 'object' || transform === null || Array.isArray(transform))
      fail(`${where}: must be an object`)
    for (const key of Object.keys(transform))
      if (!TRANSFORM_KEYS.has(key)) fail(`${where}: unknown key '${key}'`)
    if (typeof transform.id !== 'string' || !transform.id.trim())
      fail(`${where}.id: must be a non-empty string`)
    if (seen.has(transform.id)) fail(`${where}.id: duplicate '${transform.id}'`)
    seen.add(transform.id)
    if (typeof transform.regex !== 'string' || !transform.regex.trim())
      fail(`${where}.regex: must be a non-empty string`)
    try {
      compileRuleRegex(transform.regex)
    } catch (error) {
      fail(`${where}.regex does not compile: ${error.message}`)
    }
    if (typeof transform.replacement !== 'string')
      fail(`${where}.replacement: must be a string`)
    const settle = transform.settle
    if (typeof settle !== 'object' || settle === null || Array.isArray(settle))
      fail(`${where}.settle: must be an object`)
    for (const key of Object.keys(settle))
      if (!SETTLE_KEYS.has(key)) fail(`${where}.settle: unknown key '${key}'`)
    const hasPrefix = settle.candidatePrefix !== undefined
    const hasHoldback = settle.holdback !== undefined
    if (hasPrefix === hasHoldback)
      fail(`${where}.settle: requires exactly one of candidatePrefix or holdback`)
    if (hasPrefix && (typeof settle.candidatePrefix !== 'string' || !settle.candidatePrefix))
      fail(`${where}.settle.candidatePrefix: must be a non-empty string`)
    if (hasHoldback && (!Number.isInteger(settle.holdback) || settle.holdback < 0))
      fail(`${where}.settle.holdback: must be a non-negative integer`)
  })
}

const validateRequestFixture = (id, path) => {
  let fixture
  try {
    fixture = JSON.parse(readFileSync(path, 'utf8'))
  } catch (error) {
    fail(`${id}/${basename(path)}: invalid JSON: ${error.message}`)
  }
  if (!fixture || typeof fixture !== 'object' || Array.isArray(fixture))
    fail(`${id}/${basename(path)}: request fixture must be an object`)
  const keys = new Set(['url', 'bodyBase64', 'expectedUserText', 'notes'])
  for (const key of Object.keys(fixture))
    if (!keys.has(key)) fail(`${id}/${basename(path)}: unknown key '${key}'`)
  for (const key of keys)
    if (typeof fixture[key] !== 'string' || !fixture[key].trim())
      fail(`${id}/${basename(path)}.${key}: must be a non-empty string`)
  let url
  try {
    url = new URL(fixture.url)
  } catch (error) {
    fail(`${id}/${basename(path)}.url: must be an absolute URL`)
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:')
    fail(`${id}/${basename(path)}.url: must use http or https`)
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(fixture.bodyBase64) || fixture.bodyBase64.length % 4 !== 0)
    fail(`${id}/${basename(path)}.bodyBase64: must be standard base64`)
  const body = Buffer.from(fixture.bodyBase64, 'base64')
  if (body.length === 0) fail(`${id}/${basename(path)}.bodyBase64: decoded body is empty`)
  if (!body.toString('utf8').includes(fixture.expectedUserText))
    fail(`${id}/${basename(path)}: decoded body does not contain expectedUserText`)
}

const validateFixedIndexEvidence = (id, evidence) => {
  const fixtureDir = join(presetsDir, 'fixtures', id)
  if (evidence === undefined) return new Map()
  if (!evidence || typeof evidence !== 'object' || Array.isArray(evidence))
    fail(`${id}/buildEvidence: must be an object`)
  for (const key of Object.keys(evidence))
    if (!BUILD_EVIDENCE_KEYS.has(key)) fail(`${id}/buildEvidence: unknown key '${key}'`)
  if (!Array.isArray(evidence.fixedIndexes))
    fail(`${id}/buildEvidence.fixedIndexes: must be an array`)
  const out = new Map()
  evidence.fixedIndexes.forEach((entry, index) => {
    const where = `${id}/buildEvidence.fixedIndexes[${index}]`
    if (!entry || typeof entry !== 'object' || Array.isArray(entry))
      fail(`${where}: must be an object`)
    for (const key of Object.keys(entry))
      if (!FIXED_INDEX_EVIDENCE_KEYS.has(key)) fail(`${where}: unknown key '${key}'`)
    for (const key of FIXED_INDEX_EVIDENCE_KEYS)
      if (typeof entry[key] !== 'string' || !entry[key].trim())
        fail(`${where}.${key}: must be a non-empty string`)
    if (!entry.path.startsWith('$')) fail(`${where}.path: must start with '$'`)
    if (entry.kind !== 'request' && entry.kind !== 'response')
      fail(`${where}.kind: must be 'request' or 'response'`)
    if (basename(entry.fixture) !== entry.fixture)
      fail(`${where}.fixture: must be a file name in presets/fixtures/${id}`)
    const fixturePath = join(fixtureDir, entry.fixture)
    if (!existsSync(fixturePath)) fail(`${where}.fixture: missing ${fixturePath}`)
    if (entry.kind === 'request') {
      if (!entry.fixture.endsWith('.request.json'))
        fail(`${where}.fixture: request evidence must reference a .request.json fixture`)
      validateRequestFixture(id, fixturePath)
    } else {
      const rawName =
        entry.fixture.endsWith('.raw.txt')
          ? entry.fixture
          : entry.fixture.endsWith('.expect.json')
            ? entry.fixture.replace(/\.expect\.json$/, '.raw.txt')
            : ''
      if (!rawName) fail(`${where}.fixture: response evidence must reference .raw.txt or .expect.json`)
      const expectName = rawName.replace(/\.raw\.txt$/, '.expect.json')
      if (!existsSync(join(fixtureDir, rawName))) fail(`${where}.fixture: missing ${rawName}`)
      if (!existsSync(join(fixtureDir, expectName))) fail(`${where}.fixture: missing ${expectName}`)
      try {
        JSON.parse(readFileSync(join(fixtureDir, expectName), 'utf8'))
      } catch (error) {
        fail(`${where}.fixture: ${expectName} is invalid JSON: ${error.message}`)
      }
    }
    const key = `${entry.kind}\0${entry.path}`
    if (out.has(key)) fail(`${where}: duplicate evidence for ${entry.kind} ${entry.path}`)
    out.set(key, {...entry, used: false})
  })
  return out
}

const validatePhases = (id, phases, memory) => {
  if (phases === undefined) return new Map()
  if (!phases || typeof phases !== 'object' || Array.isArray(phases))
    fail(`${id}/parserRule.phases: must be an object`)
  const out = new Map()
  for (const [rawKey, value] of Object.entries(phases)) {
    const key = rawKey.trim()
    if (!key) fail(`${id}/parserRule.phases: keys must be non-empty`)
    if (out.has(key)) fail(`${id}/parserRule.phases: duplicate phase '${key}'`)
    if (memory && Object.prototype.hasOwnProperty.call(memory, key))
      fail(`${id}/parserRule.phases.${key}: conflicts with root memory key`)
    if (typeof value !== 'string' || !value.trim())
      fail(`${id}/parserRule.phases.${key}: must be a non-empty string`)
    out.set(key, value.trim())
  }
  return out
}

const validateRule = (id, rule, warnings, fixedIndexEvidence) => {
  for (const key of Object.keys(rule))
    if (!RULE_KEYS.has(key)) fail(`${id}/parserRule: unknown key '${key}'`)
  if (rule.schemaVersion !== 1)
    fail(`${id}/parserRule.schemaVersion: must be 1`)
  validateContentTransforms(id, rule.contentTransforms)
  const phases = validatePhases(id, rule.phases, rule.memory)
  const handlers = rule.handlers
  const sets = new Set()
  const uses = new Set()
  const deltaHandlers = []
  handlers.forEach((handler, handlerIndex) => {
    if (!handler || typeof handler !== 'object' || Array.isArray(handler))
      fail(`${id}/handlers[${handlerIndex}]: must be an object`)
    const where = `${id}/${handler.id}`
    for (const key of Object.keys(handler))
      if (!HANDLER_KEYS.has(key)) fail(`${where}: unknown handler key '${key}'`)
    const match = handler.match || {}
    if (typeof match !== 'object' || Array.isArray(match)) fail(`${where}: match must be an object`)
    for (const key of Object.keys(match))
      if (!MATCH_KEYS.has(key)) fail(`${where}: unknown match key '${key}'`)
    for (const cond of collectConditions(match)) {
      const hasTransport = Object.prototype.hasOwnProperty.call(cond, 'transport')
      if (hasTransport) {
        if (cond !== match) fail(`${where}: transport is only allowed on handler.match`)
        if (typeof cond.transport !== 'string' || !TRANSPORT_KINDS.has(cond.transport))
          fail(`${where}.transport: unsupported transport '${cond.transport}'`)
        if (
          Object.prototype.hasOwnProperty.call(cond, 'rawRegex') ||
          Object.prototype.hasOwnProperty.call(cond, 'jsonGroup')
        )
          fail(`${where}: transport cannot be combined with rawRegex or jsonGroup`)
      }
      const selectedTextMatches = TEXT_MATCH_KEYS.filter(key =>
        Object.prototype.hasOwnProperty.call(cond, key)
      )
      if (selectedTextMatches.length > 1)
        fail(`${where}: only one text match shorthand is allowed`)
      if (selectedTextMatches.length > 0 && Object.prototype.hasOwnProperty.call(cond, 'regex'))
        fail(`${where}: regex and ${selectedTextMatches[0]} are mutually exclusive`)
      for (const key of selectedTextMatches)
        if (typeof cond[key] !== 'string' || !cond[key].trim())
          fail(`${where}.match.${key}: must be a non-empty string`)
      if (cond.memoryKey) uses.add(cond.memoryKey)
      if (cond.mode && key0(rule, 'mode') === undefined) fail(`${where}: match.mode needs rule.mode.fromStream`)
    }
    if (handler.match?.memoryPath && !handler.match?.memoryKey)
      fail(`${where}: memoryPath needs memoryKey`)
    const hasPhase = Object.prototype.hasOwnProperty.call(handler, 'phase')
    const hasStartsPhase = Object.prototype.hasOwnProperty.call(handler, 'startsPhase')
    let phase = ''
    let startsPhase = false
    if (hasStartsPhase && typeof handler.startsPhase !== 'boolean')
      fail(`${where}.startsPhase: must be a boolean`)
    if (hasStartsPhase && !hasPhase) fail(`${where}.startsPhase: requires phase`)
    if (hasPhase) {
      if (typeof handler.phase !== 'string' || !handler.phase.trim())
        fail(`${where}.phase: must be a non-empty string`)
      phase = handler.phase.trim()
      if (!phases.has(phase)) fail(`${where}.phase: unknown phase '${phase}'`)
      startsPhase = hasStartsPhase && handler.startsPhase
      if (collectConditions(match).some(cond =>
        MEMORY_MATCH_KEYS.some(key => Object.prototype.hasOwnProperty.call(cond, key))
      ))
        fail(`${where}: phase cannot be combined with explicit memory gates`)
    }
    const hasActions = Object.prototype.hasOwnProperty.call(handler, 'actions')
    const hasContent = Object.prototype.hasOwnProperty.call(handler, 'content')
    if (hasActions && hasContent) fail(`${where}: content and actions are mutually exclusive`)
    if (hasActions && !Array.isArray(handler.actions)) fail(`${where}: actions must be an array`)
    if (hasContent) {
      if (typeof handler.content !== 'string' || !handler.content.trim())
        fail(`${where}.content: must be a non-empty JSONPath`)
      if (!handler.content.trim().startsWith('$'))
        fail(`${where}.content: must be a JSONPath starting with '$'`)
    }
    const actions = hasActions ? handler.actions : []
    if (phase && startsPhase && actions.some(action => action.op === 'setMemory' && action.key === phase))
      fail(`${where}: startsPhase cannot be combined with setMemory for '${phase}'`)
    if (phase) {
      if (startsPhase) sets.add(phase)
      else uses.add(phase)
    }
    // 只统计「会产生 delta」的 handler：纯 setMemory / setFinishReason 的 handler 永远不出现在 hits 里
    // （finishReason 没变化时 emit 返回空），拿 fixture 覆盖要求它们不合理。
    const producesDelta =
      hasContent ||
      actions.some(a => ['appendContent', 'appendDelta', 'setContent', 'truncateContent'].includes(a.op))
    if (producesDelta) deltaHandlers.push(handler.id)
    const fixedIndexPaths = [
      match.path,
      hasContent ? handler.content : undefined,
      ...actions.map(action => action.path),
      ...collectConditions(match).flatMap(cond => [cond.path, cond.memoryPath])
    ].filter(hasUnsafeFixedIndex)
    for (const path of new Set(fixedIndexPaths)) {
      const evidence = fixedIndexEvidence.get(`response\0${path}`)
      if (evidence) evidence.used = true
      else
        warnings.push(
          `${where}: fixed array index in '${path}' — use eventsPath + a discriminator unless a buildEvidence entry names the exact response fixture`
        )
    }
    for (const action of actions) {
      if (!ACTION_OPS.has(action.op)) fail(`${where}: unknown action op '${action.op}'`)
      for (const key of Object.keys(action)) if (!ACTION_KEYS.has(key)) fail(`${where}: unknown action key '${key}'`)
      if (action.op === 'setMemory' && action.key) sets.add(action.key)
    }
  })
  for (const used of uses)
    if (!sets.has(used)) fail(`${id}: memory key '${used}' is matched but never set by any handler`)
  const requestPaths = Array.isArray(rule.request?.userText) ? rule.request.userText : [rule.request?.userText]
  for (const path of requestPaths.filter(hasUnsafeFixedIndex)) {
    const evidence = fixedIndexEvidence.get(`request\0${path}`)
    if (evidence) evidence.used = true
    else
      warnings.push(
        `${id}/request: fixed array index in '${path}' — use a nested discriminator filter unless a buildEvidence entry names the exact request fixture`
      )
  }

  const fixtureDir = join(presetsDir, 'fixtures', id)
  if (!existsSync(fixtureDir)) warnings.push(`${id}: no fixtures (add presets/fixtures/${id}/*)`)
  else {
    const files = readdirSync(fixtureDir)
    const fixtures = files.filter(name => name.endsWith('.expect.json'))
    const requestFixtures = files.filter(name => name.endsWith('.request.json'))
    if (fixtures.length + requestFixtures.length === 0) warnings.push(`${id}: no fixtures`)
    for (const file of requestFixtures) validateRequestFixture(id, join(fixtureDir, file))
    const covered = new Set()
    for (const file of fixtures) {
      const spec = JSON.parse(readFileSync(join(fixtureDir, file), 'utf8'))
      for (const handler of spec.expect?.handlers || []) covered.add(handler)
    }
    for (const handlerId of deltaHandlers)
      if (!covered.has(handlerId))
        warnings.push(`${id}: delta handler '${handlerId}' is not pinned by any fixture`)
  }
  for (const evidence of fixedIndexEvidence.values())
    if (!evidence.used)
      fail(`${id}/buildEvidence: no fixed-index path matches ${evidence.kind} '${evidence.path}'`)
}

const key0 = (rule, key) => rule[key]

const validate = (id, doc) => {
  const keys = Object.keys(doc)
  const extra = keys.filter(key => !DOCUMENT_KEYS.has(key))
  const missing = REQUIRED_KEYS.filter(key => !keys.includes(key))
  if (extra.length) fail(`${id}: unknown keys ${extra.join(', ')}`)
  if (missing.length) fail(`${id}: missing keys ${missing.join(', ')}`)
  if (doc.schemaVersion !== 1) fail(`${id}: schemaVersion must be 1`)
  if (typeof doc.name !== 'string' || !doc.name.trim()) fail(`${id}: name must be a non-empty string`)
  if (typeof doc.page !== 'object' || doc.page === null || Array.isArray(doc.page))
    fail(`${id}: page must be an object`)
  for (const key of Object.keys(doc.page)) if (!PAGE_KEYS.has(key)) fail(`${id}/page: unknown key '${key}'`)
  if (typeof doc.page.url !== 'string' || !doc.page.url.trim()) fail(`${id}: page.url must be a non-empty string`)
  if (typeof doc.page.selectors !== 'object' || doc.page.selectors === null || Array.isArray(doc.page.selectors))
    fail(`${id}: page.selectors must be an object`)
  if (typeof doc.page.selectors.input !== 'string' || !doc.page.selectors.input.trim())
    fail(`${id}: page.selectors.input must be a non-empty string`)
  if (typeof doc.page.selectors['new-chat'] !== 'string')
    fail(`${id}: page.selectors.new-chat must be a string`)
  if (typeof doc.page.fingerprints !== 'object' || doc.page.fingerprints === null || Array.isArray(doc.page.fingerprints))
    fail(`${id}: page.fingerprints must be an object`)
  const selectorRoles = Object.keys(doc.page.selectors).sort()
  const fingerprintRoles = Object.keys(doc.page.fingerprints).sort()
  if (JSON.stringify(selectorRoles) !== JSON.stringify(fingerprintRoles))
    fail(`${id}: page.selectors and page.fingerprints roles must match (${selectorRoles} vs ${fingerprintRoles})`)
  if (typeof doc.network !== 'object' || doc.network === null || Array.isArray(doc.network))
    fail(`${id}: network must be an object`)
  for (const key of Object.keys(doc.network)) if (!NETWORK_KEYS.has(key)) fail(`${id}/network: unknown key '${key}'`)
  if (!Array.isArray(doc.network.apiPatterns) || doc.network.apiPatterns.length === 0)
    fail(`${id}: network.apiPatterns must be a non-empty array`)
  if (doc.network.followUpPatterns !== undefined && !Array.isArray(doc.network.followUpPatterns))
    fail(`${id}: network.followUpPatterns must be an array when present`)
  if (doc.network.followUpIdentityPath !== undefined && typeof doc.network.followUpIdentityPath !== 'string')
    fail(`${id}: network.followUpIdentityPath must be a string`)
  if (typeof doc.attachment !== 'object' || doc.attachment === null || Array.isArray(doc.attachment))
    fail(`${id}: attachment must be an object`)
  for (const key of Object.keys(doc.attachment))
    if (!ATTACHMENT_KEYS.has(key)) fail(`${id}/attachment: unknown key '${key}'`)
  if (typeof doc.attachment.strategy !== 'string') fail(`${id}: attachment.strategy must be a string`)
  if (typeof doc.attachment.selector !== 'string') fail(`${id}: attachment.selector must be a string`)
  const rule = doc.parserRule
  if (typeof rule !== 'object' || rule === null || Array.isArray(rule)) fail(`${id}: parserRule must be an object`)
  if (!Array.isArray(rule.handlers) || rule.handlers.length === 0) fail(`${id}: parserRule.handlers must be a non-empty array`)
}

const version = readVersion()

const providerFiles = readdirSync(providersDir)
const staleJson = providerFiles.filter(name => name.endsWith('.json'))
if (staleJson.length > 0)
  fail(`legacy .json provider files are no longer read; migrate them to .jsonc: ${staleJson.join(', ')}`)

const ids = providerFiles
  .filter(name => name.endsWith('.jsonc'))
  .map(name => basename(name, '.jsonc'))
  .sort()

if (ids.length === 0) fail('no .jsonc provider files found in presets/providers')

const warnings = []
const providers = ids.map(id => {
  const doc = readJsonc(join(providersDir, `${id}.jsonc`), `${id}.jsonc`)
  validate(id, doc)
  const evidence = validateFixedIndexEvidence(id, doc.buildEvidence)
  validateRule(id, doc.parserRule, warnings, evidence)
  const {buildEvidence, ...runtimeDoc} = doc
  return { id, ...runtimeDoc }
})

let previous = null
if (existsSync(bundlePath)) {
  try {
    previous = JSON.parse(readFileSync(bundlePath, 'utf8'))
  } catch (error) {
    fail(`${bundlePath} is invalid JSON: ${error.message}`)
  }
}
const sameAsPrevious =
  previous &&
  previous.schemaVersion === 1 &&
  previous.bundleVersion === version &&
  Array.isArray(previous.providers) &&
  JSON.stringify(previous.providers) === JSON.stringify(providers)

const bundle = {
  schemaVersion: 1,
  bundleVersion: version,
  generatedAt: sameAsPrevious ? previous.generatedAt : new Date().toISOString(),
  providers
}

if (strictMode && warnings.length > 0) {
  for (const warning of warnings) console.error(`build-presets: error: ${warning}`)
  console.error(`build-presets: strict mode rejected ${warnings.length} warning(s)`)
  process.exit(1)
}

if (checkMode) {
  if (!previous) fail(`missing ${bundlePath}; run: npm run presets:build --prefix client-extension`)
  const reasons = []
  if (previous.schemaVersion !== bundle.schemaVersion) {
    reasons.push(`schemaVersion ${previous.schemaVersion} != ${bundle.schemaVersion}`)
  }
  if (previous.bundleVersion !== bundle.bundleVersion) {
    reasons.push(`bundleVersion ${previous.bundleVersion} != ${bundle.bundleVersion}`)
  }
  if (!Array.isArray(previous.providers)) {
    reasons.push('providers must be an array')
  }
  if (JSON.stringify(previous.providers) !== JSON.stringify(bundle.providers)) {
    const previousIds = new Set(
      (Array.isArray(previous.providers) ? previous.providers : []).map(provider => provider.id)
    )
    const currentIds = new Set(providers.map(provider => provider.id))
    const added = [...currentIds].filter(id => !previousIds.has(id))
    const removed = [...previousIds].filter(id => !currentIds.has(id))
    const detail = [
      added.length > 0 ? `added=${added.join(',')}` : '',
      removed.length > 0 ? `removed=${removed.join(',')}` : ''
    ].filter(Boolean).join(' ')
    reasons.push(`providers differ${detail ? ` (${detail})` : ''}`)
  }
  if (reasons.length > 0) {
    for (const reason of reasons) console.error(`build-presets: stale bundle: ${reason}`)
    fail('bundle.json is stale; run: npm run presets:build --prefix client-extension')
  }
  for (const warning of warnings) console.warn(`build-presets: warning: ${warning}`)
  console.log(
    `build-presets: bundle v${bundle.bundleVersion} · ${providers.length} providers · ` +
      `in sync · ${warnings.length} warning(s)`
  )
  process.exit(0)
}

writeFileSync(bundlePath, `${JSON.stringify(bundle, null, 2)}\n`)
for (const warning of warnings) console.warn(`build-presets: warning: ${warning}`)
console.log(
  `build-presets: bundle v${version} · ${providers.length} providers (${ids.join(', ')}) · ` +
    `${sameAsPrevious ? 'unchanged' : 'updated'} · ${warnings.length} warning(s)`
)
