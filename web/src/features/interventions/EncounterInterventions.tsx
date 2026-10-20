import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { Plus, Search, FileText, Pill, FlaskConical, ScanLine, Send, CalendarCheck, ArrowLeft } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { createUuid } from '../../lib/create-uuid'
import { ReportComposer } from '../orders/ReportComposer'
import { OrderComposer } from '../orders/OrderComposer'
import { PrescriptionComposer } from '../prescribing/PrescriptionComposer'
import { ReferralComposer } from '../referrals/ReferralComposer'
import { FollowUpComposer } from '../follow-up/FollowUpComposer'
import { clinicalKey, atSite, request, ClinicalRequestError, type Encounter, type Scope } from '../encounters/services/encounter-api'
import { loadTasks } from '../encounters/services/worklist-api'
import { capabilitiesSchema, listInterventions, labels, sourcePath, contextPath, sourceSchema, preparationSchema, actionLabels, authorizePaths, type Kind, type Item, type Preparation, type SourceRecord } from './services/intervention-api'
import { executeConfirmedSteps } from './services/command-sequence'
import { Field } from './components/ComposerFields'
import './interventions.css'
export type PendingWork = { dirty: boolean; mutationPending: boolean; unresolvedOutcome: boolean }
type Step = { path: string; method: string; key: string; body: unknown }
type Command = { steps: Step[]; purpose: 'save' | 'prepare' | 'authorize' | 'evidence' | 'end' | 'group'; index: number }
const icons = { PRESCRIPTION: Pill, LAB_ORDER: FlaskConical, IMAGING_ORDER: ScanLine, REFERRAL: Send, FOLLOW_UP: CalendarCheck }
const readable = (s: string) => s.toLowerCase().replaceAll('_', ' ')
export function EncounterInterventions({ scope, record, disabled, onPendingWorkChange, onSummaryVersionChange, onAccessDenied }: { scope: Scope; record: Encounter; disabled: boolean; onPendingWorkChange: (state: PendingWork) => void; onSummaryVersionChange: (version: number) => void; onAccessDenied: () => void }) {
  const query = useQueryClient()
  const base = contextPath(record.patientId, record.encounterId)
  const key = [...clinicalKey(scope, record.patientId), record.encounterId, 'interventions']
  const [page, setPage] = useState(0)
  const capabilities = useQuery({ queryKey: [...key, 'capabilities'], queryFn: ({ signal }) => request(atSite(scope, `${base}/intervention-capabilities`), capabilitiesSchema, { signal }), retry: false })
  const list = useQuery({ queryKey: [...key, 'list', page], queryFn: ({ signal }) => listInterventions(scope, record.patientId, record.encounterId, page, signal), retry: false, refetchInterval: 15000 })
  const tasks = useQuery({ queryKey: [...clinicalKey(scope), 'worklist'], queryFn: ({ signal }) => loadTasks(scope, signal), retry: false, enabled: capabilities.data?.some(x => x.kind === 'FOLLOW_UP') ?? false, refetchInterval: 15000 })
  const [filter, setFilter] = useState('ALL')
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState<'picker' | 'composer' | 'detail' | 'review' | 'report'>('picker')
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState<Kind | null>(null)
  const [selected, setSelected] = useState<Item | null>(null)
  const [loaded, setLoaded] = useState<SourceRecord | null>(null)
  const [draftId, setDraftId] = useState(createUuid)
  const [replacement, setReplacement] = useState<SourceRecord | null>(null)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<Command | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [message, setMessage] = useState('')
  const [discard, setDiscard] = useState(false)
  const [candidate, setCandidate] = useState<Preparation | null>(null)
  const [html, setHtml] = useState<string | null>(null)
  const [reviewed, setReviewed] = useState(false)
  const [reason, setReason] = useState('')
  const [evidenceAction, setEvidenceAction] = useState('delivery')
  const [outcome, setOutcome] = useState('PRINTED')
  const [occurredAt, setOccurredAt] = useState('')
  const active = useRef(true)
  const opener = useRef<HTMLButtonElement>(null)
  const printable = useRef<HTMLIFrameElement>(null)
  const readGeneration = useRef(0)
  const saveAndAdd = useRef(false)
  const [documentPath, setDocumentPath] = useState<string | null>(null)
  const previous = useQuery({ queryKey: [...clinicalKey(scope, record.patientId), kind, 'previous-sources'], queryFn: ({ signal }) => request(atSite(scope, sourcePath(record.patientId, kind && kind !== 'FOLLOW_UP' ? kind : 'PRESCRIPTION')), z.array(sourceSchema), { signal }), enabled: open && mode === 'composer' && !!kind && kind !== 'FOLLOW_UP' && !loaded, retry: false })
  const copiedData = replacement ? { ...replacement.data, reviewId: undefined, requestedDate: '', groupId: undefined } : undefined
  const frozen = busy || pending !== null || disabled
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  useEffect(() => { onPendingWorkChange({ dirty, mutationPending: busy, unresolvedOutcome: !!pending }) }, [dirty, busy, pending, onPendingWorkChange])
  useEffect(() => { if (list.data) onSummaryVersionChange(list.data.interventionSetVersion) }, [list.data, onSummaryVersionChange])
  const denied = [list.error, capabilities.error, error].some(e => e instanceof ClinicalRequestError && [401, 403].includes(e.status))
  useEffect(() => { if (denied) { query.removeQueries({ queryKey: clinicalKey(scope, record.patientId) }); onAccessDenied() } }, [denied, query, scope, record.patientId, onAccessDenied])
  const changeDirty = useCallback((value: boolean) => setDirty(value), [])
  function close() {
    if (busy || pending) return
    if (dirty) { setDiscard(true); return }
    setOpen(false); readGeneration.current++; setHtml(null); setCandidate(null); setError(null)
  }
  function add() {
    setReplacement(null); setSelected(null); setLoaded(null); setKind(null); setMode('picker'); setSearch(''); setDirty(false); setReason(''); setCandidate(null); setHtml(null); setError(null); setDraftId(createUuid()); setOpen(true)
  }
  function choose(value: Kind) { setKind(value); setMode('composer'); setDirty(false) }
  async function view(item: Item) {
    setSelected(item); setKind(item.summary.kind); setLoaded(null); setCandidate(null); setHtml(null); setReason(''); setError(null); setDirty(false); setMode('detail'); setOpen(true)
    if (item.summary.kind === 'FOLLOW_UP') return
    const generation = ++readGeneration.current
    setBusy(true)
    try { const result = await request(atSite(scope, sourcePath(record.patientId, item.summary.kind, item.summary.sourceId)), sourceSchema); if (active.current && generation === readGeneration.current) setLoaded(result) }
    catch (e) { if (active.current && generation === readGeneration.current) setError(e) }
    finally { if (active.current && generation === readGeneration.current) setBusy(false) }
  }
  async function readArtifact(id: string, preparationId?: string) {
    if (!kind || kind === 'FOLLOW_UP') return
    const path = `${atSite(scope, `${sourcePath(record.patientId, kind, id)}/artifact`)}${preparationId ? `&preparationId=${preparationId}` : ''}`
    setDocumentPath(path)
    const response = await fetch(path, { credentials: 'include', cache: 'no-store' })
    if (!response.ok) throw new ClinicalRequestError(response.status, 'ARTIFACT_UNAVAILABLE')
    const bytes = await response.text(); if (active.current) setHtml(bytes)
  }
  async function execute(command: Command) {
    if (busy || disabled) return
    const current = pending ?? command
    setPending(current); setBusy(true); setError(null); setMessage('')
    let result: unknown
    try {
      result = await executeConfirmedSteps(current.steps, current.index, step => request(atSite(scope, step.path), z.unknown(), step), next => {
        if (!active.current) return false
        if (next < current.steps.length) setPending({ ...current, index: next })
        return true
      })
      if (!active.current) return
      setPending(null); setDirty(false)
      if (current.purpose === 'prepare') {
        const prepared = preparationSchema.parse(result); setCandidate(prepared); setReviewed(false); setMode('review'); setHtml(null)
        if (loaded) await readArtifact(loaded.id, prepared.preparationId)
      } else if (current.purpose === 'group') {
        const doc = z.object({ groupId: z.uuid(), documentId: z.uuid() }).parse(result)
        const path = atSite(scope, `${sourcePath(record.patientId, 'LAB_ORDER')}/groups/${doc.groupId}/documents/${doc.documentId}`)
        setDocumentPath(path)
        const response = await fetch(path, { credentials: 'include', cache: 'no-store' })
        if (!response.ok) throw new ClinicalRequestError(response.status, 'GROUP_DOCUMENT_UNAVAILABLE')
        const content = await response.text(); if (active.current) { setHtml(content); setMode('review') }
      } else {
        if (current.purpose === 'save' && saveAndAdd.current) add(); else setOpen(false); setHtml(null); setCandidate(null)
        setMessage(kind === 'FOLLOW_UP' && current.purpose === 'save' ? 'Follow-up requested · task delivery pending.' : current.purpose === 'save' ? 'Draft saved to the server.' : 'The server confirmed this action.')
      }
      await query.invalidateQueries({ queryKey: key })
      await query.invalidateQueries({ queryKey: [...clinicalKey(scope), 'worklist'] })
      await query.invalidateQueries({ queryKey: [...clinicalKey(scope), 'unmatched-reports'] })
    } catch (e) {
      if (!active.current) return
      setError(e)
      if (e instanceof ClinicalRequestError && [400, 401, 403, 404, 409, 422].includes(e.status)) { setPending(null); setCandidate(null); setHtml(null); if (current.purpose === 'authorize') setMode('detail') }
    } finally { if (active.current) setBusy(false) }
  }
  function save(data: Record<string, unknown>) {
    if (!kind) return
    const steps: Step[] = []
    if (!loaded && (kind === 'LAB_ORDER' || kind === 'IMAGING_ORDER') && Array.isArray(data.additionalServices) && data.additionalServices.length > 0) {
      const groupId = createUuid(); const clinicalTime = new Date().toISOString()
      const { additionalServices, ...primary } = data
      const selectedServices = additionalServices as Array<{ code: string | null; version: string | null; label: string }>
      const entries = [primary, ...selectedServices.map(service => ({ ...primary, catalogCode: service.code, catalogVersion: service.version, serviceName: service.label }))]
      steps.push({ path: `${sourcePath(record.patientId, kind)}/groups`, method: 'POST', key: createUuid(), body: { groupId, requests: entries.map((entry, index) => ({ id: index === 0 ? draftId : createUuid(), encounterId: record.encounterId, occurredAt: clinicalTime, data: { ...entry, groupId } })) } })
      void execute({ steps, purpose: 'save', index: 0 }); return
    }
    const { additionalServices: ignoredAdditionalServices, ...sourceData } = data
    void ignoredAdditionalServices
    const path = kind === 'FOLLOW_UP' ? `${base}/follow-up-intents` : sourcePath(record.patientId, kind, loaded?.id)
    const body = kind === 'FOLLOW_UP' ? { ...data, intentId: draftId, expectedVersion: record.version } : loaded ? { expectedVersion: loaded.version, data: sourceData } : { id: draftId, encounterId: record.encounterId, occurredAt: new Date().toISOString(), data: sourceData, supersedesId: replacement?.id }
    steps.push({ path: path + (loaded && kind !== 'FOLLOW_UP' ? '/draft' : ''), method: loaded ? 'PUT' : 'POST', key: createUuid(), body: structuredClone(body) })
    void execute({ steps, purpose: 'save', index: 0 })
  }
  function action(suffix: string, body: unknown, purpose: Command['purpose']) {
    if (!kind || kind === 'FOLLOW_UP' || !loaded) return
    void execute({ steps: [{ path: `${sourcePath(record.patientId, kind, loaded.id)}/${suffix}`, method: 'POST', key: createUuid(), body: structuredClone(body) }], purpose, index: 0 })
  }
  function receiveReport(data: Record<string, unknown>, match: boolean, existing?: { id: string; version: number }) {
    if (!loaded || (kind !== 'LAB_ORDER' && kind !== 'IMAGING_ORDER')) return
    const reportId = existing?.id ?? createUuid()
    const steps: Step[] = existing ? [] : [{ path: '/incoming-service-reports', method: 'POST', key: createUuid(), body: { id: reportId, kind, source: data.source, externalReference: data.externalReference, content: data.content, occurredAt: data.occurredAt, correctsId: data.correctsId } }]
    if (match) steps.push({ path: `/incoming-service-reports/${reportId}/match`, method: 'POST', key: createUuid(), body: { expectedVersion: existing?.version ?? 0, patientId: record.patientId, requestId: loaded.id, expectedRequestVersion: loaded.version, reason: data.reason, confirmPatientAndSource: true } })
    void execute({ steps, purpose: 'evidence', index: 0 })
  }
  const selectedCapability = capabilities.data?.find(x => x.kind === kind)
  const canCreate = record.careState === 'IN_PROGRESS' && record.noteState !== 'SIGNED'
  const filtered = list.data?.items.filter(item => filter === 'ALL' || filter === 'DRAFTS' && item.summary.clinicalState === 'DRAFT' || item.summary.kind === filter) ?? []
  const outcomes = evidenceAction === 'delivery' ? ['PRINTED', 'DELIVERED', 'ACKNOWLEDGED', 'FAILED', 'UNKNOWN'] : evidenceAction === 'fulfillment' ? ['PARTIAL', 'FULFILLED', 'UNABLE'] : evidenceAction === 'result' ? ['RECEIVED', 'CORRECTED'] : evidenceAction === 'review' ? ['REVIEWED'] : ['ACCEPTED', 'DECLINED', 'ATTENDED', 'UNABLE', 'PATIENT_REFUSED']
  if (denied) return null
  return <section className="surface encounter-interventions" aria-label="Encounter interventions">
    <div className="intervention-section-heading"><div><h2>Interventions</h2><p>Prescriptions, requests and continuing care</p></div><Button ref={opener} disabled={disabled || !canCreate || !capabilities.data?.some(c => c.prepare)} onClick={add}><Plus aria-hidden="true" />Add intervention</Button></div>
    {!canCreate ? <p>New clinical decisions require an active follow-up encounter. Existing requests remain available for review and disposition.</p> : null}
    {list.isPending ? <p role="status">Loading authorized interventions…</p> : list.error || capabilities.error ? <div role="alert"><p>Interventions could not be loaded in this scope.</p><Button variant="outline" onClick={() => { void list.refetch(); void capabilities.refetch() }}>Retry loading</Button></div> : <>
      <div className="intervention-filters" role="group" aria-label="Filter interventions">{[['ALL', 'All'], ['DRAFTS', 'Drafts'], ...(capabilities.data ?? []).map(c => [c.kind, labels[c.kind]])].map(([value, label]) => <Button variant={filter === value ? 'default' : 'ghost'} key={value} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</Button>)}</div>
      {[...new Set(list.data?.items.map(x => x.summary.groupId).filter((id): id is string => !!id))].map(groupId => <Button key={groupId} variant="outline" disabled={busy || disabled} onClick={() => { setOpen(true); setMode('review'); setKind('LAB_ORDER'); setCandidate(null); setLoaded(null); setHtml(null); void execute({ purpose: 'group', index: 0, steps: [{ path: `${sourcePath(record.patientId, 'LAB_ORDER')}/groups/${groupId}/documents`, method: 'POST', key: createUuid(), body: { documentId: createUuid() } }] }) }}>View grouped requisition</Button>)}
      {list.data?.total === 0 ? <p className="intervention-empty">No interventions added to this encounter.</p> : filtered.length === 0 ? <p>No matching interventions on this page.</p> : <ul className="intervention-list">{filtered.map(item => { const summary = item.summary; const Icon = icons[summary.kind]; const task = tasks.data?.find(t => t.sourceId === summary.sourceId); return <li key={item.linkId}><div className="intervention-type-icon"><Icon aria-hidden="true" /></div><div className="intervention-row-content"><h3>{summary.title}</h3><p>{summary.details.filter(Boolean).join(' · ')}</p><div className="intervention-row-status"><span className={`badge ${summary.clinicalState === 'DRAFT' ? 'amber' : 'blue'}`}>{readable(summary.kind === 'FOLLOW_UP' ? task?.state ?? summary.clinicalState : summary.clinicalState)}</span><span>{summary.kind === 'FOLLOW_UP' ? task ? 'Task delivered' : 'Task delivery pending' : readable(summary.deliveryState)}</span>{summary.fulfillmentState !== 'NOT_STARTED' ? <span>{readable(summary.fulfillmentState)}</span> : null}{task && summary.kind !== 'FOLLOW_UP' ? <span>Outcome work: {readable(task.state)}</span> : null}{summary.reviewState !== 'NOT_APPLICABLE' ? <span>{readable(summary.reviewState)}</span> : null}</div><small>Owner: {summary.ownerId === record.responsibleClinicianId ? scope.clinicianName : summary.ownerId}{summary.dueDate ? ` · Due ${summary.dueDate}` : ''}</small></div><Button variant="outline" disabled={disabled || busy} onClick={() => void view(item)}>Review</Button></li> })}</ul>}
      {list.data && list.data.total > 30 ? <div className="clinical-buttons"><Button variant="outline" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button><span>Page {page + 1}</span><Button variant="outline" disabled={(page + 1) * 30 >= list.data.total} onClick={() => setPage(p => p + 1)}>Next</Button></div> : null}
    </>}
    {message ? <p role="status" className="intervention-confirmed">{message}</p> : null}
    <Sheet open={open} onClose={close} onRestoreFocus={() => opener.current?.focus()} title={mode === 'picker' ? 'Add intervention' : kind ? labels[kind] : 'Intervention'} description={`${record.patient.displayName} · ${record.patient.recordNumber} · ${scope.siteName}`}>
      <div className="intervention-dialog" onSubmitCapture={event => { saveAndAdd.current = (event.nativeEvent as SubmitEvent).submitter?.getAttribute('name') === 'add-another' }}><p className="intervention-context">{scope.clinicianName}<span>Demo patient · Not for patient care</span></p>
      {error ? <div className="clinical-error" role="alert"><p>{error instanceof Error ? error.message : 'The action could not be confirmed.'}</p>{error instanceof ClinicalRequestError ? <strong>{readable(error.code)}</strong> : null}</div> : null}
      {pending && !busy ? <div className="clinical-alert"><p>Confirmation pending. Inputs are held for an exact retry.</p><Button onClick={() => void execute(pending)}>Retry original action</Button></div> : null}
      {discard ? <div className="intervention-discard" role="alert"><h3>Discard unsaved intervention changes?</h3><p>Only server-confirmed drafts survive leaving this form.</p><div className="clinical-buttons"><Button variant="outline" onClick={() => setDiscard(false)}>Keep editing</Button><Button onClick={() => { setDirty(false); setDiscard(false); setOpen(false) }}>Discard changes</Button></div></div> : null}
      {mode === 'picker' ? <><label className="intervention-search"><Search aria-hidden="true" /><input autoFocus aria-label="Search intervention types" placeholder="Search intervention types…" value={search} onChange={e => setSearch(e.target.value)} /></label><div className="intervention-picker">{capabilities.data?.filter(c => c.prepare && labels[c.kind].toLowerCase().includes(search.toLowerCase())).map(c => { const Icon = icons[c.kind]; return <button key={c.kind} onClick={() => choose(c.kind)}><Icon aria-hidden="true" /><span>{labels[c.kind]}</span></button> })}</div></> : null}
      {mode === 'composer' && kind ? <>{!loaded && kind !== 'FOLLOW_UP' ? kind === 'LAB_ORDER' ? !dirty ? <details className="intervention-copy lab-replacement"><summary>Replace a previous order</summary><Field label="Order to replace"><select disabled={frozen} value={replacement?.id ?? ''} onChange={e => { setReplacement(previous.data?.find(x => x.id === e.target.value) ?? null); setDraftId(createUuid()) }}><option value="">New order</option>{previous.data?.filter(x => ['ISSUED', 'AUTHORIZED', 'RELEASED', 'CANCELLED'].includes(x.state)).map(x => <option key={x.id} value={x.id}>{x.occurredAt.slice(0, 10)} · {String(x.data.serviceName ?? x.data.indication ?? x.id)} · {readable(x.state)}</option>)}</select></Field>{replacement ? <p>Review copied details before saving. The previous order stays in the record.</p> : null}</details> : null : !dirty ? <details className="intervention-copy lab-replacement"><summary>Replace a previous {kind === 'PRESCRIPTION' ? 'prescription' : 'record'}</summary><Field label="Record to replace"><select disabled={frozen} value={replacement?.id ?? ''} onChange={e => { setReplacement(previous.data?.find(x => x.id === e.target.value) ?? null); setDraftId(createUuid()) }}><option value="">New draft</option>{previous.data?.filter(x => ['ISSUED', 'AUTHORIZED', 'RELEASED', 'CANCELLED'].includes(x.state)).map(x => <option key={x.id} value={x.id}>{x.occurredAt.slice(0, 10)} · {String(x.data.serviceName ?? x.data.indication ?? x.id)} · {readable(x.state)}</option>)}</select></Field>{replacement ? <p>Review copied details before saving.</p> : null}</details> : null : null}{!dirty && !loaded ? <Button variant="ghost" disabled={frozen} onClick={() => setMode('picker')}><ArrowLeft aria-hidden="true" />Intervention types</Button> : null}{kind === 'LAB_ORDER' || kind === 'IMAGING_ORDER' ? <OrderComposer key={draftId} scope={scope} patient={record.patientId} kind={kind} initial={loaded?.data ?? copiedData} busy={frozen} onDirty={changeDirty} onSave={save} onCancel={close} /> : kind === 'PRESCRIPTION' ? <PrescriptionComposer key={draftId} scope={scope} patient={record.patientId} initial={loaded?.data ?? copiedData} busy={frozen} onDirty={changeDirty} onSave={save} onCancel={close} /> : kind === 'REFERRAL' ? <ReferralComposer key={draftId} initial={loaded?.data ?? copiedData} busy={frozen} onDirty={changeDirty} onSave={save} onCancel={close} /> : <FollowUpComposer busy={frozen} owner={scope.clinicianName} onDirty={changeDirty} onSave={save} onCancel={close} />}</> : null}
      {mode === 'detail' && selected ? <><h3>{selected.summary.title}</h3>{selected.summary.details.filter(Boolean).map((line, index) => <p key={index}>{line}</p>)}<p>Recorded state: {readable(loaded?.state ?? selected.summary.clinicalState)}</p>
        {kind === 'FOLLOW_UP' ? <p>{tasks.data?.some(t => t.sourceId === selected.summary.sourceId) ? 'This task is available in Owned clinical work for disposition.' : 'Task delivery is pending. The durable intent remains owned by the clinician.'}</p> : busy && !loaded ? <p role="status">Loading current source…</p> : loaded && kind ? <>
          {loaded.state === 'DRAFT' ? <><div className="clinical-buttons"><Button variant="outline" disabled={frozen || !selectedCapability?.prepare || !canCreate} onClick={() => setMode('composer')}>Edit draft</Button><Button disabled={frozen || !selectedCapability?.authorize} onClick={() => action('preparations', { preparationId: createUuid(), expectedVersion: loaded.version }, 'prepare')}><FileText aria-hidden="true" />Review {kind === 'PRESCRIPTION' ? 'prescription' : kind === 'REFERRAL' ? 'referral' : 'requisition'}</Button></div>{!selectedCapability?.authorize ? <p>{selectedCapability?.unavailableReason}</p> : null}
          <label className="clinical-field">Reason for abandoning draft<textarea value={reason} onChange={e => { setReason(e.target.value); setDirty(true) }} disabled={frozen} /></label><Button variant="outline" disabled={frozen || !reason.trim() || !selectedCapability?.prepare} onClick={() => action('abandon', { expectedVersion: loaded.version, reason }, 'end')}>Abandon draft</Button></> : <>
          {selected.summary.artifactId ? <Button variant="outline" disabled={frozen} onClick={() => { setMode('review'); setCandidate(null); setHtml(null); void readArtifact(loaded.id).catch(setError) }}>View original printable document</Button> : null}
          {selectedCapability?.manageExisting && (kind === 'LAB_ORDER' || kind === 'IMAGING_ORDER') && loaded.state !== 'DRAFT' && loaded.state !== 'ABANDONED' ? <Button variant="outline" disabled={frozen} onClick={() => setMode('report')}>Receive / match report</Button> : null}
          {selectedCapability?.manageExisting && loaded.state !== 'ABANDONED' ? <form className="intervention-composer" data-clinical-autosave onSubmit={e => { e.preventDefault(); if (occurredAt) action(evidenceAction, { expectedVersion: loaded.version, outcome, evidence: reason, occurredAt: new Date(occurredAt).toISOString() }, 'evidence') }}><h3>Record subsequent activity</h3><fieldset disabled={frozen}><Field label="Activity"><select value={evidenceAction} onChange={e => { setEvidenceAction(e.target.value); setOutcome(e.target.value === 'delivery' ? 'PRINTED' : e.target.value === 'review' ? 'REVIEWED' : e.target.value === 'result' ? 'RECEIVED' : e.target.value === 'outcome' ? 'ACCEPTED' : 'PARTIAL'); setDirty(true) }}><option value="delivery">Print / delivery evidence</option>{kind !== 'PRESCRIPTION' ? <><option value={kind === 'REFERRAL' ? 'outcome' : 'fulfillment'}>{kind === 'REFERRAL' ? 'Referral outcome' : 'Fulfillment'}</option>{kind !== 'REFERRAL' ? <option value="result">Report receipt / correction</option> : null}<option value="review">Review and disposition</option></> : null}</select></Field><Field label="Outcome"><select value={outcome} onChange={e => { setOutcome(e.target.value); setDirty(true) }}>{outcomes.map(x => <option key={x} value={x}>{readable(x)}</option>)}</select></Field><label className="clinical-field">Actual occurrence time · device local time<input type="datetime-local" required value={occurredAt} onChange={e => { setOccurredAt(e.target.value); setDirty(true) }} /></label><label className="clinical-field">Evidence / disposition / cancellation reason<textarea required maxLength={2000} value={reason} onChange={e => { setReason(e.target.value); setDirty(true) }} /></label></fieldset><div className="clinical-buttons"><Button type="submit" disabled={frozen || !reason.trim()}>Record evidence</Button>{['ISSUED', 'AUTHORIZED', 'RELEASED'].includes(loaded.state) ? <Button variant="outline" type="button" disabled={frozen || !reason.trim()} onClick={() => action('cancellation', { expectedVersion: loaded.version, reason }, 'end')}>Cancel with recorded reason</Button> : null}</div><p>Cancellation cannot retract delivered documents or erase performed care.</p></form> : null}</>}
        </> : null}</> : null}
      {mode === 'report' && loaded && (kind === 'LAB_ORDER' || kind === 'IMAGING_ORDER') ? <ReportComposer scope={scope} kind={kind} patientId={record.patientId} sourceId={loaded.id} patientName={`${record.patient.displayName}, ${record.patient.recordNumber}`} busy={frozen} onDirty={changeDirty} onSave={receiveReport} /> : null}
      {mode === 'review' ? <div className="intervention-document-review">{html ? <iframe ref={printable} title="Exact intervention document" sandbox="allow-same-origin allow-modals" srcDoc={html} /> : <p role="status">{error ? 'The verified document is unavailable.' : 'Loading the exact document…'}</p>}{!html && error && documentPath ? <Button variant="outline" onClick={() => { setError(null); void fetch(documentPath, { credentials: 'include', cache: 'no-store' }).then(async response => { if (!response.ok) throw new ClinicalRequestError(response.status, 'ARTIFACT_UNAVAILABLE'); const content = await response.text(); if (active.current) setHtml(content) }).catch(setError) }}>Retry document</Button> : null}{html && !candidate ? <><Button variant="outline" onClick={() => printable.current?.contentWindow?.print()}>Print this verified copy</Button><p>Printing does not record delivery or fulfillment. Record the actual outcome separately.</p></> : null}{candidate && kind && kind !== 'FOLLOW_UP' && loaded ? <><p>Source version {candidate.sourceVersion}</p><label className="clinical-field">Late action / repeated intent reason, when applicable<textarea value={reason} onChange={e => setReason(e.target.value)} disabled={frozen} /></label><label className="clinical-checkbox"><input type="checkbox" checked={reviewed} onChange={e => setReviewed(e.target.checked)} disabled={frozen} />I reviewed this synthetic patient, exact document, draft ownership and proposed action.</label><Button disabled={frozen || !html || !reviewed} onClick={() => action(authorizePaths[kind], { expectedVersion: loaded.version, preparationId: candidate.preparationId, reviewedArtifactHash: candidate.artifactHash, confirmSyntheticReview: reviewed, lateActionReason: reason || undefined, duplicateReason: reason || undefined }, 'authorize')}>{actionLabels[kind]}</Button></> : null}</div> : null}
      </div>
    </Sheet>
  </section>
}
