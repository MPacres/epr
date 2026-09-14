import { describe, expect, it } from 'vitest'
import { homePathFor, type AuthUser } from './auth-client'

describe('role landing paths', () => {
  it('routes platform administrators to the provider portal', () => {
    expect(homePathFor({ username: 'superadmin', displayName: 'Local Superadmin', roles: ['SUPERADMIN'] })).toBe('/provider')
  })

  it('keeps clinicians in the clinical workspace', () => {
    expect(homePathFor({ username: 'doctor', displayName: 'Dr. Santos', roles: ['CLINICIAN'] })).toBe('/dashboard')
  })

  it('does not route an account into the wrong host surface', () => {
    const superadmin: AuthUser = { username: 'superadmin', displayName: 'Local Superadmin', roles: ['SUPERADMIN'] }
    const clinician: AuthUser = { username: 'doctor', displayName: 'Dr. Santos', roles: ['CLINICIAN'] }
    expect(homePathFor(superadmin, 'clinical')).toBeNull()
    expect(homePathFor(clinician, 'support')).toBeNull()
  })
})
