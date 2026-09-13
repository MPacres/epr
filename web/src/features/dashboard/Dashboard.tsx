import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { ArrowRight, ArrowUpRight, Building2, CalendarDays, Check, CheckCheck, ChevronRight, CircleCheck, ClipboardCheck, Clock3, FileText, Inbox, Info, MapPin, PencilLine, Pill, ShieldCheck, TriangleAlert, UserRound } from 'lucide-react'
import { WorkspaceShell, type Destination } from '../../components/layout/workspace-shell'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { activeSession, demoTime, doctor, doctorClinics, inbox, queue, sessions, sessionSummaries } from './demo-data'
import { distinctInbox, readyEntries, patientAgeLabel, type InboxCategory, type InboxItem, type Patient, type PracticeSession, type QueueEntry } from './model'
import { PatientSpotlight } from './PatientSpotlight'
import { MyPatients } from '../patients/MyPatients'
import { usePatientPreview } from '../patients/patient-preview-context'
import { ageAt } from '../patients/model'
import './dashboard.css'

const categories: { label: InboxCategory; icon: typeof Inbox }[] = [
  { label: 'Results', icon: FileText }, { label: 'Unsigned notes', icon: PencilLine }, { label: 'Follow-ups', icon: CalendarDays }, { label: 'Refills', icon: Pill },
]
const actionable = distinctInbox(inbox)
const ready = readyEntries(queue, activeSession)
const overdueCount = actionable.filter(item => item.overdue).length
const dateLabel = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'Asia/Manila' }).format(new Date(`${activeSession.date}T00:00:00+08:00`))
type Panel = { kind: 'queue'; session: PracticeSession } | { kind: 'patient'; patient: Patient } | { kind: 'item'; item: InboxItem } | { kind: 'inbox' | 'patients' | 'schedule' | 'practices' | 'more' | 'profile' }
type Filter = 'All items' | 'Overdue' | InboxCategory

