export interface DirectoryPatient {
  id: string
  practiceId: string
  name: string
  mrn: string
  initials: string
  birthDate: string | null
  birthDateAccuracy?: 'exact' | 'estimated' | 'unknown'
  sex: 'Female' | 'Male' | 'Not recorded'
  city: string | null
  lastVisit: { date: string; type: string } | null
  identity: 'recorded' | 'provisional'
}
export type DirectoryTab = 'directory' | 'recent'
export type VisitFilter = 'all' | 'visited' | 'never'
export type IdentityFilter = 'all' | 'provisional'
export type PatientSort = 'last-visit' | 'name' | 'oldest-visit'
export interface DirectoryQuery {
  practiceId: string
  allowedPracticeIds: readonly string[]
  query: string
  tab: DirectoryTab
  recentKeys: readonly string[]
  visit: VisitFilter
  identity: IdentityFilter
  sort: PatientSort
}
export const patientKey = (patient: Pick<DirectoryPatient, 'practiceId' | 'id'>) => `${patient.practiceId}:${patient.id}`
const normalize = (value: string) => value.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
export function formatPatientDate(value: string | null) {
  if (!value) return 'Not recorded'
  if (!Number.isFinite(new Date(`${value}T00:00:00Z`).getTime())) return 'Invalid date'
  return new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${value}T00:00:00Z`))
}
export function ageAt(birthDate: string | null, today: string) {
  if (!birthDate || birthDate > today) return null
  const [year, month, day] = birthDate.split('-').map(Number)
  const [nowYear, nowMonth, nowDay] = today.split('-').map(Number)
  return nowYear - year - (nowMonth < month || (nowMonth === month && nowDay < day) ? 1 : 0)
}
// Fixture display scoping only. Real authorization must be enforced by the service.
export function selectDirectory(patients: readonly DirectoryPatient[], options: DirectoryQuery) {
  if (!options.allowedPracticeIds.includes(options.practiceId)) return []
  const tokens = normalize(options.query).split(' ').filter(Boolean)
  const recent = new Map(options.recentKeys.map((key, index) => [key, index]))
  return patients.filter(patient => {
    if (patient.practiceId !== options.practiceId) return false
    if (options.tab === 'recent' && !recent.has(patientKey(patient))) return false
    if (options.visit === 'visited' && !patient.lastVisit) return false
    if (options.visit === 'never' && patient.lastVisit) return false
    if (options.identity === 'provisional' && patient.identity !== 'provisional') return false
    const searchable = normalize(`${patient.name} ${patient.mrn} ${patient.birthDate ?? ''} ${patient.birthDate ? formatPatientDate(patient.birthDate) : ''}`)
    return tokens.every(token => searchable.includes(token))
  }).sort((a, b) => {
    if (options.tab === 'recent') return recent.get(patientKey(a))! - recent.get(patientKey(b))!
    if (options.sort === 'name') return a.name.localeCompare(b.name) || a.mrn.localeCompare(b.mrn)
    // Missing visit dates remain last in either date direction.
    if (!a.lastVisit || !b.lastVisit) return Number(!a.lastVisit) - Number(!b.lastVisit) || a.name.localeCompare(b.name)
    return (options.sort === 'last-visit' ? b.lastVisit.date.localeCompare(a.lastVisit.date) : a.lastVisit.date.localeCompare(b.lastVisit.date)) || a.name.localeCompare(b.name)
  })
}
export function directoryPage(patients: readonly DirectoryPatient[], requestedPage: number, pageSize = 6) {
  const pageCount = Math.max(1, Math.ceil(patients.length / pageSize))
  const page = Math.min(Math.max(1, requestedPage), pageCount)
  const start = (page - 1) * pageSize
  return { page, pageCount, start: patients.length ? start + 1 : 0, end: Math.min(start + pageSize, patients.length), patients: patients.slice(start, start + pageSize) }
}
