import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Building2, CircleAlert, CircleCheck, CirclePause, Copy, Database, FileClock, KeyRound, Pencil, RotateCcw, Save, ShieldCheck, TriangleAlert, UserRound } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { copyTextToClipboard } from '../../lib/clipboard'
import { createUuid } from './tenant-creation-client'
import { changePracticeServiceStatus, getPractice, updatePracticeName, type PracticeAdministration, type PracticeServiceStatus } from './practice-administration-client'
import { formatPracticeDate } from './practice-administration-model'
import { PracticeLifecycleDialog } from './PracticeLifecycleDialog'
import { PracticeStatus } from './PracticeStatus'
import { TemporaryPasswordDialog } from './TemporaryPasswordDialog'
import { issueAdministratorTemporaryPassword } from './practice-administration-client'
import './practice-administration.css'

type IdempotentCommand = { fingerprint: string; idempotencyKey: string }

function PracticeIdentityEditor({ practice, onSave, pending, error }: {
  practice: PracticeAdministration
  onSave: (displayName: string) => void
  pending: boolean
  error: string | null
}) {
  const [editing, setEditing] = useState(false)
  const [displayName, setDisplayName] = useState(practice.displayName)
  const valid = displayName.trim().length >= 2 && displayName.trim().length <= 160

  if (!editing) return <div className="practice-identity-title">
    <div><h1>{practice.displayName}</h1><p>{practice.practiceCode}</p></div>
    <Button variant="outline" size="sm" onClick={() => setEditing(true)}><Pencil aria-hidden="true" />Edit name</Button>
  </div>

  return <form className="practice-name-editor" onSubmit={event => { event.preventDefault(); if (valid) onSave(displayName) }}>
    <label htmlFor="practice-display-name">Practice display name</label>
    <div><input id="practice-display-name" value={displayName} onChange={event => setDisplayName(event.target.value)} minLength={2} maxLength={160} required disabled={pending} /><Button type="submit" size="sm" disabled={!valid || pending}><Save aria-hidden="true" />{pending ? 'Saving…' : 'Save name'}</Button><Button variant="ghost" size="sm" onClick={() => { setDisplayName(practice.displayName); setEditing(false) }} disabled={pending}>Cancel</Button></div>
    <p>The immutable Practice code and tenant identifier will not change.</p>
    {error ? <p className="practice-inline-error" role="alert">{error}</p> : null}
  </form>
}

