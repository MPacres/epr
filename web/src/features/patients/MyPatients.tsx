import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Building2, CalendarDays, ChevronLeft, ChevronRight, Clock3, Plus, Search, ShieldCheck, SlidersHorizontal, Users, X } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { activeSession, demoDate, doctorClinics } from '../dashboard/demo-data'
import { usePatientPreview } from './patient-preview-context'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { directoryPage, formatPatientDate, patientKey, selectDirectory, type DirectoryPatient, type DirectoryTab, type IdentityFilter, type PatientSort, type VisitFilter } from './model'
import { PatientDetails } from './PatientDetails'
import './patients.css'

const allowedPracticeIds = doctorClinics.map(clinic => clinic.practiceId)
const compactQuery = '(max-width: 1100px)'
export function MyPatients() {
  const { records: directoryPatients } = usePatientPreview()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const initialPractice = allowedPracticeIds.includes(searchParams.get('practice') ?? '') ? searchParams.get('practice')! : activeSession.practiceId
  const initialPatient = directoryPatients.find(patient => patient.practiceId === initialPractice && patient.id === searchParams.get('patient'))
  const [practiceId, setPracticeId] = useState(initialPractice)
  const [query, setQuery] = useState(initialPatient?.mrn ?? '')
  const [tab, setTab] = useState<DirectoryTab>('directory')
  const [visit, setVisit] = useState<VisitFilter>('all')
  const [identity, setIdentity] = useState<IdentityFilter>('all')
  const [sort, setSort] = useState<PatientSort>('last-visit')
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [page, setPage] = useState(1)
  const [selectedKey, setSelectedKey] = useState<string | null>(initialPatient ? patientKey(initialPatient) : null)
  const [recentKeys, setRecentKeys] = useState<string[]>([])
  const [dialog, setDialog] = useState<'details' | 'overview' | null>(null)
  const [compact, setCompact] = useState(() => window.matchMedia(compactQuery).matches)
  const opener = useRef<HTMLElement | null>(null)
  const searchInput = useRef<HTMLInputElement>(null)
  const clinic = doctorClinics.find(item => item.practiceId === practiceId)!
  const results = selectDirectory(directoryPatients, { practiceId, allowedPracticeIds, query, tab, recentKeys, visit, identity, sort })
  const pagination = directoryPage(results, page)
  const dialogPatient = dialog ? results.find(patient => patientKey(patient) === selectedKey) : undefined
  const selected = pagination.patients.find(patient => patientKey(patient) === selectedKey) ?? pagination.patients[0]
  const filterCount = Number(visit !== 'all') + Number(identity !== 'all')
  const total = directoryPatients.filter(patient => patient.practiceId === practiceId).length
  const recentCount = directoryPatients.filter(patient => patient.practiceId === practiceId && recentKeys.includes(patientKey(patient))).length
  useEffect(() => {
    const media = window.matchMedia(compactQuery)
    const onChange = () => { setCompact(media.matches); setDialog(null) }
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])
  function resetSelection() { setPage(1); setSelectedKey(null); setDialog(null) }
  function clearFilters() { setQuery(''); setVisit('all'); setIdentity('all'); resetSelection() }
  function switchPractice(value: string) {
    setPracticeId(value); clearFilters(); setSort('last-visit'); setFiltersOpen(false)
  }
  function switchTab(value: DirectoryTab) { setTab(value); clearFilters() }
  function tabKey(event: KeyboardEvent<HTMLButtonElement>) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const next = event.key === 'Home' ? 'directory' : event.key === 'End' ? 'recent' : tab === 'directory' ? 'recent' : 'directory'
    switchTab(next); document.getElementById(`patients-tab-${next}`)?.focus()
  }
  function remember(patient: DirectoryPatient) {
    const key = patientKey(patient)
    setRecentKeys(keys => [key, ...keys.filter(item => item !== key)].slice(0, 50))
  }
  function showDialog(value: 'details' | 'overview') {
    if (!dialog && document.activeElement instanceof HTMLElement) opener.current = document.activeElement
    setDialog(value)
  }
  function selectPatient(patient: DirectoryPatient) {
    setSelectedKey(patientKey(patient))
    if (compact) showDialog('details')
  }
  function closeDialog() {
    setDialog(null)
    requestAnimationFrame(() => { if (opener.current?.isConnected) opener.current.focus(); else searchInput.current?.focus() })
  }
  function openOverview() {
    const patient = dialogPatient ?? selected
    if (!patient) return
    setSelectedKey(patientKey(patient))
    remember(patient); showDialog('overview')
  }
  return <main id="main-content" className="dashboard patients-workspace" tabIndex={-1}>
    <div className="page-heading patients-heading"><div><h1>My Patients</h1><p>Find a patient and continue their care.</p></div><div className="registration-action"><Button onClick={() => navigate(`/patients/new?practice=${encodeURIComponent(practiceId)}`)}><Plus aria-hidden="true" />New patient</Button></div></div>
    {location.state?.patientSave ? <p className="form-success" role="status">{location.state.patientSave}</p> : null}
    <div className="dashboard-tabs patient-tabs" role="tablist" aria-label="Patient views">
      <button id="patients-tab-directory" role="tab" aria-selected={tab === 'directory'} aria-controls="patient-directory-panel" tabIndex={tab === 'directory' ? 0 : -1} onClick={() => switchTab('directory')} onKeyDown={tabKey}>Patient directory</button>
      <button id="patients-tab-recent" role="tab" aria-selected={tab === 'recent'} aria-controls="patient-directory-panel" tabIndex={tab === 'recent' ? 0 : -1} onClick={() => switchTab('recent')} onKeyDown={tabKey}>Recent patients{recentCount > 0 ? <span className="tab-count">{recentCount}</span> : null}</button>
    </div>
    <div className="directory-context"><div className="practice-field"><label htmlFor="directory-practice"><Building2 aria-hidden="true" />Practice</label><select id="directory-practice" value={practiceId} onChange={event => switchPractice(event.target.value)}>{doctorClinics.map(item => <option key={item.practiceId} value={item.practiceId}>{item.site}</option>)}</select><span>{total} patients</span></div><span className="directory-snapshot"><CalendarDays aria-hidden="true" />Demo snapshot · <time dateTime={demoDate}>{formatPatientDate(demoDate)}</time></span></div>
    {practiceId !== activeSession.practiceId ? <p className="directory-scope-alert"><ShieldCheck aria-hidden="true" />Viewing {clinic.site} records. Hospital A remains your active clinical session.</p> : null}
    <div role="tabpanel" id="patient-directory-panel" aria-labelledby={`patients-tab-${tab}`} className="directory-grid">
      <section className="surface directory-list" aria-label={`${clinic.site} patient directory`}>
        <div className="directory-search-row"><div className="directory-search"><Search aria-hidden="true" /><label className="sr-only" htmlFor="directory-search">Search this practice by name, patient ID or birth date</label><input id="directory-search" ref={searchInput} value={query} onChange={event => { setQuery(event.target.value); resetSelection() }} placeholder="Name, patient ID or birth date" autoComplete="off" spellCheck={false} />{query ? <button aria-label="Clear directory search" onClick={() => { setQuery(''); resetSelection(); searchInput.current?.focus() }}><X aria-hidden="true" /></button> : null}</div><Button variant="outline" aria-expanded={filtersOpen} aria-controls="directory-filters" onClick={() => setFiltersOpen(value => !value)}><SlidersHorizontal aria-hidden="true" />Filters{filterCount ? <span className="tab-count">{filterCount}</span> : null}</Button></div>
        {filtersOpen ? <div className="directory-filters" id="directory-filters"><label htmlFor="directory-visit-filter">Visit history<select id="directory-visit-filter" aria-label="Visit history" value={visit} onChange={event => { setVisit(event.target.value as VisitFilter); resetSelection() }}><option value="all">All patients</option><option value="visited">Has recorded visits</option><option value="never">No recorded visits</option></select></label><label htmlFor="directory-identity-filter">Identity<select id="directory-identity-filter" aria-label="Identity" value={identity} onChange={event => { setIdentity(event.target.value as IdentityFilter); resetSelection() }}><option value="all">All identities</option><option value="provisional">Provisional identity</option></select></label>{filterCount ? <Button variant="ghost" onClick={clearFilters}>Clear filters</Button> : null}</div> : null}
        <div className="directory-list-heading"><p role="status">{tab === 'recent' ? 'Opened this session' : query.trim() || filterCount ? `${results.length} matching patients` : 'All patients'}</p>{tab === 'directory' ? <label className="directory-sort"><span className="sr-only">Sort patients</span><select value={sort} onChange={event => { setSort(event.target.value as PatientSort); resetSelection() }}><option value="last-visit">Last visit · Newest</option><option value="oldest-visit">Last visit · Oldest</option><option value="name">Name · A–Z</option></select></label> : <span className="recent-order"><Clock3 aria-hidden="true" />Most recently opened</span>}</div>
        {pagination.patients.length ? <><div className="directory-table" role="table" aria-label="Patient records"><div className="directory-table-head" role="row"><span role="columnheader">Patient</span><span role="columnheader">Date of birth</span><span role="columnheader">Last visit</span><span role="columnheader" className="sr-only">Preview</span></div>{pagination.patients.map(patient => <div role="row" key={patientKey(patient)} className={`directory-row ${selected && patientKey(patient) === patientKey(selected) ? 'is-selected' : ''}`}>
          <div className="directory-patient-cell" role="cell"><span className="avatar blue" aria-hidden="true">{patient.initials}</span><div><button className="directory-patient-name" aria-label={`Preview ${patient.name}, ${patient.mrn}`} aria-pressed={!!selected && patientKey(patient) === patientKey(selected)} onClick={() => selectPatient(patient)}>{patient.name}</button><small>{patient.mrn}{patient.identity === 'provisional' ? <span className="provisional-label">Provisional</span> : null}</small></div></div><div className="directory-birth" role="cell"><span className="mobile-fact-label">Born </span>{patient.birthDateAccuracy === 'estimated' ? 'Estimated ' : ''}{formatPatientDate(patient.birthDate)}</div><div className="directory-last-visit" role="cell"><span className="mobile-fact-label">Last visit </span>{patient.lastVisit ? formatPatientDate(patient.lastVisit.date) : 'No recorded visits'}</div><span className="directory-chevron" role="cell"><ChevronRight aria-hidden="true" /></span>
        </div>)}</div><footer className="directory-pagination"><p role="status">Showing {pagination.start}–{pagination.end} of {results.length}</p><nav aria-label="Patient directory pages"><Button variant="outline" size="icon" aria-label="Previous page" disabled={pagination.page === 1} onClick={() => { setPage(pagination.page - 1); setSelectedKey(null) }}><ChevronLeft aria-hidden="true" /></Button><span>Page {pagination.page} of {pagination.pageCount}</span><Button variant="outline" size="icon" aria-label="Next page" disabled={pagination.page === pagination.pageCount} onClick={() => { setPage(pagination.page + 1); setSelectedKey(null) }}><ChevronRight aria-hidden="true" /></Button></nav></footer></> : <div className="directory-empty">{tab === 'recent' && !recentCount ? <Clock3 aria-hidden="true" /> : <Search aria-hidden="true" />}<h2>{tab === 'recent' && !recentCount ? 'No recently opened patients' : 'No matching patients'}</h2><p>{tab === 'recent' && !recentCount ? `Patient overviews you open from this directory at ${clinic.site} will appear here until you leave this view.` : 'Try another name, patient ID or birth date, or clear your filters.'}</p><Button variant="outline" onClick={() => { if (tab === 'recent' && !recentCount) switchTab('directory'); else clearFilters() }}>{tab === 'recent' && !recentCount ? 'Browse patient directory' : 'Clear search and filters'}</Button></div>}
      </section>
      {!compact ? <aside className="surface directory-preview" aria-label="Selected patient details">{selected ? <PatientDetails key={patientKey(selected)} patient={selected} onOpenOverview={openOverview} /> : <div className="directory-empty"><Users aria-hidden="true" /><h2>Select a patient</h2><p>Patient details will appear here when a record is available.</p></div>}</aside> : null}
    </div>
    <footer className="dashboard-footer patients-footer"><span><ShieldCheck aria-hidden="true" />Showing {clinic.site} records only</span><span>Synthetic data · Preview only</span></footer>
    <Sheet open={!!dialog && !!dialogPatient} onClose={closeDialog} title={dialog === 'overview' ? 'Patient overview' : 'Patient details'} description={`${clinic.site} · Synthetic patient record`}>
      {dialogPatient ? <PatientDetails key={patientKey(dialogPatient)} patient={dialogPatient} overview={dialog === 'overview'} onOpenOverview={openOverview} /> : null}
    </Sheet>
  </main>
}
