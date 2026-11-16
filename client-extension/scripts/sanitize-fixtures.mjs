// Rewrites fixture identifiers while preserving provider payload shapes for replay tests.
import {readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const extensionRoot = path.resolve(scriptDir, '..')
const rootIndex = process.argv.indexOf('--root')
const fixturesRoot =
  rootIndex >= 0
    ? path.resolve(process.argv[rootIndex + 1] || '')
    : path.join(extensionRoot, 'presets', 'fixtures')

const sensitiveKeys = new Set([
  'authorization',
  'auth',
  'chatid',
  'chattoken',
  'city',
  'contentid',
  'conversationid',
  'cookie',
  'email',
  'ip',
  'mobile',
  'messageid',
  'password',
  'phone',
  'province',
  'refreshtoken',
  'replyid',
  'requestid',
  'reqid',
  'runid',
  'sessionid',
  'token',
  'traceid',
  'uid',
  'userid',
  'utdid'
])

const sensitiveUrlParams = new Set([
  'auth',
  'authorization',
  'chat_token',
  'key',
  'signature',
  'ticket',
  'token'
])

const exactRegionValues = new Map([
  ['Fixture Province', 'Fixture Province'],
  ['Fixture City', 'Fixture City']
])

const normalizeKey = value => String(value || '').toLowerCase().replace(/[^a-z0-9]/g, '')
const isSensitiveKey = key => sensitiveKeys.has(normalizeKey(key))
const looksPlaceholder = value =>
  value === '' ||
  value == null ||
  value === 'Fixture Province' ||
  value === 'Fixture City' ||
  value === 'fixture' ||
  value === 'fixture@example.invalid' ||
  value === 'fixture-token' ||
  value.startsWith('fixture-') ||
  value.startsWith('00000000-0000-4000-8000-') ||
  value.startsWith('0123456789abcdef') ||
  value.startsWith('1000000000000000000') ||
  value.startsWith('192.0.2.')

const repeatHex = length => '0123456789abcdef'.repeat(Math.ceil(length / 16)).slice(0, length)
const repeatDigits = length => '1000000000000000000'.repeat(Math.ceil(length / 19)).slice(0, length)

const syntheticValue = (key, value) => {
  const normalized = normalizeKey(key)
  if (normalized === 'ip') return '192.0.2.10'
  if (normalized === 'province') return 'Fixture Province'
  if (normalized === 'city') return 'Fixture City'
  if (normalized === 'email') return 'fixture@example.invalid'
  if (normalized === 'phone' || normalized === 'mobile') return '10000000000'
  if (
    normalized === 'authorization' ||
    normalized === 'auth' ||
    normalized === 'chattoken' ||
    normalized === 'cookie' ||
    normalized === 'password' ||
    normalized === 'refreshtoken' ||
    normalized === 'token'
  ) return 'fixture-token'
  if (typeof value === 'number') return 1000000000000000000

  const text = String(value)
  if (/^[0-9]+$/.test(text)) return repeatDigits(Math.max(19, text.length))
  if (/^[0-9a-f]+$/i.test(text)) return repeatHex(Math.max(16, text.length))
  if (/^[0-9a-f]{8}-[0-9a-f-]{27,}$/i.test(text)) return '00000000-0000-4000-8000-000000000001'
  if (normalized === 'userid') return 'fixture-user'
  return `fixture-${normalized || 'value'}`
}

const createSanitizer = () => {
  const replacements = new Map()
  const remember = (before, after) => {
    const oldText = String(before)
    if (oldText === after || looksPlaceholder(oldText)) return
    if (oldText.length >= 6 || exactRegionValues.has(oldText)) replacements.set(oldText, after)
  }

  const sanitizeUrl = value => {
    if (!/^https?:\/\//i.test(value)) return value
    try {
      const url = new URL(value)
      let changed = false
      for (const key of [...url.searchParams.keys()]) {
        if (
          sensitiveUrlParams.has(normalizeKey(key)) ||
          ['qak', 'qsignature', 'qkeytime', 'qsigntime'].includes(normalizeKey(key))
        ) {
          url.searchParams.set(key, 'fixture-token')
          changed = true
        }
      }
      const next = changed ? url.toString() : value
      remember(value, next)
      return next
    } catch {
      return value
    }
  }

  const sanitizeString = value => {
    if (exactRegionValues.has(value)) {
      const next = exactRegionValues.get(value)
      remember(value, next)
      return next
    }
    let normalized = value
    for (const match of value.matchAll(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g)) {
      const ip = match[0]
      if (!/^(?:127\.|0\.0\.0\.0$|192\.0\.2\.|198\.51\.100\.|203\.0\.113\.)/.test(ip)) {
        normalized = normalized.replaceAll(ip, '192.0.2.10')
        remember(ip, '192.0.2.10')
      }
    }
    const trimmed = normalized.trim()
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      try {
        const nested = JSON.parse(trimmed)
        const sanitized = sanitizeValue(nested, '')
        const next = JSON.stringify(sanitized)
        remember(value, next)
        return next
      } catch {
        // Keep ordinary strings unchanged.
      }
    }
    return sanitizeUrl(normalized)
  }

  const sanitizeValue = (value, key) => {
    if (Array.isArray(value)) return value.map(item => sanitizeValue(item, key))
    if (value && typeof value === 'object') {
      const out = {}
      for (const [childKey, childValue] of Object.entries(value)) {
        out[childKey] = isSensitiveKey(childKey) && !looksPlaceholder(String(childValue ?? ''))
          ? syntheticValue(childKey, childValue)
          : sanitizeValue(childValue, childKey)
        if (
          isSensitiveKey(childKey) &&
          typeof childValue === 'string' &&
          !looksPlaceholder(childValue)
        ) remember(childValue, out[childKey])
      }
      return out
    }
    return typeof value === 'string' ? sanitizeString(value) : value
  }

  return {sanitizeValue, replacements}
}

const walk = directory =>
  readdirSync(directory).flatMap(name => {
    const full = path.join(directory, name)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })

const fixtureFiles = walk(fixturesRoot)
  .filter(file => file.endsWith('.raw.txt') || file.endsWith('.request.json') || file.endsWith('.expect.json'))
  .sort()

const {sanitizeValue, replacements} = createSanitizer()
let changedFiles = 0

for (const file of fixtureFiles) {
  const original = readFileSync(file, 'utf8')
  let next = original
  if (file.endsWith('.raw.txt')) {
    next = original
      .split('\n')
      .map(line => {
        const match = line.match(/^(\s*data:\s*)(.*)$/)
        if (!match) return line
        try {
          return `${match[1]}${JSON.stringify(sanitizeValue(JSON.parse(match[2]), ''))}`
        } catch {
          return line
        }
      })
      .join('\n')
  } else if (file.endsWith('.request.json')) {
    try {
      const request = JSON.parse(original)
      if (typeof request.bodyBase64 === 'string' && request.bodyBase64.length > 0) {
        try {
          const body = Buffer.from(request.bodyBase64, 'base64').toString('utf8')
          const parsed = JSON.parse(body)
          request.bodyBase64 = Buffer.from(JSON.stringify(sanitizeValue(parsed, '')), 'utf8').toString('base64')
        } catch {
          // Non-JSON gRPC-Web request bodies are preserved byte-for-byte.
        }
      }
      next = `${JSON.stringify(sanitizeValue(request, ''), null, 2)}\n`
    } catch {
      // The privacy checker reports malformed request fixtures.
    }
  } else {
    try {
      next = `${JSON.stringify(sanitizeValue(JSON.parse(original), ''), null, 2)}\n`
    } catch {
      // The privacy checker reports malformed expectation fixtures.
    }
  }
  if (next !== original) {
    changedFiles += 1
    if (process.argv.includes('--write')) writeFileSync(file, next)
  }
}

const mappingIndex = process.argv.indexOf('--mapping')
if (mappingIndex >= 0) {
  const mappingPath = process.argv[mappingIndex + 1]
  if (!mappingPath) {
    console.error('missing path after --mapping')
    process.exit(2)
  }
  const lines = [...replacements.entries()]
    .filter(([oldText]) => !oldText.includes('\n') && !oldText.includes('\0'))
    .map(([oldText, newText]) => `literal:${oldText}==>${newText}`)
  writeFileSync(mappingPath, `${lines.join('\n')}\n`)
  console.log(`replacement mapping: ${mappingPath} (${lines.length} entries)`)
}

console.log(
  `${changedFiles === 0 ? 'fixtures already sanitized' : `fixture changes ${process.argv.includes('--write') ? 'written' : 'available'}: ${changedFiles} file(s)`}`
)
