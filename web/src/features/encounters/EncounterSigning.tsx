import { useEffect, useImperativeHandle, useState, useRef, type Ref } from 'react'
import { z } from 'zod'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { createUuid } from '../../lib/create-uuid'
import { atSite, request, clinicalApi, ClinicalRequestError, type Encounter, type Scope } from './services/encounter-api'
const preparationSchema = z.object({ preparationId: z.uuid(), responseRevision: z.number(), encounterVersion: z.number(), artifactId: z.uuid(), artifactHash: z.string(), releaseChecksum: z.string(), interventionSetVersion: z.number().nullable().optional() })
const signatureSchema = z.object({ signatureId: z.uuid(), preparationId: z.uuid(), responseRevision: z.number(), artifactId: z.uuid(), artifactHash: z.string(), signerId: z.uuid(), signedAt: z.string(), assuranceMethod: z.string(), amendmentOf: z.uuid().nullable(), amendmentReason: z.string().nullable(), interventionSnapshotCaptured: z.boolean().default(false) })
type Preparation = z.infer<typeof preparationSchema>
type Signature = z.infer<typeof signatureSchema>
export type EncounterSigningActions = { review: (trigger: HTMLButtonElement | null) => void }
export function EncounterSigning({ scope, record, disabled, onRecord, onBusy, interventionVersion, actionRef }: { scope: Scope; record: Encounter; interventionVersion: number; disabled: boolean; onRecord: (value: Encounter) => void; onBusy: (value: boolean) => void; actionRef: Ref<EncounterSigningActions> }) {
  const [signatures, setSignatures] = useState<Signature[]>([])
  const [candidate, setCandidate] = useState<Preparation | null>(null)
  const [archivedHtml, setArchivedHtml] = useState<string | null>(null)
  const [html, setHtml] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState<{ kind: 'prepare' | 'sign' | 'amend'; key: string; body: unknown } | null>(null)
  const restoreFocus = useRef<HTMLButtonElement>(null)
  const path = `/patients/${record.patientId}/encounters/${record.encounterId}`
  useEffect(() => {
    const abort = new AbortController()
    request(atSite(scope, `${path}/signatures`), z.array(signatureSchema), { signal: abort.signal }).then(setSignatures).catch(e => { if (!abort.signal.aborted) setError(e) })
    return () => abort.abort()
  }, [scope, path, record.noteState])
  const candidateStale = candidate !== null && candidate.interventionSetVersion !== interventionVersion
  const latestSignature = signatures.at(-1)?.signatureId
  useEffect(() => {
    if (record.noteState !== 'SIGNED' || !latestSignature) return
    const abort = new AbortController()
    fetch(atSite(scope, `${path}/signatures/${latestSignature}/artifact`), { credentials: 'include', cache: 'no-store', signal: abort.signal }).then(async response => {
      if (!response.ok) throw new ClinicalRequestError(response.status, 'ARCHIVED_ARTIFACT_UNAVAILABLE')
      const bytes = await response.text(); if (!abort.signal.aborted) setArchivedHtml(bytes)
    }).catch(error => { if (!abort.signal.aborted) setError(error) })
    return () => abort.abort()
  }, [scope, path, latestSignature, record.noteState])
  async function readArtifact(suffix: string) {
    const response = await fetch(atSite(scope, `${path}/${suffix}/artifact`), { credentials: 'include', cache: 'no-store' })
    if (!response.ok) throw new ClinicalRequestError(response.status, 'ARCHIVED_ARTIFACT_UNAVAILABLE')
    setHtml(await response.text())
  }
  async function perform(kind: 'prepare' | 'sign' | 'amend') {
    if (busy || pending && pending.kind !== kind) return
    const command = pending ?? { kind, key: createUuid(), body: kind === 'prepare' ? { preparationId: createUuid(), expectedVersion: record.version }
      : kind === 'sign' ? { preparationId: candidate?.preparationId, expectedVersion: candidate?.encounterVersion, reviewedArtifactHash: candidate?.artifactHash, confirmSyntheticReview: confirmed }
        : { expectedVersion: record.version, originalSignatureId: signatures.at(-1)?.signatureId, reason } }
    setPending(command); setBusy(true); onBusy(true); setError(null)
    try {
      if (kind === 'prepare') {
        const result = await request(atSite(scope, `${path}/sign-preparations`), preparationSchema, { method: 'POST', key: command.key, body: command.body })
        setCandidate(result); setConfirmed(false); setOpen(true)
        onRecord(await clinicalApi.encounter(scope, record.patientId, record.encounterId))
        await readArtifact(`sign-preparations/${result.preparationId}`)
      } else if (kind === 'sign') {
        const result = await request(atSite(scope, `${path}/signatures`), signatureSchema, { method: 'POST', key: command.key, body: command.body })
        setSignatures(previous => [...previous.filter(s => s.signatureId !== result.signatureId), result])
        onRecord(await clinicalApi.encounter(scope, record.patientId, record.encounterId))
        setCandidate(null); setOpen(false); setHtml(null)
      } else {
        await request(atSite(scope, `${path}/amendments`), z.unknown(), { method: 'POST', key: command.key, body: command.body })
        onRecord(await clinicalApi.encounter(scope, record.patientId, record.encounterId)); setReason('')
      }
      setPending(null)
    } catch (e) {
      setError(e)
      if (e instanceof ClinicalRequestError && [403, 404, 409, 422].includes(e.status)) { setPending(null); setCandidate(null); setHtml(null) }
      if (e instanceof ClinicalRequestError && e.code.startsWith('SIGN_REVIEW_REQUIRED:')) {
        const fieldId = e.code.split(':')[1]?.split('/')[0]
        const target = document.querySelector<HTMLElement>(`[data-field-id="${fieldId}"]`)
        target?.scrollIntoView({ block: 'center' }); target?.querySelector<HTMLElement>('input,textarea,select,button')?.focus()
      }
    } finally { setBusy(false); onBusy(false) }
  }
  useImperativeHandle(actionRef, () => ({ review: trigger => {
    if (record.noteState === 'SIGNED' || disabled || busy || pending?.kind === 'sign') return
    restoreFocus.current = trigger
    void perform('prepare')
  } }))
  async function viewSigned(signature: Signature, trigger: HTMLButtonElement) {
    restoreFocus.current = trigger
    setCandidate(null); setHtml(null); setOpen(true); setError(null)
    try { await readArtifact(`signatures/${signature.signatureId}`) } catch (e) { setError(e) }
  }
  return <section className="surface signing-panel" aria-label="Note signing and history">
    <h2>{record.noteState === 'SIGNED' ? 'Signed synthetic note' : 'Review and sign'}</h2>
    <p>Development confirmation only. Clinical content and signing assurance have not been approved for real-patient issuance.</p>
    {error ? <div role="alert" className="clinical-error"><p>{error instanceof Error ? error.message : 'Signing could not be confirmed.'}</p>{error instanceof ClinicalRequestError ? <small>{error.code}</small> : null}</div> : null}
    {record.noteState === 'SIGNED' ? archivedHtml ? <iframe className="signed-primary-artifact" title="Archived signed original" sandbox="" srcDoc={archivedHtml} /> : <p role="status">{error ? 'The archived artifact is unavailable. It has not been reconstructed.' : 'Loading the archived signed bytes…'}</p> : null}
    {record.noteState !== 'SIGNED' ? <Button disabled={disabled || busy || pending?.kind === 'sign'} onClick={event => { restoreFocus.current = event.currentTarget; void perform('prepare') }}>{busy ? 'Preparing…' : pending?.kind === 'prepare' ? 'Retry original preparation' : 'Review & sign'}</Button> : null}
    {signatures.map(signature => <div key={signature.signatureId} className="signed-history-entry"><strong>{signature.amendmentOf ? 'Signed amendment' : 'Signed original'} · revision {signature.responseRevision}</strong><p>{new Date(signature.signedAt).toLocaleString('en-PH', { timeZone: 'Asia/Manila' })} PHT</p>{!signature.interventionSnapshotCaptured ? <p>No intervention snapshot captured by this version.</p> : null}{signature.amendmentReason ? <p>{signature.amendmentReason}</p> : null}<Button variant="outline" onClick={event => void viewSigned(signature, event.currentTarget)}>View archived note</Button></div>)}
    {record.noteState === 'SIGNED' ? <div className="clinical-field"><label htmlFor="amendment-reason">Reason for amendment</label><textarea id="amendment-reason" value={reason} maxLength={500} disabled={busy || pending !== null} onChange={event => setReason(event.target.value)} /><Button variant="outline" disabled={!reason.trim() || busy || signatures.length === 0} onClick={() => void perform('amend')}>{pending?.kind === 'amend' ? 'Retry amendment' : 'Create linked amendment'}</Button></div> : null}
    <Sheet open={open} onClose={() => { if (!busy) { setOpen(false); setHtml(null) } }} onRestoreFocus={() => restoreFocus.current?.focus()} title={candidate ? 'Review exact candidate' : 'Archived signed note'} description={`${record.patient.displayName} · ${record.patient.recordNumber}`}>
      <div className="clinical-sign-review">{html ? <iframe title="Exact server artifact" sandbox="" srcDoc={html} /> : <p role="status">{error ? 'Artifact unavailable. Signing is blocked.' : 'Loading verified artifact…'}</p>}
        {candidate && html ? <><p role={candidateStale ? 'alert' : undefined}>{candidateStale ? 'Interventions changed after this review. Close this panel and prepare a fresh note. ' : ''}Revision {candidate.responseRevision} · checksum {candidate.artifactHash}</p><label className="clinical-checkbox"><input type="checkbox" checked={confirmed} disabled={busy} onChange={e => setConfirmed(e.target.checked)} />I reviewed this synthetic patient, content and exact candidate.</label><Button disabled={!confirmed || busy || disabled || candidateStale} onClick={() => void perform('sign')}>{busy ? 'Confirming…' : pending?.kind === 'sign' ? 'Retry original confirmation' : 'Confirm synthetic signature'}</Button></> : null}
      </div>
    </Sheet>
  </section>
}
