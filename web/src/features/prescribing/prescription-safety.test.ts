import { describe, expect, it } from 'vitest'
import { latestEligibleSafetyReview, safetyReviewSchema } from './prescription-safety'

const site = '11111111-1111-4111-8111-111111111111'
const now = Date.parse('2026-10-21T00:00:00Z')
const base = safetyReviewSchema.parse({ reviewId: '22222222-2222-4222-8222-222222222222', siteId: site, reviewType: 'RECONCILIATION', stale: false, reviewedAt: '2026-10-20T23:00:00Z', interactionsUnavailableAcknowledged: true, kinds: [{ sourceKind: 'ALLERGY' }, { sourceKind: 'MEDICATION_USE' }, { sourceKind: 'PROBLEM' }] })

describe('prescription safety review selection', () => {
  it('uses the most recent eligible reconciliation for the current site', () => {
    const newer = { ...base, reviewId: '33333333-3333-4333-8333-333333333333', reviewedAt: '2026-10-20T23:30:00Z' }
    expect(latestEligibleSafetyReview([base, newer], site, now)?.reviewId).toBe(newer.reviewId)
  })

  it('does not link stale, old, partial, or other-site evidence', () => {
    const ineligible = [
      { ...base, stale: true },
      { ...base, reviewedAt: '2026-10-19T23:00:00Z' },
      { ...base, siteId: '44444444-4444-4444-8444-444444444444' },
      { ...base, interactionsUnavailableAcknowledged: false },
      { ...base, kinds: [{ sourceKind: 'ALLERGY' }] },
      { ...base, reviewType: 'ALLERGY' },
    ]
    for (const review of ineligible) expect(latestEligibleSafetyReview([review], site, now)).toBeUndefined()
  })
})
