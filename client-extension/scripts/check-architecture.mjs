import {readdirSync, readFileSync, statSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '../..')
const rootArgIndex = process.argv.indexOf('--root')
const sourceRoot = path.resolve(
  rootArgIndex >= 0 && process.argv[rootArgIndex + 1]
    ? process.argv[rootArgIndex + 1]
    : path.join(repoRoot, 'client-extension/src/main/scala/com/just3bowls')
)

// This graph is intentional. background/content/ui are entry surfaces; model is shared
// domain logic; facade contains only interop declarations.
const allowedDependencies = new Map([
  ['facade', new Set(['facade'])],
  ['model', new Set(['model', 'facade'])],
  ['background', new Set(['background', 'model', 'facade'])],
  ['content', new Set(['content', 'ui', 'model', 'facade'])],
  ['ui', new Set(['ui', 'model', 'facade'])],
])

function scalaFiles(dir) {
  return readdirSync(dir)
    .flatMap(name => {
      const full = path.join(dir, name)
      return statSync(full).isDirectory() ? scalaFiles(full) : full.endsWith('.scala') ? [full] : []
    })
    .sort()
}

const violations = []
const dynamicViolations = []
const interopViolations = []
const files = scalaFiles(sourceRoot)
const dynamicAccess = /\bjs\.Dynamic\b|\bselectDynamic\b|\bupdateDynamic\b|asInstanceOf\[js\.Dynamic\]/
const wildcardBridgeImport = /^\s*import\s+com\.just3bowls\.facade\.JsBridge\.\*\s*$/
const unsafeDynamicConversion = /\bConversion\[\s*js\.Any\s*,\s*js\.Dynamic\s*\]/
const forbiddenJsObjectExtension = /\.asJsObject\b/
const dynamicExtensionCall = /\.(?:fieldOf|updateField|callOf)\s*\(/
const avoidableCast =
  /asInstanceOf\[(?:js\.(?:Object|Any|UndefOr|Promise|Array|Dictionary)|String|Double|Int|Boolean)/

function checkLine(fromLayer, line) {
  const allowed = allowedDependencies.get(fromLayer)
  if (!allowed) return null
  const match = line.match(/^\s*import\s+com\.just3bowls\.([A-Za-z0-9_]+)/)
  if (!match) return null
  const toLayer = match[1]
  if (!allowedDependencies.has(toLayer) || allowed.has(toLayer)) return null
  return toLayer
}

if (process.argv.includes('--self-test')) {
  const forbidden = checkLine('background', 'import com.just3bowls.content.BadDependency')
  const allowed = checkLine('background', 'import com.just3bowls.model.GoodDependency')
  const dynamicForbidden = dynamicAccess.test('const value: js.Dynamic = raw')
  const facadeAllowed = dynamicAccess.test('object Bridge { val value: js.Dynamic = raw }')
  const wildcardForbidden = wildcardBridgeImport.test('import com.just3bowls.facade.JsBridge.*')
  const conversionForbidden = unsafeDynamicConversion.test(
    'given unsafe: Conversion[js.Any, js.Dynamic] = ???'
  )
  const memberForbidden = forbiddenJsObjectExtension.test('ack.asJsObject')
  const extensionForbidden = dynamicExtensionCall.test('ack.updateField("ok")(true)')
  const castForbidden = avoidableCast.test('value.asInstanceOf[js.UndefOr[String]]')
  if (
    forbidden !== 'content' ||
    allowed !== null ||
    !dynamicForbidden ||
    !facadeAllowed ||
    !wildcardForbidden ||
    !conversionForbidden ||
    !memberForbidden ||
    !extensionForbidden ||
    !castForbidden
  ) {
    console.error('Architecture self-test failed')
    process.exit(1)
  }
  console.log('Architecture self-test passed')
  process.exit(0)
}

for (const file of files) {
  const relative = path.relative(sourceRoot, file)
  const fromLayer = relative.split(path.sep)[0]

  const lines = readFileSync(file, 'utf8').split(/\r?\n/)
  lines.forEach((line, index) => {
    const toLayer = checkLine(fromLayer, line)
    if (!toLayer) return
    violations.push({
      file: path.relative(repoRoot, file),
      line: index + 1,
      fromLayer,
      toLayer,
    })
  })
  if (fromLayer !== 'facade') {
    lines.forEach((line, index) => {
      if (dynamicAccess.test(line)) {
        dynamicViolations.push({
          file: path.relative(repoRoot, file),
          line: index + 1,
          fromLayer,
        })
      }
      if (
        wildcardBridgeImport.test(line) ||
        unsafeDynamicConversion.test(line) ||
        forbiddenJsObjectExtension.test(line) ||
        dynamicExtensionCall.test(line) ||
        avoidableCast.test(line)
      ) {
        interopViolations.push({
          file: path.relative(repoRoot, file),
          line: index + 1,
          fromLayer,
        })
      }
    })
  }
}

if (violations.length > 0 || dynamicViolations.length > 0 || interopViolations.length > 0) {
  console.error(
    `Architecture check failed with ${violations.length} forbidden dependency/dependencies ` +
      `${dynamicViolations.length} direct dynamic-access and ` +
      `${interopViolations.length} unsafe JS bridge violation(s):`
  )
  for (const violation of violations) {
    console.error(
      `  ${violation.file}:${violation.line}  ${violation.fromLayer} -> ${violation.toLayer}`
    )
  }
  for (const violation of dynamicViolations) {
    console.error(
      `  ${violation.file}:${violation.line}  ${violation.fromLayer} must use a facade adapter`
    )
  }
  for (const violation of interopViolations) {
    console.error(`  ${violation.file}:${violation.line}  ${violation.fromLayer} must use explicit typed facades`)
  }
  process.exit(1)
}

console.log(`Architecture check passed: ${files.length} Scala files, zero business-layer dynamic access`)
