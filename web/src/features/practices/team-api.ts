import { z } from 'zod'
import type * as Wire from '../encounters/services/api.generated'
import { fetchCsrfToken } from '../auth/auth-client'
const uuid = z.uuid()
export const teamRoleSchema = z.enum(['PRACTICE_ADMINISTRATOR', 'CLINICIAN', 'NURSE', 'SECRETARY'])
export type TeamRole = z.infer<typeof teamRoleSchema>
export const roleLabels: Record<TeamRole, string> = { PRACTICE_ADMINISTRATOR: 'Practice administrator', CLINICIAN: 'Doctor', NURSE: 'Nurse', SECRETARY: 'Secretary' }
export const clinicalListSourceSchema = z.enum(['ALLERGY', 'MEDICATION_USE', 'PROBLEM'])
export type ClinicalListSource = z.infer<typeof clinicalListSourceSchema>
export const clinicalListSourceLabels: Record<ClinicalListSource, string> = {
  ALLERGY: 'Allergies and adverse reactions',
  MEDICATION_USE: 'Reported medications',
  PROBLEM: 'Problems and diagnoses',
}
export const clinicalListSources = clinicalListSourceSchema.options
export const clinicalReadinessSchema = z.object({
  state: z.enum(['READY', 'SCHEMA_UPGRADE_REQUIRED', 'CLINICAL_DISABLED', 'SOURCE_CAPABILITY_DISABLED', 'MIGRATION_IN_PROGRESS', 'QUARANTINED']),
  currentSchemaVersion: z.string().nullable(),
  requiredSchemaVersion: z.string(),
  rolloutState: z.string().nullable(),
  clinicalEnabled: z.boolean(),
  sourceCapabilities: z.array(z.object({ sourceKind: clinicalListSourceSchema, enabled: z.boolean() })),
  latestFailure: z.string().nullable(),
})
export type ClinicalReadiness = z.infer<typeof clinicalReadinessSchema>
export const teamScopeSchema = z.object({ practiceId: uuid, practiceName: z.string(), clinicalEnabled: z.boolean(), schemaVersion: z.string(), clinicalReadiness: clinicalReadinessSchema })
export type TeamScope = z.infer<typeof teamScopeSchema>
export const siteSchema = z.object({ siteId: uuid, name: z.string() })
export type TeamSite = z.infer<typeof siteSchema>
const grantSchema = z.object({ siteId: uuid, reproductiveAccess: z.boolean() })
export const clinicalListGrantSchema = z.object({ siteId: uuid, sourceKind: clinicalListSourceSchema, read: z.boolean(), propose: z.boolean(), write: z.boolean(), review: z.boolean() })
export type ClinicalListGrant = z.infer<typeof clinicalListGrantSchema>
export const memberSchema = z.object({ userId: uuid, displayName: z.string(), username: z.string(), accountEnabled: z.boolean(), setupRequired: z.boolean(), enabled: z.boolean(), version: z.number().int().nonnegative(), roles: z.array(teamRoleSchema), siteGrants: z.array(grantSchema), clinicalListGrants: z.array(clinicalListGrantSchema).default([]), canReissue: z.boolean(), clinicalRemovalBlocked: z.boolean() })
export type TeamMember = z.infer<typeof memberSchema>
export const resolutionSchema = z.object({ userId: uuid.nullable(), displayName: z.string(), username: z.string(), existing: z.boolean() })
export type TeamResolution = z.infer<typeof resolutionSchema>
export const resultSchema = z.object({ member: memberSchema, temporaryPassword: z.string().nullable(), replayed: z.boolean() })
export type TeamResult = z.infer<typeof resultSchema>
export const accessSchema = z.object({ expectedVersion: z.number().int().nonnegative(), enabled: z.boolean(), roles: z.array(teamRoleSchema).min(1), siteGrants: z.array(grantSchema), clinicalListGrants: z.array(clinicalListGrantSchema), reason: z.string().trim().min(1, 'Enter a reason for this access change.').max(500) }).superRefine((data, ctx) => {
  if (data.roles.includes('CLINICIAN') && data.siteGrants.length === 0) ctx.addIssue({ code: 'custom', path: ['siteGrants'], message: 'Choose at least one clinical site for a doctor.' })
  if (!data.roles.includes('CLINICIAN') && data.siteGrants.length > 0) ctx.addIssue({ code: 'custom', path: ['siteGrants'], message: 'Clinical site access requires the doctor role.' })
  const siteIds = new Set(data.siteGrants.map(grant => grant.siteId))
  const identities = new Set<string>()
  data.clinicalListGrants.forEach((grant, index) => {
    const identity = `${grant.siteId}/${grant.sourceKind}`
    if (identities.has(identity)) ctx.addIssue({ code: 'custom', path: ['clinicalListGrants', index], message: 'Choose one profile for each clinical list.' })
    identities.add(identity)
    if (!siteIds.has(grant.siteId)) ctx.addIssue({ code: 'custom', path: ['clinicalListGrants', index], message: 'Clinical-list permissions require access to the same site.' })
    if (!permissionProfileForGrant(grant)) ctx.addIssue({ code: 'custom', path: ['clinicalListGrants', index], message: 'Choose a supported clinical-list permission profile.' })
  })
  if (!data.roles.includes('CLINICIAN') && data.clinicalListGrants.some(grant => grant.read || grant.propose || grant.write || grant.review)) ctx.addIssue({ code: 'custom', path: ['clinicalListGrants'], message: 'Clinical-list permissions require the doctor role.' })
})
export type TeamAccess = z.infer<typeof accessSchema>
export type TeamCreate = { email: string; displayName: string; confirmedUserId: string | null; roles: TeamRole[]; siteGrants: TeamAccess['siteGrants']; clinicalListGrants: TeamAccess['clinicalListGrants']; reason: string }

