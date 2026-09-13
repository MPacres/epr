import { describe, expect, it } from 'vitest'
import { scheduledAppointments, scheduledSessions } from './demo-data'
import { appointmentsForPractice, countAppointmentsBySession, formatScheduleDate, sessionsForPractice, shiftScheduleDate } from './model'

describe('schedule display selectors', () => {
  it('keeps practice schedules separate while allowing an authorized combined view', () => {
    expect(appointmentsForPractice(scheduledAppointments, 'all')).toHaveLength(18)
    expect(appointmentsForPractice(scheduledAppointments, 'practice-a')).toHaveLength(10)
    expect(appointmentsForPractice(scheduledAppointments, 'practice-b')).toHaveLength(8)
    expect(sessionsForPractice(scheduledSessions, 'practice-b').map(session => session.site)).toEqual(['Clinic B'])
  })

  it('counts appointments within their own practice session', () => {
    expect(countAppointmentsBySession(scheduledAppointments)).toEqual({ 'session-a': 10, 'session-b': 8 })
  })

  it('formats and moves the Manila schedule date without a local timezone shift', () => {
    expect(formatScheduleDate('2026-09-12')).toBe('Sat, Sep 12, 2026')
    expect(shiftScheduleDate('2026-09-12', -1)).toBe('2026-09-11')
    expect(shiftScheduleDate('2026-09-12', 1)).toBe('2026-09-13')
  })
})
