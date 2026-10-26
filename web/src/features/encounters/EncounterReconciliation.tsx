import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, ClipboardCheck, Pencil, Plus, RotateCw, ShieldAlert, Trash2 } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { acknowledgeForm } from './hooks/use-form-edits'
import { clinicalListApi, clinicalListKey, clinicalListKinds, type ClinicalListKind, type ClinicalRecord } from '../clinical-chart/clinical-list-api'
import { ClinicalListEditor } from '../clinical-chart/ClinicalListEditor'
import { ClinicalRequestError, type Encounter, type Scope } from './services/encounter-api'
import type * as Wire from './services/api.generated'
import { currentReconciliation } from './reconciliation-model'

const labels = { ALLERGY: 'Allergies and adverse reactions', MEDICATION_USE: 'Reported medication use', PROBLEM: 'Problems and diagnoses' } as const
const singularLabels = { ALLERGY: 'allergy', MEDICATION_USE: 'medication', PROBLEM: 'problem' } as const
const reviewSources = { PATIENT: 'Patient', CHART: 'Chart', PATIENT_AND_CHART: 'Patient and chart', OTHER: 'Other source' } as const
type ReviewSource = keyof typeof reviewSources | ''
const listFor = (data: Awaited<ReturnType<typeof loadReconciliation>>, kind: ClinicalListKind) => kind === 'ALLERGY' ? data.summary.allergies : kind === 'MEDICATION_USE' ? data.summary.medications : data.summary.problems
const recordLabel = (kind: ClinicalListKind, record: ClinicalRecord) => kind === 'ALLERGY' ? 'substanceDisplay' in record.data ? record.data.substanceDisplay : '' : kind === 'MEDICATION_USE' ? 'medicationDisplay' in record.data ? record.data.medicationDisplay : '' : 'problemDisplay' in record.data ? record.data.problemDisplay : ''
function recordDetails(kind: ClinicalListKind, record: ClinicalRecord) {
  if (kind === 'ALLERGY' && 'substanceDisplay' in record.data) return [record.data.reactionType, record.data.criticality, record.data.reactions.map(item => item.manifestation).join(', '), `Source: ${record.data.informationSource}`].filter(Boolean).join(' · ')
  if (kind === 'MEDICATION_USE' && 'medicationDisplay' in record.data) return [record.data.strength, record.data.dose && record.data.doseUnit ? `${record.data.dose} ${record.data.doseUnit}` : '', record.data.route, record.data.frequency, record.data.adherence.replaceAll('_', ' ').toLowerCase(), `Source: ${record.data.informationSource}`].filter(Boolean).join(' · ')
  if (kind === 'PROBLEM' && 'problemDisplay' in record.data) return [record.data.verificationStatus, record.data.severity, record.data.onset ? `Onset ${record.data.onset}` : '', `Source: ${record.data.informationSource}`].filter(Boolean).join(' · ')
  return ''
}

async function loadReconciliation(scope: Scope, patientId: string, encounterId: string, signal?: AbortSignal) {
  const summary = await clinicalListApi.summary(scope, patientId, signal)
  if (!clinicalListKinds.every(kind => (kind === 'ALLERGY' ? summary.allergies : kind === 'MEDICATION_USE' ? summary.medications : summary.problems).actions.read))
    return { summary, records: null, reviews: [], proposals: [] }
  const [allergies, medications, problems, reviews, proposals] = await Promise.all([
    clinicalListApi.active(scope, patientId, 'ALLERGY', signal), clinicalListApi.active(scope, patientId, 'MEDICATION_USE', signal),
    clinicalListApi.active(scope, patientId, 'PROBLEM', signal), clinicalListApi.encounterReviews(scope, patientId, encounterId, signal),
    clinicalListApi.proposals(scope, patientId, signal),
  ])
  return { summary, records: { ALLERGY: allergies, MEDICATION_USE: medications, PROBLEM: problems }, reviews, proposals }
}

