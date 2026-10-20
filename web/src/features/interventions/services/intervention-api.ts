import { z } from 'zod'
import { atSite, request, type Scope } from '../../encounters/services/encounter-api'
import type * as Wire from '../../encounters/services/api.generated'
export const kindSchema = z.enum(['PRESCRIPTION', 'LAB_ORDER', 'IMAGING_ORDER', 'REFERRAL', 'FOLLOW_UP'])
export type Kind = z.infer<typeof kindSchema>
export const labels: Record<Kind, string> = { PRESCRIPTION: 'Prescription', LAB_ORDER: 'Laboratory order', IMAGING_ORDER: 'Imaging order', REFERRAL: 'Doctor referral', FOLLOW_UP: 'Follow-up task' }
export const sourcePaths: Record<Exclude<Kind, 'FOLLOW_UP'>, string> = { PRESCRIPTION: 'prescriptions', LAB_ORDER: 'service-requests', IMAGING_ORDER: 'service-requests', REFERRAL: 'referrals' }
export const authorizePaths = { PRESCRIPTION: 'issue', LAB_ORDER: 'authorization', IMAGING_ORDER: 'authorization', REFERRAL: 'release' } as const
export const actionLabels = { PRESCRIPTION: 'Issue prescription', LAB_ORDER: 'Authorize order', IMAGING_ORDER: 'Authorize order', REFERRAL: 'Release referral' } as const
export const summarySchema = z.object({ kind: kindSchema, sourceId: z.uuid(), sourceVersion: z.number(), title: z.string(), details: z.array(z.string()), clinicalState: z.string(), deliveryState: z.string(), fulfillmentState: z.string(), reviewState: z.string(), ownerId: z.uuid(), dueDate: z.string().nullable(), occurredAt: z.string(), artifactId: z.uuid().nullable(), artifactHash: z.string().nullable(), groupId: z.uuid().nullish() })
export const itemSchema = z.object({ linkId: z.uuid(), summaryRevision: z.number(), summary: summarySchema })
export type Item = z.infer<typeof itemSchema>
export const pageSchema = z.object({ interventionSetVersion: z.number(), items: z.array(itemSchema), total: z.number(), page: z.number(), pageSize: z.number() })
export const capabilitiesSchema = z.array(z.object({ kind: kindSchema, read: z.boolean(), prepare: z.boolean(), authorize: z.boolean(), manageExisting: z.boolean().default(false), unavailableReason: z.string().nullable() }))
export const preparationSchema = z.object({ preparationId: z.uuid(), sourceVersion: z.number(), artifactId: z.uuid(), artifactHash: z.string() })
export type Preparation = z.infer<typeof preparationSchema>
export const sourceSchema = z.object({ id: z.uuid(), patientId: z.uuid(), encounterId: z.uuid(), siteId: z.uuid(), version: z.number(), state: z.string(), occurredAt: z.string(), data: z.record(z.string(), z.unknown()) })
export type SourceRecord = z.infer<typeof sourceSchema>
export type SourceCreate = Wire.PrescriptionCreate | Wire.ServiceRequestCreate | Wire.ReferralCreate
export const contextPath = (patient: string, encounter: string) => `/patients/${patient}/encounters/${encounter}`
export const sourcePath = (patient: string, kind: Exclude<Kind, 'FOLLOW_UP'>, id?: string) => `/patients/${patient}/${sourcePaths[kind]}${id ? `/${id}` : ''}`
export const listInterventions = (scope: Scope, patient: string, encounter: string, page: number, signal?: AbortSignal) => request(`${atSite(scope, `${contextPath(patient, encounter)}/interventions`)}&page=${page}&size=30`, pageSchema, { signal })