export function PracticeDetail({ practiceId }: { practiceId: string }) {
  const queryClient = useQueryClient()
  const [lifecycleTarget, setLifecycleTarget] = useState<PracticeServiceStatus | null>(null)
  const [copyState, setCopyState] = useState<'copied' | 'failed' | null>(null)
  const [temporaryPasswordOpen, setTemporaryPasswordOpen] = useState(false)
  const nameCommandRef = useRef<IdempotentCommand | null>(null)
  const lifecycleCommandRef = useRef<IdempotentCommand | null>(null)
  const temporaryPasswordCommandRef = useRef<IdempotentCommand | null>(null)
  const practiceQuery = useQuery({
    queryKey: ['provider', 'practice', practiceId],
    queryFn: () => getPractice(practiceId),
  })
  const nameMutation = useMutation({
    mutationFn: ({ practice, displayName, idempotencyKey }: { practice: PracticeAdministration; displayName: string; idempotencyKey: string }) => updatePracticeName({
      practiceId: practice.practiceId,
      displayName,
      version: practice.version,
      idempotencyKey,
    }),
    onSuccess: updated => {
      nameCommandRef.current = null
      queryClient.setQueryData(['provider', 'practice', practiceId], updated)
      void queryClient.invalidateQueries({ queryKey: ['provider', 'practices'] })
    },
  })
  const lifecycleMutation = useMutation({
    mutationFn: ({ practice, targetStatus, reason, idempotencyKey }: { practice: PracticeAdministration; targetStatus: PracticeServiceStatus; reason: string; idempotencyKey: string }) => changePracticeServiceStatus({
      practiceId: practice.practiceId,
      targetStatus,
      reason,
      version: practice.version,
      idempotencyKey,
    }),
    onSuccess: updated => {
      lifecycleCommandRef.current = null
      queryClient.setQueryData(['provider', 'practice', practiceId], updated)
      void queryClient.invalidateQueries({ queryKey: ['provider', 'practices'] })
      setLifecycleTarget(null)
    },
  })
  const temporaryPasswordMutation = useMutation({
    mutationFn: ({ practice, reason, idempotencyKey }: { practice: PracticeAdministration; reason: string; idempotencyKey: string }) => issueAdministratorTemporaryPassword({
      practiceId: practice.practiceId,
      reason,
      version: practice.version,
      idempotencyKey,
    }),
    onSuccess: result => {
      queryClient.setQueryData(['provider', 'practice', practiceId], result.practice)
      void queryClient.invalidateQueries({ queryKey: ['provider', 'practices'] })
    },
  })

  function saveName(practice: PracticeAdministration, displayName: string) {
    const fingerprint = `${practice.practiceId}:${practice.version}:${displayName.trim()}`
    if (nameCommandRef.current?.fingerprint !== fingerprint) nameCommandRef.current = { fingerprint, idempotencyKey: createUuid() }
    nameMutation.mutate({ practice, displayName, idempotencyKey: nameCommandRef.current.idempotencyKey })
  }

  function changeLifecycle(practice: PracticeAdministration, targetStatus: PracticeServiceStatus, reason: string) {
    const fingerprint = `${practice.practiceId}:${practice.version}:${targetStatus}:${reason.trim()}`
    if (lifecycleCommandRef.current?.fingerprint !== fingerprint) lifecycleCommandRef.current = { fingerprint, idempotencyKey: createUuid() }
    lifecycleMutation.mutate({ practice, targetStatus, reason, idempotencyKey: lifecycleCommandRef.current.idempotencyKey })
  }

  function issueTemporaryPassword(practice: PracticeAdministration, reason: string) {
    const fingerprint = `${practice.practiceId}:${practice.version}:temporary-password:${reason.trim()}`
    if (temporaryPasswordCommandRef.current?.fingerprint !== fingerprint) temporaryPasswordCommandRef.current = { fingerprint, idempotencyKey: createUuid() }
    temporaryPasswordMutation.mutate({ practice, reason, idempotencyKey: temporaryPasswordCommandRef.current.idempotencyKey })
  }

  async function copyPracticeId(value: string) {
    try {
      await copyTextToClipboard(value)
      setCopyState('copied')
    } catch {
      setCopyState('failed')
    }
  }

  if (practiceQuery.isPending) return <div className="practice-detail-message" role="status"><span className="practice-loading-mark" aria-hidden="true" /><div><strong>Loading Practice workspace…</strong><p>Checking current provisioning and service state.</p></div></div>

  if (practiceQuery.isError) return <div className="practice-detail-message error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Practice could not be loaded</strong><p>{practiceQuery.error.message}</p><Link to="/provider/practices">Return to Practice directory</Link></div><Button variant="outline" onClick={() => void practiceQuery.refetch()}>Try again</Button></div>

  const practice = practiceQuery.data
  const canChangeService = practice.status === 'ACTIVE'
  const suspending = practice.serviceStatus === 'ENABLED'
  const canIssueTemporaryPassword = practice.administratorSetupStatus === 'PASSWORD_NOT_ISSUED' || practice.administratorSetupStatus === 'TEMPORARY_PASSWORD_ISSUED'
  const administratorStatus = practice.administratorSetupStatus === 'PASSWORD_NOT_ISSUED' ? 'Password not issued'
    : practice.administratorSetupStatus === 'TEMPORARY_PASSWORD_ISSUED' ? 'Temporary password issued'
      : practice.administratorSetupStatus === 'PASSWORD_SET' ? 'Private password set'
        : practice.administratorSetupStatus === 'EXISTING_ACCOUNT' ? 'Existing account linked' : 'Status unavailable'

  return <div className="practice-detail-page">
    <Link className="practice-back-link" to="/provider/practices"><ArrowLeft aria-hidden="true" />Practice directory</Link>
    <header className="practice-detail-header">
      <div className="practice-detail-brand"><span><Building2 aria-hidden="true" /></span><PracticeIdentityEditor key={`${practice.practiceId}:${practice.version}`} practice={practice} onSave={name => saveName(practice, name)} pending={nameMutation.isPending} error={nameMutation.isError ? nameMutation.error.message : null} /></div>
      <PracticeStatus practice={practice} />
    </header>

    {practice.serviceStatus === 'SUSPENDED' ? <div className="practice-suspension-banner" role="status"><CirclePause aria-hidden="true" /><p><strong>Practice access is suspended.</strong><span>{practice.suspensionReason} {practice.suspendedAt ? `Recorded ${formatPracticeDate(practice.suspendedAt)}.` : ''}</span></p></div> : null}
    {practice.status === 'QUARANTINED' ? <div className="practice-provisioning-alert" role="alert"><CircleAlert aria-hidden="true" /><p><strong>Provisioning needs attention.</strong><span>{practice.failureMessage ?? 'The tenant database is not routable. Review the provisioning failure before retrying setup.'}</span></p></div> : null}

    <div className="practice-detail-grid">
      <div className="practice-detail-main">
        <section className="practice-detail-section" aria-labelledby="practice-identity-heading">
          <div className="practice-section-title"><div><h2 id="practice-identity-heading">Workspace identity</h2><p>Trusted identifiers used to resolve this tenant boundary.</p></div><ShieldCheck aria-hidden="true" /></div>
          <dl className="practice-definition-grid">
            <div><dt>Practice ID</dt><dd><span>{practice.practiceId}</span><button type="button" aria-label={copyState === 'failed' ? 'Retry copying Practice ID' : 'Copy Practice ID'} onClick={() => void copyPracticeId(practice.practiceId)}><Copy aria-hidden="true" /></button>{copyState ? <small className={`practice-copy-feedback ${copyState}`} role="status">{copyState === 'copied' ? 'Practice ID copied.' : 'Copy failed. Select the ID instead.'}</small> : null}</dd></div>
            <div><dt>Practice code</dt><dd>{practice.practiceCode}<small>Immutable after creation</small></dd></div>
            <div><dt>Created</dt><dd><time dateTime={practice.createdAt}>{formatPracticeDate(practice.createdAt)}</time></dd></div>
            <div><dt>Last updated</dt><dd><time dateTime={practice.updatedAt}>{formatPracticeDate(practice.updatedAt)}</time></dd></div>
          </dl>
        </section>

        <section className="practice-detail-section" aria-labelledby="practice-admin-heading">
          <div className="practice-section-title"><div><h2 id="practice-admin-heading">Initial administrator</h2><p>Identity preparation is separate from clinical permissions.</p></div><UserRound aria-hidden="true" /></div>
          <div className="practice-admin-record"><span><UserRound aria-hidden="true" /></span><div><strong>{practice.administratorName ?? 'Administrator record unavailable'}</strong><p>{practice.administratorEmail ?? 'No setup record was found.'}</p></div><span className="practice-setup-status">{administratorStatus}</span>{canIssueTemporaryPassword ? <Button variant="outline" size="sm" onClick={() => { temporaryPasswordMutation.reset(); temporaryPasswordCommandRef.current = null; setTemporaryPasswordOpen(true) }}><KeyRound aria-hidden="true" />{practice.administratorSetupStatus === 'TEMPORARY_PASSWORD_ISSUED' ? 'Issue new password' : 'Issue temporary password'}</Button> : null}</div>
          <p className="practice-detail-note"><KeyRound aria-hidden="true" />Managing administrators and Practice roles requires the tenant-authorized administration workflow; it is not available from this global view.</p>
        </section>

        <section className="practice-detail-section" aria-labelledby="practice-provisioning-heading">
          <div className="practice-section-title"><div><h2 id="practice-provisioning-heading">Provisioning health</h2><p>Control-plane status without exposing database routes or credentials.</p></div><Database aria-hidden="true" /></div>
          <dl className="practice-definition-grid">
            <div><dt>Provisioning status</dt><dd>{practice.status.toLowerCase().replace('_', ' ')}</dd></div>
            <div><dt>Schema version</dt><dd>{practice.schemaVersion ? `Version ${practice.schemaVersion}` : 'Not available'}</dd></div>
            <div><dt>Provisioning attempts</dt><dd>{practice.provisioningAttempts}</dd></div>
            <div><dt>Service access</dt><dd>{practice.serviceStatus === 'ENABLED' ? 'Enabled' : 'Suspended'}</dd></div>
          </dl>
        </section>
      </div>

      <aside className="practice-control-panel" aria-labelledby="practice-controls-heading">
        <div><h2 id="practice-controls-heading">Service controls</h2><p>Consequential changes require a reason and are recorded with the acting administrator.</p></div>
        <div className="practice-control-status"><span className={practice.serviceStatus === 'ENABLED' ? 'enabled' : 'suspended'}>{practice.serviceStatus === 'ENABLED' ? <CircleCheck aria-hidden="true" /> : <CirclePause aria-hidden="true" />}{practice.serviceStatus === 'ENABLED' ? 'Access enabled' : 'Access suspended'}</span><small>Record version {practice.version}</small></div>
        <Button variant={suspending ? 'outline' : 'default'} onClick={() => { lifecycleMutation.reset(); lifecycleCommandRef.current = null; setLifecycleTarget(suspending ? 'SUSPENDED' : 'ENABLED') }} disabled={!canChangeService}>
          {suspending ? <CirclePause aria-hidden="true" /> : <RotateCcw aria-hidden="true" />}{suspending ? 'Suspend access' : 'Reactivate access'}
        </Button>
        {!canChangeService ? <p className="practice-control-help">Resolve provisioning before changing service access.</p> : null}
        <div className="practice-danger-zone">
          <div><FileClock aria-hidden="true" /><span><strong>Decommission Practice</strong><small>Final closure needs verified export, retention, restore, and unresolved-clinical-work checks.</small></span></div>
          <Button variant="outline" disabled>Not available yet</Button>
        </div>
      </aside>
    </div>

    <PracticeLifecycleDialog
      key={`lifecycle:${practice.version}:${lifecycleTarget ?? 'closed'}`}
      open={lifecycleTarget !== null}
      practiceName={practice.displayName}
      targetStatus={lifecycleTarget ?? 'SUSPENDED'}
      pending={lifecycleMutation.isPending}
      error={lifecycleMutation.isError ? lifecycleMutation.error.message : null}
      onCancel={() => { lifecycleMutation.reset(); lifecycleCommandRef.current = null; setLifecycleTarget(null) }}
      onConfirm={reason => lifecycleTarget ? changeLifecycle(practice, lifecycleTarget, reason) : undefined}
    />
    <TemporaryPasswordDialog
      key={`credential:${practice.version}:${temporaryPasswordOpen ? 'open' : 'closed'}`}
      open={temporaryPasswordOpen}
      practiceName={practice.displayName}
      administratorEmail={practice.administratorEmail ?? ''}
      replacing={practice.administratorSetupStatus === 'TEMPORARY_PASSWORD_ISSUED'}
      pending={temporaryPasswordMutation.isPending}
      error={temporaryPasswordMutation.isError ? temporaryPasswordMutation.error.message : null}
      temporaryPassword={temporaryPasswordMutation.data?.temporaryPassword ?? null}
      issuanceAlreadyProcessed={temporaryPasswordMutation.data?.newlyIssued === false}
      onCancel={() => { temporaryPasswordMutation.reset(); temporaryPasswordCommandRef.current = null; setTemporaryPasswordOpen(false) }}
      onConfirm={reason => issueTemporaryPassword(practice, reason)}
    />
  </div>
}
