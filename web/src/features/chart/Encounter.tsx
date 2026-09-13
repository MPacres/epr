import { useSourceSheet } from './use-source-sheet'
import { useEffect, useState } from 'react'
import { Link, useBlocker } from 'react-router-dom'
import { ClipboardCheck, FileText, LockKeyhole, Plus, ShieldCheck, X } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { doctor, doctorClinics } from '../dashboard/demo-data'
import { usePatientPreview } from '../patients/patient-preview-context'
import { formatPatientDate, type DirectoryPatient } from '../patients/model'
import { ChartSection, ChartUnavailable } from './PatientChart'
import { ClinicalPanel, LocalSections, Observations, PatientBanner, SourceSheet } from './ChartShared'
import { chartPath, encounterPath, findChartPatient, findEncounter, initialNote, noteChanged, type DemoEncounter, type EncounterNote } from './model'
import './chart.css'

const sections = ['Consultation note', 'Medications', 'Orders & Results', 'Documents', 'Activity'] as const
const noteFields = [
  { key: 'history', label: 'History of present illness', placeholder: 'Document the patient’s account and relevant interval history…', rows: 4 },
  { key: 'examination', label: 'Examination', placeholder: 'Record findings assessed during this encounter…', rows: 3 },
  { key: 'assessment', label: 'Assessment', placeholder: 'Document the clinical assessment…', rows: 3 },
] as const
export function Encounter({ practiceId, patientId, encounterId }: { practiceId: string; patientId: string; encounterId: string }) {
  const { records } = usePatientPreview()
  const patient = findChartPatient(records, doctorClinics.map(item => item.practiceId), practiceId, patientId)
  const encounter = patient && findEncounter(patient, encounterId)
  return patient && encounter ? <EncounterContent patient={patient} encounter={encounter} /> : <ChartUnavailable />
}
function EncounterContent({ patient, encounter }: { patient: DirectoryPatient; encounter: DemoEncounter }) {
  const [tab, setTab] = useState<typeof sections[number]>('Consultation note')
  const { source, openSource, closeSource, restoreSourceFocus } = useSourceSheet()
  const [baseline] = useState(() => initialNote(encounter))
  const [note, setNote] = useState<EncounterNote>(baseline)
  const [diagnosis, setDiagnosis] = useState('')
  const [diagnosisError, setDiagnosisError] = useState('')
  const signed = encounter.documentation === 'Signed'
  const dirty = !signed && (noteChanged(note, baseline) || diagnosis.length > 0)
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && `${currentLocation.pathname}${currentLocation.search}` !== `${nextLocation.pathname}${nextLocation.search}`)
  useEffect(() => {
    function beforeUnload(event: BeforeUnloadEvent) { if (dirty) event.preventDefault() }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty])
  function update(key: keyof Omit<EncounterNote, 'diagnoses'>, value: string) { setNote(previous => ({ ...previous, [key]: value })) }
  function addDiagnosis() {
    const value = diagnosis.trim()
    if (!value) { setDiagnosisError('Enter a visit diagnosis before adding it.'); return }
    if (note.diagnoses.some(item => item.toLocaleLowerCase() === value.toLocaleLowerCase())) { setDiagnosisError('This visit diagnosis is already listed.'); return }
    setNote(previous => ({ ...previous, diagnoses: [...previous.diagnoses, value] })); setDiagnosis(''); setDiagnosisError('')
  }
  return <main id="main-content" className="dashboard chart-workspace encounter-workspace" tabIndex={-1}>
    <nav className="chart-breadcrumb" aria-label="Breadcrumb"><Link to={chartPath(patient)}>Patient chart</Link><span>/</span><span>{patient.name}</span></nav>
    <div className="page-heading"><div><h1>Follow-up encounter</h1><p>{formatPatientDate(encounter.date)} · {encounter.id} · Earlier encounter</p></div><Link className="text-link chart-outline-link" to={chartPath(patient)}>View patient chart</Link></div>
    <PatientBanner patient={patient} />
    <div className="encounter-context"><div className="chart-statuses"><span className="badge neutral">Care: Finished</span><span className={`badge ${signed ? 'green' : 'amber'}`}>Note: {encounter.documentation}</span></div><span><LockKeyhole aria-hidden="true" />Hospital A · Outpatient Clinic · {formatPatientDate(encounter.date)} · {doctor}</span></div>
    <p className="chart-caption encounter-boundary">Synthetic historical encounter. Today’s queue remains unchanged. {signed ? 'Signed document bytes and signature evidence are unavailable in this demo.' : 'Demo edits stay in this open screen and are discarded on leaving or reload.'}</p>
    <LocalSections name="Encounter section" labels={sections} value={tab} onChange={setTab} />
    <div className="encounter-grid"><div id="clinical-section" role="tabpanel" aria-labelledby={`local-tab-${sections.indexOf(tab)}`}>
      {tab === 'Consultation note' ? <ClinicalPanel title={signed ? 'Signed note · Read-only' : 'Consultation note'} action={<button className="encounter-template" onClick={() => openSource('template')}>{encounter.template}<LockKeyhole aria-hidden="true" /></button>}>
        <div className="encounter-author"><span>Author: {doctor}</span><span role="status">{signed ? 'Historical metadata only' : dirty ? 'Unsaved demo changes · Not saved to server' : 'Demo draft · Not saved to server'}</span></div>
        {signed ? <><dl className="chart-history"><div><dt>Encounter</dt><dd>{encounter.reason}</dd></div><div><dt>Care date</dt><dd>{formatPatientDate(encounter.date)}</dd></div><div><dt>Documentation</dt><dd>Signed · Synthetic status</dd></div></dl><div className="chart-empty"><LockKeyhole aria-hidden="true" /><h3>Original signed document unavailable</h3><p>This demo contains historical metadata only. The original signed note has not been supplied and cannot be reconstructed from the current template.</p></div><Button disabled variant="outline">Add amendment</Button><p className="chart-caption">Amendments require the original signed record and a connected service.</p></> : <form className="encounter-form" onSubmit={event => event.preventDefault()}>
          <label>Reason for visit<input aria-label="Reason for visit" value={note.reason} onChange={event => update('reason', event.target.value)} /></label>
          {noteFields.map(field => <label key={field.key}>{field.label}<textarea aria-label={field.label} rows={field.rows} value={note[field.key]} placeholder={field.placeholder} onChange={event => update(field.key, event.target.value)} />{field.key === 'examination' ? <small>Findings are not carried forward automatically.</small> : null}</label>)}
          <fieldset><legend>Visit diagnoses</legend><p className="chart-caption">Add only diagnoses assessed at this visit. Ongoing problems are reference information.</p><div className="encounter-diagnoses">{note.diagnoses.map(value => <span key={value}>{value}<button aria-label={`Remove diagnosis ${value}`} onClick={() => setNote(previous => ({ ...previous, diagnoses: previous.diagnoses.filter(item => item !== value) }))}><X aria-hidden="true" /></button></span>)}</div><div className="encounter-diagnosis-entry"><input aria-label="Visit diagnosis" aria-invalid={!!diagnosisError} aria-describedby={diagnosisError ? 'diagnosis-error' : undefined} value={diagnosis} placeholder="Enter a visit diagnosis" onChange={event => { setDiagnosis(event.target.value); setDiagnosisError('') }} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); addDiagnosis() } }} /><Button variant="outline" onClick={addDiagnosis}><Plus aria-hidden="true" />Add diagnosis</Button></div>{diagnosisError ? <p id="diagnosis-error" role="alert" className="encounter-field-error">{diagnosisError}</p> : null}{!note.diagnoses.length ? <p className="chart-caption">No visit diagnosis entered</p> : null}</fieldset>
          <label>Plan & counselling<textarea aria-label="Plan & counselling" rows={4} value={note.plan} placeholder="Treatment, counselling and agreed next steps…" onChange={event => update('plan', event.target.value)} /></label>
          <fieldset><legend>Disposition & follow-up</legend><div className="encounter-two-fields"><label>Disposition<select aria-label="Disposition" value={note.disposition} onChange={event => update('disposition', event.target.value)}><option value="">Not recorded</option><option>Home</option><option>Referred for further care</option><option>Transferred</option><option>Other</option></select></label><label>Follow-up date<input aria-label="Follow-up date" type="date" min={encounter.date} value={note.followup} onChange={event => update('followup', event.target.value)} /></label></div><label>Return precautions<textarea aria-label="Return precautions" rows={3} value={note.precautions} placeholder="Document advice discussed with the patient…" onChange={event => update('precautions', event.target.value)} /></label></fieldset>
        </form>}
      </ClinicalPanel> : tab === 'Activity' ? <ClinicalPanel title="Encounter activity"><div className="chart-record-row"><div><h3>{formatPatientDate(encounter.date)} · Historical encounter</h3><p>Care finished · Note {encounter.documentation.toLowerCase()} · {doctor}</p><p>Template: {encounter.template}</p></div></div><p className="chart-caption">Exact care and signature times and audit evidence are unavailable in this fixture. Demo edits are not clinical audit events.</p></ClinicalPanel> : <ChartSection section={tab} patient={patient} onSource={openSource} />}
    </div><aside className="chart-stack">
      <Observations available onSource={() => openSource('observations')} /><p className="chart-caption">Chart reference measured 12 Sep 2026, after this encounter. Not an observation from this visit.</p>
      <ClinicalPanel title="Review & reconcile" icon={ClipboardCheck}><div className="encounter-review-row"><span><strong>Allergies</strong><small>Review due</small></span><Button variant="outline" size="sm" onClick={() => openSource('allergy')}>View allergy</Button></div><div className="encounter-review-row"><span><strong>Medications</strong><small>2 reported · Review due</small></span><Button variant="outline" size="sm" onClick={() => setTab('Medications')}>View list</Button></div><div className="encounter-review-row"><span><strong>Imported report</strong><small>Awaiting clinician review</small></span><button className="text-link" onClick={() => openSource('report')}>Open report</button></div></ClinicalPanel>
      <ClinicalPanel title="Chart reference"><div className="chart-reference-row">Essential hypertension<span className="badge green">Active</span></div><div className="chart-reference-row">Type 2 diabetes<span className="badge green">Active</span></div><p className="chart-source">Ongoing problems · Not this visit’s diagnoses<br />Recorded 15 Aug 2026 · {doctor}</p>{!signed ? <Link className="text-link" to={encounterPath(patient, 'EN-DEMO-005')}>Previous signed note · 15 Aug 2026</Link> : null}</ClinicalPanel>
      <ClinicalPanel title="Visit actions"><div className="encounter-visit-actions"><Button variant="outline" disabled><Plus aria-hidden="true" />Prescription draft</Button><Button variant="outline" disabled><Plus aria-hidden="true" />Order draft</Button><Button variant="outline" disabled><Plus aria-hidden="true" />Follow-up task</Button></div><p className="chart-source">A connected service is required. Nothing issued.<br />Follow-up owner: {doctor}</p></ClinicalPanel>
    </aside></div>
    {!signed ? <footer className="encounter-actions"><div><p><ShieldCheck aria-hidden="true" />Synthetic data · No server connection</p><small>Care completion and note signing are separate actions.</small></div><div><Button variant="outline" disabled>Save draft</Button><Button variant="outline" disabled>Care finished</Button><Button disabled><FileText aria-hidden="true" />Review & sign</Button></div></footer> : null}
    <SourceSheet onRestoreFocus={restoreSourceFocus} kind={source} patient={patient} template={encounter.template} onClose={closeSource} />
    <ConfirmationDialog open={blocker.state === 'blocked'} title="Discard unsaved encounter changes?" description={`Your demo note changes for ${patient.name}, ${patient.mrn}, at Hospital A have not been saved. Leaving this screen discards them.`} onCancel={() => blocker.state === 'blocked' && blocker.reset()} onConfirm={() => blocker.state === 'blocked' && blocker.proceed()} />
  </main>
}
