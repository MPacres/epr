import { z } from 'zod'
import { atSite, request, type Scope } from '../encounters/services/encounter-api'
import type * as Wire from '../encounters/services/api.generated'

export const clinicalListKinds = ['ALLERGY', 'MEDICATION_USE', 'PROBLEM'] as const
export type ClinicalListKind = (typeof clinicalListKinds)[number]

const uuid = z.uuid()
const codeSchema = z.object({ system: z.string(), code: z.string(), version: z.string().nullable(), display: z.string().nullable() })
const actionsSchema = z.object({ read: z.boolean(), propose: z.boolean(), write: z.boolean(), review: z.boolean() })
const reviewSchema = z.object({
  reviewId: uuid,
  outcome: z.string(),
  source: z.string(),
  reviewedAt: z.string(),
  stale: z.boolean(),
}).passthrough()
const previewSchema = z.object({ id: uuid, version: z.number(), display: z.string(), status: z.string(), code: codeSchema.nullable() })
export const listSummarySchema = z.object({
  kind: z.enum(clinicalListKinds),
  state: z.enum(['UNAVAILABLE', 'NOT_REVIEWED', 'EMPTY_REVIEWED', 'AVAILABLE', 'STALE']),
  generation: z.number(),
  activeCount: z.number(),
  preview: z.array(previewSchema),
  lastReview: reviewSchema.nullable(),
  actions: actionsSchema,
})
export type ListSummary = z.infer<typeof listSummarySchema>
export const clinicalSummarySchema = z.object({ allergies: listSummarySchema, medications: listSummarySchema, problems: listSummarySchema })
export type ClinicalSummaryData = z.infer<typeof clinicalSummarySchema>

const reactionSchema = z.object({ reactionId: uuid, manifestation: z.string(), severity: z.string(), onset: z.string().nullable(), exposureRoute: z.string().nullable() })
const allergyDataSchema = z.object({
  substanceDisplay: z.string(), code: codeSchema.nullable(), category: z.string(), reactionType: z.string(), verificationStatus: z.string(), criticality: z.string(),
  onset: z.string().nullable(), onsetPrecision: z.string(), lastOccurrence: z.string().nullable(), informationSource: z.string(), asserter: z.string().nullable(), notes: z.string().nullable(), clinicalTime: z.string().nullable(), encounterId: uuid.nullable(), reactions: z.array(reactionSchema),
})
const allergySchema = z.object({ id: uuid, patientId: uuid, version: z.number(), clinicalStatus: z.string(), createdBy: uuid, createdAt: z.string(), data: allergyDataSchema, revisions: z.array(z.unknown()), duplicateCandidate: z.boolean() })
export type Allergy = z.infer<typeof allergySchema>

const medicationDataSchema = z.object({
  medicationDisplay: z.string(), code: codeSchema.nullable(), brand: z.string().nullable(), strength: z.string().nullable(), formulation: z.string().nullable(), dose: z.number().nullable(), doseUnit: z.string().nullable(), route: z.string().nullable(), frequency: z.string().nullable(), prn: z.boolean(), effectiveStart: z.string().nullable(), effectiveEnd: z.string().nullable(), effectivePrecision: z.string(), informationSource: z.string(), prescriber: z.string().nullable(), adherence: z.string(), notes: z.string().nullable(), indicationProblemId: uuid.nullable(), prescriptionId: uuid.nullable(), clinicalTime: z.string().nullable(), encounterId: uuid.nullable(),
})
const medicationSchema = z.object({ id: uuid, patientId: uuid, version: z.number(), status: z.string(), createdBy: uuid, createdAt: z.string(), data: medicationDataSchema, revisions: z.array(z.unknown()), duplicateCandidate: z.boolean() })
export type MedicationUse = z.infer<typeof medicationSchema>

const problemDataSchema = z.object({
  problemDisplay: z.string(), code: codeSchema.nullable(), verificationStatus: z.string(), onset: z.string().nullable(), onsetPrecision: z.string(), abatement: z.string().nullable(), abatementPrecision: z.string(), severity: z.string().nullable(), informationSource: z.string(), notes: z.string().nullable(), clinicalTime: z.string().nullable(), encounterId: uuid.nullable(),
})
const problemSchema = z.object({ id: uuid, patientId: uuid, version: z.number(), clinicalStatus: z.string(), priority: z.number(), createdBy: uuid, createdAt: z.string(), data: problemDataSchema, revisions: z.array(z.unknown()), encounterIds: z.array(uuid), duplicateCandidate: z.boolean() })
export type Problem = z.infer<typeof problemSchema>
export type ClinicalRecord = Allergy | MedicationUse | Problem

