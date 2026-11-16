import {existsSync, readdirSync, readFileSync, statSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '../..')
const targetRoot = path.join(repoRoot, 'client-extension/target')
const forbidden = /\.asJsObject\b/
const linkedArtifactNames = ['client-extension-fastopt', 'client-extension-opt']

if (process.argv.includes('--self-test')) {
  if (!forbidden.test('JSON.parse(raw).asJsObject') || forbidden.test('JsBridge.asObject(raw)')) {
    console.error('Linked JS self-test failed')
    process.exit(1)
  }
  console.log('Linked JS self-test passed')
  process.exit(0)
}

function linkedRoots() {
  if (!existsSync(targetRoot)) return []
  return readdirSync(targetRoot)
    .flatMap(name => linkedArtifactNames.map(artifact => path.join(targetRoot, name, artifact)))
    .filter(dir => existsSync(dir) && statSync(dir).isDirectory())
}

function jsFiles(dir) {
  return readdirSync(dir)
    .flatMap(name => {
      const full = path.join(dir, name)
      return statSync(full).isDirectory() ? jsFiles(full) : full.endsWith('.js') ? [full] : []
    })
    .sort()
}

const roots = linkedRoots()
if (roots.length === 0) {
  console.error('Linked JS check failed: run extension/fastLinkJS or extension/fullLinkJS first')
  process.exit(1)
}

const violations = []
for (const root of roots) {
  for (const file of jsFiles(root)) {
    const lines = readFileSync(file, 'utf8').split(/\r?\n/)
    lines.forEach((line, index) => {
      if (forbidden.test(line)) violations.push(`${path.relative(repoRoot, file)}:${index + 1}`)
    })
  }
}

if (violations.length > 0) {
  console.error(`Linked JS check failed with ${violations.length} runtime .asJsObject call(s):`)
  for (const violation of violations) console.error(`  ${violation}`)
  process.exit(1)
}

console.log(`Linked JS check passed: ${roots.length} linked output dir(s), zero runtime .asJsObject calls`)
