import type { DirectoryPatient } from '../patients/model'

export const chartTabs = ['Overview', 'Encounters', 'Medications', 'Orders & Results', 'Care Plan', 'Referrals', 'Documents', 'Patient Details'] as const
export type ChartTab = typeof chartTabs[number]
export interface ChartEvent { id: string; date: string; title: string; category: 'Encounters' | 'Results' | 'Medications'; detail: string; target: ChartTab; encounterId?: string }
export interface EncounterNote { reason: string; history: string; examination: string; assessment: string; plan: string; disposition: string; followup: string; precautions: string; diagnoses: string[] }
export interface DemoEncounter { id: string; date: string; care: 'Finished'; documentation: 'Draft' | 'Signed'; template: string; reason: string }
// Explicit synthetic clinical fixtures. These are never persisted or shared across practices.
const encounters: readonly DemoEncounter[] = [
  { id: 'EN-DEMO-006', date: '2026-08-28', care: 'Finished', documentation: 'Draft', template: 'General outpatient · v1.2', reason: 'Follow-up for hypertension and diabetes' },
  { id: 'EN-DEMO-005', date: '2026-08-15', care: 'Finished', documentation: 'Signed', template: 'General outpatient · v1.1', reason: 'Follow-up consultation' },
]
export function findChartPatient(records: readonly DirectoryPatient[], allowedPractices: readonly string[], practiceId: string, patientId: string) {
  if (!allowedPractices.includes(practiceId)) return undefined
  return records.find(record => record.practiceId === practiceId && record.id === patientId)
}
export function hasClinicalFixture(patient: Pick<DirectoryPatient, 'practiceId' | 'id'>) { return patient.practiceId === 'practice-a' && patient.id === 'patient-maria' }
export function patientEncounters(patient: Pick<DirectoryPatient, 'practiceId' | 'id'>) { return hasClinicalFixture(patient) ? encounters : [] }
export function findEncounter(patient: Pick<DirectoryPatient, 'practiceId' | 'id'>, id: string) { return patientEncounters(patient).find(item => item.id === id) }
export function initialNote(encounter: DemoEncounter): EncounterNote { return { reason: encounter.reason, history: '', examination: '', assessment: '', plan: '', disposition: '', followup: '', precautions: '', diagnoses: [] } }
export function noteChanged(note: EncounterNote, baseline: EncounterNote) { return JSON.stringify(note) !== JSON.stringify(baseline) }
export function chartEvents(patient: Pick<DirectoryPatient, 'practiceId' | 'id'>): ChartEvent[] {
  if (!hasClinicalFixture(patient)) return []
  return [
    { id: 'result-maria', date: '2026-09-11', title: 'Uploaded laboratory report', category: 'Results', detail: 'External report · Awaiting clinician review', target: 'Orders & Results' },
    ...encounters.map(item => ({ id: item.id, date: item.date, title: item.reason, category: 'Encounters' as const, detail: `${item.documentation} note · Dr. Ana Santos · Care finished`, target: 'Encounters' as const, encounterId: item.id })),
    { id: 'medications-maria', date: '2026-08-15', title: 'Medication list recorded', category: 'Medications', detail: 'Patient reported · Reconciliation due', target: 'Medications' },
  ]
}
export function filterEvents(events: readonly ChartEvent[], query: string, category: string) {
  const words = query.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean)
  return events.filter(event => (category === 'All' || event.category === category) && words.every(word => `${event.title} ${event.detail} ${event.date}`.toLocaleLowerCase().includes(word)))
}
export const chartPath = (patient: Pick<DirectoryPatient, 'practiceId' | 'id'>) => `/patients/${encodeURIComponent(patient.practiceId)}/${encodeURIComponent(patient.id)}/chart`
export const encounterPath = (patient: Pick<DirectoryPatient, 'practiceId' | 'id'>, id: string) => `/patients/${encodeURIComponent(patient.practiceId)}/${encodeURIComponent(patient.id)}/encounters/${encodeURIComponent(id)}`
