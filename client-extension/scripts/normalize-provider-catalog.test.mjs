import assert from 'node:assert/strict'
import test from 'node:test'
import {parse} from 'jsonc-parser'

import {isDefaultFingerprintValue} from './lib/provider-catalog-defaults.mjs'
import {normalizeProviderText} from './normalize-provider-catalog.mjs'

test('recognizes fingerprint defaults without treating meaningful values as default', () => {
  assert(isDefaultFingerprintValue('parentClasses', []))
  assert(isDefaultFingerprintValue('editable', false))
  assert(!isDefaultFingerprintValue('parentClasses', ['shell']))
  assert(!isDefaultFingerprintValue('editable', true))
})

test('removes redundant provider and fingerprint defaults while preserving comments', () => {
  const text = `{
    "name": "demo",
    "page": {
      "selectors": {"input": "//input", "new-chat": ""},
      "fingerprints": {
        "input": {
          "tag": "textarea",
          "id": "",
          "classes": [],
          "editable": true,
          "x": 10
        }
      }
    },
    "attachment": {"strategy": "paste", "selector": ""},
    // keep this comment
    "parserRule": {"schemaVersion": 1, "handlers": []}
  }`
  const normalized = normalizeProviderText(text, 'demo')
  const errors = []
  const parsed = parse(normalized, errors, {allowTrailingComma: true, disallowComments: false})

  assert.equal(errors.length, 0)
  assert(!('name' in parsed))
  assert(!('new-chat' in parsed.page.selectors))
  assert(!('selector' in parsed.attachment))
  assert.deepEqual(parsed.page.fingerprints.input, {tag: 'textarea', editable: true, x: 10})
  assert(normalized.includes('// keep this comment'))
})
