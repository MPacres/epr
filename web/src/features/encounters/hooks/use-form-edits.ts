import { useCallback, useEffect, useState, type FormEvent } from 'react'
/** Tracks unsent ancillary forms separately from the serialized encounter draft. */
export function useFormEdits() {
  const [forms, setForms] = useState<Set<HTMLFormElement>>(() => new Set())
  const dirty = [...forms].some(form => form.isConnected)
  const onChangeCapture = useCallback((event: FormEvent<HTMLElement>) => {
    const form = event.target instanceof Element ? event.target.closest('form') : null
    if (form && !form.hasAttribute('data-clinical-autosave')) setForms(previous => new Set(previous).add(form))
  }, [])
  useEffect(() => {
    const saved = (event: Event) => { if (event.target instanceof HTMLFormElement) { const form = event.target; setForms(previous => { const next = new Set(previous); next.delete(form); return next }) } }
    const warn = (event: BeforeUnloadEvent) => { if (dirty) event.preventDefault() }
    document.addEventListener('clinical-form-saved', saved)
    window.addEventListener('beforeunload', warn)
    return () => { document.removeEventListener('clinical-form-saved', saved); window.removeEventListener('beforeunload', warn) }
  }, [dirty])
  return { dirty, onChangeCapture, discard: () => setForms(new Set()) }
}
export const acknowledgeForm = (form: HTMLFormElement) => form.dispatchEvent(new Event('clinical-form-saved', { bubbles: true }))
