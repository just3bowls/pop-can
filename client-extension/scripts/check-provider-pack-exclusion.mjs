#!/usr/bin/env node
import {existsSync, readdirSync, readFileSync, statSync} from 'node:fs'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')
const distArg = process.argv.indexOf('--dist')
if (distArg >= 0 && !process.argv[distArg + 1]) {
  console.error('--dist requires a directory')
  process.exit(2)
}
const distDir = resolve(distArg >= 0 ? process.argv[distArg + 1] : join(extensionRoot, 'dist'))
const bundlePath = join(extensionRoot, 'provider-catalog', 'bundle.json')

if (!existsSync(distDir)) {
  console.error(`provider pack exclusion check: extension build not found: ${distDir}`)
  process.exit(1)
}
const bundle = JSON.parse(readFileSync(bundlePath, 'utf8'))
const needles = bundle.providers
  .flatMap(provider => [provider.page?.url, ...(provider.network?.apiPatterns || [])])
  .filter(value => typeof value === 'string' && value.length >= 12)

function textFiles(dir) {
  return readdirSync(dir).flatMap(name => {
    const file = join(dir, name)
    const stat = statSync(file)
    if (stat.isDirectory()) return textFiles(file)
    return /\.(?:js|json|html|css)$/.test(name) ? [file] : []
  })
}

const violations = []
for (const file of textFiles(distDir)) {
  const text = readFileSync(file, 'utf8')
  for (const needle of needles) {
    if (text.includes(needle)) violations.push(`${file}: ${needle}`)
  }
}
if (violations.length > 0) {
  console.error('provider pack exclusion check failed: production build contains provider rule markers')
  for (const violation of violations) console.error(`  ${violation}`)
  process.exit(1)
}
console.log(`Provider pack exclusion check passed: ${needles.length} rule markers absent from ${distDir}`)
