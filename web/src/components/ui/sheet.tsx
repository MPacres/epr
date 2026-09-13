import * as Dialog from '@radix-ui/react-dialog'
import type { ReactNode } from 'react'
import { X } from 'lucide-react'

export function Sheet({ open, onClose, title, description, children, onRestoreFocus }: { open: boolean; onClose: () => void; title: string; description: string; children: ReactNode; onRestoreFocus?: () => void }) {
  return <Dialog.Root open={open} onOpenChange={(value) => { if (!value) onClose() }}>
    <Dialog.Portal>
      <Dialog.Overlay className="sheet-overlay" />
      <Dialog.Content className="sheet-content" onCloseAutoFocus={onRestoreFocus ? event => { event.preventDefault(); onRestoreFocus() } : undefined}>
        <header className="sheet-header">
          <div><Dialog.Title>{title}</Dialog.Title><Dialog.Description>{description}</Dialog.Description></div>
          <Dialog.Close className="icon-button" aria-label="Close panel"><X aria-hidden="true" /></Dialog.Close>
        </header>
        <div className="sheet-body">{children}</div>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
