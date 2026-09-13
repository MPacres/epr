import { ArrowRight, CalendarDays, Info, PencilLine, ShieldCheck, TriangleAlert } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { activeSession, demoDate, doctor, doctorClinics, queue } from '../dashboard/demo-data'
import { ageAt, formatPatientDate, type DirectoryPatient } from './model'

export function PatientDetails({ patient, overview = false, onOpenOverview }: {
  patient: DirectoryPatient; overview?: boolean; onOpenOverview: () => void
}) {
  const navigate = useNavigate()
  const clinic = doctorClinics.find(item => item.practiceId === patient.practiceId)
  const age = ageAt(patient.birthDate, demoDate)
  const entry = queue.find(item => item.patient.id === patient.id && item.patient.practiceId === patient.practiceId && item.sessionId === activeSession.id)
  return <div className="patient-details">
    <div className="directory-identity"><span className="avatar blue" aria-hidden="true">{patient.initials}</span><div><h2>{patient.name}</h2><p>{patient.mrn} <span>·</span> {clinic?.site}</p></div></div>
    <button className="edit-patient-link" onClick={() => navigate(`/patients/${encodeURIComponent(patient.practiceId)}/${encodeURIComponent(patient.id)}/edit`)}><PencilLine aria-hidden="true" />Edit patient details</button>
    {patient.identity === 'provisional' ? <div className="identity-notice"><TriangleAlert aria-hidden="true" /><p><strong>Provisional identity</strong><span>Verify identity details before clinical actions.</span></p></div> : null}
    <dl className="patient-facts">
      <div><dt>Date of birth</dt><dd>{patient.birthDateAccuracy === 'estimated' ? 'Estimated ' : ''}{formatPatientDate(patient.birthDate)}{age !== null ? <span> · {patient.birthDateAccuracy === 'estimated' ? 'about ' : ''}{age} years</span> : null}</dd></div>
      <div><dt>Sex</dt><dd>{patient.sex}</dd></div>
      <div><dt>City</dt><dd>{patient.city ?? 'Not recorded'}</dd></div>
      <div><dt>Last visit</dt><dd>{patient.lastVisit ? <>{formatPatientDate(patient.lastVisit.date)}<span> · {patient.lastVisit.type}</span></> : 'No recorded visits'}</dd></div>
    </dl>
    <div className="patient-visit"><CalendarDays aria-hidden="true" /><div><h3>Visit on {formatPatientDate(demoDate)}</h3>{entry ? <><p>{entry.visit} · {clinic?.site}</p><p><span className="status-dot" />Ready for doctor · Queue #{entry.token}</p><small>{activeSession.clinic} · {activeSession.time}<br />{doctor} · Current session</small></> : <p>No visit listed in this demo snapshot.</p>}</div></div>
    {overview ? <><dl className="patient-facts overview-facts"><div><dt>Allergy review</dt><dd>Not available in preview</dd></div><div><dt>Medication review</dt><dd>Not available in preview</dd></div></dl><p className="patient-scope-note"><Info aria-hidden="true" />The longitudinal chart is not connected. This read-only overview does not create an encounter.</p></> : <div className="patient-detail-actions"><Button onClick={onOpenOverview}>Open patient chart<ArrowRight aria-hidden="true" /></Button><Button variant="outline" disabled aria-describedby="scheduling-unavailable"><CalendarDays aria-hidden="true" />Schedule visit</Button><p id="scheduling-unavailable">Scheduling is not connected in this preview.</p></div>}
    <p className="patient-record-scope"><ShieldCheck aria-hidden="true" />Patient record at {clinic?.site} · Synthetic data</p>
  </div>
}
