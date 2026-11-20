import {execFile} from 'node:child_process'
import {dirname, join, resolve, sep} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')
const catalogDir = join(extensionRoot, 'provider-catalog')
const providersDir = join(catalogDir, 'providers')
const versionPath = join(catalogDir, 'VERSION')
const buildScript = join(here, 'build-provider-catalog.mjs')
const debounceMs = 300
const watchedEvents = new Set(['add', 'change', 'unlink'])

export const isProviderCatalogInput = file => {
  const target = resolve(file)
  return (
    target === versionPath ||
    (target.startsWith(`${providersDir}${sep}`) && target.endsWith('.jsonc'))
  )
}

export const providerCatalogBuildWatchPlugin = () => ({
  name: 'popcan-provider-catalog-build-watch',
  apply: 'serve',
  configureServer(server) {
    let timer
    let running = false
    let queued = false

    const logBuild = (error, stdout, stderr) => {
      const output = `${stdout || ''}${stderr || ''}`.trim()
      if (output) console.log(output)
      if (error) {
        server.config.logger.error(
          '[provider-catalog] bundle rebuild failed; keeping the previous valid provider-catalog/bundle.json'
        )
        return
      }
      server.config.logger.info(
        '[provider-catalog] bundle rebuilt; re-import provider-catalog/bundle.json to apply it'
      )
    }

    const runBuild = () => {
      if (running) {
        queued = true
        return
      }
      running = true
      execFile(process.execPath, [buildScript], {cwd: extensionRoot}, (error, stdout, stderr) => {
        running = false
        logBuild(error, stdout, stderr)
        if (queued) {
          queued = false
          schedule()
        }
      })
    }

    const schedule = () => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = undefined
        runBuild()
      }, debounceMs)
    }

    const onChange = (event, file) => {
      if (watchedEvents.has(event) && isProviderCatalogInput(file)) schedule()
    }

    server.watcher.add([providersDir, versionPath])
    server.watcher.on('all', onChange)
    server.httpServer?.once('close', () => {
      if (timer) clearTimeout(timer)
      server.watcher.off('all', onChange)
    })
  },
})
