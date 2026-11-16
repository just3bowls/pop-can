#!/usr/bin/env node
import {createHash, createPrivateKey, createPublicKey, sign} from 'node:crypto'
import {existsSync, mkdirSync, readFileSync, writeFileSync} from 'node:fs'
import {homedir} from 'node:os'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')
const repoRoot = resolve(extensionRoot, '..')
const bundlePath = join(extensionRoot, 'presets', 'bundle.json')
const publicKeyPath = join(extensionRoot, 'keys', 'provider-pack-public-key.spki.base64')
const productVersion = readFileSync(join(repoRoot, 'VERSION'), 'utf8').trim()
const outIndex = process.argv.indexOf('--out')
if (outIndex >= 0 && !process.argv[outIndex + 1]) {
  console.error('--out requires a directory')
  process.exit(2)
}
const outputDir = resolve(outIndex >= 0 ? process.argv[outIndex + 1] : join(repoRoot, 'release', 'artifacts', 'provider-packs'))
const localPrivateKeyPath = join(homedir(), '.config', 'pop-can', 'provider-pack-private-key.pem')
const privateKeyPem =
  process.env.PROVIDER_PACK_PRIVATE_KEY ||
  (existsSync(localPrivateKeyPath)
    ? readFileSync(localPrivateKeyPath, 'utf8')
    : '')

if (!privateKeyPem.trim()) {
  console.error('Provider pack private key is missing.')
  console.error('Set PROVIDER_PACK_PRIVATE_KEY or place the key at ~/.config/pop-can/provider-pack-private-key.pem')
  process.exit(1)
}
if (!existsSync(bundlePath)) {
  console.error('presets/bundle.json is missing; run: npm run presets:build --prefix client-extension')
  process.exit(1)
}

const bundle = JSON.parse(readFileSync(bundlePath, 'utf8'))
if (!Number.isInteger(bundle.bundleVersion) || bundle.bundleVersion <= 0) {
  console.error('presets/bundle.json has no positive bundleVersion')
  process.exit(1)
}
if (!Array.isArray(bundle.providers) || bundle.providers.length === 0) {
  console.error('presets/bundle.json has no providers')
  process.exit(1)
}

const privateKey = createPrivateKey(privateKeyPem)
const derivedPublic = createPublicKey(privateKey).export({format: 'der', type: 'spki'})
const expectedPublic = Buffer.from(readFileSync(publicKeyPath, 'utf8').trim(), 'base64')
if (!derivedPublic.equals(expectedPublic)) {
  console.error('Provider pack private key does not match client-extension/keys/provider-pack-public-key.spki.base64')
  process.exit(1)
}
const keyId = createHash('sha256').update(derivedPublic).digest('hex').slice(0, 16)

const payloadBytes = value => Buffer.from(`${JSON.stringify(value, null, 2)}\n`, 'utf8')
const base64url = value => Buffer.from(value).toString('base64url')
const signPayload = (value, bytes = payloadBytes(value)) => {
  const signature = sign('sha256', bytes, {key: privateKey, dsaEncoding: 'ieee-p1363'})
  return {
    formatVersion: 1,
    keyId,
    algorithm: 'ECDSA-P256-SHA256',
    payload: base64url(bytes),
    signature: base64url(signature)
  }
}

mkdirSync(outputDir, {recursive: true})
const packName = `provider-pack-v${bundle.bundleVersion}.popcan.json`
const packPayload = {
  kind: 'provider-pack',
  packSchemaVersion: 1,
  providerSchemaVersion: 2,
  bundleVersion: bundle.bundleVersion,
  minExtensionVersion: productVersion,
  generatedAt: bundle.generatedAt,
  providers: bundle.providers
}
const packPayloadBytes = payloadBytes(packPayload)
const packEnvelope = signPayload(packPayload, packPayloadBytes)
const packText = `${JSON.stringify(packEnvelope, null, 2)}\n`
const packPath = join(outputDir, packName)
writeFileSync(packPath, packText)
const latestPackPath = join(outputDir, 'provider-pack-latest.popcan.json')
writeFileSync(latestPackPath, packText)

const indexPayload = {
  kind: 'provider-index',
  packSchemaVersion: 1,
  providerSchemaVersion: 2,
  bundleVersion: bundle.bundleVersion,
  minExtensionVersion: productVersion,
  generatedAt: bundle.generatedAt,
  sha256: createHash('sha256').update(packPayloadBytes).digest('hex'),
  packUrl: packName,
  providers: bundle.providers.map(provider => ({
    id: provider.id,
    name: provider.name || provider.id
  }))
}
const indexText = `${JSON.stringify(signPayload(indexPayload), null, 2)}\n`
const indexPath = join(outputDir, 'provider-index.popcan.json')
writeFileSync(indexPath, indexText)

console.log(`provider pack: v${bundle.bundleVersion} | ${bundle.providers.length} providers | key=${keyId}`)
console.log(`index: ${indexPath}`)
console.log(`pack : ${packPath}`)
console.log(`latest: ${latestPackPath}`)
