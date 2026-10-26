import { z } from 'zod'
import type { Scope } from './encounter-api'

export const encounterStartSchema = z.object({
  releaseId: z.uuid('Select a template release.'),
  episodeId: z.string(),
  careMode: z.enum(['IN_PERSON', 'TELEPHONE', 'TELECONSULTATION']),
  serviceDate: z.iso.date('Enter a valid date of service.'),
  serviceTime: z.iso.time({ precision: -1, error: 'Enter the time of service.' }),
})
export type EncounterStartValues = z.infer<typeof encounterStartSchema>

export function serviceDateTime(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(now)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(value => value.type === type)!.value
  return { serviceDate: `${part('year')}-${part('month')}-${part('day')}`, serviceTime: `${part('hour')}:${part('minute')}` }
}

export function clinicalTimeForService(serviceDate: string, serviceTime: string) {
  return `${serviceDate}T${serviceTime}:00+08:00`
}

// A location change must never move a chart into another Practice tenant.
export function encounterLocations(scopes: readonly Scope[], practiceId: string) {
  return scopes.filter(scope => scope.practiceId === practiceId)
}
