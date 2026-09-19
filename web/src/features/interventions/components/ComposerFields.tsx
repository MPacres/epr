import { cloneElement, useId, type ReactElement } from 'react'
import { Button } from '../../../components/ui/button'
export function Field({ label, children, error }: { label: string; children: ReactElement<{ id?: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }>; error?: string }) {
  const id = useId()
  return <div className="clinical-field intervention-field"><label htmlFor={id}>{label}</label>{cloneElement(children, { id, 'aria-describedby': error ? `${id}-error` : undefined, 'aria-invalid': !!error })}{error ? <span id={`${id}-error`} role="alert" className="intervention-field-error">{error}</span> : null}</div>
}
export function ComposerActions({ busy, onCancel, label = 'Save draft' }: { busy: boolean; onCancel: () => void; label?: string }) {
  return <div className="intervention-composer-actions"><Button type="button" variant="outline" onClick={onCancel} disabled={busy}>Cancel</Button><Button type="submit" variant="outline" name="add-another" disabled={busy}>{label === 'Save draft' ? 'Save and add another' : 'Create task and add another'}</Button><Button type="submit" disabled={busy}>{busy ? 'Waiting for confirmation…' : label}</Button></div>
}
