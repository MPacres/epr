import { describe, expect, it } from 'vitest'
import { directoryPatients } from './demo-data'
import { ageAt, directoryPage, formatPatientDate, patientKey, selectDirectory, type DirectoryQuery } from './model'

const defaults: DirectoryQuery = { practiceId: 'practice-a', allowedPracticeIds: ['practice-a', 'practice-b'], query: '', tab: 'directory', recentKeys: [], visit: 'all', identity: 'all', sort: 'last-visit' }
const select = (options: Partial<DirectoryQuery> = {}) => selectDirectory(directoryPatients, { ...defaults, ...options })
describe('patient directory display scoping and identity', () => {
  it('keeps same-name records in different practices separate', () => {
    expect(select({ query: 'Maria Dela Cruz' }).map(patient => patient.mrn)).toEqual(['HA-02481'])
    expect(select({ query: 'Maria Dela Cruz', practiceId: 'practice-b' }).map(patient => patient.mrn)).toEqual(['CB-01024'])
    expect(select({ query: 'CB-01024' })).toEqual([])
  })
  it('fails closed for an unavailable practice, including recent records', () => {
    expect(select({ practiceId: 'practice-b', allowedPracticeIds: ['practice-a'] })).toEqual([])
    expect(select({ practiceId: 'unknown' })).toEqual([])
    expect(select({ tab: 'recent', recentKeys: ['practice-b:demo-maria-b'] })).toEqual([])
  })
  it('retains distinct records with the same name within a practice', () => {
    expect(select({ query: '  REYES, José  ' }).map(patient => patient.mrn)).toEqual(['HA-01942', 'HA-04026'])
  })
  it('searches record numbers and date-only birth dates in ISO and display formats', () => {
    for (const query of ['ha 02481', '1981-06-14', '14 Jun 1981']) expect(select({ query }).map(patient => patient.id)).toEqual(['patient-maria'])
  })
  it('keeps unknown identity facts unknown and separate from no visits', () => {
    const results = select({ identity: 'provisional', visit: 'never' })
    expect(results).toHaveLength(1)
    expect(results[0].birthDate).toBeNull()
    expect(formatPatientDate(results[0].birthDate)).toBe('Not recorded')
    expect(ageAt(results[0].birthDate, '2026-09-12')).toBeNull()
    expect(select({ identity: 'provisional', visit: 'visited' })).toEqual([])
  })
  it('calculates age using date-only birthday boundaries', () => {
    expect(ageAt('1981-09-13', '2026-09-12')).toBe(44)
    expect(ageAt('1981-09-12', '2026-09-12')).toBe(45)
    expect(ageAt('2027-01-01', '2026-09-12')).toBeNull()
    expect(formatPatientDate('1981-06-14')).toBe('14 Jun 1981')
  })
  it('sorts visits with missing dates last in both directions without changing fixtures', () => {
    const before = directoryPatients.map(patientKey)
    expect(select()[0].id).toBe('patient-maria')
    for (const sort of ['last-visit', 'oldest-visit'] as const) {
      const results = select({ sort })
      const firstMissing = results.findIndex(patient => !patient.lastVisit)
      expect(results.slice(firstMissing).every(patient => !patient.lastVisit)).toBe(true)
    }
    expect(directoryPatients.map(patientKey)).toEqual(before)
  })
  it('uses opened order for recents, without making unopened records recent', () => {
    expect(select({ tab: 'recent' })).toEqual([])
    expect(select({ tab: 'recent', recentKeys: ['practice-a:patient-jose', 'practice-b:demo-maria-b', 'practice-a:patient-maria'] }).map(patient => patient.id)).toEqual(['patient-jose', 'patient-maria'])
  })
  it('clamps stale pages after filtering and does not expose records from a previous scope', () => {
    const page = directoryPage(select({ practiceId: 'practice-b' }), 5)
    expect(page.page).toBe(1)
    expect(page.patients.every(patient => patient.practiceId === 'practice-b')).toBe(true)
    expect(directoryPage([], 5)).toEqual({ page: 1, pageCount: 1, start: 0, end: 0, patients: [] })
  })
})
