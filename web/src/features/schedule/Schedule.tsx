import { useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ArrowRight, Building2, CalendarClock, CalendarDays, ChevronDown, ChevronLeft,
  ChevronRight, ChevronUp, Clock3, Info, ListOrdered, Plus, ShieldCheck,
  UserRoundPlus,
} from 'lucide-react'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { chartPath } from '../chart/model'
import { formatPatientDate } from '../patients/model'
import { scheduleDemoDate, scheduledAppointments, scheduledSessions } from './demo-data'
import {
  appointmentsForPractice, countAppointmentsBySession, formatScheduleDate, sessionsForPractice,
  shiftScheduleDate, type ScheduledAppointment, type ScheduledSession, type SchedulePracticeFilter,
} from './model'
import './schedule.css'

const tabs = ['Calendar', 'Appointments', 'Waitlist', 'Availability & Leave'] as const
type ScheduleTab = typeof tabs[number]
type Notice = 'new' | 'appointment' | 'reschedule' | null

function BookingBadge({ appointment }: { appointment: ScheduledAppointment }) {
  return <span className={`schedule-status ${appointment.booking === 'CONFIRMED' ? 'confirmed' : 'pending'}`}>
    <span className="status-dot" aria-hidden="true" />
    {appointment.booking === 'CONFIRMED' ? 'Confirmed' : 'Pending confirmation'}
  </span>
}

function AppointmentRows({ appointments, selectedId, onSelect }: {
  appointments: readonly ScheduledAppointment[]
  selectedId: string
  onSelect: (appointment: ScheduledAppointment) => void
}) {
  return <div className="appointment-list" aria-label="Scheduled appointments">
    <div className="appointment-columns" aria-hidden="true"><span>Time</span><span>Patient</span><span>Visit</span><span>Booking</span><span /></div>
    {appointments.map(appointment => <button
      className="appointment-row"
      key={appointment.id}
      aria-pressed={appointment.id === selectedId}
      aria-label={`${appointment.start}, ${appointment.patient.name}, ${appointment.patient.mrn}, ${appointment.visit}, ${appointment.booking === 'CONFIRMED' ? 'confirmed' : 'pending confirmation'}`}
      onClick={() => onSelect(appointment)}
    >
      <time>{appointment.start}</time>
      <span className="appointment-patient"><strong>{appointment.patient.name}</strong><small>{appointment.patient.mrn}</small></span>
      <span className="appointment-visit"><span className="mobile-row-label">Visit</span>{appointment.visit}</span>
      <span className="appointment-booking"><BookingBadge appointment={appointment} /></span>
      <ChevronRight aria-hidden="true" />
    </button>)}
  </div>
}

function SessionAgenda({ session, appointments, selectedId, expanded, onToggle, onSelect }: {
  session: ScheduledSession
  appointments: readonly ScheduledAppointment[]
  selectedId: string
  expanded: boolean
  onToggle: () => void
  onSelect: (appointment: ScheduledAppointment) => void
}) {
  const initiallyVisible = appointments.filter(appointment => !appointment.initiallyHidden)
  const initiallyHidden = appointments.filter(appointment => appointment.initiallyHidden)
  const visibleAppointments = expanded ? appointments : initiallyVisible
  const current = session.state === 'IN_PROGRESS'
  const hiddenLabel = session.id === 'session-a'
    ? `${session.start}–10:00 AM · ${initiallyHidden.length} earlier appointments`
    : `View ${initiallyHidden.length} more appointments`
  return <section className={`schedule-session ${session.accent}`} aria-labelledby={`${session.id}-heading`}>
    <header className="schedule-session-header">
      <span className="schedule-session-icon"><Building2 aria-hidden="true" /></span>
      <span className="schedule-session-copy">
        <span className="schedule-session-title"><h3 id={`${session.id}-heading`}>{session.site}</h3><span className={`schedule-session-state ${current ? 'current' : 'upcoming'}`}>{current ? 'Current' : 'Upcoming'}</span></span>
        <span>{session.clinic} · {session.start}–{session.end} · {appointments.length} appointments</span>
      </span>
    </header>
    {session.id === 'session-a' ? <button className="appointment-expander top" aria-expanded={expanded} onClick={onToggle}>
      {expanded ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}<span>{hiddenLabel}</span>
    </button> : null}
    <AppointmentRows appointments={visibleAppointments} selectedId={selectedId} onSelect={onSelect} />
    {session.id === 'session-b' && initiallyHidden.length > 0 ? <button className="appointment-expander bottom" aria-expanded={expanded} onClick={onToggle}>
      {expanded ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}<span>{expanded ? 'Show fewer appointments' : hiddenLabel}</span>
    </button> : null}
  </section>
}

