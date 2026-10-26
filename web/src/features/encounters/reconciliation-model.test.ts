import { describe, expect, it } from 'vitest'
import type { ClinicalSummaryData, Reconciliation } from '../clinical-chart/clinical-list-api'
import { currentReconciliation } from './reconciliation-model'

const summary = {
  allergies: { generation: 2 }, medications: { generation: 3 }, problems: { generation: 4 },
} as ClinicalSummaryData
const review = {
  reviewId: '0e740dbc-cdd8-4e92-955f-2ecc735014a8', reviewType: 'RECONCILIATION', stale: false,
  kinds: [
    { sourceKind: 'ALLERGY', sourceGeneration: 2, assertion: 'CURRENT' },
    { sourceKind: 'MEDICATION_USE', sourceGeneration: 3, assertion: 'CURRENT' },
    { sourceKind: 'PROBLEM', sourceGeneration: 4, assertion: 'CURRENT' },
  ],
} as Reconciliation

describe('encounter reconciliation freshness', () => {
  it('accepts only an encounter reconciliation that covers all current list generations', () => {
    expect(currentReconciliation([review], summary)?.reviewId).toBe(review.reviewId)
    expect(currentReconciliation([{ ...review, reviewType: 'ALLERGY' }], summary)).toBeUndefined()
    expect(currentReconciliation([{ ...review, stale: true }], summary)).toBeUndefined()
    expect(currentReconciliation([{ ...review, kinds: review.kinds.slice(0, 2) }], summary)).toBeUndefined()
    expect(currentReconciliation([review], { ...summary, medications: { ...summary.medications, generation: 5 } })).toBeUndefined()
  })
})
