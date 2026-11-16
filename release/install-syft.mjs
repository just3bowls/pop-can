#!/usr/bin/env node
import {createHash} from 'node:crypto'
import {chmodSync, existsSync, mkdirSync, readdirSync, statSync, writeFileSync} from 'node:fs'
import {dirname, join, resolve} from 'node:path'
import {spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'

const VERSION = '1.57.1'
const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..')
const cacheRoot = process.env.POP_CAN_CACHE_DIR?.trim() || join(repoRoot, 'server', '.cache')
const platform = process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'darwin' : 'linux'
const arch = process.arch === 'arm64' ? 'arm64' : 'amd64'
const archiveName =
  platform === 'windows'
    ? `syft_${VERSION}_windows_${arch}.zip`
    : `syft_${VERSION}_${platform}_${arch}.tar.gz`
const checksums = {
  'syft_1.57.1_linux_amd64.tar.gz': 'bf52519eb57f675d47c07d43213cb6dff6449f3d6d1147ff6701ed91f44eef0c',
  'syft_1.57.1_darwin_amd64.tar.gz': '2355758c1142982a67b309604c5d75effdbe638a57c771979ea8fd1a16656abc',
  'syft_1.57.1_darwin_arm64.tar.gz': 'e0a74a5d2688d1254b4e17b71f068a83215e3625f846265bf6614c7ba06f3a96',
  'syft_1.57.1_windows_amd64.zip': '3a01fe865076bb548352195b77e88fa4649277861ea458efe165c3a810b6dcc7'
}
const expected = checksums[archiveName]
if (!expected) {
  console.error(`Unsupported Syft platform: ${platform}/${arch}`)
  process.exit(1)
}

const toolDir = join(cacheRoot, 'tools', `syft-v${VERSION}-${platform}-${arch}`)
const executableName = platform === 'windows' ? 'syft.exe' : 'syft'
const executable = join(toolDir, executableName)
if (existsSync(executable)) {
  process.stdout.write(executable)
  process.exit(0)
}

mkdirSync(toolDir, {recursive: true})
const url = `https://github.com/anchore/syft/releases/download/v${VERSION}/${archiveName}`
const response = await fetch(url)
if (!response.ok) {
  console.error(`Syft download failed: HTTP ${response.status}`)
  process.exit(1)
}
const bytes = Buffer.from(await response.arrayBuffer())
const actual = createHash('sha256').update(bytes).digest('hex')
if (actual !== expected) {
  console.error(`Syft checksum mismatch: ${actual} != ${expected}`)
  process.exit(1)
}
const archive = join(toolDir, archiveName)
writeFileSync(archive, bytes)

const extract =
  platform === 'windows'
    ? spawnSync(
        'powershell',
        ['-NoProfile', '-Command', `Expand-Archive -LiteralPath '${archive.replace(/'/g, "''")}' -DestinationPath '${toolDir.replace(/'/g, "''")}' -Force`],
        {stdio: 'inherit'}
      )
    : spawnSync('tar', ['-xzf', archive, '-C', toolDir], {stdio: 'inherit'})
if (extract.status !== 0) {
  console.error('Syft extraction failed')
  process.exit(1)
}

function findExecutable(dir) {
  for (const name of readdirSync(dir)) {
    const file = join(dir, name)
    const stat = statSync(file)
    if (stat.isDirectory()) {
      const found = findExecutable(file)
      if (found) return found
    } else if (name === executableName) return file
  }
  return ''
}
const found = findExecutable(toolDir)
if (!found) {
  console.error(`Extracted Syft executable not found under ${toolDir}`)
  process.exit(1)
}
if (platform !== 'windows') chmodSync(found, 0o755)
process.stdout.write(found)
