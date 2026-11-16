#!/usr/bin/env node
import {createHash} from 'node:crypto'
import {
  createReadStream,
  existsSync,
  lstatSync,
  readFileSync,
  readdirSync,
  readlinkSync,
  utimesSync,
  writeFileSync
} from 'node:fs'
import {dirname, join, relative, resolve, sep} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..')

const arg = name => {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] || '' : ''
}
const mode = process.argv[2] || ''

const sha256 = value => createHash('sha256').update(value).digest('hex')
const hashFile = path =>
  new Promise((resolveHash, reject) => {
    const hash = createHash('sha256')
    createReadStream(path)
      .on('data', chunk => hash.update(chunk))
      .on('error', reject)
      .on('end', () => resolveHash(hash.digest('hex')))
  })
const posix = path => path.split(sep).join('/')

function walk(root, current = root) {
  return readdirSync(current)
    .sort()
    .flatMap(name => {
      const absolute = join(current, name)
      return lstatSync(absolute).isDirectory() ? walk(root, absolute) : [absolute]
    })
}

async function entriesFor(root) {
  const excluded = new Set(['FILES.sha256', 'BUILD-MANIFEST.json'])
  const files = []
  for (const absolute of walk(root)) {
    const path = posix(relative(root, absolute))
    if (excluded.has(path)) continue
    const stat = lstatSync(absolute)
    if (stat.isSymbolicLink()) {
      files.push({path, type: 'symlink', target: readlinkSync(absolute)})
    } else if (stat.isFile()) {
      files.push({
        path,
        type: 'file',
        size: stat.size,
        executable: (stat.mode & 0o111) !== 0,
        sha256: await hashFile(absolute)
      })
    }
  }
  return files.sort((a, b) => a.path.localeCompare(b.path))
}

const treeHash = (entries, prefix) =>
  sha256(
    entries
      .filter(entry => entry.path.startsWith(prefix))
      .map(entry => `${entry.path}\u0000${entry.type}\u0000${entry.sha256 || entry.target || ''}`)
      .join('\n')
  )

async function prepare() {
  const root = resolve(arg('--root'))
  const platform = arg('--platform')
  const version = arg('--version')
  const archiveName = arg('--archive-name')
  const commit = arg('--commit')
  const commitTimestamp = arg('--commit-time')
  const sourceDateEpoch = Number(arg('--source-date-epoch'))
  const sourceClean = arg('--source-clean') === 'true'
  if (!root || !platform || !version || !archiveName || !commit || !commitTimestamp || !Number.isInteger(sourceDateEpoch)) {
    console.error('prepare requires --root --platform --version --archive-name --commit --commit-time --source-date-epoch --source-clean')
    process.exit(2)
  }
  const versionFile = join(root, 'VERSION')
  if (!existsSync(versionFile) || readFileSync(versionFile, 'utf8').trim() !== version) {
    console.error('staged VERSION does not match the release version')
    process.exit(1)
  }
  if (!existsSync(join(root, 'SBOM.cdx.json'))) {
    console.error('SBOM.cdx.json is missing')
    process.exit(1)
  }
  const files = await entriesFor(root)
  const fileLines = files
    .filter(entry => entry.type === 'file')
    .map(entry => `${entry.sha256}  ${entry.path}`)
    .join('\n')
  const filesManifest = `${fileLines}\n`
  writeFileSync(join(root, 'FILES.sha256'), filesManifest)
  const sbomSha256 = files.find(entry => entry.path === 'SBOM.cdx.json')?.sha256 || ''
  const manifest = {
    schemaVersion: 1,
    productVersion: version,
    platform,
    archiveName,
    commit,
    commitTimestamp,
    sourceClean,
    sourceDateEpoch,
    extensionTreeSha256: treeHash(files, 'extension/'),
    nativeHostTreeSha256: treeHash(files, 'native-host/'),
    filesManifestSha256: sha256(filesManifest),
    sbomSha256,
    fileCount: files.filter(entry => entry.type === 'file').length,
    symlinkCount: files.filter(entry => entry.type === 'symlink').length,
    providerRulesIncluded: false,
    files
  }
  writeFileSync(join(root, 'BUILD-MANIFEST.json'), `${JSON.stringify(manifest, null, 2)}\n`)
  console.log(`archive metadata: ${manifest.fileCount} files, commit ${commit.slice(0, 12)}, clean=${sourceClean}`)
}

function finalize() {
  const archive = resolve(arg('--archive'))
  const root = resolve(arg('--root'))
  const buildManifestPath = join(root, 'BUILD-MANIFEST.json')
  if (!archive || !root || !existsSync(archive) || !existsSync(buildManifestPath)) {
    console.error('finalize requires --archive and --root containing BUILD-MANIFEST.json')
    process.exit(2)
  }
  const buildManifestBytes = readFileSync(buildManifestPath)
  const buildManifest = JSON.parse(buildManifestBytes.toString('utf8'))
  const archiveBytes = readFileSync(archive)
  const manifest = {
    schemaVersion: 1,
    productVersion: buildManifest.productVersion,
    platform: buildManifest.platform,
    commit: buildManifest.commit,
    commitTimestamp: buildManifest.commitTimestamp,
    sourceClean: buildManifest.sourceClean,
    archiveName: buildManifest.archiveName,
    archiveSize: archiveBytes.length,
    archiveSha256: sha256(archiveBytes),
    buildManifestSha256: sha256(buildManifestBytes),
    sbomSha256: buildManifest.sbomSha256
  }
  writeFileSync(`${archive}.manifest.json`, `${JSON.stringify(manifest, null, 2)}\n`)
  console.log(`archive manifest: ${archive}.manifest.json`)
}

