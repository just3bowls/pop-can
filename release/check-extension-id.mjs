#!/usr/bin/env node
import {createHash} from 'node:crypto'
import {readFileSync} from 'node:fs'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..')
const keyPath = join(repoRoot, 'client-extension', 'keys', 'extension-public-key.base64')
const idPath = join(repoRoot, 'client-extension', 'keys', 'extension-id.txt')
const key = Buffer.from(readFileSync(keyPath, 'utf8').trim(), 'base64')
const expected = readFileSync(idPath, 'utf8').trim()
const hex = createHash('sha256').update(key).digest('hex').slice(0, 32)
const derived = hex.replace(/[0-9a-f]/g, char => String.fromCharCode(97 + Number.parseInt(char, 16)))

if (!/^[a-p]{32}$/.test(expected)) {
  console.error(`extension-id.txt is invalid: ${expected}`)
  process.exit(1)
}
if (derived !== expected) {
  console.error(`extension ID mismatch: public key derives ${derived}, extension-id.txt contains ${expected}`)
  process.exit(1)
}
console.log(`Extension ID check passed: ${derived}`)
