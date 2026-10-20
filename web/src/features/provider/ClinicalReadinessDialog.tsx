import * as Dialog from '@radix-ui/react-dialog'
import { DatabaseZap, Stethoscope, X } from 'lucide-react'
import { useRef, useState } from 'react'
import { Button } from '../../components/ui/button'

export type ClinicalReadinessAction = 'upgrade' | 'enable'

export function ClinicalReadinessDialog({ open, action, practiceName, requiredSchemaVersion, pending, error, onCancel, onConfirm }: {
  open: boolean
  action: ClinicalReadinessAction
  practiceName: string
  requiredSchemaVersion: string
  pending: boolean
  error: string | null
  onCancel: () => void
  onConfirm: (reason: string) => void
}) {
  const [reason, setReason] = useState('')
  const openerRef = useRef<HTMLElement | null>(null)
  const upgrading = action === 'upgrade'
  const inputId = `clinical-readiness-reason-${action}`

  function cancel() {
    setReason('')
    onCancel()
  }

  return <Dialog.Root open={open} onOpenChange={value => { if (!value && !pending) cancel() }}>
    <Dialog.Portal>
      <Dialog.Overlay className="sheet-overlay" />
      <Dialog.Content className="practice-lifecycle-dialog" role="alertdialog" onOpenAutoFocus={event => {
        if (document.activeElement instanceof HTMLElement) openerRef.current = document.activeElement
        event.preventDefault()
        requestAnimationFrame(() => document.getElementById(inputId)?.focus())
      }} onCloseAutoFocus={event => {
        event.preventDefault()
        openerRef.current?.focus({ preventScroll: true })
      }}>
        <div className="practice-lifecycle-icon clinical">{upgrading ? <DatabaseZap aria-hidden="true" /> : <Stethoscope aria-hidden="true" />}</div>
        <Dialog.Title>{upgrading ? 'Upgrade tenant schema?' : 'Enable synthetic clinical workflows?'}</Dialog.Title>
        <Dialog.Description>{upgrading
          ? `${practiceName} will run the guarded tenant migration to schema ${requiredSchemaVersion}. A failed rollout is quarantined and remains unavailable to clinical traffic.`
          : `${practiceName} will receive the local synthetic clinical capability and all three longitudinal-list source capabilities. No doctor receives individual access automatically.`}</Dialog.Description>
        <label htmlFor={inputId}>Reason <span aria-hidden="true">*</span></label>
        <textarea id={inputId} value={reason} onChange={event => setReason(event.target.value)} rows={4} maxLength={500} required disabled={pending} aria-describedby={`${inputId}-hint`} />
        <p id={`${inputId}-hint`}>Recorded with the support operation. Up to 500 characters.</p>
        {error ? <p className="practice-dialog-error" role="alert">{error}</p> : null}
        <div className="practice-dialog-actions"><Button variant="outline" onClick={cancel} disabled={pending}>Cancel</Button><Button onClick={() => onConfirm(reason)} disabled={pending || !reason.trim()}>{pending ? 'Working…' : upgrading ? `Upgrade to schema ${requiredSchemaVersion}` : 'Enable workflows'}</Button></div>
        <Dialog.Close className="practice-dialog-close" aria-label="Close" disabled={pending}><X aria-hidden="true" /></Dialog.Close>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
