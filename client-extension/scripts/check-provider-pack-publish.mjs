#!/usr/bin/env node
import {existsSync, readFileSync} from 'node:fs'

const arg = name => {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] || '' : ''
}

const newIndexPath = arg('--new')
const previousIndexPath = arg('--previous')
if (!newIndexPath) {
  console.error('usage: check-provider-pack-publish.mjs --new <index> [--previous <index>]')
  process.exit(2)
}

const payload = path => {
  const envelope = JSON.parse(readFileSync(path, 'utf8'))
  if (!envelope.payload) throw new Error(`${path}: missing envelope payload`)
  return JSON.parse(Buffer.from(envelope.payload, 'base64url').toString('utf8'))
}

const next = payload(newIndexPath)
const previousExists =
  previousIndexPath && existsSync(previousIndexPath) && readFileSync(previousIndexPath, 'utf8').trim().length > 0
if (!previousExists) {
  console.log(`provider pack publish check: first publication v${next.bundleVersion}`)
  process.exit(0)
}

const previous = payload(previousIndexPath)
if (next.sha256 === previous.sha256) {
  console.log(`provider pack publish check: unchanged v${next.bundleVersion}`)
  process.exit(0)
}
if (next.bundleVersion <= previous.bundleVersion) {
  console.error(
    `provider pack publish check failed: content changed but bundleVersion ${next.bundleVersion} ` +
      `did not increase above ${previous.bundleVersion}`
  )
  process.exit(1)
}
console.log(`provider pack publish check: v${previous.bundleVersion} -> v${next.bundleVersion}`)
