import { useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleAlert, ClipboardCheck, Pencil, Plus, RotateCw } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { ClinicalRequestError, type Patient, type Scope } from '../encounters/services/encounter-api'
import type * as Wire from '../encounters/services/api.generated'
import { ClinicalListEditor } from './ClinicalListEditor'
import { clinicalListApi, clinicalListKey, type ClinicalListKind, type ClinicalRecord, type ClinicalSummaryData } from './clinical-list-api'

const meta = {
  ALLERGY: { label: 'Allergies', singular: 'allergy', summaryKey: 'allergies' },
  MEDICATION_USE: { label: 'Medications', singular: 'medication', summaryKey: 'medications' },
  PROBLEM: { label: 'Problem list', singular: 'problem', summaryKey: 'problems' },
} as const

function recordView(kind: ClinicalListKind, record: ClinicalRecord) {
  if (kind === 'ALLERGY' && 'clinicalStatus' in record && 'substanceDisplay' in record.data) return { title: record.data.substanceDisplay, status: record.clinicalStatus, detail: [record.data.reactionType, record.data.criticality, record.data.reactions.map(item => item.manifestation).join(', ')].filter(Boolean).join(' · ') }
  if (kind === 'MEDICATION_USE' && 'status' in record && 'medicationDisplay' in record.data) return { title: record.data.medicationDisplay, status: record.status, detail: [record.data.strength, record.data.dose && record.data.doseUnit ? `${record.data.dose} ${record.data.doseUnit}` : '', record.data.route, record.data.frequency, record.data.adherence?.replaceAll('_', ' ').toLowerCase()].filter(Boolean).join(' · ') }
  if ('clinicalStatus' in record && 'problemDisplay' in record.data) return { title: record.data.problemDisplay, status: record.clinicalStatus, detail: [record.data.verificationStatus, record.data.severity, record.data.onset ? `Onset ${record.data.onset}` : ''].filter(Boolean).join(' · ') }
  return { title: 'Clinical record', status: 'UNKNOWN', detail: '' }
}

function ReviewControl({ scope, patientId, kind, activeCount, onSaved }: { scope: Scope; patientId: string; kind: ClinicalListKind; activeCount: number; onSaved: () => void }) {
  const [source, setSource] = useState('')
  const [outcome, setOutcome] = useState(activeCount > 0 ? 'REVIEWED_KNOWN' : 'REVIEWED_NONE_KNOWN')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<{ body: Wire.ReviewCommand; key: string } | null>(null)
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || !source.trim()) return
    setBusy(true); setError('')
    const command = pending ?? { body: { reviewId: createUuid(), outcome: outcome as Wire.ReviewCommand['outcome'], source: source.trim(), interactionsUnavailableAcknowledged: false, clientTime: new Date().toISOString() }, key: createUuid() }
    if (!pending) setPending(command)
    try {
      await clinicalListApi.review(scope, patientId, kind, command.body, command.key)
      setPending(null); setSource(''); onSaved()
    } catch (caught) {
      if (caught instanceof ClinicalRequestError && caught.status >= 400 && caught.status < 500) setPending(null)
      setError(caught instanceof ClinicalRequestError ? `${caught.message} (${caught.code})` : 'The review outcome is unconfirmed. Retry the original review without changing these fields.')
    }
    finally { setBusy(false) }
  }
  return <details className="clinical-list-review"><summary><ClipboardCheck aria-hidden="true" />Record list review</summary><form onSubmit={submit}>
    <fieldset disabled={busy || Boolean(pending)}>
      <label>Review conclusion<select value={outcome} onChange={event => setOutcome(event.target.value)}><option value="REVIEWED_KNOWN" disabled={activeCount === 0}>Reviewed · known items listed</option><option value="REVIEWED_NONE_KNOWN" disabled={activeCount > 0}>Reviewed · none known</option>{kind === 'ALLERGY' ? <option value="REVIEWED_NO_KNOWN_MEDICATION_ALLERGIES">Reviewed · no known medication allergies</option> : null}<option value="UNABLE_TO_CONFIRM">Unable to confirm</option></select></label>
      <label>Source or evidence<textarea required maxLength={2000} rows={2} value={source} onChange={event => setSource(event.target.value)} placeholder="e.g. Patient interview and prior chart reviewed" /></label>
    </fieldset>
    {error ? <p className="clinical-error" role="alert">{error}</p> : null}<Button type="submit" variant="outline" disabled={busy || (!pending && !source.trim())}>{busy ? 'Recording review…' : pending ? 'Retry original review' : 'Record review'}</Button>
  </form></details>
}

