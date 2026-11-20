import assert from 'node:assert/strict'
import test from 'node:test'

import {diffValues, ruleFromProviderText} from './explain-provider-rule.mjs'

test('extracts parserRule from provider JSONC', () => {
  const rule = ruleFromProviderText(
    `{
      // source comment
      "parserRule": {"schemaVersion": 1, "handlers": []},
    }`,
    'fixture'
  )
  assert.deepEqual(JSON.parse(rule), {schemaVersion: 1, handlers: []})
})

test('diffs effective rule values instead of source text', () => {
  const changes = diffValues(
    {handlers: [{id: 'answer', actions: [{op: 'appendContent'}]}]},
    {handlers: [{id: 'answer', actions: [{op: 'appendDelta'}]}]}
  )
  assert.deepEqual(changes, [
    '- $.handlers[0].actions[0].op: "appendContent"',
    '+ $.handlers[0].actions[0].op: "appendDelta"'
  ])
})
