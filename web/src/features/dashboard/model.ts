export type QueueState = 'READY_FOR_DOCTOR' | 'CHECKED_IN' | 'COMPLETED'
export type InboxCategory = 'Results' | 'Unsigned notes' | 'Follow-ups' | 'Refills'
export interface Patient {
  birthDateAccuracy?: 'exact' | 'estimated' | 'unknown'
  id: string; practiceId: string; name: string; mrn: string; age: number | null; initials: string; tone: string
}
export interface QueueEntry {
  id: string; sessionId: string; patient: Patient; state: QueueState; token: string
  visit: string; arrival: string; wait: number; preparation: string; encounter: string
}
export interface PracticeSession {
  id: string; practiceId: string; site: string; clinic: string; time: string
  date: string; state: 'IN_PROGRESS' | 'OPEN'; appointments: number
}
export interface InboxItem {
  id: string; patient: Patient; site: string; title: string; category: InboxCategory
  overdue: boolean; due: string; owner: string; detail: string
}
// Display helpers are never an authorization boundary.
export function readyEntries(entries: QueueEntry[], session: PracticeSession | undefined) {
  return session ? entries.filter(entry => entry.sessionId === session.id && entry.patient.practiceId === session.practiceId && entry.state === 'READY_FOR_DOCTOR') : []
}
export function distinctInbox(items: InboxItem[]) {
  return [...new Map(items.map(item => [`${item.patient.practiceId}:${item.id}`, item])).values()]
}
export function searchPatients(patients: Patient[], practiceIds: readonly string[], query: string) {
  const text = query.trim().toLocaleLowerCase()
  return patients.filter(patient => practiceIds.includes(patient.practiceId) && (!text || `${patient.name} ${patient.mrn}`.toLocaleLowerCase().includes(text)))
}

export function patientAgeLabel(patient: Patient) {
  return patient.age === null ? 'Age not recorded' : `${patient.birthDateAccuracy === 'estimated' ? 'About ' : ''}${patient.age} years`
}
