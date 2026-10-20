import { z } from 'zod'

export const safetyReviewSchema = z.object({ reviewId: z.uuid(), siteId: z.uuid(), reviewType: z.string(), stale: z.boolean(), reviewedAt: z.string(), interactionsUnavailableAcknowledged: z.boolean(), kinds: z.array(z.object({ sourceKind: z.string() })) })
type SafetyReview = z.infer<typeof safetyReviewSchema>

export function latestEligibleSafetyReview(reviews: SafetyReview[], siteId: string, now: number): SafetyReview | undefined {
  return reviews.filter(review => review.siteId === siteId && review.reviewType === 'RECONCILIATION' && !review.stale && review.interactionsUnavailableAcknowledged && Date.parse(review.reviewedAt) > now - 24 * 60 * 60 * 1000 && ['ALLERGY', 'MEDICATION_USE'].every(kind => review.kinds.some(item => item.sourceKind === kind)))
    .sort((a, b) => Date.parse(b.reviewedAt) - Date.parse(a.reviewedAt))[0]
}
