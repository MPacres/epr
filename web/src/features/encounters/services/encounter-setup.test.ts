import { describe, expect, it } from 'vitest'
import { clinicalTimeForService, encounterLocations, encounterStartSchema, serviceDateTime } from './encounter-setup'

describe('patient-scoped encounter setup', () => {
  it('offers only locations in the selected patient’s Practice', () => {
    const locations = [
      { practiceId: 'practice-a', siteId: 'site-a', practiceName: 'A', siteName: 'Clinic A', clinicianName: 'Doctor' },
      { practiceId: 'practice-a', siteId: 'site-b', practiceName: 'A', siteName: 'Clinic B', clinicianName: 'Doctor' },
      { practiceId: 'practice-b', siteId: 'site-c', practiceName: 'B', siteName: 'Clinic C', clinicianName: 'Doctor' },
    ]
    expect(encounterLocations(locations, 'practice-a').map(scope => scope.siteId)).toEqual(['site-a', 'site-b'])
    expect(encounterLocations(locations, 'unavailable')).toEqual([])
  })

  it('defaults service date and time in Manila, including the next calendar day', () => {
    expect(serviceDateTime(new Date('2026-09-19T16:05:00Z'))).toEqual({ serviceDate: '2026-09-20', serviceTime: '00:05' })
    expect(serviceDateTime(new Date('2026-09-19T00:00:00Z'))).toEqual({ serviceDate: '2026-09-19', serviceTime: '08:00' })
  })

  it('preserves the selected service date in the API occurrence timestamp', () => {
    expect(clinicalTimeForService('2026-09-18', '23:45')).toBe('2026-09-18T23:45:00+08:00')
  })

  it('requires a real calendar date and a valid service time', () => {
    const command = { releaseId: '11111111-1111-4111-8111-111111111111', episodeId: '', careMode: 'IN_PERSON', serviceDate: '2026-09-19', serviceTime: '09:30' }
    expect(encounterStartSchema.safeParse(command).success).toBe(true)
    for (const serviceDate of ['', '2026-02-30', '2026-13-01']) {
      expect(encounterStartSchema.safeParse({ ...command, serviceDate }).success).toBe(false)
    }
    for (const serviceTime of ['', '24:01', '09:60']) {
      expect(encounterStartSchema.safeParse({ ...command, serviceTime }).success).toBe(false)
    }
  })
})
