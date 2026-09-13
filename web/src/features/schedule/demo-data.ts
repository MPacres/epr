import type { ScheduledAppointment, ScheduledPatient, ScheduledSession } from './model'

// Explicit synthetic scheduling fixtures. No appointment changes are persisted.
export const scheduleDemoDate = '2026-09-12'

const patients: Record<string, ScheduledPatient> = {
  maria: { id: 'patient-maria', practiceId: 'practice-a', name: 'Maria Dela Cruz', mrn: 'HA-02481', initials: 'MC', birthDate: '1981-06-14' },
  jose: { id: 'patient-jose', practiceId: 'practice-a', name: 'Jose Reyes', mrn: 'HA-01942', initials: 'JR', birthDate: '1974-03-22' },
  elena: { id: 'patient-elena', practiceId: 'practice-a', name: 'Elena Garcia', mrn: 'HA-03108', initials: 'EG', birthDate: '1988-08-11' },
  ramon: { id: 'patient-ramon', practiceId: 'practice-a', name: 'Ramon Lim', mrn: 'HA-02276', initials: 'RL', birthDate: '1965-02-03' },
  patricia: { id: 'demo-directory-6', practiceId: 'practice-a', name: 'Patricia Castillo', mrn: 'HA-04005', initials: 'PC', birthDate: '1996-04-06' },
  andres: { id: 'demo-directory-3', practiceId: 'practice-a', name: 'Andres Villanueva', mrn: 'HA-03012', initials: 'AV', birthDate: '1978-12-10' },
  gabriel: { id: 'demo-directory-8', practiceId: 'practice-a', name: 'Gabriel Aquino', mrn: 'HA-04008', initials: 'GA', birthDate: '1994-04-09' },
  isabella: { id: 'demo-directory-9', practiceId: 'practice-a', name: 'Isabella Torres', mrn: 'HA-04009', initials: 'IT', birthDate: '1993-04-10' },
  miguel: { id: 'demo-directory-10', practiceId: 'practice-a', name: 'Miguel Fernandez', mrn: 'HA-04010', initials: 'MF', birthDate: '1992-04-11' },
  andrea: { id: 'demo-directory-11', practiceId: 'practice-a', name: 'Andrea Santiago', mrn: 'HA-04011', initials: 'AS', birthDate: '1991-04-12' },
  sofia: { id: 'patient-sofia', practiceId: 'practice-b', name: 'Sofia Tan', mrn: 'CB-01023', initials: 'ST', birthDate: '1992-07-10' },
  luis: { id: 'demo-directory-18', practiceId: 'practice-b', name: 'Luis De Leon', mrn: 'CB-01408', initials: 'LD', birthDate: '1988-04-19' },
  carla: { id: 'schedule-carla', practiceId: 'practice-b', name: 'Carla Navarro', mrn: 'CB-00917', initials: 'CN', birthDate: '1995-04-08' },
  beatriz: { id: 'demo-directory-15', practiceId: 'practice-b', name: 'Beatriz Flores', mrn: 'CB-01204', initials: 'BF', birthDate: '1987-04-16' },
  nina: { id: 'demo-directory-21', practiceId: 'practice-b', name: 'Nina Rosario', mrn: 'CB-01176', initials: 'NR', birthDate: '1981-04-22' },
  adrian: { id: 'demo-directory-20', practiceId: 'practice-b', name: 'Adrian Salazar', mrn: 'CB-01311', initials: 'AS', birthDate: '1982-04-21' },
  clara: { id: 'demo-directory-19', practiceId: 'practice-b', name: 'Clara Dizon', mrn: 'CB-01088', initials: 'CD', birthDate: '1983-04-20' },
  paolo: { id: 'demo-directory-16', practiceId: 'practice-b', name: 'Paolo Alonzo', mrn: 'CB-01502', initials: 'PA', birthDate: '1986-04-17' },
}

export const scheduledSessions: ScheduledSession[] = [
  { id: 'session-a', practiceId: 'practice-a', site: 'Hospital A', clinic: 'Outpatient Clinic', date: scheduleDemoDate, start: '8:00 AM', end: '12:00 PM', state: 'IN_PROGRESS', accent: 'blue' },
  { id: 'session-b', practiceId: 'practice-b', site: 'Clinic B', clinic: 'Afternoon clinic', date: scheduleDemoDate, start: '2:00 PM', end: '5:00 PM', state: 'OPEN', accent: 'purple' },
]

