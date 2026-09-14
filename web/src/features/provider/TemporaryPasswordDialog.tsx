import * as Dialog from '@radix-ui/react-dialog'
import { KeyRound, X } from 'lucide-react'
import { useRef, useState } from 'react'
import { Button } from '../../components/ui/button'
import { TemporaryCredentialPanel } from './TemporaryCredentialPanel'

export function TemporaryPasswordDialog({ open, practiceName, administratorEmail, replacing, pending, error, temporaryPassword, issuanceAlreadyProcessed, onCancel, onConfirm }: {
  open: boolean
  practiceName: string
  administratorEmail: string
  replacing: boolean
  pending: boolean
  error: string | null
  temporaryPassword: string | null
  issuanceAlreadyProcessed: boolean
  onCancel: () => void
  onConfirm: (reason: string) => void
}) {
  const [reason, setReason] = useState('')
  const [credentialSaved, setCredentialSaved] = useState(false)
  const openerRef = useRef<HTMLElement | null>(null)

  function close() {
    if (temporaryPassword && !credentialSaved) return
    setReason('')
    setCredentialSaved(false)
    onCancel()
  }

  return <Dialog.Root open={open} onOpenChange={value => { if (!value && !pending && (!temporaryPassword || credentialSaved)) close() }}>
    <Dialog.Portal>
      <Dialog.Overlay className="sheet-overlay" />
      <Dialog.Content className="practice-lifecycle-dialog temporary-password-dialog" onEscapeKeyDown={event => { if (temporaryPassword) event.preventDefault() }} onPointerDownOutside={event => { if (temporaryPassword) event.preventDefault() }} onOpenAutoFocus={event => {
        if (document.activeElement instanceof HTMLElement) openerRef.current = document.activeElement
        event.preventDefault()
        requestAnimationFrame(() => document.getElementById(temporaryPassword ? 'temporary-credential-panel' : 'temporary-password-reason')?.focus())
      }} onCloseAutoFocus={event => { event.preventDefault(); openerRef.current?.focus({ preventScroll: true }) }}>
        <div className="practice-lifecycle-icon credential"><KeyRound aria-hidden="true" /></div>
        <Dialog.Title>{temporaryPassword ? 'Temporary password created' : issuanceAlreadyProcessed ? 'Temporary password already issued' : replacing ? 'Issue a new temporary password?' : 'Issue a temporary password?'}</Dialog.Title>
        <Dialog.Description>{temporaryPassword
          ? `Give the sign-in details directly to ${administratorEmail}. They will not be available again after this dialog closes.`
          : issuanceAlreadyProcessed
            ? 'The original request was completed, but its one-time password cannot be shown again. Close this message, then issue a new password if the credential was not saved.'
          : replacing
            ? `This immediately invalidates the previous temporary password for ${practiceName}. The administrator must replace the new password at first sign-in.`
            : `This enables the prepared administrator account for ${practiceName}. The administrator must replace the temporary password at first sign-in.`}</Dialog.Description>
        {temporaryPassword ? <><TemporaryCredentialPanel username={administratorEmail} temporaryPassword={temporaryPassword} /><label className="temporary-credential-acknowledgement"><input type="checkbox" checked={credentialSaved} onChange={event => setCredentialSaved(event.target.checked)} />I have securely copied the temporary password.</label></> : issuanceAlreadyProcessed ? <p className="practice-dialog-error" role="status">No password was rotated by this retry. The account still uses the temporary password created by the original request.</p> : <>
          <label htmlFor="temporary-password-reason">Reason <span aria-hidden="true">*</span></label>
          <textarea id="temporary-password-reason" value={reason} onChange={event => setReason(event.target.value)} rows={4} minLength={8} maxLength={500} required aria-describedby="temporary-password-hint" disabled={pending} />
          <p id="temporary-password-hint">Recorded in the Practice’s append-only administration history. Minimum 8 characters.</p>
          {error ? <p className="practice-dialog-error" role="alert">{error}</p> : null}
        </>}
        <div className="practice-dialog-actions">
          {temporaryPassword ? <Button onClick={close} disabled={!credentialSaved}>Done</Button> : issuanceAlreadyProcessed ? <Button onClick={close}>Close</Button> : <><Button variant="outline" onClick={close} disabled={pending}>Cancel</Button><Button onClick={() => onConfirm(reason)} disabled={pending || reason.trim().length < 8}>{pending ? 'Creating password…' : replacing ? 'Issue new password' : 'Issue temporary password'}</Button></>}
        </div>
        {!temporaryPassword ? <Dialog.Close className="practice-dialog-close" aria-label="Close" disabled={pending}><X aria-hidden="true" /></Dialog.Close> : null}
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
