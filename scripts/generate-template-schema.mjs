import { readFileSync, writeFileSync } from 'node:fs'
const doc = JSON.parse(readFileSync(new URL('../api/contracts/clinical-openapi.json', import.meta.url), 'utf8'))
const names = ['TemplateDefinition','Section','Field','QuickGroup','Rule']
const defs = Object.fromEntries(names.map(name => [name, structuredClone(doc.components.schemas[name])]))
function normalize(schema) {
  if (schema.$ref) schema.$ref = schema.$ref.replace('#/components/schemas/', '#/$defs/')
  if (schema.properties) { schema.required = Object.keys(schema.properties); schema.additionalProperties = false }
  for (const value of Object.values(schema)) if (value && typeof value === 'object') { if (Array.isArray(value)) value.forEach(v => v && typeof v === 'object' && normalize(v)); else normalize(value) }
}
Object.values(defs).forEach(normalize)
for (const [key,value] of Object.entries({ schemaVersion:'1.0',ruleVersion:'1',rendererVersion:'1',extractionVersion:'none',terminologyVersion:'uncoded-1',syntheticOnly:true })) defs.TemplateDefinition.properties[key] = {const:value}
defs.TemplateDefinition.properties.sections.maxItems=30
defs.Field.properties.maxItems={type:'integer',minimum:0,maximum:30}
defs.Field.properties.maxLength={type:'integer',minimum:1,maximum:10000}
defs.Field.properties.precision={type:'integer',minimum:0,maximum:6}
defs.Field.required=defs.Field.required.filter(name=>name!=='quickFindings')
defs.Field.properties.quickFindings.maxItems=20
defs.QuickGroup.properties.key={type:'string',pattern:'^[a-z][a-z0-9.-]*$',maxLength:60}
defs.QuickGroup.properties.label={type:'string',minLength:1,maxLength:80}
defs.QuickGroup.properties.phrases={type:'array',minItems:1,maxItems:12,uniqueItems:true,items:{type:'string',minLength:1,maxLength:220}}
writeFileSync(new URL('../api/contracts/template-definition-v1.schema.json', import.meta.url), JSON.stringify({$schema:'https://json-schema.org/draft/2020-12/schema', title:'Synthetic EPR template definition v1', $ref:'#/$defs/TemplateDefinition', $defs:defs},null,2)+'\n')