async function verify() {
  const root = resolve(arg('--root'))
  const platform = arg('--platform')
  const expectedVersion = arg('--extension-version')
  const manifestPath = join(root, 'BUILD-MANIFEST.json')
  if (!root || !platform || !existsSync(manifestPath)) {
    console.error('verify requires --root --platform and BUILD-MANIFEST.json')
    process.exit(2)
  }
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
  if (manifest.platform !== platform || manifest.schemaVersion !== 1) {
    console.error('build manifest platform/schema mismatch')
    process.exit(1)
  }
  if (manifest.productVersion !== readFileSync(join(root, 'VERSION'), 'utf8').trim()) {
    console.error('build manifest productVersion does not match VERSION')
    process.exit(1)
  }
  const files = await entriesFor(root)
  if (JSON.stringify(files) !== JSON.stringify(manifest.files)) {
    console.error('staged files differ from BUILD-MANIFEST.json')
    process.exit(1)
  }
  const filesManifest = readFileSync(join(root, 'FILES.sha256'))
  if (sha256(filesManifest) !== manifest.filesManifestSha256) {
    console.error('FILES.sha256 hash mismatch')
    process.exit(1)
  }
  const sbom = JSON.parse(readFileSync(join(root, 'SBOM.cdx.json'), 'utf8'))
  if (sbom.bomFormat !== 'CycloneDX') {
    console.error('SBOM.cdx.json is not CycloneDX')
    process.exit(1)
  }
  const extensionManifest = JSON.parse(readFileSync(join(root, 'extension', 'manifest.json'), 'utf8'))
  if (expectedVersion && extensionManifest.version !== expectedVersion) {
    console.error(`extension version mismatch: ${extensionManifest.version} != ${expectedVersion}`)
    process.exit(1)
  }
  if (!extensionManifest.key) {
    console.error('extension manifest has no fixed public key')
    process.exit(1)
  }
  const keyHex = createHash('sha256').update(Buffer.from(extensionManifest.key, 'base64')).digest('hex').slice(0, 32)
  const manifestExtensionId = keyHex.replace(/[0-9a-f]/g, char =>
    String.fromCharCode(97 + Number.parseInt(char, 16))
  )
  const packagedExtensionId = readFileSync(join(root, '.pop-can', 'extension-id.txt'), 'utf8').trim()
  if (manifestExtensionId !== packagedExtensionId) {
    console.error(`packaged extension ID mismatch: ${manifestExtensionId} != ${packagedExtensionId}`)
    process.exit(1)
  }
  const launcher =
    platform === 'linux'
      ? join(root, 'native-host', 'PopCanHost', 'bin', 'PopCanHost')
      : platform === 'macos'
        ? join(root, 'native-host', 'PopCanHost.app', 'Contents', 'MacOS', 'PopCanHost')
        : join(root, 'native-host', 'PopCanHost', 'PopCanHost.exe')
  const hostScript = join(root, platform === 'windows' ? 'host.ps1' : 'host.sh')
  if (!existsSync(launcher) || !existsSync(hostScript)) {
    console.error('native host launcher or registration script is missing')
    process.exit(1)
  }
  if (platform !== 'windows' && (lstatSync(launcher).mode & 0o111) === 0) {
    console.error('native host launcher is not executable')
    process.exit(1)
  }
  const providerBundle = JSON.parse(readFileSync(join(repoRoot, 'client-extension', 'presets', 'bundle.json'), 'utf8'))
  const needles = providerBundle.providers
    .flatMap(provider => [provider.page?.url, ...(provider.network?.apiPatterns || [])])
    .filter(value => typeof value === 'string' && value.length >= 12)
  const text = files
    .filter(entry => entry.type === 'file' && /\.(?:js|json|html|css|md|txt|cfg)$/.test(entry.path))
    .map(entry => readFileSync(join(root, entry.path), 'utf8'))
    .join('\n')
  if (needles.some(needle => text.includes(needle))) {
    console.error('archive contains bundled provider rules')
    process.exit(1)
  }
  for (const required of ['README.md', 'NATIVE_HOST.md', 'PROVIDER_PACK.md', 'LICENSE', 'SECURITY.md']) {
    if (!existsSync(join(root, required))) {
      console.error(`required document is missing: ${required}`)
      process.exit(1)
    }
  }
  console.log(`archive verify passed: ${manifest.fileCount} files, ${manifest.platform}, v${manifest.productVersion}`)
}

function touchTimes() {
  const root = resolve(arg('--root'))
  const epoch = Number(arg('--epoch'))
  if (!root || !Number.isInteger(epoch)) {
    console.error('touch requires --root and --epoch')
    process.exit(2)
  }
  const paths = walk(root)
  for (const path of paths.reverse()) utimesSync(path, epoch, epoch)
  utimesSync(root, epoch, epoch)
  console.log(`normalized mtimes: ${paths.length} files`)
}

if (mode === 'prepare') await prepare()
else if (mode === 'finalize') finalize()
else if (mode === 'verify') await verify()
else if (mode === 'touch') touchTimes()
else {
  console.error('usage: archive-metadata.mjs prepare|finalize|verify|touch ...')
  process.exit(2)
}
