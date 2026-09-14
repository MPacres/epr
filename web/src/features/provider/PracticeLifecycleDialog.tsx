import * as Dialog from '@radix-ui/react-dialog'
import { CirclePause, RotateCcw, X } from 'lucide-react'
import { useRef, useState } from 'react'
import { Button } from '../../components/ui/button'
import type { PracticeServiceStatus } from './practice-administration-client'

export function PracticeLifecycleDialog({
  open,
  practiceName,
  targetStatus,
  pending,
  error,
  onCancel,
  onConfirm,
}: {
  open: boolean
  practiceName: string
  targetStatus: PracticeServiceStatus
  pending: boolean
  error: string | null
  onCancel: () => void
  onConfirm: (reason: string) => void
}) {
  const [reason, setReason] = useState('')
  const openerRef = useRef<HTMLElement | null>(null)
  const suspending = targetStatus === 'SUSPENDED'

  function cancel() {
    setReason('')
    onCancel()
  }

  return <Dialog.Root open={open} onOpenChange={value => { if (!value && !pending) cancel() }}>
    <Dialog.Portal>
      <Dialog.Overlay className="sheet-overlay" />
      <Dialog.Content
        className="practice-lifecycle-dialog"
        role="alertdialog"
        onOpenAutoFocus={event => {
          if (document.activeElement instanceof HTMLElement) openerRef.current = document.activeElement
          event.preventDefault()
          requestAnimationFrame(() => document.getElementById('practice-lifecycle-reason')?.focus())
        }}
        onCloseAutoFocus={event => {
          event.preventDefault()
          openerRef.current?.focus({ preventScroll: true })
        }}
      >
        <div className={`practice-lifecycle-icon ${suspending ? 'suspend' : 'reactivate'}`}>
          {suspending ? <CirclePause aria-hidden="true" /> : <RotateCcw aria-hidden="true" />}
        </div>
        <Dialog.Title>{suspending ? 'Suspend Practice access?' : 'Reactivate Practice access?'}</Dialog.Title>
        <Dialog.Description>
          {suspending
            ? `${practiceName} will stop being routable for Practice users. Its tenant database, memberships, and records will be preserved.`
            : `${practiceName} will become routable again. Existing Practice memberships will not be changed.`}
        </Dialog.Description>
        <label htmlFor="practice-lifecycle-reason">Reason <span aria-hidden="true">*</span></label>
        <textarea
          id="practice-lifecycle-reason"
          value={reason}
          onChange={event => setReason(event.target.value)}
          rows={4}
          minLength={8}
          maxLength={500}
          required
          aria-describedby="practice-lifecycle-hint"
          disabled={pending}
        />
        <p id="practice-lifecycle-hint">Recorded in the Practice’s append-only administration history. Minimum 8 characters.</p>
        {error ? <p className="practice-dialog-error" role="alert">{error}</p> : null}
        <div className="practice-dialog-actions">
          <Button variant="outline" onClick={cancel} disabled={pending}>Cancel</Button>
          <Button className={suspending ? 'practice-suspend-confirm' : ''} onClick={() => onConfirm(reason)} disabled={pending || reason.trim().length < 8}>
            {pending ? 'Saving change…' : suspending ? 'Suspend access' : 'Reactivate access'}
          </Button>
        </div>
        <Dialog.Close className="practice-dialog-close" aria-label="Close" disabled={pending}><X aria-hidden="true" /></Dialog.Close>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