const appointment = (
  id: string,
  sessionId: 'session-a' | 'session-b',
  patient: ScheduledPatient,
  start: string,
  end: string,
  visit: ScheduledAppointment['visit'],
  booking: ScheduledAppointment['booking'] = 'CONFIRMED',
  extra: Pick<ScheduledAppointment, 'arrival' | 'queue' | 'initiallyHidden'> = { arrival: 'EXPECTED' },
): ScheduledAppointment => ({ id, sessionId, practiceId: patient.practiceId, patient, start, end, visit, booking, ...extra })

export const scheduledAppointments: ScheduledAppointment[] = [
  appointment('apt-a-0800', 'session-a', patients.jose, '8:00 AM', '8:20 AM', 'Consultation', 'CONFIRMED', { arrival: 'CHECKED_IN', initiallyHidden: true }),
  appointment('apt-a-0820', 'session-a', patients.ramon, '8:20 AM', '8:40 AM', 'Follow-up', 'CONFIRMED', { arrival: 'CHECKED_IN', initiallyHidden: true }),
  appointment('apt-a-0840', 'session-a', patients.patricia, '8:40 AM', '9:00 AM', 'Consultation', 'CONFIRMED', { arrival: 'CHECKED_IN', initiallyHidden: true }),
  appointment('apt-a-0900', 'session-a', patients.gabriel, '9:00 AM', '9:20 AM', 'Follow-up', 'CONFIRMED', { arrival: 'CHECKED_IN', initiallyHidden: true }),
  appointment('apt-a-0920', 'session-a', patients.isabella, '9:20 AM', '9:40 AM', 'Consultation', 'CONFIRMED', { arrival: 'CHECKED_IN', initiallyHidden: true }),
  appointment('apt-a-0940', 'session-a', patients.miguel, '9:40 AM', '10:00 AM', 'Follow-up', 'CONFIRMED', { arrival: 'CHECKED_IN', initiallyHidden: true }),
  appointment('apt-a-1000', 'session-a', patients.maria, '10:00 AM', '10:20 AM', 'Follow-up', 'CONFIRMED', { arrival: 'CHECKED_IN', queue: { token: '07', state: 'READY_FOR_DOCTOR' } }),
  appointment('apt-a-1020', 'session-a', patients.elena, '10:20 AM', '10:40 AM', 'Follow-up', 'CONFIRMED', { arrival: 'CHECKED_IN' }),
  appointment('apt-a-1040', 'session-a', patients.andrea, '10:40 AM', '11:00 AM', 'Consultation', 'CONFIRMED', { arrival: 'EXPECTED' }),
  appointment('apt-a-1100', 'session-a', patients.andres, '11:00 AM', '11:20 AM', 'Consultation', 'PENDING_CONFIRMATION', { arrival: 'EXPECTED' }),
  appointment('apt-b-1400', 'session-b', patients.sofia, '2:00 PM', '2:20 PM', 'Follow-up'),
  appointment('apt-b-1420', 'session-b', patients.luis, '2:20 PM', '2:40 PM', 'Consultation'),
  appointment('apt-b-1440', 'session-b', patients.carla, '2:40 PM', '3:00 PM', 'Follow-up', 'PENDING_CONFIRMATION'),
  appointment('apt-b-1500', 'session-b', patients.beatriz, '3:00 PM', '3:20 PM', 'Consultation', 'CONFIRMED', { arrival: 'EXPECTED', initiallyHidden: true }),
  appointment('apt-b-1520', 'session-b', patients.nina, '3:20 PM', '3:40 PM', 'Follow-up', 'CONFIRMED', { arrival: 'EXPECTED', initiallyHidden: true }),
  appointment('apt-b-1540', 'session-b', patients.adrian, '3:40 PM', '4:00 PM', 'Consultation', 'CONFIRMED', { arrival: 'EXPECTED', initiallyHidden: true }),
  appointment('apt-b-1600', 'session-b', patients.clara, '4:00 PM', '4:20 PM', 'Follow-up', 'CONFIRMED', { arrival: 'EXPECTED', initiallyHidden: true }),
  appointment('apt-b-1620', 'session-b', patients.paolo, '4:20 PM', '4:40 PM', 'Consultation', 'CONFIRMED', { arrival: 'EXPECTED', initiallyHidden: true }),
]
