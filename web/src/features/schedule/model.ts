export type SchedulePracticeFilter = 'all' | 'practice-a' | 'practice-b'
export type BookingState = 'CONFIRMED' | 'PENDING_CONFIRMATION'
export type ArrivalState = 'EXPECTED' | 'CHECKED_IN'

export interface ScheduledPatient {
  id: string
  practiceId: string
  name: string
  mrn: string
  initials: string
  birthDate: string | null
}

export interface ScheduledAppointment {
  id: string
  sessionId: string
  practiceId: string
  patient: ScheduledPatient
  start: string
  end: string
  visit: 'Consultation' | 'Follow-up'
  booking: BookingState
  arrival: ArrivalState
  queue?: { token: string; state: 'READY_FOR_DOCTOR' }
  initiallyHidden?: boolean
}

export interface ScheduledSession {
  id: string
  practiceId: 'practice-a' | 'practice-b'
  site: string
  clinic: string
  date: string
  start: string
  end: string
  state: 'IN_PROGRESS' | 'OPEN'
  accent: 'blue' | 'purple'
}

export function appointmentsForPractice(appointments: readonly ScheduledAppointment[], practice: SchedulePracticeFilter) {
  return practice === 'all' ? [...appointments] : appointments.filter(appointment => appointment.practiceId === practice)
}

export function sessionsForPractice(sessions: readonly ScheduledSession[], practice: SchedulePracticeFilter) {
  return practice === 'all' ? [...sessions] : sessions.filter(session => session.practiceId === practice)
}

export function countAppointmentsBySession(appointments: readonly ScheduledAppointment[]) {
  return appointments.reduce<Record<string, number>>((counts, appointment) => {
    counts[appointment.sessionId] = (counts[appointment.sessionId] ?? 0) + 1
    return counts
  }, {})
}

export function formatScheduleDate(value: string) {
  return new Intl.DateTimeFormat('en-US', {
    weekday: 'short', month: 'short', day: 'numeric', year: 'numeric', timeZone: 'Asia/Manila',
  }).format(new Date(`${value}T00:00:00+08:00`))
}

export function shiftScheduleDate(value: string, days: number) {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}
