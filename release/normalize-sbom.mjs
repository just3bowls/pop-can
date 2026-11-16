#!/usr/bin/env node
import {createHash} from 'node:crypto'
import {readFileSync, writeFileSync} from 'node:fs'

const arg = name => {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] || '' : ''
}
const input = arg('--input')
const output = arg('--output')
const commit = arg('--commit')
const platform = arg('--platform')
const timestamp = arg('--timestamp')
if (!input || !output || !commit || !platform || !timestamp) {
  console.error('usage: normalize-sbom.mjs --input FILE --output FILE --commit SHA --platform NAME --timestamp ISO')
  process.exit(2)
}

const sbom = JSON.parse(readFileSync(input, 'utf8'))
if (sbom.bomFormat !== 'CycloneDX') {
  console.error(`Unsupported SBOM format: ${sbom.bomFormat || '<missing>'}`)
  process.exit(1)
}

const key = value =>
  [value?.group || '', value?.name || '', value?.version || '', value?.purl || '', value?.['bom-ref'] || ''].join(
    '\u001f'
  )
const sortProperties = values =>
  Array.isArray(values)
    ? values.sort((a, b) => `${a?.name || ''}\u001f${a?.value || ''}`.localeCompare(`${b?.name || ''}\u001f${b?.value || ''}`))
    : values
const sortComponents = components => {
  if (!Array.isArray(components)) return components
  components.forEach(component => {
    if (component.components) component.components = sortComponents(component.components)
    if (component.properties) component.properties = sortProperties(component.properties)
  })
  return components.sort((a, b) => key(a).localeCompare(key(b)))
}

const digest = createHash('sha256').update(`${commit}\u001f${platform}\u001f${timestamp}`).digest('hex')
sbom.serialNumber = `urn:uuid:${digest.slice(0, 8)}-${digest.slice(8, 12)}-${digest.slice(12, 16)}-${digest.slice(16, 20)}-${digest.slice(20, 32)}`
sbom.metadata = sbom.metadata || {}
sbom.metadata.timestamp = timestamp
if (sbom.metadata.component) sbom.metadata.component['bom-ref'] = `urn:pop-can:${platform}:${commit}`
if (sbom.metadata.tools?.components) sbom.metadata.tools.components = sortComponents(sbom.metadata.tools.components)
if (Array.isArray(sbom.components)) sbom.components = sortComponents(sbom.components)
if (Array.isArray(sbom.dependencies)) {
  sbom.dependencies.forEach(dependency => {
    if (Array.isArray(dependency.dependsOn)) dependency.dependsOn.sort()
  })
  sbom.dependencies.sort((a, b) => String(a.ref || '').localeCompare(String(b.ref || '')))
}

writeFileSync(output, `${JSON.stringify(sbom, null, 2)}\n`)
