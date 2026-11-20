#!/usr/bin/env node
import {existsSync, readdirSync, readFileSync, writeFileSync} from 'node:fs'
import {dirname, join, basename, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {applyEdits, modify, parse, printParseErrorCode} from 'jsonc-parser'

import {isDefaultFingerprintValue} from './lib/provider-catalog-defaults.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')
const providersDir = join(extensionRoot, 'provider-catalog', 'providers')

const removePath = (text, path) => {
  const edits = modify(text, path, undefined, {
    formattingOptions: {insertSpaces: true, tabSize: 2}
  })
  return applyEdits(text, edits)
}

export const normalizeProviderText = (text, id) => {
  const errors = []
  const doc = parse(text, errors, {allowTrailingComma: true, disallowComments: false})
  if (errors.length > 0) {
    const first = errors[0]
    throw new Error(`${id}: ${printParseErrorCode(first.error)} at offset ${first.offset}`)
  }
  let next = text
  const paths = []
  if (doc?.name === id) paths.push(['name'])
  if (doc?.page?.selectors?.['new-chat'] === '') paths.push(['page', 'selectors', 'new-chat'])
  if (doc?.attachment?.selector === '') paths.push(['attachment', 'selector'])
  for (const [role, fingerprint] of Object.entries(doc?.page?.fingerprints || {})) {
    for (const [key, value] of Object.entries(fingerprint || {})) {
      if (isDefaultFingerprintValue(key, value)) paths.push(['page', 'fingerprints', role, key])
    }
  }
  for (const path of paths) next = removePath(next, path)
  return next
}

export const normalizeProviderCatalog = checkMode => {
  const files = readdirSync(providersDir)
    .filter(name => name.endsWith('.jsonc'))
    .sort()
  let changed = 0
  for (const file of files) {
    const path = join(providersDir, file)
    const id = basename(file, '.jsonc')
    const before = readFileSync(path, 'utf8')
    const after = normalizeProviderText(before, id)
    if (before === after) continue
    changed += 1
    if (checkMode) {
      console.error(`${file}: contains redundant catalog defaults`)
    } else {
      if (!existsSync(path)) throw new Error(`provider file disappeared: ${path}`)
      writeFileSync(path, after)
      console.log(`${file}: normalized`)
    }
  }

  if (checkMode && changed > 0) {
    console.error(`provider catalog has ${changed} unnormalized file(s); run providers:normalize`)
    process.exitCode = 1
  }
  console.log(
    checkMode ? `provider catalog metadata is normalized (${files.length} files)` : `normalized ${changed} file(s)`
  )
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  normalizeProviderCatalog(process.argv.includes('--check'))
}