export type EncounterReconciliationActions = { ensureReviewed: () => Promise<boolean> }
export function EncounterReconciliation({ scope, record, onReconciled, onPendingChange, actionRef }: { scope: Scope; record: Encounter; onReconciled: () => void; onPendingChange: (pending: boolean) => void; actionRef: Ref<EncounterReconciliationActions> }) {
  const client = useQueryClient()
  const patientId = record.patientId
  const query = useQuery({ queryKey: [...clinicalListKey(scope, patientId), 'encounter', record.encounterId], queryFn: ({ signal }) => loadReconciliation(scope, patientId, record.encounterId, signal), retry: false })
  const [sourceKind, setSourceKind] = useState<ReviewSource>('CHART')
  const [otherSource, setOtherSource] = useState('')
  const [pending, setPending] = useState<{ key: string; body: Wire.Reconcile } | null>(null)
  const [editor, setEditor] = useState<{ kind: ClinicalListKind; record?: ClinicalRecord } | null>(null)
  const [chartChanged, setChartChanged] = useState(false)
  const editorOpener = useRef<HTMLElement | null>(null)
  const removeRow = useRef<HTMLLIElement | null>(null)
  const keepAllergy = useRef<HTMLButtonElement | null>(null)
  const [removal, setRemoval] = useState<{ item: ClinicalRecord; command: { key: string; body: Wire.Transition } | null } | null>(null)
  const [removeBusy, setRemoveBusy] = useState(false)
  const [removeError, setRemoveError] = useState<unknown>(null)
  const [removeNotice, setRemoveNotice] = useState('')
  const terminology = useQuery({
    queryKey: [...clinicalListKey(scope, patientId), editor?.kind ?? '', 'terminology'],
    queryFn: ({ signal }) => clinicalListApi.terminology(scope, patientId, editor!.kind, signal),
    enabled: Boolean(editor), retry: false, staleTime: 60_000,
  })
  useEffect(() => { onPendingChange(pending !== null || removal?.command != null || removeBusy); return () => onPendingChange(false) }, [pending, removal?.command, removeBusy, onPendingChange])
  useEffect(() => { if (removal && !removal.command) keepAllergy.current?.focus() }, [removal])
  const [busy, setBusy] = useState(false)
  const listMutationBusy = busy || pending !== null || chartChanged || removeBusy || removal?.command != null
  const [error, setError] = useState<unknown>(null)
  const formRef = useRef<HTMLFormElement>(null)
  const data = query.data
  const current = data?.records && !chartChanged ? currentReconciliation(data.reviews, data.summary) : null
  const incompleteAccess = data && clinicalListKinds.filter(kind => !listFor(data, kind).actions.read || !listFor(data, kind).actions.review)
  const unresolved = data?.proposals.filter(proposal => proposal.decision === null) ?? []
  const source = sourceKind === 'OTHER' ? otherSource.trim() : sourceKind ? reviewSources[sourceKind] : ''
  const canSubmit = Boolean(data?.records && !current && !chartChanged && !incompleteAccess?.length && !unresolved.length && source)
  function openEditor(kind: ClinicalListKind, item?: ClinicalRecord) {
    editorOpener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    setEditor({ kind, record: item })
  }
  async function savedChartChange() {
    setEditor(null)
    setChartChanged(true)
    try { await client.invalidateQueries({ queryKey: clinicalListKey(scope, patientId) }) }
    finally { setChartChanged(false) }
  }
  async function removeAllergy() {
    if (!removal || removeBusy || busy || pending || chartChanged) return
    const item = removal.item
    const command = removal.command ?? { key: createUuid(), body: { expectedVersion: item.version, transition: 'ENTER_IN_ERROR', reason: 'Mistaken allergy entry removed from the active chart' } }
    setRemoval({ item, command }); setRemoveBusy(true); setRemoveError(null)
    try {
      await clinicalListApi.transitionAllergy(scope, patientId, item.id, command.body, command.key)
      try {
        await savedChartChange()
        setRemoveNotice(`${recordLabel('ALLERGY', item)} was removed from active allergies. Its history remains in the chart.`)
      } catch {
        setRemoveNotice(`${recordLabel('ALLERGY', item)} was removed. The chart could not refresh; retry loading the clinical lists.`)
      }
      setRemoval(null)
    } catch (failure) {
      setRemoveError(failure)
      if (failure instanceof ClinicalRequestError && failure.status >= 400 && failure.status < 500) setRemoval({ item, command: null })
    } finally { setRemoveBusy(false) }
  }
  const focusReview = () => requestAnimationFrame(() => {
    const target = document.getElementById('encounter-reconciliation-title')
    target?.scrollIntoView({ block: 'start' })
    target?.focus({ preventScroll: true })
  })

  async function submit(): Promise<boolean> {
    if (busy || removeBusy || removal?.command || (!pending && !canSubmit) || !data) return false
    const command = pending ?? { key: createUuid(), body: {
      reviewId: createUuid(), encounterId: record.encounterId,
      expectedGenerations: { ALLERGY: data.summary.allergies.generation, MEDICATION_USE: data.summary.medications.generation, PROBLEM: data.summary.problems.generation },
      decisions: [], source, interactionsUnavailableAcknowledged: true, clientTime: new Date().toISOString(),
    } }
    setPending(command); setBusy(true); setError(null)
    try {
      await clinicalListApi.reconcile(scope, patientId, command.body, command.key)
      if (formRef.current) acknowledgeForm(formRef.current)
      setPending(null)
      await query.refetch(); onReconciled()
      return true
    } catch (failure) {
      setError(failure)
      focusReview()
      if (failure instanceof ClinicalRequestError && failure.status !== 0 && failure.status !== 503) {
        setPending(null)
        void query.refetch()
      }
      return false
    } finally { setBusy(false) }
  }
  useImperativeHandle(actionRef, () => ({ ensureReviewed: async () => {
    if (pending) return submit()
    if (query.isPending || query.error || chartChanged || removeBusy || removal?.command || !data?.records || incompleteAccess?.length || unresolved.length) {
      focusReview()
      return false
    }
    if (current) return true
    if (!canSubmit) {
      if (sourceKind === 'OTHER' && !otherSource.trim()) setError(new Error('Describe the source you reviewed before choosing Review & sign.'))
      focusReview()
      return false
    }
    return submit()
  } }))

  return <section id="encounter-reconciliation" className="surface encounter-reconciliation" aria-labelledby="encounter-reconciliation-title">
    <div className="encounter-reconciliation-heading"><div><h2 id="encounter-reconciliation-title" tabIndex={-1}><ClipboardCheck aria-hidden="true" />Chart review</h2><p>Check these records before selecting Review &amp; sign.</p></div>{current ? <span className="badge green"><CheckCircle2 aria-hidden="true" />Reviewed</span> : null}</div>
    {query.isPending ? <p role="status">Loading allergies, medications, problems, and encounter review…</p> : query.error ? <div className="clinical-error" role="alert"><p>Clinical lists could not be loaded. The note cannot be prepared until their status is known.</p><Button variant="outline" onClick={() => void query.refetch()}><RotateCw aria-hidden="true" />Retry loading lists</Button></div> : data ? <>
      {current ? <p className="encounter-reconciliation-confirmed">Saved {new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Manila' }).format(new Date(current.reviewedAt))} PHT. The server will recheck this exact review when you prepare the note.</p> : null}
      {chartChanged ? <p className="encounter-reconciliation-confirmed" role="status">Refreshing the chart after your change. Review &amp; sign will wait for current records.</p> : null}
      {incompleteAccess?.length ? <div className="clinical-error" role="alert"><p>{incompleteAccess.length === 3 && clinicalListKinds.every(kind => !listFor(data, kind).actions.read) ? 'No clinical list sources are available in this site and role. The server will determine whether note preparation is permitted.' : `Your current site and role cannot review ${incompleteAccess.map(kind => labels[kind]).join(', ')}. Ask a Practice administrator to grant the required source access before signing.`}</p></div> : null}
      {removeNotice ? <p className="encounter-reconciliation-confirmed" role="status">{removeNotice}</p> : null}
      {data.records ? <div className="encounter-reconciliation-lists">{clinicalListKinds.map(kind => <section key={kind} className="encounter-reconciliation-list" aria-label={labels[kind]}>
        <div className="encounter-reconciliation-list-heading"><h3>{labels[kind]}</h3><div><span>{data.records![kind].length ? `${data.records![kind].length} active` : 'Not recorded'}</span>{listFor(data, kind).actions.write ? <Button variant="outline" size="sm" disabled={listMutationBusy} onClick={() => openEditor(kind)}><Plus aria-hidden="true" />Add {singularLabels[kind]}</Button> : null}</div></div>
        {data.records![kind].length ? <ul>{data.records![kind].map(item => {
          const label = recordLabel(kind, item)
          const confirming = kind === 'ALLERGY' && removal?.item.id === item.id
          const canWrite = listFor(data, kind).actions.write
          return <li key={item.id} tabIndex={-1}>
            <div><strong>{label}{item.duplicateCandidate ? ' · possible duplicate' : ''}</strong><small>{recordDetails(kind, item)}</small></div>
            {!confirming && canWrite ? <div className="encounter-reconciliation-row-actions"><Button variant="outline" size="sm" disabled={listMutationBusy} aria-label={`Manage ${label} in ${labels[kind]}`} onClick={() => openEditor(kind, item)}><Pencil aria-hidden="true" />Manage</Button>{kind === 'ALLERGY' ? <Button variant="ghost" size="sm" className="encounter-reconciliation-remove-trigger" disabled={listMutationBusy} aria-label={`Remove ${label} from active allergies`} onClick={event => { removeRow.current = event.currentTarget.closest('li'); setRemoval({ item, command: null }); setRemoveError(null); setRemoveNotice('') }}><Trash2 aria-hidden="true" />Remove</Button> : null}</div> : null}
            {confirming ? <div className="encounter-reconciliation-remove" role="group" aria-label={`Remove ${label}`}><p>Remove {label} from active allergies for {record.patient.displayName} ({record.patient.recordNumber})? Use this for an entry added by mistake. Its history remains, and this does not confirm that the patient has no allergies.</p>{removeError ? <p role="alert">{removeError instanceof ClinicalRequestError && removeError.status === 409 ? 'This allergy changed. Refresh the list before deciding.' : removeError instanceof Error ? removeError.message : 'The removal could not be confirmed.'}</p> : null}<div className="encounter-reconciliation-remove-actions"><Button ref={keepAllergy} variant="outline" disabled={removeBusy || !!removal?.command} onClick={() => { removeRow.current?.focus(); setRemoval(null); setRemoveError(null) }}>Keep allergy</Button>{removal?.command && !removeBusy ? <Button onClick={() => void removeAllergy()}>Retry removal</Button> : removeError instanceof ClinicalRequestError && removeError.status === 409 ? <Button variant="outline" onClick={() => { setRemoval(null); setRemoveError(null); void query.refetch() }}>Refresh list</Button> : <Button variant="outline" className="encounter-reconciliation-remove-trigger" disabled={removeBusy} onClick={() => void removeAllergy()}><Trash2 aria-hidden="true" />{removeBusy ? 'Removing…' : 'Remove allergy'}</Button>}</div></div> : null}
          </li>
        })}</ul> : <p className="encounter-reconciliation-empty">No active entries recorded; absence has not been verified.</p>}
      </section>)}</div> : null}
      {data.records && !current ? <form ref={formRef} className="encounter-reconciliation-form" onSubmit={event => event.preventDefault()}>
        {unresolved.length ? <div className="clinical-error" role="alert"><p>{unresolved.length} proposed list change{unresolved.length === 1 ? '' : 's'} still need a decision. Resolve them in the clinical list workflow before reconciling this encounter.</p></div> : null}
        <label className="encounter-reconciliation-source">Source reviewed<select value={sourceKind} disabled={busy || !!pending} onChange={event => { setSourceKind(event.target.value as ReviewSource); setError(null) }}>{Object.entries(reviewSources).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        {sourceKind === 'OTHER' ? <label className="encounter-reconciliation-source">Describe source<input value={otherSource} maxLength={2000} required disabled={busy || !!pending} onChange={event => { setOtherSource(event.target.value); setError(null) }} placeholder="Source actually reviewed" /></label> : null}
        {error ? <div className="clinical-error" role="alert"><p>{error instanceof ClinicalRequestError && error.code === 'RECONCILIATION_CONFLICT' ? 'One or more clinical lists changed. Refresh them and review the new versions before retrying.' : error instanceof Error ? error.message : 'The reconciliation could not be confirmed.'}</p>{pending ? <p>Confirmation is uncertain. Retry the original request; do not start a new review.</p> : null}</div> : null}
        <p id="encounter-reconciliation-disclosure" className="encounter-reconciliation-note"><ShieldAlert aria-hidden="true" />Drug interaction checking is unavailable. Review &amp; sign saves your review of these records and the selected source before preparing the note. Chart changes are saved separately.</p>
        <div className="encounter-reconciliation-actions"><Button variant="ghost" disabled={busy || !!pending || chartChanged} onClick={() => { if (formRef.current) acknowledgeForm(formRef.current); void query.refetch() }}><RotateCw aria-hidden="true" />Refresh</Button></div>
      </form> : null}
      {current ? <p className="encounter-reconciliation-note"><ShieldAlert aria-hidden="true" />The saved review binds these clinical list versions. It did not create or change an allergy, medication, or problem.</p> : null}
    </> : null}
    {editor ? <ClinicalListEditor key={`${editor.kind}/${editor.record?.id ?? 'new'}`} scope={scope} patient={record.patient} kind={editor.kind} record={editor.record} terminology={terminology.data ?? []} onClose={() => setEditor(null)} onSaved={() => void savedChartChange()} onRestoreFocus={() => editorOpener.current?.isConnected && editorOpener.current.focus()} /> : null}
  </section>
}
