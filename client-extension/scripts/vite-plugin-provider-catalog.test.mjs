import assert from 'node:assert/strict'
import test from 'node:test'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

import {isProviderCatalogInput} from './vite-plugin-provider-catalog.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')

test('watches provider JSONC files and the catalog version', () => {
  assert(isProviderCatalogInput(join(extensionRoot, 'provider-catalog', 'providers', 'longcat.jsonc')))
  assert(isProviderCatalogInput(join(extensionRoot, 'provider-catalog', 'VERSION')))
})

test('ignores derived bundles and fixtures', () => {
  assert(!isProviderCatalogInput(join(extensionRoot, 'provider-catalog', 'bundle.json')))
  assert(
    !isProviderCatalogInput(
      join(extensionRoot, 'provider-catalog', 'fixtures', 'longcat', 'agent-preview.raw.txt')
    )
  )
})