export function ClinicalListsPanel({ scope, patient, summary, selectedKind, onSelectKind }: { scope: Scope; patient: Patient; summary: ClinicalSummaryData; selectedKind: ClinicalListKind; onSelectKind: (kind: ClinicalListKind) => void }) {
  const patientId = patient.patientId
  const client = useQueryClient()
  const capability = summary[meta[selectedKind].summaryKey]
  const records = useQuery({ queryKey: [...clinicalListKey(scope, patientId), selectedKind, 'all'], queryFn: ({ signal }) => clinicalListApi.list(scope, patientId, selectedKind, signal), enabled: capability.actions.read, retry: false })
  const terminology = useQuery({ queryKey: [...clinicalListKey(scope, patientId), selectedKind, 'terminology'], queryFn: ({ signal }) => clinicalListApi.terminology(scope, patientId, selectedKind, signal), enabled: capability.actions.read && capability.actions.write, retry: false, staleTime: 60_000 })
  const [editor, setEditor] = useState<ClinicalRecord | 'new' | null>(null)
  const editorOpener = useRef<HTMLElement | null>(null)
  function openEditor(value: ClinicalRecord | 'new') { editorOpener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setEditor(value) }
  async function refreshed() { setEditor(null); await client.invalidateQueries({ queryKey: clinicalListKey(scope, patientId) }) }
  return <section id="clinical-lists" className="clinical-lists" aria-labelledby="clinical-lists-title">
    <header className="clinical-lists-heading"><div><h2 id="clinical-lists-title" tabIndex={-1}>Clinical lists</h2><p>Longitudinal records for this Practice. Maintaining them does not start an encounter.</p></div>{capability.actions.write ? <Button onClick={() => openEditor('new')}><Plus aria-hidden="true" />Add {meta[selectedKind].singular}</Button> : null}</header>
    <div className="clinical-list-tabs" role="tablist" aria-label="Clinical list source">
      {(Object.keys(meta) as ClinicalListKind[]).map(kind => { const item = summary[meta[kind].summaryKey]; return <button type="button" role="tab" aria-selected={selectedKind === kind} className={selectedKind === kind ? 'active' : ''} key={kind} onClick={() => onSelectKind(kind)}><span>{meta[kind].label}</span><small>{item.actions.read ? `${item.activeCount} active` : 'Unavailable'}</small></button> })}
    </div>
    {!capability.actions.read ? <div className="clinical-list-unavailable"><CircleAlert aria-hidden="true" /><div><h3>{meta[selectedKind].label} unavailable</h3><p>Your current Practice, site, and role do not include read access to this source.</p></div></div> : records.isPending ? <p className="clinical-list-loading" role="status">Loading {meta[selectedKind].label.toLowerCase()}…</p> : records.error ? <div className="clinical-error" role="alert"><span>{records.error.message}</span><Button variant="outline" onClick={() => void records.refetch()}><RotateCw aria-hidden="true" />Retry</Button></div> : <>
      <div className="clinical-record-list">{records.data.items.map(record => { const view = recordView(selectedKind, record); return <article className="clinical-record-row" key={record.id}><div><div className="clinical-record-title"><h3>{view.title}</h3><span className={`clinical-record-status ${view.status === 'ACTIVE' ? 'active' : ''}`}>{view.status.replaceAll('_', ' ').toLowerCase()}</span>{record.duplicateCandidate ? <span className="clinical-record-status warning">Possible duplicate</span> : null}</div><p>{view.detail || 'No additional detail recorded.'}</p><small>Revision {record.version} · Recorded {new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeZone: 'Asia/Manila' }).format(new Date(record.createdAt))}</small></div>{capability.actions.write && !view.status.includes('ENTERED_IN_ERROR') ? <Button variant="outline" size="sm" onClick={() => openEditor(record)}><Pencil aria-hidden="true" />Correct</Button> : null}</article> })}{records.data.items.length === 0 ? <div className="clinical-list-empty"><h3>No records in this list</h3><p>This is not a reviewed absence. Record a list review only after checking an appropriate source.</p>{capability.actions.write ? <Button variant="outline" onClick={() => openEditor('new')}><Plus aria-hidden="true" />Add {meta[selectedKind].singular}</Button> : null}</div> : null}</div>
      {capability.actions.review ? <ReviewControl scope={scope} patientId={patientId} kind={selectedKind} activeCount={capability.activeCount} onSaved={() => void refreshed()} /> : null}
    </>}
    {editor ? <ClinicalListEditor key={`${selectedKind}/${editor === 'new' ? 'new' : editor.id}`} scope={scope} patient={patient} kind={selectedKind} record={editor === 'new' ? undefined : editor} terminology={terminology.data ?? []} onClose={() => setEditor(null)} onSaved={() => void refreshed()} onRestoreFocus={() => editorOpener.current?.isConnected && editorOpener.current.focus()} /> : null}
  </section>
}