const page = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item), page: z.number(), pageSize: z.number(), total: z.number() })
const kindPath = (kind: ClinicalListKind) => kind === 'ALLERGY' ? 'allergies' : kind === 'MEDICATION_USE' ? 'medication-use' : 'problems'
const clinicalRecordSchema = z.union([allergySchema, medicationSchema, problemSchema])
const proposalSchema = z.object({ proposalId: uuid, sourceKind: z.enum(clinicalListKinds), proposedAction: z.string(), informationSource: z.string(), decision: z.string().nullable() }).passthrough()
export const reconciliationSchema = z.object({ reviewId: uuid, reviewType: z.string(), reviewedAt: z.string(), stale: z.boolean(), manifestHash: z.string(), kinds: z.array(z.object({ sourceKind: z.enum(clinicalListKinds), sourceGeneration: z.number(), assertion: z.string() })) }).passthrough()
export type Reconciliation = z.infer<typeof reconciliationSchema>

export const clinicalListKey = (scope: Scope, patientId: string) => ['clinical-lists', scope.practiceId, scope.siteId, patientId] as const

export const clinicalListApi = {
  summary: (scope: Scope, patientId: string, signal?: AbortSignal) => request(atSite(scope, `/patients/${patientId}/clinical-summary`), clinicalSummarySchema, { signal }),
  list: (scope: Scope, patientId: string, kind: ClinicalListKind, signal?: AbortSignal) => request(atSite(scope, `/patients/${patientId}/${kindPath(kind)}`) + '&status=all&page=0&pageSize=50', page(clinicalRecordSchema), { signal }),
  activePage: (scope: Scope, patientId: string, kind: ClinicalListKind, number: number, signal?: AbortSignal) => request(atSite(scope, `/patients/${patientId}/${kindPath(kind)}`) + `&status=active&page=${number}&pageSize=100`, page(clinicalRecordSchema), { signal }),
  active: async (scope: Scope, patientId: string, kind: ClinicalListKind, signal?: AbortSignal) => {
    const first = await clinicalListApi.activePage(scope, patientId, kind, 0, signal)
    const pages = Math.ceil(first.total / first.pageSize)
    const rest = await Promise.all(Array.from({ length: Math.max(0, pages - 1) }, (_, index) => clinicalListApi.activePage(scope, patientId, kind, index + 1, signal)))
    return [first, ...rest].flatMap(result => result.items)
  },
  encounterReviews: (scope: Scope, patientId: string, encounterId: string, signal?: AbortSignal) => request(atSite(scope, `/patients/${patientId}/clinical-list-reviews`) + `&encounterId=${encounterId}`, z.array(reconciliationSchema), { signal }),
  proposals: (scope: Scope, patientId: string, signal?: AbortSignal) => request(atSite(scope, `/patients/${patientId}/clinical-record-proposals`), z.array(proposalSchema), { signal }),
  reconcile: (scope: Scope, patientId: string, body: Wire.Reconcile, key: string) => request(atSite(scope, `/patients/${patientId}/clinical-list-reconciliations`), reconciliationSchema, { method: 'POST', body, key }),
  terminology: (scope: Scope, patientId: string, kind: ClinicalListKind, signal?: AbortSignal) => request(atSite(scope, `/patients/${patientId}/clinical-terminology`) + `&kind=${kind}&q=`, z.array(codeSchema), { signal }),
  create: (scope: Scope, patientId: string, kind: ClinicalListKind, body: Wire.CreateAllergy | Wire.CreateMedicationUse | Wire.CreateProblem, key: string) => request(atSite(scope, `/patients/${patientId}/${kindPath(kind)}`), clinicalRecordSchema, { method: 'POST', body, key }),
  revise: (scope: Scope, patientId: string, kind: ClinicalListKind, recordId: string, body: Wire.ReviseAllergy | Wire.ReviseMedicationUse | Wire.ReviseProblem, key: string) => request(atSite(scope, `/patients/${patientId}/${kindPath(kind)}/${recordId}/revisions`), clinicalRecordSchema, { method: 'POST', body, key }),
  transitionAllergy: (scope: Scope, patientId: string, allergyId: string, body: Wire.Transition, key: string) => request(atSite(scope, `/patients/${patientId}/allergies/${allergyId}/transitions`), allergySchema, { method: 'POST', body, key }),
  review: (scope: Scope, patientId: string, kind: ClinicalListKind, body: Wire.ReviewCommand, key: string) => request(atSite(scope, `/patients/${patientId}/${kind === 'ALLERGY' ? 'allergy' : kind === 'MEDICATION_USE' ? 'medication' : 'problem'}-list-reviews`), reviewSchema, { method: 'POST', body, key }),
}
