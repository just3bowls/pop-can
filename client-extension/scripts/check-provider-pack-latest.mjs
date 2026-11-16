import {readFileSync} from 'node:fs'
import path from 'node:path'

const directory = path.resolve(process.argv[2] || '')
if (!process.argv[2]) {
  console.error('usage: check-provider-pack-latest.mjs <provider-pack-directory>')
  process.exit(2)
}

const indexEnvelope = JSON.parse(readFileSync(path.join(directory, 'provider-index.popcan.json'), 'utf8'))
const indexPayload = JSON.parse(Buffer.from(indexEnvelope.payload, 'base64url').toString('utf8'))
const versionedPath = path.join(directory, indexPayload.packUrl)
const latestPath = path.join(directory, 'provider-pack-latest.popcan.json')
const versioned = readFileSync(versionedPath)
const latest = readFileSync(latestPath)

if (!versioned.equals(latest)) {
  console.error('provider-pack-latest.popcan.json does not match the current versioned provider pack')
  process.exit(1)
}

console.log(`Provider Pack latest alias check passed: ${indexPayload.packUrl}`)
