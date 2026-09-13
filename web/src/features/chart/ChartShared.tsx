import { useLayoutEffect, useState, type ReactNode } from 'react'
import { Activity, Building2, FileText, TriangleAlert, UserRound } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { doctor, doctorClinics, demoDate } from '../dashboard/demo-data'
import { ageAt, formatPatientDate, type DirectoryPatient } from '../patients/model'
import { hasClinicalFixture } from './model'

export function PatientBanner({ patient }: { patient: DirectoryPatient }) {
  const [headerHeight, setHeaderHeight] = useState(84)
  useLayoutEffect(() => {
    const header = document.querySelector('.topbar')
    if (!header) return
    const observer = new ResizeObserver(() => setHeaderHeight(header.getBoundingClientRect().height))
    observer.observe(header)
    return () => observer.disconnect()
  }, [])
  const clinic = doctorClinics.find(item => item.practiceId === patient.practiceId)!
  const age = ageAt(patient.birthDate, demoDate)
  return <section className="surface chart-patient-banner" style={{ top: headerHeight }} aria-label="Patient identity and alerts">
    <div className="chart-patient-row"><span className="avatar blue">{patient.initials}</span><div className="chart-patient-name"><h2>{patient.name}</h2><p>{patient.sex} · {age === null ? 'Age unknown' : `${patient.birthDateAccuracy === 'estimated' ? 'About ' : ''}${age} years`} · {patient.birthDateAccuracy === 'estimated' ? 'Estimated DOB' : 'DOB'} {formatPatientDate(patient.birthDate)} · MRN {patient.mrn}</p></div><div className="chart-context"><Building2 aria-hidden="true" /><span><small>Chart practice</small>{clinic.site} · {clinic.clinic}</span></div><div className="chart-context chart-clinician"><UserRound aria-hidden="true" /><span><small>Responsible clinician</small>{doctor}</span></div></div>
    <div className={`chart-alert ${hasClinicalFixture(patient) ? 'red' : 'amber'}`}><TriangleAlert aria-hidden="true" />{hasClinicalFixture(patient) ? <><strong>Allergy: Penicillin · Reported rash</strong><span>Patient reported · 15 Aug 2026 · Review due</span></> : <><strong>Allergy status unknown</strong><span>No allergy review available in this demo</span></>}</div>
    {patient.identity === 'provisional' ? <p className="chart-provisional">Provisional identity · Verify patient details before clinical actions.</p> : null}
  </section>
}
export function ClinicalPanel({ title, icon: Icon = FileText, children, action }: { title: string; icon?: typeof FileText; children: ReactNode; action?: ReactNode }) {
  return <section className="surface chart-panel"><header><span className="chart-panel-icon"><Icon aria-hidden="true" /></span><h2>{title}</h2>{action}</header>{children}</section>
}
export function LocalSections<T extends string>({ labels, value, onChange, name }: { labels: readonly T[]; value: T; onChange: (value: T) => void; name: string }) {
  return <><div className="chart-tabs" role="tablist" aria-label={name}>{labels.map((label, index) => <button key={label} role="tab" id={`local-tab-${index}`} aria-controls="clinical-section" aria-selected={label === value} tabIndex={label === value ? 0 : -1} onClick={() => onChange(label)} onKeyDown={event => { if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? labels.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + labels.length) % labels.length; onChange(labels[next]); document.getElementById(`local-tab-${next}`)?.focus() }}>{label}</button>)}</div><label className="chart-section-select">{name}<select aria-label={name} value={value} onChange={event => onChange(event.target.value as T)}>{labels.map(label => <option key={label}>{label}</option>)}</select></label></>
}
export function Observations({ available, onSource }: { available: boolean; onSource: () => void }) {
  return <ClinicalPanel title="Latest observations" icon={Activity}>{available ? <><dl className="chart-observations">{[['BP', '132/84', 'mmHg'], ['Pulse', '78', 'bpm'], ['Temperature', '36.7', '°C'], ['Weight', '64', 'kg']].map(([label, value, unit]) => <div key={label}><dt>{label}</dt><dd>{value}<small>{unit}</small></dd></div>)}</dl><p className="chart-source">Measured 12 Sep 2026, 9:42 AM PHT<br />Nurse L. Reyes · Manual entry</p><button className="text-link chart-source-link" onClick={onSource}>View source & history</button></> : <EmptyClinical title="Observations unavailable" detail="No measurement fixtures are available for this patient." />}</ClinicalPanel>
}
export function EmptyClinical({ title, detail }: { title: string; detail: string }) { return <div className="chart-empty"><FileText aria-hidden="true" /><h3>{title}</h3><p>{detail}</p></div> }
export type SourceKind = 'observations' | 'allergy' | 'report' | 'template'
export function SourceSheet({ kind, patient, onClose, template, onRestoreFocus }: { kind: SourceKind | null; patient: DirectoryPatient; onClose: () => void; template?: string; onRestoreFocus: () => void }) {
  return <Sheet open={!!kind} onClose={onClose} onRestoreFocus={onRestoreFocus} title={kind === 'observations' ? 'Observation source & history' : kind === 'allergy' ? 'Allergy record' : kind === 'template' ? 'Pinned template release' : 'External laboratory report'} description={`${patient.name} · ${patient.mrn} · ${doctorClinics.find(item => item.practiceId === patient.practiceId)?.site} · Synthetic data`}>
    {kind === 'observations' ? <><dl className="detail-grid"><div><dt>Measured</dt><dd>12 Sep 2026 · 9:42 AM PHT</dd></div><div><dt>Recorded</dt><dd>12 Sep 2026 · 9:44 AM PHT</dd></div><div><dt>Source / author</dt><dd>Manual entry · Nurse L. Reyes</dd></div><div><dt>Version</dt><dd>1 · Original synthetic measurement</dd></div></dl><p>BP 132/84 mmHg · Pulse 78 bpm · Temperature 36.7 °C · Weight 64 kg</p><p className="preview-note">No correction history is included in this fixture. Measurements retain their original date and source.</p></> : kind === 'allergy' ? <><div className="chart-alert red"><TriangleAlert aria-hidden="true" /><strong>Penicillin · Reported rash</strong></div><dl className="detail-grid"><div><dt>Source</dt><dd>Patient reported · 15 Aug 2026</dd></div><div><dt>Review</dt><dd>Review due</dd></div><div><dt>Severity / onset</dt><dd>Not recorded</dd></div><div><dt>Certainty</dt><dd>Not verified</dd></div></dl><p>Opening this record does not reconcile or verify the allergy.</p><Button disabled className="chart-disabled-action">Confirm review</Button><p className="chart-source">Clinical review requires a connected service.</p></> : kind === 'template' ? <><h3>{template}</h3><p className="preview-note">This encounter is pinned to its original template release. Opening a newer template must not change this note.</p><p>Stable fields: reason, history, examination, assessment, visit diagnoses, plan, disposition, follow-up and return precautions.</p></> : kind === 'report' ? <><span className="badge amber">Awaiting clinician review</span><dl className="detail-grid"><div><dt>Received</dt><dd>11 Sep 2026</dd></div><div><dt>Owner</dt><dd>{doctor}</dd></div><div><dt>Due</dt><dd>11 Sep 2026 · Overdue</dd></div><div><dt>Source</dt><dd>External upload · Synthetic metadata</dd></div></dl><EmptyClinical title="Report attachment unavailable" detail="This fixture includes the review task only. No original file, verified results or reference ranges are available." /><p className="preview-note">Opening this record does not mark it reviewed. The task remains assigned to {doctor}.</p></> : null}
  </Sheet>
}
