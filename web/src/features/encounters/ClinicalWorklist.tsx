import { useState } from 'react'
import { IncomingReportInbox } from '../orders/IncomingReportInbox'
import { InterventionWorkReview } from '../interventions/InterventionWorkReview'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { acknowledgeForm } from './hooks/use-form-edits'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { request, atSite, clinicalKey, ClinicalRequestError, type Encounter, type Scope } from './services/encounter-api'
import { patientPath } from './services/encounter-api'
import { taskSchema, loadTasks } from './services/worklist-api'
export function ClinicalWorklist({ scope }: { scope: Scope }) {
  const query = useQueryClient(); const navigate = useNavigate()
  const [selectedWork, setSelectedWork] = useState<z.infer<typeof taskSchema> | null>(null)
  const tasks = useQuery({ queryKey: [...clinicalKey(scope), 'worklist'], queryFn: ({ signal }) => loadTasks(scope, signal), retry: false })
  const [error, setError] = useState<unknown>(null); const [busy, setBusy] = useState(false); const [reason, setReason] = useState('')
  const [pending, setPending] = useState<(() => Promise<unknown>) | null>(null)
  async function execute(work: () => Promise<unknown>) {
    const command = pending ?? work; setPending(() => command); setBusy(true); setError(null)
    try { await command(); setPending(null); await query.invalidateQueries({ queryKey: [...clinicalKey(scope), 'worklist'] }) }
    catch(e) { setError(e); if (e instanceof ClinicalRequestError && [403, 404, 409, 422].includes(e.status)) setPending(null) } finally { setBusy(false) }
  }
  return <main id="main-content" className="dashboard connected-clinical" tabIndex={-1}><div className="page-heading"><div><h1>Owned clinical work</h1><p>{scope.practiceName} · {scope.siteName}</p></div><Button disabled={busy} onClick={() => void execute(() => request(atSite(scope, '/clinical-worklist/reconcile'), z.array(taskSchema), { method: 'POST' }))}>{busy ? 'Reconciling…' : pending ? 'Retry original action' : 'Reconcile pending work'}</Button></div><p>Open notes, results, referrals and follow-ups retain their owner. Reconciliation delivers pending encounter tasks. Closing a session or signing a note does not close a follow-up.</p>
    {error || tasks.error ? <p role="alert" className="clinical-error">{String(error ?? tasks.error)}</p> : null}<label>Follow-up disposition reason<textarea value={reason} maxLength={500} onChange={e => setReason(e.target.value)} disabled={busy || !!pending} /></label>
    <div className="clinical-directory">{tasks.data?.filter(t => t.state === 'OPEN').map(task => <div className="clinical-directory-row" key={task.sourceId}><span><strong>{task.patientName}</strong><small>{task.recordNumber}</small><strong>{task.kind === 'UNSIGNED_NOTE' ? 'Unsigned encounter note' : task.kind === 'FOLLOW_UP' ? 'Owned follow-up' : task.kind === 'REFERRAL' ? 'Referral outcome' : 'Order result / outcome'}</strong><small>{task.instruction}{task.dueDate ? ` · Due ${task.dueDate}` : ''}</small></span><div className="clinical-buttons">{task.kind === 'UNSIGNED_NOTE' ? <Button variant="outline" onClick={() => navigate(`${patientPath(scope, task.patientId)}/encounters/${task.encounterId}`)}>Open encounter</Button> : <Button variant="outline" onClick={() => setSelectedWork(task)}>{task.currentOwner ? 'Review owned work' : 'Review proposed handoff'}</Button>}{task.kind === 'FOLLOW_UP' && task.currentOwner ? <Button disabled={!reason.trim() || busy || !!pending} onClick={() => { const key = createUuid(); const body = { expectedVersion: task.version, reason }; void execute(() => request(atSite(scope, `/clinical-worklist/${task.sourceId}/resolve`), taskSchema, { method: 'POST', key, body })) }}>Resolve follow-up</Button> : null}</div></div>)}</div>{tasks.data?.every(t => t.state === 'RESOLVED') ? <p>No open work is currently listed. Reconcile to check pending encounter tasks.</p> : null}<IncomingReportInbox scope={scope} />{selectedWork ? <InterventionWorkReview key={selectedWork.sourceId} scope={scope} task={selectedWork} onClose={() => setSelectedWork(null)} /> : null}</main>
}
export function FollowUpIntent({ scope, record }: { scope: Scope; record: Encounter }) {
  const [pending, setPending] = useState<{ key: string; body: unknown } | null>(null); const [busy, setBusy] = useState(false); const [message, setMessage] = useState('')
  return <details className="surface clinical-start"><summary>Record an owned follow-up</summary><p>{scope.clinicianName} will own this follow-up. Signing will not resolve it.</p><form onSubmit={async e => { e.preventDefault(); const form = e.currentTarget; const values = new FormData(form); const command = pending ?? { key: createUuid(), body: { intentId: createUuid(), expectedVersion: record.version, dueDate: values.get('dueDate'), instruction: values.get('instruction') } }; setPending(command); setBusy(true)
    try { await request(atSite(scope, `/patients/${record.patientId}/encounters/${record.encounterId}/follow-up-intents`), z.object({ deliveryState: z.literal('PENDING') }), { method: 'POST', ...command }); setMessage('Follow-up intent saved. Task delivery is pending; reconcile in Owned clinical work.'); setPending(null); acknowledgeForm(form); form.reset() }
    catch(error) { setMessage(error instanceof Error ? error.message : 'Request unconfirmed'); if (error instanceof ClinicalRequestError && [403, 404, 409, 422].includes(error.status)) setPending(null) } finally { setBusy(false) }
  }}><fieldset disabled={busy || !!pending}><label>Follow-up due date<input required type="date" name="dueDate" /></label><label>Clinician instruction<textarea required name="instruction" maxLength={2000} /></label></fieldset><Button type="submit" variant="outline" disabled={busy}>{pending ? 'Retry original follow-up' : 'Save follow-up intent'}</Button></form>{message ? <p role="status">{message}</p> : null}</details>
}
