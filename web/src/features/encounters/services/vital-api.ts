import { z } from 'zod'
import { atSite, request, type Scope } from './encounter-api'

const valuesSchema = z.object({
  systolicMmhg: z.number().nullable(), diastolicMmhg: z.number().nullable(),
  pulseBpm: z.number().nullable(), respiratoryRateBpm: z.number().nullable(),
  temperatureC: z.number().nullable(), oxygenSaturationPercent: z.number().nullable(),
  weightKg: z.number().nullable(), heightCm: z.number().nullable(),
})
const revisionSchema = z.object({ version: z.number(), measuredAt: z.string(), source: z.string(),
  values: valuesSchema, correctionReason: z.string().nullable(), recordedBy: z.uuid(), receivedAt: z.string() })
const vitalSetSchema = z.object({ vitalSetId: z.uuid(), current: revisionSchema, history: z.array(revisionSchema) })
export type VitalSet = z.infer<typeof vitalSetSchema>
export type VitalValues = z.infer<typeof valuesSchema>
export type VitalCommand = { measuredAt: string; source: string; values: VitalValues }
const path = (scope: Scope, patient: string, encounter: string, suffix = '') =>
  atSite(scope, `/patients/${patient}/encounters/${encounter}/vital-sets${suffix}`)
export const vitalKey = (scope: Scope, patient: string, encounter: string) =>
  ['encounter-vitals', scope.practiceId, scope.siteId, patient, encounter] as const
export const vitalApi = {
  list: (scope: Scope, patient: string, encounter: string, signal?: AbortSignal) =>
    request(path(scope, patient, encounter), z.array(vitalSetSchema), { signal }),
  create: (scope: Scope, patient: string, encounter: string, key: string, body: VitalCommand & { vitalSetId: string }) =>
    request(path(scope, patient, encounter), vitalSetSchema, { method: 'POST', key, body }),
  correct: (scope: Scope, patient: string, encounter: string, id: string, key: string, body: VitalCommand & { expectedVersion: number; reason: string }) =>
    request(path(scope, patient, encounter, `/${id}/corrections`), vitalSetSchema, { method: 'POST', key, body }),
}
