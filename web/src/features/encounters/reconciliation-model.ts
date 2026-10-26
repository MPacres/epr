import type { ClinicalSummaryData, Reconciliation } from '../clinical-chart/clinical-list-api'

export function currentReconciliation(reviews: Reconciliation[], summary: ClinicalSummaryData) {
  return reviews.find(review => review.reviewType === 'RECONCILIATION' && !review.stale &&
    review.kinds.length === 3 && new Set(review.kinds.map(kind => kind.sourceKind)).size === 3 && review.kinds.every(kind => {
      const list = kind.sourceKind === 'ALLERGY' ? summary.allergies : kind.sourceKind === 'MEDICATION_USE' ? summary.medications : summary.problems
      return list.generation === kind.sourceGeneration
    }))
}
