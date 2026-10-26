import type { Encounter } from './services/encounter-api'

export function groupEncounterHistory<T extends Pick<Encounter, 'noteState' | 'clinicalTime'>>(encounters: readonly T[]) {
  const ordered = [...encounters].sort((a, b) => b.clinicalTime.localeCompare(a.clinicalTime))
  return { drafts: ordered.filter(item => item.noteState !== 'SIGNED'), signed: ordered.filter(item => item.noteState === 'SIGNED') }
}
