import './confirmation-dialog.css'
import * as Dialog from '@radix-ui/react-dialog'
import { useRef } from 'react'
import { Button } from './button'
export function ConfirmationDialog({ open, title, description, onCancel, onConfirm }: { open: boolean; title: string; description: string; onCancel: () => void; onConfirm: () => void }) {
  const openerRef = useRef<HTMLElement | null>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  return <Dialog.Root open={open} onOpenChange={value => { if (!value) onCancel() }}><Dialog.Portal><Dialog.Overlay className="sheet-overlay" /><Dialog.Content className="confirmation-dialog" role="alertdialog" onOpenAutoFocus={event => { event.preventDefault(); if (document.activeElement instanceof HTMLElement) openerRef.current = document.activeElement; cancelRef.current?.focus() }} onCloseAutoFocus={event => { event.preventDefault(); const target = openerRef.current?.isConnected ? openerRef.current : document.getElementById('main-content'); target?.focus({ preventScroll:true }) }}><Dialog.Title>{title}</Dialog.Title><Dialog.Description>{description}</Dialog.Description><div><button ref={cancelRef} className="confirmation-keep" onClick={onCancel}>Keep editing</button><Button onClick={onConfirm}>Discard changes</Button></div></Dialog.Content></Dialog.Portal></Dialog.Root>
}
