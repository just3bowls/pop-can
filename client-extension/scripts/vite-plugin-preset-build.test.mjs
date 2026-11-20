import assert from 'node:assert/strict'
import test from 'node:test'
import {dirname, join, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

import {isPresetInput} from './vite-plugin-preset-build.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const extensionRoot = resolve(here, '..')

test('watches provider JSONC files and the preset version', () => {
  assert(isPresetInput(join(extensionRoot, 'presets', 'providers', 'longcat.jsonc')))
  assert(isPresetInput(join(extensionRoot, 'presets', 'VERSION')))
})

test('ignores derived bundles and fixtures', () => {
  assert(!isPresetInput(join(extensionRoot, 'presets', 'bundle.json')))
  assert(!isPresetInput(join(extensionRoot, 'presets', 'fixtures', 'longcat', 'agent-preview.raw.txt')))
})
