import { useEffect, useRef, useState } from 'react'
import { useBlocker } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { Sheet } from '../../components/ui/sheet'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { request, atSite, clinicalKey, ClinicalRequestError, type Scope } from '../encounters/services/encounter-api'
import { taskSchema } from '../encounters/services/worklist-api'
import { sourceSchema, sourcePath, labels } from './services/intervention-api'
import { Field } from './components/ComposerFields'
import './interventions.css'
type Task = z.infer<typeof taskSchema>
export function InterventionWorkReview({ scope, task, onClose }: { scope: Scope; task: Task; onClose: () => void }) {
  const query = useQueryClient()
  const active = useRef(true)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const kind = task.kind === 'UNSIGNED_NOTE' ? 'FOLLOW_UP' : task.kind
  const [busy, setBusy] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [discard, setDiscard] = useState(false)
  const [reason, setReason] = useState('')
  const [ownerId, setOwnerId] = useState('')
  const [evidenceAction, setEvidenceAction] = useState('review')
  const [outcome, setOutcome] = useState('REVIEWED')
  const [time, setTime] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState<{ path: string; body: unknown; key: string } | null>(null)
  const blocker = useBlocker(({ currentLocation, nextLocation }) => (dirty || busy || !!pending) && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => { const warn = (event: BeforeUnloadEvent) => { if (dirty || busy || pending) event.preventDefault() }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn) }, [dirty, busy, pending])
  const path = kind === 'FOLLOW_UP' ? `/clinical-worklist/${task.sourceId}` : sourcePath(task.patientId, kind, task.sourceId)
  const source = useQuery({ queryKey: [...clinicalKey(scope, task.patientId), kind, task.sourceId], queryFn: ({ signal }) => request(atSite(scope, path), sourceSchema, { signal }), enabled: kind !== 'FOLLOW_UP' && task.currentOwner, retry: false })
  const owners = useQuery({ queryKey: [...clinicalKey(scope), kind, 'owners'], queryFn: ({ signal }) => request(atSite(scope, `/intervention-work/${kind}/owners`), z.array(z.object({ userId: z.uuid(), displayName: z.string() })), { signal }), enabled: task.currentOwner, retry: false })
  async function execute(command: { path: string; body: unknown; key: string }) {
    if (busy) return
    const frozen = pending ?? command; setPending(frozen); setBusy(true); setError(null)
    try { await request(atSite(scope, frozen.path), z.unknown(), { ...frozen, method: 'POST' }); if (!active.current) return; setPending(null); setDirty(false); await query.invalidateQueries({ queryKey: [...clinicalKey(scope), 'worklist'] }); onClose() }
    catch (e) { if (!active.current) return; setError(e); if (e instanceof ClinicalRequestError && [400, 401, 403, 404, 409, 422].includes(e.status)) setPending(null) } finally { if (active.current) setBusy(false) }
  }
  function close() { if (busy || pending) return; if (dirty) setDiscard(true); else onClose() }
  const choices = evidenceAction === 'review' ? ['REVIEWED'] : evidenceAction === 'result' ? ['RECEIVED', 'CORRECTED'] : ['ACCEPTED', 'DECLINED', 'ATTENDED', 'UNABLE', 'PATIENT_REFUSED']
  return <Sheet open onClose={close} title={labels[kind]} description={`${task.patientName} · ${task.recordNumber} · ${scope.siteName}`}><div className="intervention-dialog">
    {blocker.state === 'blocked' ? <div role="alert"><p>{busy || pending ? 'Wait for confirmation or retry the original action before leaving.' : 'Discard the unsaved disposition and leave?'}</p><Button onClick={() => blocker.reset()}>Keep working</Button>{!busy && !pending ? <Button variant="outline" onClick={() => blocker.proceed()}>Discard and leave</Button> : null}</div> : null}
    <p>{task.instruction}</p><p>Due {task.dueDate ?? 'date not recorded'}. {task.currentOwner ? 'You own this work.' : 'The existing owner remains responsible until you accept.'}</p>
    {error || source.error ? <div role="alert" className="clinical-error">{String(error ?? source.error)}</div> : null}
    {pending && !busy ? <Button onClick={() => void execute(pending)}>Retry original action</Button> : null}
    {discard ? <div role="alert"><p>Discard this unsaved disposition?</p><Button variant="outline" onClick={() => setDiscard(false)}>Keep editing</Button><Button onClick={onClose}>Discard changes</Button></div> : null}
    <form className="intervention-composer" data-clinical-autosave onSubmit={e => { e.preventDefault(); if (task.currentOwner && kind === 'FOLLOW_UP') void execute({ path: `${path}/resolve`, key: createUuid(), body: { expectedVersion: task.version, reason } }); else if (source.data && time) void execute({ path: `${path}/${evidenceAction}`, key: createUuid(), body: { expectedVersion: source.data.version, outcome, evidence: reason, occurredAt: new Date(time).toISOString() } }) }}>
      <fieldset disabled={busy || !!pending}><Field label="Disposition / handoff evidence"><textarea required maxLength={500} value={reason} onChange={e => { setReason(e.target.value); setDirty(true) }} /></Field>
      {task.currentOwner && kind !== 'FOLLOW_UP' ? <><Field label="Activity"><select value={evidenceAction} onChange={e => { setEvidenceAction(e.target.value); setOutcome(e.target.value === 'review' ? 'REVIEWED' : e.target.value === 'result' ? 'RECEIVED' : 'ACCEPTED'); setDirty(true) }}><option value="review">Review and disposition</option><option value={kind === 'REFERRAL' ? 'outcome' : 'result'}>{kind === 'REFERRAL' ? 'Referral outcome' : 'Matched report / correction'}</option></select></Field><Field label="Outcome"><select value={outcome} onChange={e => { setOutcome(e.target.value); setDirty(true) }}>{choices.map(x => <option key={x} value={x}>{x.toLowerCase().replaceAll('_', ' ')}</option>)}</select></Field><Field label="Actual occurrence time · device local time"><input required type="datetime-local" value={time} onChange={e => { setTime(e.target.value); setDirty(true) }} /></Field></> : null}
      </fieldset>{task.currentOwner ? <Button type="submit" disabled={busy || !!pending || !reason.trim() || kind !== 'FOLLOW_UP' && !source.data}>Record disposition</Button> : <Button type="button" disabled={busy || !!pending || !reason.trim()} onClick={() => void execute({ path: kind === 'FOLLOW_UP' ? `${path}/accept` : `/intervention-work/${kind}/${task.sourceId}/accept`, key: createUuid(), body: { expectedVersion: task.version, reason } })}>Accept handoff</Button>}
      {task.currentOwner ? <section className="medication-safety-review"><h3>Request a handoff</h3><p>You remain responsible until the receiving clinician accepts.</p><Field label="Receiving clinician"><select value={ownerId} onChange={e => { setOwnerId(e.target.value); setDirty(true) }} disabled={busy || !!pending || !owners.data}><option value="">Choose an authorized clinician</option>{owners.data?.map(x => <option key={x.userId} value={x.userId}>{x.displayName}</option>)}</select></Field>{owners.data?.length === 0 ? <p>No other authorized clinician is available at this site.</p> : null}<Button variant="outline" type="button" disabled={busy || !!pending || !ownerId || !reason.trim()} onClick={() => void execute({ path: kind === 'FOLLOW_UP' ? `${path}/transfer` : `/intervention-work/${kind}/${task.sourceId}/transfer`, key: createUuid(), body: { expectedVersion: task.version, ownerId, reason } })}>Propose handoff</Button></section> : null}
    </form></div></Sheet>
}
