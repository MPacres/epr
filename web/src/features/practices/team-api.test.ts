import { afterEach, describe, expect, it, vi } from 'vitest'
import { accessSchema, clinicalListGrant, clinicalListStatus, memberSchema, permissionProfileForGrant, teamApi, teamKeys, TeamRequestError, type ClinicalReadiness } from './team-api'
const p = '00000000-0000-4000-8000-000000000001'
const u = '00000000-0000-4000-8000-000000000002'
const s = '00000000-0000-4000-8000-000000000003'
const member = { userId: u, displayName: 'Synthetic User', username: 'synthetic@example.test', accountEnabled: true, setupRequired: true, enabled: true, version: 1, roles: ['CLINICIAN'], siteGrants: [{ siteId: s, reproductiveAccess: false }], clinicalListGrants: [clinicalListGrant(s, 'ALLERGY', 'VIEW')], canReissue: true, clinicalRemovalBlocked: true }
const readiness: ClinicalReadiness = { state: 'READY', currentSchemaVersion: '14', requiredSchemaVersion: '14', rolloutState: 'READY', clinicalEnabled: true, sourceCapabilities: [{ sourceKind: 'ALLERGY', enabled: true }, { sourceKind: 'MEDICATION_USE', enabled: true }, { sourceKind: 'PROBLEM', enabled: true }], latestFailure: null }
afterEach(() => vi.unstubAllGlobals())
describe('Practice team contracts', () => {
  it('requires explicit clinical sites and keeps ordinary administration separate', () => {
    const access = { expectedVersion: 0, enabled: true, roles: ['PRACTICE_ADMINISTRATOR'], siteGrants: [], clinicalListGrants: [], reason: 'Reviewed access' }
    expect(accessSchema.safeParse(access).success).toBe(true)
    expect(accessSchema.safeParse({ ...access, roles: ['CLINICIAN'] }).success).toBe(false)
    expect(accessSchema.safeParse({ ...access, siteGrants: [{ siteId: s, reproductiveAccess: false }] }).success).toBe(false)
    expect(accessSchema.safeParse({ ...access, reason: '  ' }).success).toBe(false)
    expect(memberSchema.safeParse({ ...member, roles: ['SUPERADMIN'] }).success).toBe(false)
  })
  it('maps only the five supported list permission profiles', () => {
    expect(permissionProfileForGrant(clinicalListGrant(s, 'ALLERGY', 'NO_ACCESS'))).toBe('NO_ACCESS')
    expect(permissionProfileForGrant(clinicalListGrant(s, 'ALLERGY', 'MAINTAIN_AND_REVIEW'))).toBe('MAINTAIN_AND_REVIEW')
    expect(permissionProfileForGrant({ read: false, propose: true, write: false, review: false })).toBeNull()
    const invalid = { expectedVersion: 1, enabled: true, roles: ['CLINICIAN'], siteGrants: [{ siteId: s, reproductiveAccess: false }], clinicalListGrants: [{ siteId: s, sourceKind: 'ALLERGY', read: false, propose: true, write: false, review: false }], reason: 'Invalid profile' }
    expect(accessSchema.safeParse(invalid).success).toBe(false)
  })
  it('does not report positive list access when Practice readiness or membership prerequisites fail', () => {
    const scope = { practiceId: p, practiceName: 'Synthetic Practice', clinicalEnabled: true, schemaVersion: '14', clinicalReadiness: readiness }
    expect(clinicalListStatus(memberSchema.parse(member), scope)).toBe('Mixed by site')
    expect(clinicalListStatus(memberSchema.parse(member), { ...scope, clinicalReadiness: { ...readiness, state: 'CLINICAL_DISABLED', clinicalEnabled: false } })).toBe('No access')
    expect(clinicalListStatus(memberSchema.parse({ ...member, enabled: false }), scope)).toBe('No access')
  })
  it('reads older access evidence without clinical-list snapshots', () => {
    const parsed = memberSchema.parse({ ...member, clinicalListGrants: undefined })
    expect(parsed.clinicalListGrants).toEqual([])
  })
  it('separates team caches by Practice', () => {
    expect(teamKeys.practice(p)).not.toEqual(teamKeys.practice(u))
    expect(teamKeys.scopes).not.toEqual(teamKeys.practice(p))
  })
  it('sends the same supplied retry key and body with CSRF protection', async () => {
    const fetcher = vi.fn().mockImplementation(async (path: string) => path.endsWith('/csrf') ? new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })) : new Response(JSON.stringify({ member, temporaryPassword: null, replayed: true })))
    vi.stubGlobal('fetch', fetcher)
    const command = { expectedVersion: 1, enabled: true, roles: ['CLINICIAN'] as const, siteGrants: member.siteGrants, clinicalListGrants: member.clinicalListGrants, reason: 'Reviewed sites' }
    for (let i = 0; i < 2; i++) await teamApi.update(p, u, { ...command, roles: [...command.roles] }, s)
    const requests = fetcher.mock.calls.filter(([path]) => !path.endsWith('/csrf'))
    expect(requests).toHaveLength(2)
    expect(requests[0][1].body).toBe(requests[1][1].body)
    expect(requests[0][1].headers).toMatchObject({ 'Idempotency-Key': s, 'X-CSRF-TOKEN': 'csrf' })
    expect(requests[0][0]).toContain(`/practices/${p}/administration/members/${u}/access`)
    expect(requests[0][1].cache).toBe('no-store')
  })
  it('preserves conflicts and access loss as distinct errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: 'Review the latest version.' }), { status: 409 })))
    await expect(teamApi.member(p, u)).rejects.toMatchObject({ status: 409, message: 'Review the latest version.' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 403 })))
    await expect(teamApi.member(p, u)).rejects.toMatchObject({ status: 403 })
  })
  it('treats malformed receipts and interrupted requests as uncertain outcomes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')))
    await expect(teamApi.member(p, u)).rejects.toMatchObject({ status: 0 })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network')))
    await expect(teamApi.member(p, u)).rejects.toBeInstanceOf(TeamRequestError)
  })
})
