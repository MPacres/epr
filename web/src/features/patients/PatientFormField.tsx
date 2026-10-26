import { useContext, useState, type ReactNode } from 'react'
import { PatientIdentifierContext } from './patient-identifier-context'
import { Eye, EyeOff } from 'lucide-react'
import { useFormContext } from 'react-hook-form'
import { draftFields, type PatientDraft } from './patient-form-model'

export function FormField({ name, label, type = 'text', hint, options, disabled = false, wide = false, placeholder, required = false }: {
  name: keyof PatientDraft; label?: string; type?: string; hint?: string; options?: readonly string[] | readonly { value: string; label: string }[]; disabled?: boolean; wide?: boolean; placeholder?: string; required?: boolean
}) {
  const { register, formState: { errors } } = useFormContext<PatientDraft>()
  const error = errors[name]?.message
  const id = `patient-field-${name}`
  const props = { ...register(name, name === 'birthAccuracy' ? { deps: ['birthDate'] } : undefined), id, disabled, 'aria-label': label ?? draftFields[name].label, 'aria-invalid': !!error, 'aria-required': required, 'aria-describedby': [error ? `${id}-error` : '', hint ? `${id}-hint` : ''].filter(Boolean).join(' ') || undefined }
  return <div className={`form-field ${wide ? 'form-field-wide' : ''}`}><label htmlFor={id}>{label ?? draftFields[name].label}{required ? <span className="required-mark" aria-hidden="true"> *</span> : null}</label>
    {options ? <select {...props}>{options.map(option => typeof option === 'string' ? <option key={option}>{option}</option> : <option key={option.value} value={option.value}>{option.label}</option>)}</select> : type === 'textarea' ? <textarea {...props} rows={3} /> : <input {...props} type={type} placeholder={placeholder} autoComplete="off" />}
    {hint ? <p className="field-hint" id={`${id}-hint`}>{hint}</p> : null}{error ? <p className="field-error" id={`${id}-error`}>{error}</p> : null}
  </div>
}
export function SecretField({ name, disabled, hint }: { name: 'pin' | 'principalPin' | 'otherIdValue'; disabled?: boolean; hint?: string }) {
  const [revealed, setRevealed] = useState(false)
  const identifiers = useContext(PatientIdentifierContext)
  const { register, formState: { errors } } = useFormContext<PatientDraft>()
  const id = `patient-field-${name}`, label = draftFields[name].label, error = errors[name]?.message
  if (identifiers) return <div className="form-field"><label htmlFor={`${id}-operation`}>{label} <span className="optional-label">Optional</span></label>
    <p className="field-hint">{identifiers.masks[name] ? `Stored: ${identifiers.masks[name]}` : 'Not recorded'}</p>
    <select id={`${id}-operation`} aria-label={`${label} action`} disabled={disabled} value={identifiers.modes[name]} onChange={event => identifiers.change(name,event.target.value as 'UNCHANGED'|'REPLACE'|'REMOVE')}><option value="UNCHANGED">Keep existing value</option><option value="REPLACE">Replace value</option><option value="REMOVE">Remove value</option></select>
    {identifiers.modes[name]==='REPLACE' && !disabled ? <input {...register(name)} id={id} aria-label={`New ${label}`} type="password" autoComplete="off" aria-invalid={!!error} /> : null}
    {error ? <p className="field-error">{error}</p> : null}
  </div>
  return <div className="form-field"><label htmlFor={id}>{label} <span className="optional-label">Optional</span></label><div className="secret-input"><input {...register(name)} id={id} aria-label={label} disabled={disabled} type={revealed && !disabled ? 'text' : 'password'} autoComplete="off" placeholder={disabled ? 'Not provided' : 'Enter identifier'} aria-invalid={!!error} aria-describedby={`${id}-hint${error ? ` ${id}-error` : ''}`} /><button type="button" disabled={disabled} aria-label={`${revealed ? 'Hide' : 'Show'} ${label}`} aria-pressed={revealed} onClick={() => setRevealed(value => !value)}>{revealed ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}</button></div><p className="field-hint" id={`${id}-hint`}>{hint ?? 'Masked by default. Use fictional information only.'}</p>{error ? <p className="field-error" id={`${id}-error`}>{error}</p> : null}</div>
}
export function FormCheckbox({ name, children }: { name: 'provisional' | 'pinUnavailable'; children: ReactNode }) {
  const { register } = useFormContext<PatientDraft>()
  return <label className="form-checkbox"><input type="checkbox" {...register(name)} />{children}</label>
}
