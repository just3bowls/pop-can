import {readFileSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..')
const version = readFileSync(path.join(repoRoot, 'VERSION'), 'utf8').trim()
const packageJson = JSON.parse(readFileSync(path.join(repoRoot, 'client-extension/package.json'), 'utf8'))
const packageLock = JSON.parse(readFileSync(path.join(repoRoot, 'client-extension/package-lock.json'), 'utf8'))

if (!/^\d+\.\d+\.\d+$/.test(version)) {
  console.error(`VERSION is not a semantic x.y.z value: ${version}`)
  process.exit(1)
}

const mismatches = []
if (packageJson.version !== version) {
  mismatches.push(`client-extension/package.json: ${packageJson.version} != ${version}`)
}
if (packageLock.version !== version) {
  mismatches.push(`client-extension/package-lock.json: ${packageLock.version} != ${version}`)
}
if (packageLock.packages?.['']?.version !== version) {
  mismatches.push(`client-extension/package-lock.json root package: ${packageLock.packages[''].version} != ${version}`)
}

const tagIndex = process.argv.indexOf('--tag')
if (tagIndex >= 0) {
  const tag = process.argv[tagIndex + 1] || ''
  if (tag !== `v${version}`) mismatches.push(`Git tag: ${tag} != v${version}`)
}

if (mismatches.length > 0) {
  console.error(`Version check failed:\n${mismatches.map(line => `  ${line}`).join('\n')}`)
  process.exit(1)
}

console.log(`Version check passed: ${version}`)
