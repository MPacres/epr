import type { InboxItem, Patient, PracticeSession, QueueEntry } from './model'

// Explicit synthetic preview boundary. No browser-persisted clinical records.
export const demoDate = '2026-09-12'
export const demoTime = '10:00 AM'
export const doctor = 'Dr. Ana Santos'
// Synthetic physician memberships; independent of which session is active or scheduled.
export const doctorClinics = [
  { practiceId: 'practice-a', site: 'Hospital A', clinic: 'Outpatient Clinic' },
  { practiceId: 'practice-b', site: 'Clinic B', clinic: 'Afternoon clinic' },
]
export const sessions: PracticeSession[] = [
  { id: 'session-a', practiceId: 'practice-a', site: 'Hospital A', clinic: 'Outpatient Clinic', time: '8:00 AM–12:00 PM', date: demoDate, state: 'IN_PROGRESS', appointments: 12 },
  { id: 'session-b', practiceId: 'practice-b', site: 'Clinic B', clinic: 'Afternoon clinic', time: '2:00–5:00 PM', date: demoDate, state: 'OPEN', appointments: 6 },
]
export const activeSession = sessions[0]
export const patients: Patient[] = [
  { id: 'patient-maria', practiceId: 'practice-a', name: 'Maria Dela Cruz', mrn: 'HA-02481', age: 45, initials: 'MC', tone: 'blue' },
  { id: 'patient-jose', practiceId: 'practice-a', name: 'Jose Reyes', mrn: 'HA-01942', age: 52, initials: 'JR', tone: 'blue' },
  { id: 'patient-elena', practiceId: 'practice-a', name: 'Elena Garcia', mrn: 'HA-03108', age: 38, initials: 'EG', tone: 'purple' },
  { id: 'patient-ramon', practiceId: 'practice-a', name: 'Ramon Lim', mrn: 'HA-02276', age: 61, initials: 'RL', tone: 'green' },
  { id: 'patient-sofia', practiceId: 'practice-b', name: 'Sofia Tan', mrn: 'CB-01023', age: 34, initials: 'ST', tone: 'purple' },
]
export const queue: QueueEntry[] = patients.slice(0, 4).map((patient, index) => ({
  id: `queue-${patient.id}`, sessionId: activeSession.id, patient, state: 'READY_FOR_DOCTOR',
  token: `0${index + 7}`, visit: index % 2 === 0 ? 'Follow-up' : 'Consultation',
  arrival: ['9:42 AM', '9:48 AM', '9:51 AM', '9:54 AM'][index], wait: [18, 12, 9, 6][index],
  preparation: 'Preparation complete', encounter: 'Not started',
}))
// Aggregate fixtures for records not exposed in this dashboard preview.
export const sessionSummaries: Record<string, { completed: number; checkedIn: number }> = { 'session-a': { completed: 6, checkedIn: 10 }, 'session-b': { completed: 0, checkedIn: 3 } }
export const inbox: InboxItem[] = [
  { id: 'result-maria', patient: patients[0], site: 'Hospital A', title: 'Review uploaded result', category: 'Results', overdue: true, due: 'Sep 11, 2026', owner: doctor, detail: 'An uploaded external result is awaiting clinician review. Receipt does not imply that the result has been reviewed.' },
  { id: 'followup-sofia', patient: patients[4], site: 'Clinic B', title: 'Complete follow-up', category: 'Follow-ups', overdue: true, due: 'Sep 11, 2026', owner: doctor, detail: 'A scheduled follow-up needs a documented contact outcome and disposition. This responsibility remains with the assigned clinician.' },
  { id: 'note-jose', patient: patients[1], site: 'Hospital A', title: 'Sign consultation note', category: 'Unsigned notes', overdue: false, due: 'Sep 12, 2026', owner: doctor, detail: 'A note from a previous encounter is awaiting review and signature. Signing is separate from ending care or completing a queue entry.' },
  { id: 'result-ramon', patient: patients[3], site: 'Hospital A', title: 'Review external laboratory report', category: 'Results', overdue: false, due: 'Sep 12, 2026', owner: doctor, detail: 'An external laboratory report is awaiting clinician review.' },
  { id: 'note-elena', patient: patients[2], site: 'Hospital A', title: 'Review draft consultation note', category: 'Unsigned notes', overdue: false, due: 'Sep 12, 2026', owner: doctor, detail: 'A previous encounter has a draft note requiring clinician review before signing.' },
  { id: 'note-maria', patient: patients[0], site: 'Hospital A', title: 'Review unsigned encounter note', category: 'Unsigned notes', overdue: false, due: 'Sep 12, 2026', owner: doctor, detail: 'An earlier encounter note remains unsigned. The original encounter and author must be preserved.' },
  { id: 'refill-ramon', patient: patients[3], site: 'Hospital A', title: 'Review medication refill request', category: 'Refills', overdue: false, due: 'Sep 12, 2026', owner: doctor, detail: 'A refill request is awaiting assessment. No prescription has been issued from this preview.' },
]

// Extra directory-only fixtures make Spotlight scrolling testable without changing today's queue.
const directoryNames = [
  'Maria Isabel Santos', 'Maria Teresa Bautista', 'Maria Cristina Ramos', 'Maria Lourdes Mendoza',
  'Marco Antonio Reyes', 'Angela Villanueva', 'Carlo Navarro', 'Patricia Castillo',
  'Gabriel Aquino', 'Isabella Torres', 'Miguel Fernandez', 'Andrea Santiago',
  'Rafael Mercado', 'Camila Soriano', 'Daniel Manalo', 'Beatriz Flores',
  'Paolo Alonzo', 'Teresa Valdez', 'Adrian Salazar', 'Nina Rosario',
  'Luis De Leon', 'Clara Dizon', 'Roberto Pascual', 'Mariana Cruz',
]
export const patientDirectory: Patient[] = [...patients, ...directoryNames.map((name, index) => ({
  id: `demo-directory-${index + 1}`, practiceId: activeSession.practiceId, name,
  mrn: `HA-${String(4000 + index).padStart(5, '0')}`, age: 24 + index,
  initials: `${name[0]}${name.split(' ').at(-1)![0]}`, tone: ['blue', 'green', 'purple'][index % 3],
}))]