function Badge({ children, tone = 'blue' }: { children: React.ReactNode; tone?: string }) {
  return <span className={`badge ${tone}`}>{children}</span>
}
function Avatar({ patient }: { patient: Patient }) {
  return <span className={`avatar ${patient.tone}`} aria-hidden="true">{patient.initials}</span>
}
function PatientRows({ entries, onPatient }: { entries: QueueEntry[]; onPatient: (patient: Patient) => void }) {
  return <div className="queue-table" role="table" aria-label="Patients ready for doctor">
    <div className="queue-table-head" role="row"><span role="columnheader">Patient</span><span role="columnheader">Visit</span><span role="columnheader">Status</span><span role="columnheader">Wait</span></div>
    {entries.map(entry => <div className="queue-row" role="row" key={entry.id}>
      <div className="patient-cell" role="cell"><Avatar patient={entry.patient} /><button className="patient-name" onClick={() => onPatient(entry.patient)}><strong>{entry.patient.name}</strong><small>{entry.patient.mrn}</small></button></div>
      <span className="visit-cell" role="cell">{entry.visit}</span>
      <span className="status-cell" role="cell"><Badge><span className="status-dot" />Ready for doctor</Badge></span>
      <span className="wait-cell" role="cell">{entry.wait} <span>min</span></span>
    </div>)}
  </div>
}
function InboxRow({ item, onClick, detailed = false }: { item: InboxItem; onClick: () => void; detailed?: boolean }) {
  const Icon = categories.find(category => category.label === item.category)!.icon
  return <button className="inbox-row" onClick={onClick}>
    <span className="inbox-icon"><Icon aria-hidden="true" /></span>
    <span className="inbox-row-copy"><strong>{item.title}</strong><small>{item.patient.name} <span>·</span> {item.site}</small>{detailed ? <small>{item.patient.mrn} · Owner: {item.owner}</small> : null}</span>
    <Badge tone={item.overdue ? 'red' : 'amber'}>{item.overdue ? 'Overdue' : 'Today'}</Badge><ChevronRight className="row-chevron" aria-hidden="true" />
  </button>
}
function SessionCards({ onPreview }: { onPreview: (session: PracticeSession) => void }) {
  return <div className="session-cards">{sessions.map(session => {
    const current = session.id === activeSession.id
    return <button className={`session-card ${current ? 'active' : ''}`} key={session.id} onClick={() => onPreview(session)}>
      <span className={`session-marker ${current ? 'blue' : 'neutral'}`}><Building2 aria-hidden="true" /></span>
      <span className="session-card-copy"><span className="session-title"><strong>{session.site}</strong><Badge tone={current ? 'blue' : 'amber'}>{current ? 'Current' : 'Upcoming'}</Badge></span>
      <span>{session.clinic} · {session.time}</span><small>{current ? `${ready.length} ready · ${sessionSummaries[session.id].completed} completed` : `${sessionSummaries[session.id].checkedIn} checked in · Staff preparing`}</small></span>
      <span className="session-action">{current ? 'Open queue' : 'Preview queue'}<ArrowRight aria-hidden="true" /></span>
    </button>
  })}</div>
}
export function Dashboard({ view = 'dashboard', onViewChange, content }: { view?: 'dashboard' | 'patients'; onViewChange: (view: 'dashboard' | 'patients') => void; content?: ReactNode }) {
  const { records: directoryPatients } = usePatientPreview()
  const searchablePatients: Patient[] = directoryPatients.map(patient => ({ ...patient, age: ageAt(patient.birthDate, activeSession.date), tone: 'blue' }))
  const livePatient = (original: Patient) => searchablePatients.find(patient => patient.id === original.id && patient.practiceId === original.practiceId) ?? original
  const ready = readyEntries(queue.map(entry => ({ ...entry, patient: livePatient(entry.patient) })), activeSession)
  const actionable = distinctInbox(inbox.map(item => ({ ...item, patient: livePatient(item.patient) })))
  const [tab, setTab] = useState<'today' | 'followups'>('today')
  const [panel, setPanel] = useState<Panel | null>(null)
  const [filter, setFilter] = useState<Filter>('All items')
  const lastOpener = useRef<HTMLElement | null>(null)
  useEffect(() => {
    function handleSearchShortcut(event: globalThis.KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' && !event.altKey && !event.isComposing) {
        event.preventDefault()
        if (panel?.kind === 'patients') {
          setPanel(null)
          requestAnimationFrame(() => lastOpener.current?.focus())
        } else if (!panel) {
          if (document.activeElement instanceof HTMLElement) lastOpener.current = document.activeElement
          setPanel({ kind: 'patients' })
        }
      }
    }
    window.addEventListener('keydown', handleSearchShortcut)
    return () => window.removeEventListener('keydown', handleSearchShortcut)
  }, [panel])
  function openPanel(value: Panel) {
    if (!panel && document.activeElement instanceof HTMLElement) lastOpener.current = document.activeElement
    setPanel(value)
  }
  function closePanel() {
    setPanel(null)
    // Programmatic sheets have no Dialog.Trigger; restore the original opener explicitly.
    requestAnimationFrame(() => lastOpener.current?.focus())
  }
  function openInbox(value: Filter = 'All items') { setFilter(value); openPanel({ kind: 'inbox' }) }
  function navigate(destination: Destination) {
    if (destination === 'dashboard' || destination === 'patients') { setTab('today'); setPanel(null); onViewChange(destination); return }
    if (destination === 'queue') openPanel({ kind: 'queue', session: activeSession })
    else if (destination === 'inbox') openInbox()
    else openPanel({ kind: destination })
  }
  function tabKey(event: KeyboardEvent<HTMLButtonElement>) {
    if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
      event.preventDefault()
      const next = event.key === 'Home' ? 'today' : event.key === 'End' ? 'followups' : tab === 'today' ? 'followups' : 'today'
      setTab(next)
      document.getElementById(`tab-${next}`)?.focus()
    }
  }
  const nextPatient = ready[0]
  const filteredInbox = actionable.filter(item => filter === 'All items' || (filter === 'Overdue' ? item.overdue : item.category === filter))
  const panelTitle = !panel ? '' : panel.kind === 'queue' ? `${panel.session.site} queue` : panel.kind === 'patient' ? 'Patient overview' : panel.kind === 'item' ? panel.item.title : { inbox: 'Clinical inbox', patients: 'My patients', schedule: 'Today’s schedule', practices: 'Practice sessions', more: 'More workspaces', profile: 'Your profile' }[panel.kind]
  const provisionalPatient = panel?.kind === 'patient' && directoryPatients.some(patient => patient.id === panel.patient.id && patient.practiceId === panel.patient.practiceId && patient.identity === 'provisional')
  const patientClinic = panel?.kind === 'patient' ? doctorClinics.find(clinic => clinic.practiceId === panel.patient.practiceId) : undefined
  const panelDescription = panel?.kind === 'item' ? `${panel.item.site} · ${panel.item.patient.mrn} · Assigned to ${panel.item.owner}` : panel?.kind === 'patient' ? `${patientClinic?.site ?? 'Practice unavailable'} · Synthetic patient record` : `${dateLabel} · ${doctor} · Demo preview`
  return <WorkspaceShell activeDestination={view} readyCount={ready.length} inboxCount={actionable.length} onNavigate={navigate} onSearchOpen={() => openPanel({ kind: 'patients' })}>
    {content ?? (view === 'patients' ? <MyPatients /> : <main id="main-content" className="dashboard" tabIndex={-1}>
      <div className="page-heading"><div><h1>Dashboard</h1><p>Your clinical day at a glance.</p></div><div className="date-display"><CalendarDays aria-hidden="true" /><time dateTime={activeSession.date}>{dateLabel}</time><span className="date-day">Saturday</span><span className="mobile-demo-label">Demo data</span></div></div>
      <div className="dashboard-tabs" role="tablist" aria-label="Dashboard views">
        <button id="tab-today" role="tab" aria-controls="panel-today" aria-selected={tab === 'today'} tabIndex={tab === 'today' ? 0 : -1} onKeyDown={tabKey} onClick={() => setTab('today')}>Today</button>
        <button id="tab-followups" role="tab" aria-controls="panel-followups" aria-selected={tab === 'followups'} tabIndex={tab === 'followups' ? 0 : -1} onKeyDown={tabKey} onClick={() => setTab('followups')}>Care follow-ups<span className="tab-count">{actionable.filter(item => item.category === 'Follow-ups').length}</span></button>
        <span className="snapshot-label">Preview as of {demoTime} PHT</span>
      </div>
      <div id="panel-today" role="tabpanel" aria-labelledby="tab-today" hidden={tab !== 'today'}>
        <section className="metrics" aria-label="Today's overview">
          <button className="metric" onClick={() => openPanel({ kind: 'schedule' })}><span className="icon-tile blue"><CalendarDays aria-hidden="true" /></span><span className="metric-copy"><span>Appointments</span><strong>{sessions.reduce((sum, session) => sum + session.appointments, 0)}</strong><small>Across 2 practices</small></span><ArrowUpRight className="metric-arrow" aria-hidden="true" /></button>
          <button className="metric" onClick={() => navigate('queue')}><span className="icon-tile green"><UserRound aria-hidden="true" /></span><span className="metric-copy"><span>Ready for doctor</span><strong>{ready.length}</strong><small>Current session</small></span><ArrowUpRight className="metric-arrow" aria-hidden="true" /></button>
          <div className="metric"><span className="icon-tile purple"><CircleCheck aria-hidden="true" /></span><span className="metric-copy"><span>Completed visits</span><strong>{sessionSummaries[activeSession.id].completed}</strong><small>Today at Hospital A</small></span></div>
          <button className="metric" onClick={() => openInbox()}><span className="icon-tile red"><TriangleAlert aria-hidden="true" /></span><span className="metric-copy"><span>Needs attention</span><span className="metric-value"><strong>{actionable.length}</strong><Badge tone="red">{overdueCount} overdue</Badge></span><small>Clinical inbox</small></span><ArrowUpRight className="metric-arrow" aria-hidden="true" /></button>
        </section>
        <div className="clinical-grid">
          <section className="surface current-session" aria-labelledby="session-heading">
            <header className="section-header"><span className="icon-tile blue"><Building2 aria-hidden="true" /></span><div className="section-heading"><h2 id="session-heading">Current session</h2><p className="session-location">Hospital A <span>·</span> Outpatient Clinic</p><p className="session-time">{activeSession.time} <span>·</span> {doctor}</p></div><Badge tone="green"><span className="status-dot" />In progress</Badge></header>
            {nextPatient ? <div className="next-patient"><div className="next-patient-label"><span>Next patient</span><span><CheckCheck aria-hidden="true" />Preparation complete</span></div>
              <div className="next-patient-main"><span className="queue-token">#{nextPatient.token}</span><div className="next-patient-identity"><button onClick={() => openPanel({ kind: 'patient', patient: nextPatient.patient })}>{nextPatient.patient.name}</button><p>{nextPatient.patient.mrn}<span>·</span>{patientAgeLabel(nextPatient.patient)}<span>·</span>{nextPatient.visit}</p></div></div>
              <div className="next-patient-bottom"><div className="next-readiness"><span><span className="status-dot" />Ready for doctor</span><span><Clock3 aria-hidden="true" />Waiting {nextPatient.wait} min</span></div><Button onClick={() => navigate('queue')}>Open queue<ArrowRight aria-hidden="true" /></Button></div>
            </div> : <div className="empty-state"><CircleCheck /><h3>No patients ready</h3><p>Your active queue will show patients when preparation is complete.</p></div>}
            <div className="subsection-header"><h3>Up next <span>{Math.max(ready.length - 1, 0)}</span></h3><Button variant="ghost" size="sm" onClick={() => navigate('queue')}>View all<ArrowRight aria-hidden="true" /></Button></div>
            <PatientRows entries={ready.slice(1)} onPatient={patient => openPanel({ kind: 'patient', patient })} />
            <div className="session-footer"><ShieldCheck aria-hidden="true" /><span>Queue for your active clinical session</span><span>Hospital A</span></div>
          </section>
          <section className="surface inbox-card" aria-labelledby="inbox-heading">
            <header className="section-header"><span className="icon-tile blue"><Inbox aria-hidden="true" /></span><div className="section-heading"><h2 id="inbox-heading">Clinical inbox</h2><p>{actionable.length} items need your attention</p></div><Button variant="ghost" size="sm" onClick={() => openInbox()}>View all<ArrowRight aria-hidden="true" /></Button></header>
            <div className="inbox-priority">{actionable.slice(0, 3).map(item => <InboxRow key={item.id} item={item} onClick={() => openPanel({ kind: 'item', item })} />)}</div>
            <div className="inbox-categories">{categories.map(({ label, icon: Icon }) => <button key={label} onClick={() => openInbox(label)}><Icon aria-hidden="true" /><span>{label}</span><strong>{actionable.filter(item => item.category === label).length}</strong><ChevronRight aria-hidden="true" /></button>)}</div>
            <div className="inbox-footer"><span className="status-dot" />Across your 2 practices</div>
          </section>
        </div>
        <section className="surface daily-sessions" aria-labelledby="daily-heading"><header className="section-header"><CalendarDays className="section-icon" aria-hidden="true" /><div className="section-heading"><h2 id="daily-heading">Today’s practice sessions</h2></div><Button variant="ghost" size="sm" onClick={() => openPanel({ kind: 'schedule' })}>View schedule<ArrowRight aria-hidden="true" /></Button></header><SessionCards onPreview={session => openPanel({ kind: 'queue', session })} /></section>
      </div>
      <div id="panel-followups" role="tabpanel" aria-labelledby="tab-followups" hidden={tab !== 'followups'}>
        <section className="surface followup-surface"><header className="section-header"><span className="icon-tile blue"><ClipboardCheck aria-hidden="true" /></span><div className="section-heading"><h2>Care follow-ups</h2><p>Keep care moving across your practices.</p></div><Badge tone="red">1 overdue</Badge></header>
        {actionable.filter(item => item.category === 'Follow-ups').map(item => <InboxRow detailed key={item.id} item={item} onClick={() => openPanel({ kind: 'item', item })} />)}
        <p className="followup-note"><Info aria-hidden="true" />Follow-up remains assigned until its outcome is documented.</p></section>
      </div>
      <footer className="dashboard-footer"><span><ShieldCheck aria-hidden="true" />Designed around your clinical day</span><span>Synthetic data · Preview only</span></footer>
    </main>)}
    {panel?.kind === 'patients' ? <PatientSpotlight patients={searchablePatients} clinics={doctorClinics} onClose={closePanel} onSelect={patient => openPanel({ kind: 'patient', patient })} /> : null}
    <Sheet open={!!panel && panel.kind !== 'patients'} onClose={closePanel} title={panelTitle} description={panelDescription}>
      {panel?.kind === 'queue' ? <><div className={`context-callout ${panel.session.id === activeSession.id ? '' : 'upcoming-context'}`}><Building2 aria-hidden="true" /><div><strong>{panel.session.id === activeSession.id ? 'Current clinical session' : 'Upcoming session · Preview only'}</strong><p>{panel.session.clinic} · {panel.session.time}</p><p>{panel.session.id === activeSession.id ? 'Hospital A remains your active practice.' : 'Your active session is still Hospital A. Previewing does not switch your practice.'}</p></div></div>
        {panel.session.id === activeSession.id ? <><h3 className="panel-section-title">Ready for doctor <Badge>{ready.length}</Badge></h3>{ready.map(entry => <div className="queue-detail" key={entry.id}><div className="queue-detail-heading"><span className="queue-token">#{entry.token}</span><div><button className="text-link" onClick={() => openPanel({ kind: 'patient', patient: entry.patient })}>{entry.patient.name}</button><p>{entry.patient.mrn} · {patientAgeLabel(entry.patient)} · {entry.visit}</p></div></div><dl className="detail-grid"><div><dt>Arrival</dt><dd>{entry.arrival} · Appointment</dd></div><div><dt>Wait at {demoTime}</dt><dd>{entry.wait} minutes</dd></div><div><dt>Preparation</dt><dd><Check aria-hidden="true" />{entry.preparation}</dd></div><div><dt>Encounter</dt><dd>{entry.encounter}</dd></div></dl><Badge>Ready for doctor</Badge></div>)}</> : <div className="empty-state"><Clock3 aria-hidden="true" /><h3>Staff are preparing the queue</h3><p>3 patients checked in. No patients are ready for the doctor in this preview.</p><p>Clinic B · Afternoon clinic · {panel.session.time}</p></div>}
        <p className="preview-note"><Info aria-hidden="true" />Queue preview only. Starting care requires a connected, authorized clinical service.</p></> : null}
      {panel?.kind === 'patient' ? <>{provisionalPatient ? <div className="identity-notice"><TriangleAlert aria-hidden="true" /><p><strong>Provisional identity</strong><span>Verify identity details before clinical actions.</span></p></div> : null}<div className="patient-overview"><Avatar patient={panel.patient} /><div><h3>{panel.patient.name}</h3><p>{panel.patient.mrn} · {patientAgeLabel(panel.patient)}</p></div></div><dl className="detail-grid"><div><dt>Practice / site</dt><dd>{patientClinic ? `${patientClinic.site} · ${patientClinic.clinic}` : 'Practice unavailable'}</dd></div><div><dt>Responsible clinician</dt><dd>{doctor}</dd></div><div><dt>Allergy review</dt><dd>Not available in preview</dd></div><div><dt>Medication review</dt><dd>Not available in preview</dd></div></dl><p className="preview-note"><Info aria-hidden="true" />This is a synthetic patient summary. Opening it does not start an encounter. The longitudinal chart is not connected.</p><Button variant="outline" onClick={() => openPanel({ kind: 'patients' })}>Back to patient search</Button></> : null}
      {panel?.kind === 'inbox' ? <><div className="filter-list" aria-label="Filter inbox">{(['All items', 'Overdue', ...categories.map(category => category.label)] as Filter[]).map(value => <button key={value} aria-pressed={filter === value} onClick={() => setFilter(value)}>{value}{value === 'Overdue' ? ` (${overdueCount})` : ''}</button>)}</div><p className="result-count" role="status">{filteredInbox.length} {filteredInbox.length === 1 ? 'item' : 'items'} · Assigned to you</p>{filteredInbox.map(item => <InboxRow detailed key={item.id} item={item} onClick={() => openPanel({ kind: 'item', item })} />)}</> : null}
      {panel?.kind === 'item' ? <><div className="patient-overview"><Avatar patient={panel.item.patient} /><div><h3>{panel.item.patient.name}</h3><p>{panel.item.patient.mrn} · {patientAgeLabel(panel.item.patient)} · {panel.item.site}</p></div></div><Badge tone={panel.item.overdue ? 'red' : 'amber'}>{panel.item.overdue ? 'Overdue' : 'Due today'}</Badge><dl className="detail-grid"><div><dt>Assigned owner</dt><dd>{panel.item.owner}</dd></div><div><dt>Due date</dt><dd>{panel.item.due}</dd></div><div><dt>Category</dt><dd>{panel.item.category}</dd></div><div><dt>Status</dt><dd>Awaiting clinician action</dd></div></dl><p className="item-description">{panel.item.detail}</p><p className="preview-note"><Info aria-hidden="true" />Source records and clinical actions are not connected in this preview. This item remains open.</p><Button variant="outline" onClick={() => openInbox()}>Back to clinical inbox</Button></> : null}
      {panel?.kind === 'schedule' || panel?.kind === 'practices' ? <><div className="context-callout"><MapPin /><div><strong>Hospital A is your active session</strong><p>Preview a queue without changing where you are providing care.</p></div></div><SessionCards onPreview={session => openPanel({ kind: 'queue', session })} /><div className="schedule-summary">{sessions.map(session => <div key={session.id}><CalendarDays /><span><strong>{session.site}</strong><small>{session.appointments} appointments · {session.time}</small></span></div>)}</div><p className="preview-note"><Info />Session activation and schedule editing require a connected service.</p></> : null}
      {panel?.kind === 'more' ? <div className="more-list">{([{ name: 'Schedule', id: 'schedule', icon: CalendarDays }, { name: 'Practices', id: 'practices', icon: Building2 }, { name: 'Your profile', id: 'profile', icon: UserRound }] as const).map(item => <button key={item.id} onClick={() => navigate(item.id)}><item.icon /><span>{item.name}</span><ChevronRight /></button>)}<p className="preview-note">Referrals, Documents, and Settings are outside this dashboard preview.</p></div> : null}
      {panel?.kind === 'profile' ? <><div className="patient-overview"><span className="avatar blue">AS</span><div><h3>{doctor}</h3><p>Internal Medicine</p></div></div><dl className="detail-grid"><div><dt>Role</dt><dd>Clinician · Demo profile</dd></div><div><dt>Practice memberships</dt><dd>Hospital A and Clinic B</dd></div><div><dt>Practice timezone</dt><dd>Asia/Manila (PHT)</dd></div></dl><p className="preview-note"><Info />Authentication and account settings are not connected.</p></> : null}
    </Sheet>
  </WorkspaceShell>
}
