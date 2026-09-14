import { describe, expect, it } from 'vitest'
import { homePathFor, type AuthUser } from './auth-client'

describe('role landing paths', () => {
  it('routes platform administrators to the provider portal', () => {
    expect(homePathFor({ username: 'superadmin', displayName: 'Local Superadmin', roles: ['SUPERADMIN'], passwordResetRequired: false })).toBe('/provider')
  })

  it('keeps clinicians in the clinical workspace', () => {
    expect(homePathFor({ username: 'doctor', displayName: 'Dr. Santos', roles: ['CLINICIAN'], passwordResetRequired: false })).toBe('/dashboard')
  })

  it('routes tenant Practice staff to the clinical host and never provider support', () => {
    const tenant: AuthUser = { username: 'admin@example.test', displayName: 'Practice Admin', roles: ['PRACTICE_STAFF'], passwordResetRequired: true }
    expect(homePathFor(tenant, 'clinical')).toBe('/dashboard')
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
