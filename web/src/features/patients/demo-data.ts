import { patientDirectory } from '../dashboard/demo-data'
import type { DirectoryPatient } from './model'

// Synthetic directory details augment existing dashboard identities. No patient data is persisted.
// Dates retain the dashboard's existing ages at the fixed demo date (2026-09-12).
const knownBirthDates: Record<string, string> = {
  'patient-maria': '1981-06-14', 'patient-jose': '1974-03-22', 'patient-elena': '1988-08-11',
  'patient-ramon': '1965-02-03', 'patient-sofia': '1992-07-10',
}
export const directoryPatients: DirectoryPatient[] = [
  ...patientDirectory.map((patient, index): DirectoryPatient => ({
    id: patient.id, practiceId: patient.practiceId, name: patient.name, mrn: patient.mrn, initials: patient.initials,
    birthDate: knownBirthDates[patient.id] ?? (patient.age !== null ? `${2026 - patient.age}-04-${String(index % 25 + 1).padStart(2, '0')}` : null),
    sex: ['patient-maria', 'patient-elena', 'patient-sofia'].includes(patient.id) ? 'Female' : ['patient-jose', 'patient-ramon'].includes(patient.id) ? 'Male' : 'Not recorded',
    city: ['Quezon City', 'Manila', 'Pasig', 'Makati'][index % 4],
    lastVisit: index > 23 ? null : { date: `2026-08-${String(28 - index).padStart(2, '0')}`, type: index % 2 === 0 ? 'Follow-up' : 'Consultation' },
    identity: 'recorded',
  })),
  { id: 'demo-provisional', practiceId: 'practice-a', name: 'Alex Ramos', mrn: 'HA-04025', initials: 'AR', birthDate: null, sex: 'Not recorded', city: null, lastVisit: null, identity: 'provisional' },
  { id: 'demo-jose-two', practiceId: 'practice-a', name: 'Jose Reyes', mrn: 'HA-04026', initials: 'JR', birthDate: '1990-11-02', sex: 'Male', city: 'Pasig', lastVisit: null, identity: 'recorded' },
  { id: 'demo-maria-b', practiceId: 'practice-b', name: 'Maria Dela Cruz', mrn: 'CB-01024', initials: 'MC', birthDate: '1981-06-14', sex: 'Female', city: 'Quezon City', lastVisit: { date: '2026-09-01', type: 'Consultation' }, identity: 'recorded' },
  { id: 'demo-andres-b', practiceId: 'practice-b', name: 'Andres Villanueva', mrn: 'CB-01025', initials: 'AV', birthDate: '1978-12-10', sex: 'Male', city: 'Marikina', lastVisit: null, identity: 'recorded' },
]
