import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Search, ShieldCheck } from 'lucide-react'
import { doctor, doctorClinics } from '../dashboard/demo-data'
import { usePatientPreview } from '../patients/patient-preview-context'
import { formatPatientDate, type DirectoryPatient } from '../patients/model'
import { ChartUnavailable } from './PatientChart'
import { EmptyClinical, PatientBanner } from './ChartShared'
import { chartPath, encounterPath, filterPatientNotes, findChartPatient, patientEncounters, type DemoEncounter } from './model'
import './chart.css'

type NoteStatus = 'All' | DemoEncounter['documentation']
const statuses: readonly NoteStatus[] = ['All', 'Draft', 'Signed']

export function PatientNotes({ practiceId, patientId }: { practiceId: string; patientId: string }) {
  const { records } = usePatientPreview()
  const patient = findChartPatient(records, doctorClinics.map(item => item.practiceId), practiceId, patientId)
  return patient ? <NotesContent patient={patient} /> : <ChartUnavailable />
}

function NotesContent({ patient }: { patient: DirectoryPatient }) {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<NoteStatus>('All')
  const notes = patientEncounters(patient)
  const visibleNotes = filterPatientNotes(notes, query, status)

  return <main id="main-content" className="dashboard chart-workspace notes-workspace" tabIndex={-1}>
    <nav className="chart-breadcrumb" aria-label="Breadcrumb"><Link to={chartPath(patient)}>Patient chart</Link><span>/</span><span>Clinical notes</span></nav>
    <div className="page-heading"><div><h1>Clinical notes</h1><p>Previous draft and signed notes for this patient</p></div><Link className="text-link chart-outline-link" to={chartPath(patient)}>View patient chart</Link></div>
    <PatientBanner patient={patient} />
    <section className="surface notes-panel" aria-labelledby="notes-heading">
      <div className="notes-panel-heading"><div><h2 id="notes-heading">Note history</h2><p>Practice-scoped synthetic records · Opening a note does not start care.</p></div><span className="notes-count">{notes.length} {notes.length === 1 ? 'record' : 'records'}</span></div>
      <div className="notes-tools"><label className="chart-search notes-search"><Search aria-hidden="true" /><input type="search" placeholder="Search notes by visit, date, ID, or template…" aria-label="Search clinical notes" value={query} onChange={event => setQuery(event.target.value)} /></label><div className="chart-filters notes-filters" role="group" aria-label="Note status">{statuses.map(value => <button key={value} type="button" aria-pressed={status === value} onClick={() => setStatus(value)}>{value}{value === 'All' ? ` (${notes.length})` : ` (${notes.filter(note => note.documentation === value).length})`}</button>)}</div></div>
      {visibleNotes.length ? <ol className="notes-list">{visibleNotes.map(note => <li key={note.id} className="notes-row"><div className="notes-row-main"><div className="notes-row-title"><h3>{note.reason}</h3><span className={`badge ${note.documentation === 'Draft' ? 'amber' : 'green'}`}>{note.documentation}</span></div><p>{formatPatientDate(note.date)} · {note.id} · {doctor}</p><p>{note.template} · Care {note.care.toLowerCase()}</p><small>{note.documentation === 'Signed' ? 'Signed status only · Original signed document unavailable in this demo' : 'Demo draft · Changes made in the note screen are not saved'}</small></div><Link className="text-link notes-open" to={encounterPath(patient, note.id)} aria-label={`${note.documentation === 'Draft' ? 'Open draft' : 'View signed note'}: ${note.reason}, ${formatPatientDate(note.date)}`}>{note.documentation === 'Draft' ? 'Open draft' : 'View signed note'}<ArrowRight aria-hidden="true" /></Link></li>)}</ol> : <EmptyClinical title={notes.length ? 'No matching notes' : 'Note history unavailable'} detail={notes.length ? 'Try another search or status filter.' : 'No clinical note fixtures are available for this patient. This does not establish an absence of care.'} />}
    </section>
    <footer className="dashboard-footer"><span><ShieldCheck aria-hidden="true" />Practice-scoped notes · Synthetic data</span><span>No server connection</span></footer>
  </main>
}
