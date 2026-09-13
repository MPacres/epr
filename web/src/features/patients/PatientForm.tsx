import { useEffect, useRef, useState, type FocusEvent } from 'react'
import { FormProvider, useForm, useWatch, type FieldErrors } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useBlocker, useNavigate } from 'react-router-dom'
import { ArrowLeft, ArrowRight, Building2, Check, CircleAlert, Clock3, Info, Save, Search, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { activeSession, doctorClinics } from '../dashboard/demo-data'
import { formatPatientDate, patientKey } from './model'
import { FormField } from './PatientFormField'
import { PatientFormSections } from './PatientFormSections'
import { usePatientPreview } from './patient-preview-context'
import { changedFields, changeValue, draftFields, duplicateCandidates, effectiveDraft, emptyDraft, fullPatientName, type DemoPatientRecord, type FormSection, type PatientDraft } from './patient-form-model'
import { duplicateReviewKey, PreviewSaveError, type DemoOutcome } from './preview-repository'
import { patientDraftSchema } from './patient-form-schema'
import './patient-form.css'

export function PatientForm({ practiceId, patientId }: { practiceId: string; patientId?: string }) {
  const { records } = usePatientPreview()
  const practice = doctorClinics.find(item => item.practiceId === practiceId)
  const record = records.find(item => item.practiceId === practiceId && item.id === patientId)
  const navigate = useNavigate()
  if (!practice || (patientId && !record)) return <main className="dashboard patient-form-workspace" id="main-content" tabIndex={-1}><div className="surface directory-empty"><ShieldCheck aria-hidden="true" /><h1>Patient record unavailable</h1><p>This record is not available in the selected practice. No details have been loaded.</p><Button onClick={() => navigate('/patients')}>Back to My Patients</Button></div></main>
  return <PatientFormEditor key={`${practiceId}:${patientId ?? 'new'}`} practiceId={practiceId} practiceName={practice.site} record={record} />
}
function PatientFormEditor({ practiceId, practiceName, record }: { practiceId: string; practiceName: string; record?: DemoPatientRecord }) {
  const navigate = useNavigate()
  const { records, save, repository } = usePatientPreview()
  const [baseline, setBaseline] = useState(record)
  const [openSections, setOpenSections] = useState<FormSection[]>(record ? ['address','philhealth'] : ['demographics','philhealth'])
  const [reviewedKey, setReviewedKey] = useState<string | null>(null)
  const [saveError, setSaveError] = useState('')
  const [success, setSuccess] = useState('')
  const [outcome, setOutcome] = useState<DemoOutcome>('success')
  const id = useRef(record?.id ?? crypto.randomUUID())
  const allowExit = useRef(false)
  const submitting = useRef(false)
  const request = useRef({ key: crypto.randomUUID(), fingerprint: '' })
  const errorSummary = useRef<HTMLDivElement>(null)
  const form = useForm<PatientDraft>({ defaultValues: record?.draft ?? { ...emptyDraft }, resolver: zodResolver(patientDraftSchema), shouldFocusError: false })
  const draft = useWatch({ control: form.control }) as PatientDraft
  const { isDirty, isSubmitting, errors } = form.formState
  const changes = changedFields(baseline?.draft ?? emptyDraft, draft)
  const candidates = duplicateCandidates(records, draft, practiceId, record?.id)
  const reviewKey = duplicateReviewKey(records, draft, practiceId, record?.id)
  const hasReview = !reviewKey || reviewKey === reviewedKey
  const blocker = useBlocker(({ currentLocation, nextLocation }) => !allowExit.current && (isDirty || isSubmitting) && `${currentLocation.pathname}${currentLocation.search}` !== `${nextLocation.pathname}${nextLocation.search}`)
  useEffect(() => {
    if (!isDirty && !isSubmitting) return
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [isDirty, isSubmitting])
  useEffect(() => { document.title = `${record ? 'Edit patient' : 'New patient'} · EPR` }, [record])
  function toggleSection(section: FormSection) { setOpenSections(sections => sections.includes(section) ? sections.filter(item => item !== section) : [...sections,section]) }
  function showField(name: keyof PatientDraft) {
    setOpenSections(sections => [...new Set([...sections,draftFields[name].section])])
    requestAnimationFrame(() => { document.getElementById(`patient-field-${name}`)?.focus(); document.getElementById(`patient-field-${name}`)?.scrollIntoView({block:'center'}) })
  }
  function onInvalid(invalid: FieldErrors<PatientDraft>) {
    setSaveError(''); setSuccess('')
    setOpenSections(sections => [...new Set([...sections,...(Object.keys(invalid) as (keyof PatientDraft)[]).map(name => draftFields[name].section)])])
    requestAnimationFrame(() => { errorSummary.current?.focus(); errorSummary.current?.scrollIntoView({block:'center'}) })
  }
  async function onSave(values: PatientDraft) {
    if (submitting.current) return
    setSaveError(''); setSuccess('')
    if (!hasReview) { setSaveError('Review the possible matching records, then confirm that this is a separate patient.'); requestAnimationFrame(() => errorSummary.current?.focus()); return }
    submitting.current = true
    const fingerprint = JSON.stringify([values, reviewedKey, baseline?.version ?? null])
    if (request.current.fingerprint !== fingerprint) request.current = { key: crypto.randomUUID(), fingerprint }
    try {
      const saved = await save({ practiceId, patientId: id.current, expectedVersion: baseline?.version ?? null, idempotencyKey: request.current.key, draft: values, duplicateReview: reviewedKey }, outcome)
      form.reset(saved.draft)
      setBaseline(saved)
      setReviewedKey(null)
      if (!record) {
        allowExit.current = true
        navigate(`/patients?practice=${encodeURIComponent(practiceId)}&patient=${encodeURIComponent(saved.id)}`, { replace:true, state:{ patientSave: `${saved.name} added to the demo workspace. Changes reset on reload.` } })
      } else {
        setSuccess('Demo changes applied. The directory and global search are updated. Reloading resets this workspace.')
        request.current = { key:crypto.randomUUID(),fingerprint:'' }
      }
    } catch (error) {
      setSaveError(error instanceof PreviewSaveError ? error.message : 'Changes could not be applied. Your draft has been kept; review it and try again.')
      requestAnimationFrame(() => { errorSummary.current?.focus(); errorSummary.current?.scrollIntoView({block:'center'}) })
    } finally { submitting.current = false }
  }
  function keepFieldVisible(event: FocusEvent<HTMLElement>) {
    const target = event.target
    if (!(target instanceof HTMLElement) || !target.closest('.patient-form-sections, .patient-form-aside')) return
    const bounds = target.getBoundingClientRect()
    const top = document.querySelector('.topbar')?.getBoundingClientRect().bottom ?? 0
    const bottom = document.querySelector('.patient-save-bar')?.getBoundingClientRect().top ?? window.innerHeight
    if (bounds.top < top + 12 || bounds.bottom > bottom - 12) {
      window.scrollBy({ top: bounds.top - top - Math.max(12, (bottom - top - bounds.height) / 2), behavior: 'instant' })
    }
  }
  const effectiveBefore = effectiveDraft(baseline?.draft ?? emptyDraft), effectiveAfter = effectiveDraft(draft)
  const history = record ? repository.historyFor(practiceId,record.id) : []
  return <main id="main-content" className="dashboard patient-form-workspace" tabIndex={-1} onFocusCapture={keepFieldVisible}>
    <nav className="patient-breadcrumb" aria-label="Breadcrumb"><button onClick={() => navigate(`/patients?practice=${encodeURIComponent(practiceId)}${record ? `&patient=${encodeURIComponent(record.id)}` : ''}`)}><ArrowLeft aria-hidden="true" />My Patients</button><span>/</span>{record ? <><span>{baseline?.name}</span><span>/</span></> : null}<span aria-current="page">{record ? 'Edit patient' : 'New patient'}</span></nav>
    <div className="page-heading patient-form-heading"><div><h1>{record ? 'Edit patient' : 'New patient'}</h1><p>{record ? 'Update patient details' : 'Create a demo patient record'} at {practiceName}.</p></div><span className={`form-draft-status ${isDirty ? 'has-changes' : ''}`}><span className="status-dot" />{isDirty ? 'Unsaved changes' : record ? 'No unsaved changes' : 'New record'}</span></div>
    <p className="form-demo-notice"><Info aria-hidden="true" />Demo workspace · Use synthetic information. Changes stay in memory and reset on reload.</p>
    {practiceId !== activeSession.practiceId ? <p className="form-practice-notice"><Building2 aria-hidden="true" />Editing {practiceName} records. Hospital A remains your active clinical session.</p> : null}
    {baseline ? <div className="form-patient-identity"><span className="avatar blue" aria-hidden="true">{baseline.initials}</span><div><strong>{baseline.name}</strong><span>{baseline.mrn} · {baseline.birthDateAccuracy === 'estimated' ? 'Estimated ' : ''}{formatPatientDate(baseline.birthDate)} · {practiceName}</span></div>{baseline.identity === 'provisional' ? <span className="badge amber">Provisional identity</span> : null}</div> : null}
    <FormProvider {...form}><form noValidate onSubmit={event => { void form.handleSubmit(onSave,onInvalid)(event) }}>
      {Object.keys(errors).length || saveError ? <div className="form-error-summary" ref={errorSummary} tabIndex={-1} role="alert"><CircleAlert aria-hidden="true" /><div><h2>{saveError ? 'Changes not applied' : 'Check the highlighted fields'}</h2>{saveError ? <p>{saveError}</p> : <ul>{(Object.keys(errors) as (keyof PatientDraft)[]).map(name => <li key={name}><button type="button" onClick={() => showField(name)}>{draftFields[name].label}: {errors[name]?.message}</button></li>)}</ul>}</div></div> : null}
      {success ? <p className="form-success" role="status"><Check aria-hidden="true" />{success}</p> : null}
      <fieldset disabled={isSubmitting} className="patient-form-grid">
        <PatientFormSections openSections={openSections} onToggle={toggleSection} mrn={baseline?.mrn} practice={practiceName} />
        <aside className="patient-form-aside" aria-label="Patient record and review">
          <section className="surface form-side-card"><h2><Building2 aria-hidden="true" />{record ? 'Patient record' : 'Registration details'}</h2><dl><div><dt>Practice</dt><dd>{practiceName}</dd></div><div><dt>Patient ID</dt><dd>{baseline?.mrn ?? 'Assigned after demo save'}</dd></div><div><dt>Record storage</dt><dd>Memory only</dd></div></dl></section>
          {record ? <section className="surface form-side-card changes-card"><h2><Clock3 aria-hidden="true" />Changes to save<span className="tab-count">{changes.length}</span></h2>{changes.length ? <ul className="change-list">{changes.map(field => <li key={field}><button type="button" onClick={() => showField(field)}>{draftFields[field].label}</button><div><span>{changeValue(field,effectiveBefore[field])}</span><ArrowRight aria-label="changes to" /><strong>{changeValue(field,effectiveAfter[field])}</strong></div></li>)}</ul> : <p className="form-help">Changes will appear here as you edit.</p>}<FormField name="changeNote" label="Change note (optional)" type="textarea" hint="Kept with the before-and-after values in demo history until reload." />{history.length ? <details className="demo-history"><summary>{history.length} demo {history.length === 1 ? 'change' : 'changes'} applied</summary>{history.map(entry => <p key={entry.after.version}>Revision {entry.after.version}: {entry.note || 'No change note provided'}</p>)}</details> : null}</section> : null}
          <section className="surface form-side-card duplicate-card"><h2><Search aria-hidden="true" />Possible matches</h2><p className="form-help">Name and birth-date check against {practiceName} demo records only.</p>{!fullPatientName(draft).trim() ? <p className="duplicate-state">Enter a name to check for existing records.</p> : candidates.length ? <><p className="duplicate-state has-matches"><TriangleAlert aria-hidden="true" />{candidates.length} possible {candidates.length === 1 ? 'match' : 'matches'} · Review required</p><ul className="candidate-list">{candidates.map(({patient,reason}) => <li key={patientKey(patient)}><strong>{patient.name}</strong><span>{patient.mrn} · {formatPatientDate(patient.birthDate)}</span><small>{reason}</small><button type="button" onClick={() => navigate(`/patients/${encodeURIComponent(practiceId)}/${encodeURIComponent(patient.id)}/edit`)}>Open existing record<ArrowRight aria-hidden="true" /></button></li>)}</ul><label className="form-checkbox duplicate-confirm"><input type="checkbox" checked={hasReview} onChange={event => setReviewedKey(event.target.checked ? reviewKey : null)} /><span>I reviewed these matches. This is a separate patient record.</span></label></> : <p className="duplicate-state"><Check aria-hidden="true" />No matches in this demo check</p>}<p className="form-help duplicate-footnote">Confirm identity with the patient. Records are never merged automatically.</p></section>
          <p className="form-side-note"><ShieldCheck aria-hidden="true" />Saving patient details does not add a visit or start an encounter.</p>
          <details className="demo-scenarios"><summary>Demo save scenarios</summary><label htmlFor="demo-outcome">Test the save response</label><select id="demo-outcome" value={outcome} onChange={event => setOutcome(event.target.value as DemoOutcome)}><option value="success">Normal save</option><option value="unavailable">Simulated connection failure</option><option value="conflict">Simulated version conflict</option></select><p>These outcomes are simulated. No clinical service is connected.</p></details>
        </aside>
      </fieldset>
      <footer className="patient-save-bar"><div className="save-record-context"><strong>{baseline?.name ?? 'New demo patient'}</strong><span>{baseline?.mrn ? `${baseline.mrn} · ` : ''}{practiceName}<span className="save-change-count"> · {changes.length} {changes.length === 1 ? 'change' : 'changes'}</span></span></div><div className="save-buttons"><Button variant="outline" onClick={() => navigate(`/patients?practice=${encodeURIComponent(practiceId)}${record ? `&patient=${encodeURIComponent(record.id)}` : ''}`)} disabled={isSubmitting}>Cancel</Button><Button type="submit" disabled={isSubmitting || (!!record && !isDirty)}><Save aria-hidden="true" />{isSubmitting ? 'Applying…' : record ? 'Save demo changes' : 'Add demo patient'}</Button></div></footer>
    </form></FormProvider>
    <ConfirmationDialog open={blocker.state === 'blocked'} title="Discard unsaved patient changes?" description={`Your changes for ${baseline?.name ?? 'this new patient'} at ${practiceName} have not been applied. Leave this form and discard them, or keep editing.`} onCancel={() => blocker.state === 'blocked' && blocker.reset()} onConfirm={() => { if (blocker.state === 'blocked') blocker.proceed() }} />
  </main>
}
