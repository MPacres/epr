import { z } from 'zod'
import { fetchCsrfToken } from '../../auth/auth-client'
import { answerSchema, definitionSchema } from '../../encounter-templates/schema/contracts'
import type * as Wire from './api.generated'
const uuid = z.uuid()
export const scopeSchema = z.object({ practiceId: uuid, siteId: uuid, practiceName: z.string(), siteName: z.string(), clinicianName: z.string(), patientCapabilities:z.array(z.string()).optional() })
export type Scope = z.infer<typeof scopeSchema>
export const patientSchema = z.object({ patientId: uuid, recordNumber: z.string(), displayName: z.string(), birthDate: z.string().nullable(), identityStatus: z.string(), version: z.number(), birthAccuracy:z.enum(['exact','estimated','unknown']).nullable().optional() })
export type Patient = z.infer<typeof patientSchema>
export const episodeSchema = z.object({ episodeId: uuid, status: z.string(), responsibleClinicianId: uuid, source: z.string(), clinicalTime: z.string().nullable(), selectedDatingId: uuid.nullable(), version: z.number(), createdBy: uuid, receivedAt: z.string() })
export type Episode = z.infer<typeof episodeSchema>
const datingSchema = z.object({ assessmentId: uuid, estimatedDate: z.string().nullable(), sourceDate: z.string().nullable(), sourcePrecision: z.string(), method: z.string(), source: z.string(), reviewStatus: z.string(), createdBy: uuid, receivedAt: z.string() })
const snapshotSchema = z.object({ episode: episodeSchema, selectedDating: datingSchema.nullable() })
export const releaseSchema = z.object({ releaseId: uuid, definition: definitionSchema, checksum: z.string(), approvedBy: uuid, approvedAt: z.string(), approvalBasis: z.string(), publishedBy: uuid, publishedAt: z.string(), retired: z.boolean(), availabilityVersion: z.number() })
export type Release = z.infer<typeof releaseSchema>
export const encounterSchema = z.object({ encounterId: uuid, patientId: uuid, siteId: uuid, responsibleClinicianId: uuid, patient: patientSchema, release: releaseSchema, episodeSnapshot: snapshotSchema.nullable(), careMode: z.string(), careState: z.enum(['IN_PROGRESS', 'PAUSED', 'FINISHED']), noteState: z.enum(['DRAFT', 'READY_TO_SIGN', 'SIGNED']), clinicalTime: z.string(), receivedAt: z.string(), endedAt: z.string().nullable(), version: z.number(), responseRevision: z.number(), unsignedNoteOwner: uuid, reservationFence: z.number(), answers: z.array(answerSchema), savedAt: z.string().nullable(), sessionId: uuid.nullable(), queueEntryId: uuid.nullable() })
export type Encounter = z.infer<typeof encounterSchema>
export class ClinicalRequestError extends Error {
  readonly status: number
  readonly code: string
  constructor(status: number, code: string) {
    super(status === 409 ? 'The record changed or this action conflicts with existing care. Your entered information is still here.' : status === 401 ? 'Your session expired. Sign in again to continue.' : status === 403 || status === 404 ? 'This record is unavailable in your current access scope.' : status === 422 ? 'Review the entered information. The server could not accept this change.' : 'The service could not confirm this action. Keep this page open and retry.')
    this.status = status; this.code = code
  }
}
export async function request<T>(path: string, schema: z.ZodType<T>, options: { method?: string; body?: unknown; key?: string; signal?: AbortSignal } = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (options.method && options.method !== 'GET') { const csrf = await fetchCsrfToken(); headers[csrf.headerName] = csrf.token }
  if (options.key) headers['Idempotency-Key'] = options.key
  let response: Response
  try { response = await fetch(path, { method: options.method, credentials: 'include', cache: 'no-store', headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: options.signal }) }
  catch (error) { if (error instanceof DOMException && error.name === 'AbortError') throw error; throw new ClinicalRequestError(0, 'CONNECTION_UNCONFIRMED') }
  if (!response.ok) { const problem = await response.json().catch(() => ({})); throw new ClinicalRequestError(response.status, typeof problem.code === 'string' ? problem.code : 'REQUEST_FAILED') }
  const result = schema.safeParse(await response.json())
  if (!result.success) throw new ClinicalRequestError(503, 'UNSUPPORTED_RESPONSE_OR_TEMPLATE')
  return result.data
}
const base = (scope: Scope) => `/api/v1/practices/${scope.practiceId}`
export const atSite = (scope: Scope, path: string) => `${base(scope)}${path}?siteId=${scope.siteId}`
export const clinicalApi = {
  scopes: (signal?: AbortSignal) => request('/api/v1/clinical-scopes', z.array(scopeSchema), { signal }),
  patients: (scope: Scope, signal?: AbortSignal) => request(atSite(scope, '/patients'), z.array(patientSchema), { signal }),
  episodes: (scope: Scope, patient: string, signal?: AbortSignal) => request(atSite(scope, `/patients/${patient}/pregnancy-episodes`), z.array(episodeSchema), { signal }),
  createEpisode: (scope: Scope, patient: string, body: Wire.CreateEpisode, key: string) => request(atSite(scope, `/patients/${patient}/pregnancy-episodes`), episodeSchema, { method: 'POST', body, key }),
  templates: (scope: Scope, signal?: AbortSignal) => request(atSite(scope, '/encounter-templates'), z.array(releaseSchema), { signal }),
  encounters: (scope: Scope, patient: string, signal?: AbortSignal) => request(atSite(scope, `/patients/${patient}/encounters`), z.array(encounterSchema), { signal }),
  create: (scope: Scope, patient: string, body: Wire.Create, key: string) => request(atSite(scope, `/patients/${patient}/encounters`), encounterSchema, { method: 'POST', body, key }),
  encounter: (scope: Scope, patient: string, id: string, signal?: AbortSignal) => request(atSite(scope, `/patients/${patient}/encounters/${id}`), encounterSchema, { signal }),
  save: (scope: Scope, patient: string, id: string, body: Wire.Save, key: string, signal?: AbortSignal) => request(atSite(scope, `/patients/${patient}/encounters/${id}/draft`), encounterSchema, { method: 'PUT', body, key, signal }),
  deleteDraft: (scope: Scope, patient: string, id: string, body: Wire.DeleteDraft, key: string) =>
    request(atSite(scope, `/patients/${patient}/encounters/${id}/delete-draft`), z.object({ encounterId: uuid, clinicianId: uuid, fence: z.number() }), { method: 'POST', body, key }),
  transition: (scope: Scope, patient: string, id: string, body: Wire.Transition, key: string) => request(atSite(scope, `/patients/${patient}/encounters/${id}/care-transitions`), encounterSchema, { method: 'POST', body, key }),
}
export const clinicalKey = (scope: Scope, patient?: string, encounter?: string) => ['clinical', scope.practiceId, scope.siteId, patient ?? '', encounter ?? ''] as const

export const patientPath = (scope: Scope, patient: string) => `/clinical/${scope.practiceId}/${scope.siteId}/patients/${patient}`
