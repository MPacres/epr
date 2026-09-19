import { useCallback, useEffect, useReducer, useState, useRef } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useBlocker, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Check, FileLock2, Save, ShieldCheck } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { createUuid } from '../../lib/create-uuid'
import { DynamicField } from '../encounter-templates/renderer/DynamicFields'
import { fieldsOf } from '../encounter-templates/schema/contracts'
import { useFormEdits } from './hooks/use-form-edits'
import { DraftWriter } from './hooks/draft-writer'
import { diffAnswers, displayAnswer, fromAnswers, type DraftValues } from './services/draft-values'
import { clinicalApi, clinicalKey, patientPath, ClinicalRequestError, type Encounter, type Scope } from './services/encounter-api'
import { EncounterInterventions, type PendingWork } from '../interventions/EncounterInterventions'
import { EncounterSigning, type EncounterSigningActions } from './EncounterSigning'
import type * as Wire from './services/api.generated'
import { patientApi, patientKeys } from '../patients/patient-api'
import { PatientContextHeader } from './PatientContextHeader'
const time = (value: string) => new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Manila' }).format(new Date(value))
export function EncounterWorkspace({ scope, initial }: { scope: Scope; initial: Encounter }) {
  const auxiliary = useFormEdits()
  const [interventionPending, setInterventionPending] = useState<PendingWork>({ dirty: false, mutationPending: false, unresolvedOutcome: false })
  const [interventionVersion, setInterventionVersion] = useState(0)
  const [interventionDenied, setInterventionDenied] = useState(false)
  const denyInterventions = useCallback(() => setInterventionDenied(true), [])
  const interventionBlocked = interventionPending.dirty || interventionPending.mutationPending || interventionPending.unresolvedOutcome
  const navigate = useNavigate()
  const query = useQueryClient()
  const patientDetail = useQuery({ queryKey: [...patientKeys(scope), initial.patientId], queryFn: ({ signal }) => patientApi.detail(scope, initial.patientId, signal), retry: false })
  const form = useForm<{ answers: DraftValues }>({ defaultValues: { answers: fromAnswers(initial.answers) } })
  const values = useWatch({ control: form.control, name: 'answers' })
  const [revision, redraw] = useReducer(n => n + 1, 0)
  const [record, setRecord] = useState(initial)
  const releaseButton = useRef<HTMLButtonElement>(null)
  const reviewButton = useRef<HTMLButtonElement>(null)
  const signingActions = useRef<EncounterSigningActions>(null)
  const [releaseOpen, setReleaseOpen] = useState(false)
  const [server, setServer] = useState<Encounter | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [transition, setTransition] = useState<{ key: string; request: Wire.Transition } | null>(null)
  const [transitionBusy, setTransitionBusy] = useState(false)
  const [signingBusy, setSigningBusy] = useState(false)
  const [writer] = useState(() => new DraftWriter({ value: fromAnswers(initial.answers), version: initial.version, savedAt: initial.savedAt }, async pending => {
    const body = { expectedVersion: pending.version, clientTime: pending.clientTime, changes: diffAnswers(pending.baseline, pending.value) }
    const saved = await clinicalApi.save(scope, initial.patientId, initial.encounterId, body as Wire.Save, pending.key)
    setRecord(saved)
    const confirmed = fromAnswers(saved.answers)
    if (JSON.stringify(form.getValues('answers')) === JSON.stringify(pending.value)) form.reset({ answers: confirmed })
    return { value: confirmed, version: saved.version, savedAt: saved.savedAt }
  }, createUuid, redraw))
  useEffect(() => { writer.activate(); return () => writer.dispose() }, [writer])
  useEffect(() => { writer.edit(values) }, [values, writer])
  useEffect(() => {
    if (writer.state !== 'unsaved' || transitionBusy || transition) return
    const timer = window.setTimeout(() => { void writer.flush() }, 1000)
    return () => window.clearTimeout(timer)
  }, [revision, writer, transitionBusy, transition])
  const blocker = useBlocker(({ currentLocation, nextLocation }) => (writer.dirty || auxiliary.dirty || interventionBlocked) && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (writer.dirty || interventionBlocked) event.preventDefault() }
    window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn)
  }, [writer, interventionBlocked])
  const denied = interventionDenied || writer.error instanceof ClinicalRequestError && [401, 403, 404].includes(writer.error.status)
  useEffect(() => { if (denied) { writer.dispose(); query.removeQueries({ queryKey: clinicalKey(scope, initial.patientId) }) } }, [denied, writer, query, scope, initial.patientId])
  if (denied) return <main id="main-content" className="dashboard connected-clinical" tabIndex={-1}><h1>Clinical access unavailable</h1><p role="alert">Your access could not be confirmed. The patient record is no longer displayed.</p><Button onClick={() => navigate('/logout')}>Sign out</Button></main>
  const error = actionError ?? writer.error
  const statuses = { saved: record.savedAt ? 'Saved to server' : 'No draft changes', unsaved: 'Unsaved changes', saving: 'Saving to server…', failed: 'Save not confirmed', conflict: 'Version conflict · autosave paused' }
  async function finishCare() {
    if (signingBusy || writer.dirty || interventionBlocked || writer.state === 'saving') return
    const command = transition ?? { key: createUuid(), request: { expectedVersion: writer.version, target: 'FINISHED', reason: 'Clinician confirmed care finished; documentation remains separately owned' } }
    setTransition(command); setTransitionBusy(true); setActionError(null)
    try {
      const result = await clinicalApi.transition(scope, record.patientId, record.encounterId, command.request, command.key)
      setRecord(result); writer.resolve({ value: writer.acknowledged, version: result.version, savedAt: result.savedAt }, writer.desired)
      setTransition(null)
    } catch (error) { setActionError(error) } finally { setTransitionBusy(false) }
  }
  async function loadConflict() {
    try { setServer(await clinicalApi.encounter(scope, record.patientId, record.encounterId)) } catch (error) { setActionError(error) }
  }
  function resolve(useServer: boolean) {
    if (!server) return
    const choice = useServer ? fromAnswers(server.answers) : values
    setRecord(server); form.reset({ answers: choice }); writer.resolve({ value: fromAnswers(server.answers), version: server.version, savedAt: server.savedAt }, choice); setServer(null)
  }
  const signing = <EncounterSigning actionRef={signingActions} interventionVersion={interventionVersion} onBusy={setSigningBusy} scope={scope} record={record} disabled={writer.dirty || writer.state === 'saving' || transitionBusy || interventionBlocked} onRecord={next => { setRecord(next); const answers = fromAnswers(next.answers); form.reset({ answers }); writer.resolve({ value: answers, version: next.version, savedAt: next.savedAt }, answers) }} />
  const allFields = fieldsOf(record.release.definition)
  return <main onChangeCapture={auxiliary.onChangeCapture} id="main-content" className="dashboard connected-clinical" tabIndex={-1}>
    <Button variant="ghost" className="clinical-back" onClick={() => navigate(patientPath(scope, record.patientId))}><ArrowLeft aria-hidden="true" />Patient chart</Button>
    <PatientContextHeader patient={record.patient} sex={patientDetail.isPending ? 'Checking…' : patientDetail.data?.data.sex ?? 'Unavailable'} actions={<div className="encounter-header-actions"><div className="encounter-save-state" role="status" aria-live="polite"><strong>{writer.state === 'saved' ? <Check aria-hidden="true" /> : <ShieldCheck aria-hidden="true" />}{statuses[writer.state]}</strong></div><div className="encounter-header-buttons">{record.noteState !== 'SIGNED' ? <Button variant="outline" disabled={!writer.dirty || writer.state === 'saving' || writer.state === 'conflict' || transitionBusy || signingBusy} onClick={() => void writer.flush()}><Save aria-hidden="true" />Save draft</Button> : <span className="badge green">Signed note</span>}{record.careState !== 'FINISHED' ? <Button variant="outline" disabled={signingBusy || writer.dirty || writer.state === 'saving' || interventionBlocked || transitionBusy} onClick={() => void finishCare()}>{transitionBusy ? 'Finishing…' : transition ? 'Retry finish care' : 'Finish care'}</Button> : null}{record.noteState !== 'SIGNED' ? <Button ref={reviewButton} disabled={writer.dirty || writer.state === 'saving' || transitionBusy || interventionBlocked || signingBusy} onClick={() => signingActions.current?.review(reviewButton.current)}>Review & sign</Button> : null}</div></div>} />
    <div className="encounter-heading"><div><h2>{record.release.definition.title}</h2><p>{scope.siteName} · {scope.clinicianName}</p><p>{record.queueEntryId ? 'Queued consultation' : 'Non-queued encounter'} · {record.careMode.replaceAll('_', ' ')} · {time(record.clinicalTime)} PHT</p></div><div className="clinical-statuses"><span className={`badge ${record.careState === 'FINISHED' ? 'green' : 'blue'}`}>{record.careState === 'FINISHED' ? 'Care finished' : record.careState === 'PAUSED' ? 'Care paused' : 'Care in progress'}</span><span className={`badge ${record.noteState === 'SIGNED' ? 'green' : 'amber'}`}>{record.noteState === 'SIGNED' ? 'Signed synthetic note' : record.noteState === 'READY_TO_SIGN' ? 'Ready for review' : 'Unsigned draft'}</span></div></div>
    {error ? <div className="clinical-error" role="alert"><strong>{error instanceof Error ? error.message : 'The action could not be confirmed.'}</strong>{error instanceof ClinicalRequestError ? <small>Reference: {error.code}</small> : null}{writer.state === 'failed' ? <Button variant="outline" onClick={() => void writer.flush()}>Retry original save</Button> : null}{writer.state === 'conflict' ? <Button variant="outline" onClick={() => void loadConflict()}>Compare with server</Button> : null}</div> : null}
    {server ? <section className="clinical-conflict" aria-label="Resolve draft conflict"><h2>Review both versions</h2><p>Server revision {server.responseRevision}. No clinical content has been merged.</p><div className="conflict-rows">{allFields.filter(f => f.type !== 'GROUP').map(field => <div key={field.id}><h3>{field.label}</h3><p><strong>Your draft: </strong>{Object.values(values).filter(a => a.fieldId === field.id).map(displayAnswer).join('; ') || 'Not recorded'}</p><p><strong>Server: </strong>{server.answers.filter(a => a.fieldId === field.id).map(displayAnswer).join('; ') || 'Not recorded'}</p></div>)}</div><div className="clinical-buttons"><Button variant="outline" onClick={() => resolve(true)}>Use server draft</Button><Button onClick={() => resolve(false)}>Use my draft for the next save</Button></div></section> : null}
    <div className="connected-editor-grid"><div>
      {record.noteState === 'SIGNED' ? signing : <><nav className="encounter-section-nav" aria-label="Encounter sections">{record.release.definition.sections.map(section => <a key={section.id} href={`#section-${section.id}`} onClick={e => { e.preventDefault(); document.getElementById(`section-${section.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }}>{section.label}</a>)}</nav>
      <form data-clinical-autosave onSubmit={e => { e.preventDefault(); void writer.flush() }}>
        {record.release.definition.sections.map(section => <section id={`section-${section.id}`} className="encounter-form-section" key={section.id}><h2>{section.label}</h2>{section.fields.map(field => <DynamicField key={field.id} field={field} values={values} disabled={record.noteState === 'SIGNED' || transitionBusy || signingBusy} onChange={next => form.setValue('answers', next, { shouldDirty: true })} />)}</section>)}
      </form></>}
      <EncounterInterventions key={`${scope.practiceId}/${scope.siteId}/${record.patientId}/${record.encounterId}`} scope={scope} record={record} disabled={signingBusy || transitionBusy} onPendingWorkChange={setInterventionPending} onSummaryVersionChange={setInterventionVersion} onAccessDenied={denyInterventions} />
    </div><aside className="encounter-reference"><section className="surface"><h2>Encounter record</h2><Button ref={releaseButton} variant="outline" onClick={() => setReleaseOpen(true)}><FileLock2 aria-hidden="true" />Release {record.release.definition.businessVersion}</Button><p>The release is locked to this encounter.</p><dl><dt>Practice</dt><dd>{scope.practiceName}</dd><dt>Documentation owner</dt><dd>{scope.clinicianName}</dd><dt>Server revision</dt><dd>{record.responseRevision}</dd></dl></section>
      {record.episodeSnapshot ? <section className="surface"><h2>Pregnancy at encounter start</h2><span className="badge blue">{record.episodeSnapshot.episode.status.toLowerCase()}</span><p>{record.episodeSnapshot.episode.source}</p><dl><dt>Episode</dt><dd>{record.episodeSnapshot.episode.episodeId}</dd><dt>Source version</dt><dd>{record.episodeSnapshot.episode.version}</dd><dt>Selected dating estimate</dt><dd>{record.episodeSnapshot.selectedDating?.estimatedDate ?? 'Not recorded'}</dd><dt>Dating source</dt><dd>{record.episodeSnapshot.selectedDating?.source ?? 'No dating basis selected'}</dd></dl><p>This captured source stays unchanged when the current episode is corrected.</p></section> : null}
      {record.noteState !== 'SIGNED' ? signing : null}
    </aside></div>
    <Sheet onRestoreFocus={() => releaseButton.current?.focus()} open={releaseOpen} onClose={() => setReleaseOpen(false)} title="Pinned template release" description="This release cannot be changed on the encounter."><div className="clinical-release-details"><h2>{record.release.definition.title}</h2><p>Version {record.release.definition.businessVersion}</p><p>{record.release.approvalBasis}</p><dl><dt>Release identity</dt><dd>{record.release.releaseId}</dd><dt>Definition checksum</dt><dd>{record.release.checksum}</dd><dt>Published</dt><dd>{time(record.release.publishedAt)}</dd></dl></div></Sheet>
    <ConfirmationDialog open={blocker.state === 'blocked'} title={interventionPending.unresolvedOutcome || interventionPending.mutationPending ? 'Confirmation is pending' : 'Leave with unsaved changes?'} confirmLabel={interventionPending.unresolvedOutcome || interventionPending.mutationPending ? 'Return to encounter' : 'Discard changes'} description={interventionPending.unresolvedOutcome || interventionPending.mutationPending ? 'An intervention request is awaiting confirmation. Keep this encounter open and retry the original request before leaving.' : `Note or intervention changes for ${record.patient.displayName}, ${record.patient.recordNumber}, have not been confirmed by the server. Leaving discards temporary form edits.`} onCancel={() => blocker.state === 'blocked' && blocker.reset()} onConfirm={() => { if (blocker.state === 'blocked') { if (interventionPending.mutationPending || interventionPending.unresolvedOutcome) blocker.reset(); else blocker.proceed() } }} />
  </main>
}
