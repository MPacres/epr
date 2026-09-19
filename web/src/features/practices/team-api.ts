import { z } from 'zod'
import type * as Wire from '../encounters/services/api.generated'
import { fetchCsrfToken } from '../auth/auth-client'
const uuid = z.uuid()
export const teamRoleSchema = z.enum(['PRACTICE_ADMINISTRATOR', 'CLINICIAN', 'NURSE', 'SECRETARY'])
export type TeamRole = z.infer<typeof teamRoleSchema>
export const roleLabels: Record<TeamRole, string> = { PRACTICE_ADMINISTRATOR: 'Practice administrator', CLINICIAN: 'Doctor', NURSE: 'Nurse', SECRETARY: 'Secretary' }
export const teamScopeSchema = z.object({ practiceId: uuid, practiceName: z.string(), clinicalEnabled: z.boolean(), schemaVersion: z.string() })
export type TeamScope = z.infer<typeof teamScopeSchema>
export const siteSchema = z.object({ siteId: uuid, name: z.string() })
export type TeamSite = z.infer<typeof siteSchema>
const grantSchema = z.object({ siteId: uuid, reproductiveAccess: z.boolean() })
export const memberSchema = z.object({ userId: uuid, displayName: z.string(), username: z.string(), accountEnabled: z.boolean(), setupRequired: z.boolean(), enabled: z.boolean(), version: z.number().int().nonnegative(), roles: z.array(teamRoleSchema), siteGrants: z.array(grantSchema), canReissue: z.boolean(), clinicalRemovalBlocked: z.boolean() })
export type TeamMember = z.infer<typeof memberSchema>
export const resolutionSchema = z.object({ userId: uuid.nullable(), displayName: z.string(), username: z.string(), existing: z.boolean() })
export type TeamResolution = z.infer<typeof resolutionSchema>
export const resultSchema = z.object({ member: memberSchema, temporaryPassword: z.string().nullable(), replayed: z.boolean() })
export type TeamResult = z.infer<typeof resultSchema>
export const accessSchema = z.object({ expectedVersion: z.number().int().nonnegative(), enabled: z.boolean(), roles: z.array(teamRoleSchema).min(1), siteGrants: z.array(grantSchema), reason: z.string().trim().min(1, 'Enter a reason for this access change.').max(500) }).superRefine((data, ctx) => {
  if (data.roles.includes('CLINICIAN') && data.siteGrants.length === 0) ctx.addIssue({ code: 'custom', path: ['siteGrants'], message: 'Choose at least one clinical site for a doctor.' })
  if (!data.roles.includes('CLINICIAN') && data.siteGrants.length > 0) ctx.addIssue({ code: 'custom', path: ['siteGrants'], message: 'Clinical site access requires the doctor role.' })
})
export type TeamAccess = z.infer<typeof accessSchema>
export type TeamCreate = { email: string; displayName: string; confirmedUserId: string | null; roles: TeamRole[]; siteGrants: TeamAccess['siteGrants']; reason: string }
export class TeamRequestError extends Error {
  readonly status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}
export async function teamRequest<T>(path: string, schema: z.ZodType<T>, options: { signal?: AbortSignal; method?: string; body?: unknown; key?: string } = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  try {
    if (options.method) { const csrf = await fetchCsrfToken(); headers[csrf.headerName] = csrf.token }
    if (options.key) headers['Idempotency-Key'] = options.key
    const response = await fetch(path, { credentials: 'include', cache: 'no-store', signal: options.signal, method: options.method, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) })
    if (!response.ok) {
      const detail = z.object({ detail: z.string() }).safeParse(await response.json().catch(() => ({})))
      throw new TeamRequestError(response.status, response.status === 401 ? 'Your session expired. Sign in again.' : response.status === 403 ? 'You no longer have permission to manage this Practice.' : detail.success ? detail.data.detail : response.status === 404 ? 'Team management is unavailable. The local team feature may be disabled.' : 'The service could not confirm the change. Keep this page open and retry.')
    }
    const result = schema.safeParse(await response.json())
    if (!result.success) throw new TeamRequestError(0, 'The response could not be verified. Retry the same request to check its outcome.')
    return result.data
  } catch (error) {
    if (error instanceof TeamRequestError || (error instanceof DOMException && error.name === 'AbortError')) throw error
    throw new TeamRequestError(0, 'The connection was interrupted. Your entries are still here. Retry the same request to confirm its outcome.')
  }
}
const base = (practice: string) => `/api/v1/practices/${encodeURIComponent(practice)}/administration`
export const teamKeys = { scopes: ['team', 'scopes'] as const, practice: (practice: string) => ['team', practice] as const }
export const teamApi = {
  scopes: (signal?: AbortSignal) => teamRequest('/api/v1/administration-scopes', z.array(teamScopeSchema), { signal }),
  sites: (p: string, signal?: AbortSignal) => teamRequest(`${base(p)}/sites`, z.array(siteSchema), { signal }),
  members: (p: string, filters: URLSearchParams, signal?: AbortSignal) => teamRequest(`${base(p)}/members?${filters}`, z.object({ items: z.array(memberSchema), total: z.number(), page: z.number(), size: z.number() }), { signal }),
  member: (p: string, u: string, signal?: AbortSignal) => teamRequest(`${base(p)}/members/${u}`, memberSchema, { signal }),
  resolve: (p: string, email: string) => teamRequest(`${base(p)}/member-resolution`, resolutionSchema, { method: 'POST', body: { email } }),
  create: (p: string, body: TeamCreate, key: string) => teamRequest(`${base(p)}/members`, resultSchema, { method: 'POST', body, key }),
  update: (p: string, u: string, body: TeamAccess & Wire.TeamAccess, key: string) => teamRequest(`${base(p)}/members/${u}/access`, resultSchema, { method: 'PUT', body, key }),
  reissue: (p: string, u: string, body: Required<Wire.TeamPassword>, key: string) => teamRequest(`${base(p)}/members/${u}/temporary-password`, resultSchema, { method: 'POST', body, key }),
  events: (p: string, u: string, page: number, signal?: AbortSignal) => teamRequest(`${base(p)}/members/${u}/events?page=${page}`, z.array(z.object({ id: uuid, actorName: z.string(), action: z.string(), reason: z.string(), beforeAccess: z.string(), afterAccess: z.string(), occurredAt: z.string() })), { signal }),
}
