import { z } from 'zod'
import { atSite, request, patientSchema, type Scope } from './encounter-api'
export const sessionSchema = z.object({ sessionId: z.uuid(), sessionDate: z.string(), label: z.string(), state: z.string(), version: z.number(), selected: z.boolean(), selectionVersion: z.number() })
export const entrySchema = z.object({ queueEntryId: z.uuid(), patientId: z.uuid(), patient: patientSchema, state: z.string(), encounterId: z.uuid().nullable(), version: z.number(), receivedAt: z.string() })
export type Session = z.infer<typeof sessionSchema>
export type Entry = z.infer<typeof entrySchema>
export const queueApi = {
  sessions: (s: Scope, signal?: AbortSignal) => request(atSite(s, '/sessions'), z.array(sessionSchema), { signal }),
  entries: (s: Scope, id: string, signal?: AbortSignal) => request(atSite(s, `/sessions/${id}/queue-entries`), z.array(entrySchema), { signal }),
  sessionCommand: (s: Scope, suffix: string, body: unknown, key: string) => request(atSite(s, `/sessions${suffix}`), sessionSchema, { method: 'POST', body, key }),
  entryCommand: (s: Scope, session: string, suffix: string, body: unknown, key: string) => request(atSite(s, `/sessions/${session}/queue-entries${suffix}`), entrySchema, { method: 'POST', body, key }),
}