function AppointmentDetails({ appointment, session, onNotice, onOpenChart }: {
  appointment: ScheduledAppointment
  session: ScheduledSession
  onNotice: (notice: Exclude<Notice, null>) => void
  onOpenChart: () => void
}) {
  return <aside className="surface appointment-details" id="appointment-details" aria-labelledby="appointment-details-heading">
    <div className="appointment-identity">
      <span className="avatar blue" aria-hidden="true">{appointment.patient.initials}</span>
      <div><h2 id="appointment-details-heading">{appointment.patient.name}</h2><p>{appointment.patient.mrn} · {formatPatientDate(appointment.patient.birthDate)}</p></div>
    </div>
    <dl className="appointment-facts">
      <div><dt>Practice</dt><dd>{session.site}</dd></div>
      <div><dt>Location</dt><dd>{session.clinic}</dd></div>
      <div><dt>Doctor</dt><dd>Dr. Ana Santos</dd></div>
      <div className="fact-divider"><dt>Date</dt><dd>{formatScheduleDate(session.date)}</dd></div>
      <div><dt>Time</dt><dd>{appointment.start}–{appointment.end}</dd></div>
      <div><dt>Visit</dt><dd>{appointment.visit}</dd></div>
      <div className="fact-divider"><dt>Booking</dt><dd><BookingBadge appointment={appointment} /></dd></div>
      <div><dt>Arrival</dt><dd><span className={`schedule-status ${appointment.arrival === 'CHECKED_IN' ? 'confirmed' : 'neutral'}`}><span className="status-dot" aria-hidden="true" />{appointment.arrival === 'CHECKED_IN' ? 'Checked in' : 'Expected'}</span></dd></div>
    </dl>
    {appointment.queue ? <div className="queue-link-note"><ListOrdered aria-hidden="true" /><span>Today’s queue · #{appointment.queue.token} · Ready for doctor</span></div> : <div className="queue-link-note neutral-note"><Clock3 aria-hidden="true" /><span>{appointment.arrival === 'CHECKED_IN' ? 'Checked in · Queue preparation pending' : 'Not yet checked in'}</span></div>}
    <div className="appointment-actions">
      <Button onClick={() => onNotice('appointment')}>View appointment<ArrowRight aria-hidden="true" /></Button>
      <Button variant="outline" onClick={() => onNotice('reschedule')}><CalendarClock aria-hidden="true" />Reschedule</Button>
      <Button variant="ghost" onClick={onOpenChart}>View patient chart<ArrowRight aria-hidden="true" /></Button>
    </div>
    <div className="appointment-details-footer"><span>Times shown in Asia/Manila</span><span>Synthetic patient data</span></div>
  </aside>
}

function EmptyDate({ date, onToday }: { date: string; onToday: () => void }) {
  return <section className="surface schedule-empty" aria-labelledby="empty-date-heading">
    <CalendarDays aria-hidden="true" />
    <h2 id="empty-date-heading">No demo appointments for this date</h2>
    <p>{formatScheduleDate(date)} has no scheduling fixtures. No absence or availability is inferred.</p>
    <Button variant="outline" onClick={onToday}>Return to demo date</Button>
  </section>
}

export function Schedule() {
  const navigate = useNavigate()
  const [tab, setTab] = useState<ScheduleTab>('Calendar')
  const [practice, setPractice] = useState<SchedulePracticeFilter>('all')
  const [date, setDate] = useState(scheduleDemoDate)
  const [selectedId, setSelectedId] = useState('apt-a-1000')
  const [earlierExpanded, setEarlierExpanded] = useState(false)
  const [afternoonExpanded, setAfternoonExpanded] = useState(false)
  const [notice, setNotice] = useState<Notice>(null)

  const dateAppointments = date === scheduleDemoDate ? appointmentsForPractice(scheduledAppointments, practice) : []
  const visibleSessions = date === scheduleDemoDate ? sessionsForPractice(scheduledSessions, practice) : []
  const selectedCandidate = dateAppointments.find(appointment => appointment.id === selectedId)
  const selectedCandidateVisible = selectedCandidate && (!selectedCandidate.initiallyHidden || (selectedCandidate.sessionId === 'session-a' ? earlierExpanded : afternoonExpanded))
  const selected = selectedCandidateVisible ? selectedCandidate : dateAppointments.find(appointment => !appointment.initiallyHidden) ?? dateAppointments[0]
  const selectedSession = selected ? scheduledSessions.find(session => session.id === selected.sessionId) : undefined
  const counts = countAppointmentsBySession(dateAppointments)
  const pendingCount = dateAppointments.filter(appointment => appointment.booking === 'PENDING_CONFIRMATION').length

  function selectAppointment(appointment: ScheduledAppointment) {
    setSelectedId(appointment.id)
    if (window.matchMedia('(max-width: 700px)').matches) {
      requestAnimationFrame(() => document.getElementById('appointment-details')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
    }
  }

  function switchPractice(next: SchedulePracticeFilter) {
    setPractice(next)
    const availableAppointments = appointmentsForPractice(scheduledAppointments, next)
    const nextAppointment = availableAppointments.find(appointment => !appointment.initiallyHidden) ?? availableAppointments[0]
    if (nextAppointment) setSelectedId(nextAppointment.id)
  }

  function resetToDemoDate() {
    setDate(scheduleDemoDate)
    const availableAppointments = appointmentsForPractice(scheduledAppointments, practice)
    const nextAppointment = availableAppointments.find(appointment => !appointment.initiallyHidden) ?? availableAppointments[0]
    if (nextAppointment) setSelectedId(nextAppointment.id)
  }

  function toggleSession(sessionId: ScheduledSession['id']) {
    const expanded = sessionId === 'session-a' ? earlierExpanded : afternoonExpanded
    const setExpanded = sessionId === 'session-a' ? setEarlierExpanded : setAfternoonExpanded
    if (expanded) {
      const current = scheduledAppointments.find(appointment => appointment.id === selectedId)
      if (current?.sessionId === sessionId && current.initiallyHidden) {
        const nextVisible = scheduledAppointments.find(appointment => appointment.sessionId === sessionId && !appointment.initiallyHidden)
        if (nextVisible) setSelectedId(nextVisible.id)
      }
    }
    setExpanded(value => !value)
  }

  function tabKey(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const nextIndex = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
    setTab(tabs[nextIndex])
    document.getElementById(`schedule-tab-${nextIndex}`)?.focus()
  }

  const appointmentTotal = dateAppointments.length
  const practiceCount = new Set(dateAppointments.map(appointment => appointment.practiceId)).size
  return <main id="main-content" className="dashboard schedule-workspace" tabIndex={-1}>
    <div className="page-heading schedule-heading"><div><h1>Schedule</h1><p>Plan your day across practices.</p></div><Button onClick={() => setNotice('new')}><Plus aria-hidden="true" />New appointment</Button></div>

    <div className="schedule-tabs" role="tablist" aria-label="Schedule workspaces">
      {tabs.map((label, index) => <button
        key={label}
        id={`schedule-tab-${index}`}
        role="tab"
        aria-controls="schedule-tabpanel"
        aria-selected={tab === label}
        tabIndex={tab === label ? 0 : -1}
        onKeyDown={event => tabKey(event, index)}
        onClick={() => setTab(label)}
      >{label}{label === 'Appointments' ? <span>{appointmentTotal}</span> : null}</button>)}
    </div>

    <section className="schedule-toolbar" aria-label="Schedule controls">
      <div className="date-controls">
        <Button variant="outline" size="icon" aria-label="Previous day" onClick={() => setDate(value => shiftScheduleDate(value, -1))}><ChevronLeft aria-hidden="true" /></Button>
        <Button variant="outline" size="icon" aria-label="Next day" onClick={() => setDate(value => shiftScheduleDate(value, 1))}><ChevronRight aria-hidden="true" /></Button>
        <span className="selected-date"><CalendarDays aria-hidden="true" /><time dateTime={date}>{formatScheduleDate(date)}</time></span>
        <Button variant="outline" onClick={resetToDemoDate}>Today</Button>
      </div>
      <label className="schedule-practice-filter"><span className="sr-only">Practice schedule</span><select value={practice} onChange={event => switchPractice(event.target.value as SchedulePracticeFilter)}><option value="all">All authorized practices</option><option value="practice-a">Hospital A</option><option value="practice-b">Clinic B</option></select></label>
      <div className="schedule-view-toggle" aria-label="Calendar view"><button aria-pressed="true">Day</button><button disabled title="Week view is not included in this preview">Week</button><button disabled title="Month view is not included in this preview">Month</button></div>
    </section>

    <div id="schedule-tabpanel" role="tabpanel" aria-labelledby={`schedule-tab-${tabs.indexOf(tab)}`}>
      {tab === 'Waitlist' ? <section className="surface schedule-empty schedule-secondary-state"><UserRoundPlus aria-hidden="true" /><h2>No patients are waiting for a slot</h2><p>This synthetic preview contains no waitlist entries. A connected scheduling service is required to add or offer appointments.</p></section> : null}
      {tab === 'Availability & Leave' ? visibleSessions.length ? <section className="surface availability-view"><header><div><h2>Availability & leave</h2><p>Practice sessions for {formatScheduleDate(date)} · {practice === 'all' ? 'All authorized practices' : visibleSessions[0].site}.</p></div><span className="schedule-status neutral"><span className="status-dot" />Demo preview</span></header><div className="availability-list">{visibleSessions.map(session => <div key={session.id}><span className={`schedule-session-icon ${session.accent}`}><Building2 aria-hidden="true" /></span><span><strong>{session.site}</strong><small>{session.clinic} · {session.start}–{session.end}</small></span><span>{session.state === 'IN_PROGRESS' ? 'In progress' : 'Scheduled'}</span></div>)}</div><p className="availability-note"><Info aria-hidden="true" />No leave is recorded in the filtered demo fixtures. Availability editing is not connected.</p></section> : <EmptyDate date={date} onToday={resetToDemoDate} /> : null}
      {tab === 'Calendar' || tab === 'Appointments' ? dateAppointments.length && selected && selectedSession ? <div className="schedule-layout">
        <section className="surface schedule-agenda" aria-labelledby="schedule-agenda-heading">
          <header className="schedule-agenda-header"><div><h2 id="schedule-agenda-heading">{tab === 'Calendar' ? 'Today’s schedule' : 'Appointment list'}</h2><p>{appointmentTotal} appointments · {practiceCount} {practiceCount === 1 ? 'practice' : 'practices'}{pendingCount ? ` · ${pendingCount} pending confirmation` : ''}</p></div><span><ShieldCheck aria-hidden="true" />Authorized scope</span></header>
          {visibleSessions.map((session, index) => <div key={session.id}>
            {index > 0 && practice === 'all' ? <div className="schedule-break"><Clock3 aria-hidden="true" /><span>12:00–2:00 PM · Break & travel</span></div> : null}
            <SessionAgenda
              session={session}
              appointments={dateAppointments.filter(appointment => appointment.sessionId === session.id)}
              selectedId={selected.id}
              expanded={session.id === 'session-a' ? earlierExpanded : afternoonExpanded}
              onToggle={() => toggleSession(session.id)}
              onSelect={selectAppointment}
            />
          </div>)}
          <footer className="schedule-agenda-footer"><Info aria-hidden="true" /><span>{Object.entries(counts).map(([sessionId, count]) => `${scheduledSessions.find(session => session.id === sessionId)?.site}: ${count}`).join(' · ')}</span></footer>
        </section>
        <AppointmentDetails appointment={selected} session={selectedSession} onNotice={setNotice} onOpenChart={() => navigate(chartPath(selected.patient))} />
      </div> : <EmptyDate date={date} onToday={() => setDate(scheduleDemoDate)} /> : null}
    </div>

    <footer className="dashboard-footer schedule-page-footer"><span><ShieldCheck aria-hidden="true" />Practice filters do not change your active session</span><span>Synthetic scheduling data · Preview only</span></footer>

    <Sheet open={notice !== null} onClose={() => setNotice(null)} title={notice === 'new' ? 'New appointment' : notice === 'reschedule' ? 'Reschedule appointment' : 'Appointment record'} description="Synthetic scheduling preview · No changes will be saved">
      {notice === 'appointment' && selected && selectedSession ? <><div className="patient-overview"><span className="avatar blue">{selected.patient.initials}</span><div><h3>{selected.patient.name}</h3><p>{selected.patient.mrn} · {selectedSession.site}</p></div></div><dl className="detail-grid"><div><dt>Appointment</dt><dd>{formatScheduleDate(selectedSession.date)}</dd></div><div><dt>Time</dt><dd>{selected.start}–{selected.end}</dd></div><div><dt>Visit</dt><dd>{selected.visit}</dd></div><div><dt>Booking</dt><dd>{selected.booking === 'CONFIRMED' ? 'Confirmed' : 'Pending confirmation'}</dd></div></dl><p className="preview-note"><Info aria-hidden="true" />Appointment review does not start an encounter or alter the queue.</p><Button onClick={() => { setNotice(null); navigate(chartPath(selected.patient)) }}>Open patient chart<ArrowRight aria-hidden="true" /></Button></> : <div className="schedule-notice"><span className="icon-tile blue">{notice === 'new' ? <Plus aria-hidden="true" /> : <CalendarClock aria-hidden="true" />}</span><h3>{notice === 'new' ? 'Appointment creation is not connected' : 'Rescheduling is not connected'}</h3><p>{notice === 'new' ? 'A server-authorized scheduling workflow is required before an appointment can be created.' : 'The original appointment and its change history must be preserved when a new slot is confirmed.'}</p><p className="preview-note"><Info aria-hidden="true" />This preview will not change appointment, queue, or patient records.</p></div>}
    </Sheet>
  </main>
}
