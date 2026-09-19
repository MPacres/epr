import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
const root = fileURLToPath(new URL('../', import.meta.url))
const doc = JSON.parse(readFileSync(`${root}api/contracts/clinical-openapi.json`, 'utf8'))
const names = Object.keys(doc.components.schemas).sort()
function type(s) {
  if (s.$ref) return s.$ref.split('/').at(-1)
  if (s.enum) return s.enum.map(v => JSON.stringify(v)).join(' | ')
  if (s.anyOf || s.oneOf) return (s.anyOf ?? s.oneOf).map(type).join(' | ')
  if (s.allOf) return s.allOf.map(type).join(' & ')
  if (Array.isArray(s.type)) return s.type.map(t => type({...s, type:t})).join(' | ')
  if (s.type === 'array') return `Array<${type(s.items)}>`
  if (s.type === 'object') return `{\n${Object.entries(s.properties ?? {}).map(([k,v]) => `  ${JSON.stringify(k)}${s.required?.includes(k) ? '' : '?'}: ${type(v)}${v.nullable ? ' | null' : ''}`).join('\n')}\n}`
  if (s.type === 'integer' || s.type === 'number') return 'number'
  if (['string','boolean','null'].includes(s.type)) return s.type
  throw new Error(`Unsupported OpenAPI schema: ${JSON.stringify(s)}`)
}
writeFileSync(`${root}web/src/features/encounters/services/api.generated.ts`, '// Generated from api/contracts/clinical-openapi.json. Run node scripts/generate-clinical-contracts.mjs.\n' + names.map(n => `export type ${n} = ${type(doc.components.schemas[n])}\n`).join('\n'))
