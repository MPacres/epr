import { afterEach, describe, expect, it, vi } from 'vitest'
import { accessSchema, memberSchema, teamApi, teamKeys, TeamRequestError } from './team-api'
const p = '00000000-0000-4000-8000-000000000001'
const u = '00000000-0000-4000-8000-000000000002'
const s = '00000000-0000-4000-8000-000000000003'
const member = { userId: u, displayName: 'Synthetic User', username: 'synthetic@example.test', accountEnabled: true, setupRequired: true, enabled: true, version: 1, roles: ['CLINICIAN'], siteGrants: [{ siteId: s, reproductiveAccess: false }], canReissue: true, clinicalRemovalBlocked: true }
afterEach(() => vi.unstubAllGlobals())
describe('Practice team contracts', () => {
  it('requires explicit clinical sites and keeps ordinary administration separate', () => {
    const access = { expectedVersion: 0, enabled: true, roles: ['PRACTICE_ADMINISTRATOR'], siteGrants: [], reason: 'Reviewed access' }
    expect(accessSchema.safeParse(access).success).toBe(true)
    expect(accessSchema.safeParse({ ...access, roles: ['CLINICIAN'] }).success).toBe(false)
    expect(accessSchema.safeParse({ ...access, siteGrants: [{ siteId: s, reproductiveAccess: false }] }).success).toBe(false)
    expect(accessSchema.safeParse({ ...access, reason: '  ' }).success).toBe(false)
    expect(memberSchema.safeParse({ ...member, roles: ['SUPERADMIN'] }).success).toBe(false)
  })
  it('separates team caches by Practice', () => {
    expect(teamKeys.practice(p)).not.toEqual(teamKeys.practice(u))
    expect(teamKeys.scopes).not.toEqual(teamKeys.practice(p))
  })
  it('sends the same supplied retry key and body with CSRF protection', async () => {
    const fetcher = vi.fn().mockImplementation(async (path: string) => path.endsWith('/csrf') ? new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })) : new Response(JSON.stringify({ member, temporaryPassword: null, replayed: true })))
    vi.stubGlobal('fetch', fetcher)
    const command = { expectedVersion: 1, enabled: true, roles: ['CLINICIAN'] as const, siteGrants: member.siteGrants, reason: 'Reviewed sites' }
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
