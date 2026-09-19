import { afterEach, describe, expect, it, vi } from 'vitest'
import { deleteSession, homePathFor, type AuthUser } from './auth-client'

afterEach(() => vi.unstubAllGlobals())

describe('session logout', () => {
  it('posts with the current CSRF token and credentials', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await deleteSession()

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/csrf', { credentials: 'include' })
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/logout', {
      method: 'POST', credentials: 'include', headers: { 'X-CSRF-TOKEN': 'csrf-token' },
    })
  })

  it('reports a failed logout so the authenticated UI can offer a retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(deleteSession()).rejects.toThrow('Sign-out could not be completed. Try again.')
  })
})

describe('role landing paths', () => {
  it('routes platform administrators to the provider portal', () => {
    expect(homePathFor({ username: 'superadmin', displayName: 'Local Superadmin', roles: ['SUPERADMIN'], passwordResetRequired: false })).toBe('/provider')
  })

  it('keeps clinicians in the clinical workspace', () => {
    expect(homePathFor({ username: 'doctor', displayName: 'Dr. Santos', roles: ['CLINICIAN'], passwordResetRequired: false })).toBe('/dashboard')
  })

  it('routes tenant Practice staff to the clinical host and never provider support', () => {
    const tenant: AuthUser = { username: 'admin@example.test', displayName: 'Practice Admin', roles: ['PRACTICE_STAFF'], passwordResetRequired: true }
    expect(homePathFor(tenant, 'clinical')).toBe('/practices')
    expect(homePathFor(tenant, 'support')).toBeNull()
  })

  it('does not route an account into the wrong host surface', () => {
    const superadmin: AuthUser = { username: 'superadmin', displayName: 'Local Superadmin', roles: ['SUPERADMIN'], passwordResetRequired: false }
    const clinician: AuthUser = { username: 'doctor', displayName: 'Dr. Santos', roles: ['CLINICIAN'], passwordResetRequired: false }
    const practiceStaff: AuthUser = { username: 'staff', displayName: 'Practice Staff', roles: ['PRACTICE_STAFF'], passwordResetRequired: false }
    expect(homePathFor(superadmin, 'clinical')).toBeNull()
    expect(homePathFor(clinician, 'support')).toBeNull()
    expect(homePathFor(practiceStaff, 'support')).toBeNull()
  })
})
