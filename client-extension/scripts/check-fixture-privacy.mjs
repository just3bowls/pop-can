import {readFileSync, readdirSync, statSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const extensionRoot = path.resolve(scriptDir, '..')
const fixturesRoot = path.join(extensionRoot, 'provider-catalog', 'fixtures')

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

const normalizeKey = value => String(value || '').toLowerCase().replace(/[^a-z0-9]/g, '')
const isSensitiveKey = key => sensitiveKeys.has(normalizeKey(key))
const isSyntheticDerivedToken = value => {
  const dash = value.indexOf('-')
  if (dash <= 0) return false
  try {
    return Buffer.from(value.slice(0, dash), 'base64').toString('utf8').startsWith('fixture-')
  } catch {
    return false
  }
}
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
  value.startsWith('192.0.2.') ||
  isSyntheticDerivedToken(value)

const walk = directory =>
  readdirSync(directory).flatMap(name => {
    const full = path.join(directory, name)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })

const findings = []
const report = (file, issue) => findings.push(`${path.relative(extensionRoot, file)}: ${issue}`)

const inspectText = (file, text) => {
  for (const match of text.matchAll(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g)) {
    const ip = match[0]
    if (!/^(?:127\.|0\.0\.0\.0$|192\.0\.2\.|198\.51\.100\.|203\.0\.113\.)/.test(ip))
      report(file, `public IP-like value ${ip}`)
  }
  for (const match of text.matchAll(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi))
    report(file, `email-like value ${match[0]}`)
  for (const match of text.matchAll(/https?:\/\/[^\s"'\\]+/gi)) {
    try {
      const url = new URL(match[0])
      for (const [key, value] of url.searchParams.entries()) {
        if (
          !looksPlaceholder(value) &&
          (sensitiveKeys.has(normalizeKey(key)) ||
            ['key', 'qak', 'qkeytime', 'qsignature', 'qsigntime', 'signature', 'ticket'].includes(normalizeKey(key)))
        )
          report(file, `sensitive URL query parameter ${key}`)
      }
    } catch {
      // Partial escaped URLs are covered by the key and value checks.
    }
  }
}

const inspectValue = (file, value) => {
  if (Array.isArray(value)) {
    value.forEach(item => inspectValue(file, item))
    return
  }
  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      if (isSensitiveKey(key) && !looksPlaceholder(typeof child === 'string' ? child : ''))
        report(file, `non-synthetic sensitive key ${key}`)
      inspectValue(file, child)
    }
    return
  }
  if (typeof value === 'string') {
    inspectText(file, value)
    const trimmed = value.trim()
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      try {
        inspectValue(file, JSON.parse(trimmed))
      } catch {
        // Ordinary prose is handled by the text checks above.
      }
    }
  }
}

const fixtureFiles = walk(fixturesRoot)
  .filter(file => file.endsWith('.raw.txt') || file.endsWith('.request.json') || file.endsWith('.expect.json'))
  .sort()

for (const file of fixtureFiles) {
  const text = readFileSync(file, 'utf8')
  inspectText(file, text)
  if (file.endsWith('.raw.txt')) {
    for (const line of text.split('\n')) {
      const match = line.match(/^\s*data:\s*(.*)$/)
      if (!match) continue
      try {
        inspectValue(file, JSON.parse(match[1]))
      } catch {
        // Non-JSON SSE frames are intentionally preserved.
      }
    }
  } else {
    try {
      const request = JSON.parse(text)
      inspectValue(file, request)
      if (file.endsWith('.request.json') && typeof request.bodyBase64 === 'string' && request.bodyBase64) {
        const body = Buffer.from(request.bodyBase64, 'base64').toString('utf8')
        try {
          inspectValue(file, JSON.parse(body))
        } catch {
          inspectText(file, body)
        }
      }
    } catch {
      report(file, 'malformed fixture JSON')
    }
  }
}

const unique = [...new Set(findings)].sort()
if (unique.length > 0) {
  console.error(`Fixture privacy check failed:\n${unique.map(line => `  ${line}`).join('\n')}`)
  process.exit(1)
}

console.log(`Fixture privacy check passed: ${fixtureFiles.length} fixture files`)
