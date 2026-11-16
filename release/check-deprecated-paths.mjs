#!/usr/bin/env node
import {execFileSync} from 'node:child_process'
import {readFileSync, statSync} from 'node:fs'
import {dirname, extname, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..')
const files = execFileSync('git', ['ls-files', '-z'], {
  cwd: repoRoot,
  encoding: 'utf8'
})
  .split('\0')
  .filter(Boolean)

const textExtensions = new Set([
  '',
  '.base64',
  '.bat',
  '.cmd',
  '.conf',
  '.css',
  '.html',
  '.js',
  '.json',
  '.jsonc',
  '.md',
  '.mjs',
  '.properties',
  '.ps1',
  '.sbt',
  '.scala',
  '.sh',
  '.ts',
  '.txt',
  '.yaml',
  '.yml'
])
const textNames = new Set(['.gitattributes', '.gitignore', 'VERSION'])
const deprecated = [
  ['target/' + 'dev', 'legacy native-host classpath directory'],
  ['register --' + 'dev', 'legacy classpath registration flag'],
  ['writeDev' + 'Classpath', 'legacy classpath task name'],
  ['host-' + 'dev-', 'legacy classpath launcher name'],
  ['native-host.class' + 'path-', 'legacy classpath metadata name'],
  ['dist/' + 'dev', 'legacy extension development output'],
  ['dist/' + 'prod', 'legacy extension production output'],
  ['server/' + 'log', 'runtime logs must stay outside the source tree'],
  ['target/' + 'memory-model', 'model cache must stay outside target'],
  ['target/' + 'tools', 'tool cache must stay outside target'],
  ['Token' + 'Heist', 'legacy project name'],
  ['token' + '-heist', 'legacy project name']
]

const violations = []
for (const relative of files) {
  const extension = extname(relative).toLowerCase()
  if (!textExtensions.has(extension) && !textNames.has(relative)) continue

  const absolute = resolve(repoRoot, relative)
  const stat = statSync(absolute)
  if (!stat.isFile() || stat.size > 2_000_000) continue

  const text = readFileSync(absolute, 'utf8')
  const lines = text.split(/\r?\n/)
  for (const [needle, reason] of deprecated) {
    lines.forEach((line, index) => {
      if (line.includes(needle)) {
        violations.push(`${relative}:${index + 1}: ${needle} (${reason})`)
      }
    })
  }
}

if (violations.length > 0) {
  console.error(`deprecated path check failed with ${violations.length} violation(s):`)
  for (const violation of violations) console.error(`  ${violation}`)
  process.exit(1)
}

console.log(`Deprecated path check passed: ${files.length} tracked files`)