export const permissionProfileSchema = z.enum(['NO_ACCESS', 'VIEW', 'CONTRIBUTE', 'MAINTAIN', 'MAINTAIN_AND_REVIEW'])
export type PermissionProfile = z.infer<typeof permissionProfileSchema>
export const permissionProfileLabels: Record<PermissionProfile, string> = {
  NO_ACCESS: 'No access',
  VIEW: 'View only',
  CONTRIBUTE: 'Contribute',
  MAINTAIN: 'Maintain',
  MAINTAIN_AND_REVIEW: 'Maintain and review',
}
export const permissionProfiles = permissionProfileSchema.options
export function clinicalListGrant(siteId: string, sourceKind: ClinicalListSource, profile: PermissionProfile): ClinicalListGrant {
  const rank = permissionProfiles.indexOf(profile)
  return { siteId, sourceKind, read: rank >= 1, propose: rank >= 2, write: rank >= 3, review: rank >= 4 }
}
export function permissionProfileForGrant(grant: Pick<ClinicalListGrant, 'read' | 'propose' | 'write' | 'review'>): PermissionProfile | null {
  return permissionProfiles.find(profile => {
    const expected = clinicalListGrant('00000000-0000-4000-8000-000000000000', 'ALLERGY', profile)
    return expected.read === grant.read && expected.propose === grant.propose && expected.write === grant.write && expected.review === grant.review
  }) ?? null
}
export function clinicalListStatus(member: TeamMember, scope: TeamScope) {
  const available = scope.clinicalReadiness.state === 'READY' && member.accountEnabled && member.enabled && member.roles.includes('CLINICIAN') && member.siteGrants.length > 0
  if (!available) return 'No access'
  const profiles = member.siteGrants.flatMap(site => clinicalListSources.map(source => permissionProfileForGrant(
    member.clinicalListGrants.find(grant => grant.siteId === site.siteId && grant.sourceKind === source) ?? clinicalListGrant(site.siteId, source, 'NO_ACCESS'),
  ) ?? 'NO_ACCESS'))
  if (profiles.every(profile => profile === 'NO_ACCESS')) return 'No access'
  if (profiles.every(profile => profile === profiles[0])) return permissionProfileLabels[profiles[0]]
  return 'Mixed by site'
}
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
