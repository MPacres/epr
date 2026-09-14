import { describe, expect, it } from 'vitest'
import { filterQuery, practiceDisplayStatus } from './practice-administration-model'

describe('practice administration model', () => {
  it('keeps provisioning health separate from service access', () => {
    expect(practiceDisplayStatus({ status: 'QUARANTINED', serviceStatus: 'ENABLED' }).label).toBe('Needs attention')
    expect(practiceDisplayStatus({ status: 'ACTIVE', serviceStatus: 'SUSPENDED' }).label).toBe('Suspended')
    expect(practiceDisplayStatus({ status: 'ACTIVE', serviceStatus: 'ENABLED' }).label).toBe('Active')
  })

  it('maps directory filters to server-owned status dimensions', () => {
    expect(filterQuery('ACTIVE')).toEqual({ provisioningStatus: 'ACTIVE', serviceStatus: 'ENABLED' })
    expect(filterQuery('ACTION_REQUIRED')).toEqual({ provisioningStatus: 'QUARANTINED' })
  })
})
