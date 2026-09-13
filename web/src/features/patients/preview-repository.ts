import { directoryPatients } from './demo-data'
import { patientKey } from './model'
import { duplicateCandidates, effectiveDraft, fullPatientName, seedPatientRecord, type DemoPatientRecord, type PatientDraft } from './patient-form-model'

export type DemoOutcome = 'success' | 'unavailable' | 'conflict'
export interface DemoSaveCommand {
  practiceId: string; patientId: string; expectedVersion: number | null; idempotencyKey: string
  draft: PatientDraft; duplicateReview: string | null
}
export interface DemoHistoryEntry { patientKey: string; before: DemoPatientRecord | null; after: DemoPatientRecord; note: string }
export class PreviewSaveError extends Error {
  code: 'forbidden' | 'missing' | 'conflict' | 'unavailable' | 'duplicate-review'
  constructor(code: PreviewSaveError['code'], message: string) { super(message); this.code = code }
}
export function duplicateReviewKey(records: readonly DemoPatientRecord[], draft: PatientDraft, practiceId: string, excludeId?: string) {
  const candidates = duplicateCandidates(records, draft, practiceId, excludeId)
  return candidates.length ? JSON.stringify([practiceId, fullPatientName(draft), draft.birthDate, draft.birthAccuracy, candidates.map(({ patient }) => [patientKey(patient), records.find(record => patientKey(record) === patientKey(patient))?.version])]) : null
}
// Explicit in-memory fixture adapter. This does not implement server authorization,
// durable audit, registration, or offline persistence. Replaced by an API repository later.
export class PatientPreviewRepository {
  private records: DemoPatientRecord[]
  private completed = new Map<string, { fingerprint: string; record: DemoPatientRecord }>()
  private history: DemoHistoryEntry[] = []
  private allowedPractices: readonly string[]
  constructor(allowedPractices: readonly string[], seed = directoryPatients) { this.allowedPractices = allowedPractices; this.records = seed.filter(record => allowedPractices.includes(record.practiceId)).map(seedPatientRecord) }
  snapshot() { return this.records }
  historyFor(practiceId: string, id: string) { return this.allowedPractices.includes(practiceId) ? this.history.filter(entry => entry.patientKey === `${practiceId}:${id}`) : [] }
  async save(command: DemoSaveCommand, outcome: DemoOutcome = 'success'): Promise<DemoPatientRecord> {
    if (!this.allowedPractices.includes(command.practiceId)) throw new PreviewSaveError('forbidden', 'This practice is not available. Your draft has been kept.')
    const { patientDraftSchema } = await import('./patient-form-schema')
    const draft = effectiveDraft(patientDraftSchema.parse(command.draft))
    const fingerprint = JSON.stringify({ ...command, draft })
    const retryKey = `${command.practiceId}:${command.idempotencyKey}`
    const completed = this.completed.get(retryKey)
    if (completed) {
      if (completed.fingerprint !== fingerprint) throw new PreviewSaveError('conflict', 'This retry contains different changes. Review your draft and try again.')
      return completed.record
    }
    if (outcome === 'unavailable') throw new PreviewSaveError('unavailable', 'Simulated connection failure. Nothing was applied; your draft is still here. Choose Normal save and retry.')
    if (outcome === 'conflict') throw new PreviewSaveError('conflict', 'Simulated version conflict. Nothing was overwritten; your draft is still here. Choose Normal save to continue testing.')
    const before = this.records.find(record => record.id === command.patientId && record.practiceId === command.practiceId)
    if (command.expectedVersion !== null && !before) throw new PreviewSaveError('missing', 'This patient record is no longer available. Your draft has been kept.')
    if ((before?.version ?? null) !== command.expectedVersion) throw new PreviewSaveError('conflict', 'The patient record changed after you opened it. Your draft has been kept. Cancel and reopen the patient to review the latest version.')
    const review = duplicateReviewKey(this.records, draft, command.practiceId, before?.id)
    if (review && command.duplicateReview !== review) throw new PreviewSaveError('duplicate-review', 'Review the possible matching records before saving this separate patient record.')
    const name = fullPatientName(draft)
    const record: DemoPatientRecord = {
      id: command.patientId, practiceId: command.practiceId,
      mrn: before?.mrn ?? `DEMO-${command.practiceId === 'practice-a' ? 'HA' : 'CB'}-${String(this.records.filter(record => record.practiceId === command.practiceId).length + 1).padStart(4, '0')}`,
      name, initials: `${name[0]}${name.split(' ').at(-1)?.[0] ?? ''}`,
      birthDate: draft.birthDate || null, birthDateAccuracy: draft.birthAccuracy,
      sex: draft.sex, city: draft.city || null, identity: draft.provisional ? 'provisional' : 'recorded',
      lastVisit: before?.lastVisit ?? null, version: (before?.version ?? 0) + 1, draft: { ...draft, changeNote: '' },
    }
    this.records = before ? this.records.map(item => patientKey(item) === patientKey(record) ? record : item) : [...this.records, record]
    this.history = [...this.history, { patientKey: patientKey(record), before: before ?? null, after: record, note: draft.changeNote }]
    this.completed.set(retryKey, { fingerprint, record })
    return record
  }
}
