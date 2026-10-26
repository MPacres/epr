import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { clinicalApi, clinicalKey, ClinicalRequestError, type Scope } from './services/encounter-api'
import { queueApi, type Entry } from './services/queue-api'
import { EncounterStart } from './EncounterStart'
import { patientPath } from './services/encounter-api'
export function QueueWorkspace({ scope }: { scope: Scope }) {
  const query = useQueryClient()
  const navigate = useNavigate()
  const sessions = useQuery({ queryKey: [...clinicalKey(scope), 'sessions'], queryFn: ({ signal }) => queueApi.sessions(scope, signal), retry: false })
  const [preview, setPreview] = useState('')
  const session = sessions.data?.find(s => s.sessionId === preview)
  const entries = useQuery({ queryKey: [...clinicalKey(scope), 'queue', preview], queryFn: ({ signal }) => queueApi.entries(scope, preview, signal), enabled: !!session, retry: false })
  const patients = useQuery({ queryKey: [...clinicalKey(scope), 'patients'], queryFn: ({ signal }) => clinicalApi.patients(scope, signal), retry: false })
  const [label, setLabel] = useState('')
  const [date, setDate] = useState('')
  const [patient, setPatient] = useState('')
  const [reason, setReason] = useState('')
  const [starting, setStarting] = useState<Entry | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState<{ run: () => Promise<unknown>; title: string } | null>(null)
  const [busy, setBusy] = useState(false)
  async function execute(run: () => Promise<unknown>, title: string) {
    if (busy) return
    const command = pending ?? { run, title }; setPending(command); setBusy(true); setError(null)
    try { await command.run(); setPending(null); await query.invalidateQueries({ queryKey: clinicalKey(scope) }) }
    catch (e) { setError(e); if (e instanceof ClinicalRequestError && [403, 404, 409, 422].includes(e.status)) setPending(null) }
    finally { setBusy(false) }
  }
  function transition(target: string) {
    if (!session) return
    const key = createUuid(); const body = { expectedVersion: session.version, target, reason, expectedSelectionVersion: session.selectionVersion }
    void execute(() => queueApi.sessionCommand(scope, `/${session.sessionId}/transitions`, body, key), target)
  }
  function entryTransition(entry: Entry, target: string) {
    if (!session) return
    const key = createUuid(); const body = { expectedVersion: entry.version, target, reason }
    void execute(() => queueApi.entryCommand(scope, session.sessionId, `/${entry.queueEntryId}/transitions`, body, key), target)
  }
  return <main id="main-content" className="dashboard connected-clinical" tabIndex={-1}>
    <div className="page-heading"><div><h1>Clinical queue</h1><p>{scope.practiceName} · {scope.siteName}</p></div><span className="badge blue">Demo only</span></div>
    <p className="clinical-notice">Previewing a session does not activate it. Active care, queue completion and note signing remain separate.</p>
    {sessions.error || entries.error || error ? <div role="alert" className="clinical-error"><p>{String((error ?? sessions.error ?? entries.error) instanceof Error ? ((error ?? sessions.error ?? entries.error) as Error).message : 'Action unavailable')}</p>{error instanceof ClinicalRequestError ? <small>{error.code}</small> : null}</div> : null}
    {pending ? <Button disabled={busy} onClick={() => void execute(pending.run, pending.title)}>{busy ? 'Waiting for server…' : `Retry original ${pending.title.toLowerCase()}`}</Button> : null}
    <section className="surface clinical-start"><h2>Sessions</h2><label>Preview session<select value={preview} disabled={busy || !!pending || !!starting} onChange={e => setPreview(e.target.value)}><option value="">Choose a session…</option>{sessions.data?.map(s => <option key={s.sessionId} value={s.sessionId}>{s.sessionDate} · {s.label} · {s.state.toLowerCase()}{s.selected && s.state === 'IN_PROGRESS' ? ' · active' : ''}</option>)}</select></label>
      <details><summary>Create a clinical session</summary><form onSubmit={e => { e.preventDefault(); const key = createUuid(); const body = { sessionId: createUuid(), sessionDate: date, label }; void execute(async () => { const created = await queueApi.sessionCommand(scope, '', body, key); setPreview(created.sessionId) }, 'create session') }}><fieldset disabled={busy || !!pending}><label>Session label<input required maxLength={120} value={label} onChange={e => setLabel(e.target.value)} /></label><label>Session date<input required type="date" value={date} onChange={e => setDate(e.target.value)} /></label><Button type="submit">Create draft session</Button></fieldset></form></details>
    </section>
    {session ? <><section className="surface clinical-start"><h2>{session.label}</h2><p>{session.state.replaceAll('_', ' ').toLowerCase()} · {session.selected && session.state === 'IN_PROGRESS' ? 'Active clinical session' : 'Preview only'}</p><label>Reason for session or queue transition<textarea value={reason} maxLength={500} onChange={e => setReason(e.target.value)} disabled={busy || !!pending} /></label><div className="clinical-buttons">{(session.state === 'DRAFT' ? ['OPEN', 'CANCELLED'] : session.state === 'OPEN' ? ['IN_PROGRESS', 'CANCELLED'] : session.state === 'IN_PROGRESS' ? session.selected ? ['CLOSING'] : ['IN_PROGRESS', 'CLOSING'] : session.state === 'CLOSING' ? ['CLOSED'] : []).map(target => <Button key={target} variant="outline" disabled={!reason.trim() || busy || !!pending || !!starting} onClick={() => transition(target)}>{target === 'IN_PROGRESS' ? 'Activate this session' : target === 'CLOSING' ? 'Begin closing' : target === 'CLOSED' ? 'Close session' : target === 'OPEN' ? 'Open session' : 'Cancel session'}</Button>)}</div>
      {['OPEN', 'IN_PROGRESS'].includes(session.state) ? <form onSubmit={e => { e.preventDefault(); const key = createUuid(); const body = { queueEntryId: createUuid(), patientId: patient, expectedSessionVersion: session.version }; void execute(() => queueApi.entryCommand(scope, session.sessionId, '', body, key), 'check in') }}><label>Check in an authorized patient<select required value={patient} disabled={busy || !!pending} onChange={e => setPatient(e.target.value)}><option value="">Select patient…</option>{patients.data?.map(p => <option key={p.patientId} value={p.patientId}>{p.displayName} · {p.recordNumber}</option>)}</select></label><Button type="submit" variant="outline" disabled={!patient || busy || !!pending}>Check in walk-in</Button></form> : null}
    </section>
    {starting ? <EncounterStart scope={scope} patient={starting.patient} onCancel={() => setStarting(null)} queued={{ sessionId: session.sessionId, entry: starting, sessionVersion: session.version }} /> : <section className="clinical-directory" aria-label="Session queue">{entries.data?.map(entry => <div className="clinical-directory-row" key={entry.queueEntryId}><span><strong>{entry.patient.displayName}</strong><small>{entry.patient.recordNumber} · {entry.state.replaceAll('_', ' ').toLowerCase()}</small></span><div className="clinical-buttons">{entry.encounterId ? <Button variant="outline" onClick={() => navigate(`${patientPath(scope, entry.patientId)}/encounters/${entry.encounterId}`)}>Open encounter</Button> : <Button variant="outline" onClick={() => navigate(patientPath(scope, entry.patientId))}>Review chart</Button>}{['CHECKED_IN', 'WAITING_FOR_PREPARATION'].includes(entry.state) ? <Button disabled={!reason.trim() || busy || !!pending} onClick={() => entryTransition(entry, 'READY_FOR_DOCTOR')}>Mark ready</Button> : null}{['READY_FOR_DOCTOR', 'CALLED'].includes(entry.state) ? <Button disabled={!session.selected || session.state !== 'IN_PROGRESS' || busy || !!pending} onClick={() => setStarting(entry)}>Start consultation</Button> : null}{entry.state === 'IN_CONSULTATION' ? <Button variant="outline" disabled={!reason.trim() || busy || !!pending} onClick={() => entryTransition(entry, 'COMPLETED')}>Complete queue entry</Button> : null}{['CHECKED_IN', 'WAITING_FOR_PREPARATION', 'READY_FOR_DOCTOR', 'CALLED'].includes(entry.state) ? <Button variant="ghost" disabled={!reason.trim() || busy || !!pending} onClick={() => entryTransition(entry, 'DEFERRED')}>Defer</Button> : null}</div></div>)}{entries.data?.length === 0 ? <p className="clinical-empty">No entries in this session.</p> : null}</section>}</> : null}
  </main>
}
