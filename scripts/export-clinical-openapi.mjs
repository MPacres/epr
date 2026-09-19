import { writeFileSync } from 'node:fs'
const base = process.env.EPR_CONTRACT_BASE_URL ?? 'http://localhost:8081'
if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(base)) throw new Error('Export only from the local synthetic development API.')
const username = process.env.EPR_CONTRACT_USERNAME ?? 'obgyn-demo@epr.test'
const password = process.env.EPR_CLINICAL_DEMO_PASSWORD ?? 'ClinicalDemo123!'
const jar = new Map()
async function request(path, options = {}) {
  const response = await fetch(base + path, { ...options, headers: { Cookie: [...jar].map(([k,v]) => `${k}=${v}`).join('; '), ...options.headers } })
  for (const cookie of response.headers.getSetCookie()) { const pair = cookie.split(';')[0]; const equal = pair.indexOf('='); jar.set(pair.slice(0, equal), pair.slice(equal + 1)) }
  if (!response.ok) throw new Error(`Contract export request failed: ${response.status}`)
  return response.json()
}
const csrf = await request('/api/v1/auth/csrf')
await request('/api/v1/auth/login', { method:'POST', headers:{ 'Content-Type':'application/json', [csrf.headerName]:csrf.token }, body:JSON.stringify({username,password,remember:false}) })
const doc = await request('/api-docs')
doc.paths = Object.fromEntries(Object.entries(doc.paths).filter(([path]) => path.startsWith('/api/v1/practices/') || path === '/api/v1/clinical-scopes' || path === '/api/v1/administration-scopes'))
doc.servers = [{url:'/'}]
const needed = new Set()
function collect(value) { if (!value || typeof value !== 'object') return; if (value.$ref?.startsWith('#/components/schemas/')) { const name = value.$ref.split('/').at(-1); if (!needed.has(name)) { needed.add(name); collect(doc.components.schemas[name]) } } Object.values(value).forEach(collect) }
collect(doc.paths)
doc.components.schemas = Object.fromEntries(Object.entries(doc.components.schemas).filter(([name]) => needed.has(name)))
writeFileSync(new URL('../api/contracts/clinical-openapi.json', import.meta.url), JSON.stringify(doc,null,2)+'\n')
console.log(`Exported ${Object.keys(doc.paths).length} local clinical paths; no session credentials written.`)
