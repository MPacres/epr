import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft, Building2, Check, ChevronRight, CircleUserRound, Clock3, Hospital, MapPin, ShieldCheck } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Button } from '../../components/ui/button'
import { tenantCreationSchema, type TenantCreationValues } from './tenant-creation-schema'
import './tenant-creation.css'

const defaultValues: TenantCreationValues = {
  practiceName: '',
  administratorName: '',
  administratorEmail: '',
  siteName: '',
  facilityName: '',
  cityMunicipality: '',
  province: '',
  contactNumber: '',
}

function Field({ id, label, required = false, hint, error, children }: {
  id: string
  label: string
  required?: boolean
  hint?: string
  error?: string
  children: ReactNode
}) {
  return <div className="tenant-field">
    <label htmlFor={id}>{label}{required ? <span aria-hidden="true"> *</span> : <small> Optional</small>}</label>
    {children}
    {hint ? <p id={`${id}-hint`} className="tenant-field-hint">{hint}</p> : null}
    {error ? <p id={`${id}-error`} className="tenant-field-error">{error}</p> : null}
  </div>
}

export function TenantCreation({ onBack }: { onBack: () => void }) {
  const [reviewing, setReviewing] = useState(false)
  const form = useForm<TenantCreationValues>({
    resolver: zodResolver(tenantCreationSchema),
    defaultValues,
  })
  const values = useWatch({ control: form.control }) as TenantCreationValues
  const { register, handleSubmit, formState: { errors } } = form
  const fieldProps = (name: keyof TenantCreationValues, hasHint = false) => ({
    ...register(name),
    id: `tenant-${name}`,
    'aria-invalid': !!errors[name],
    'aria-describedby': [hasHint ? `tenant-${name}-hint` : '', errors[name] ? `tenant-${name}-error` : ''].filter(Boolean).join(' ') || undefined,
  })

  useEffect(() => {
    if (!reviewing) return
    const frame = requestAnimationFrame(() => {
      window.scrollTo({ top: 0, behavior: 'instant' })
      document.getElementById('tenant-review')?.focus({ preventScroll: true })
    })
    return () => cancelAnimationFrame(frame)
  }, [reviewing])

  function review() { setReviewing(true) }

  if (reviewing) {
    return <div className="tenant-create-page tenant-review-page" id="tenant-review" tabIndex={-1}>
      <button className="tenant-back" type="button" onClick={() => setReviewing(false)}><ArrowLeft aria-hidden="true" />Edit practice details</button>
      <div className="tenant-create-heading">
        <div><h1>Review the practice workspace</h1><p>Confirm the record boundary, initial administrator, and first clinic location.</p></div>
        <span className="tenant-draft-status"><Clock3 aria-hidden="true" />Draft setup</span>
      </div>
      <div className="tenant-review-layout">
        <section className="tenant-review-sheet" aria-labelledby="review-practice-name">
          <div className="tenant-review-identity"><span><Building2 aria-hidden="true" /></span><div><h2 id="review-practice-name">{values.practiceName}</h2><p>Practice workspace</p></div></div>
          <dl className="tenant-review-list">
            <div><dt>Initial administrator</dt><dd>{values.administratorName}<span>{values.administratorEmail}</span></dd></div>
            <div><dt>First clinic location</dt><dd>{values.siteName}<span>{values.cityMunicipality}, {values.province}</span></dd></div>
            <div><dt>Facility or building</dt><dd>{values.facilityName || 'Not provided'}</dd></div>
            <div><dt>Practice contact</dt><dd>{values.contactNumber || 'Not provided'}</dd></div>
          </dl>
        </section>
        <aside className="tenant-review-actions" aria-label="Provisioning status">
          <div className="tenant-boundary-card"><ShieldCheck aria-hidden="true" /><div><h2>One practice, one record boundary</h2><p>The Practice owns this workspace. Clinic locations may be inside shared facilities without sharing patient charts.</p></div></div>
          <div className="tenant-provisioning-note"><strong>Provisioning is not connected</strong><p>The final action stays unavailable until administrator invitation, initial-location persistence, and tenant routing are connected.</p></div>
          <Button className="tenant-create-disabled" disabled><Check aria-hidden="true" />Create practice</Button>
          <button className="tenant-edit-link" type="button" onClick={() => setReviewing(false)}>Edit details</button>
        </aside>
      </div>
    </div>
  }

  return <div className="tenant-create-page">
    <button className="tenant-back" type="button" onClick={onBack}><ArrowLeft aria-hidden="true" />Back to provider overview</button>
    <div className="tenant-create-heading">
      <div><h1>Create a practice workspace</h1><p>Set up the organization that governs its patient records, administrator, and first clinic location.</p></div>
      <span className="tenant-draft-status"><Clock3 aria-hidden="true" />Draft setup</span>
    </div>
    <form className="tenant-create-layout" onSubmit={handleSubmit(review)} noValidate>
      <div className="tenant-form-sheet">
        <section className="tenant-form-section" aria-labelledby="practice-section-title">
          <div className="tenant-section-heading"><span><Building2 aria-hidden="true" /></span><div><h2 id="practice-section-title">Practice identity</h2><p>Name the record-owning workspace the way its staff and patients know it.</p></div></div>
          <div className="tenant-field-grid tenant-field-grid-single">
            <Field id="tenant-practiceName" label="Practice name" required error={errors.practiceName?.message} hint="Example: Santos Family Medicine">
              <input {...fieldProps('practiceName', true)} placeholder="Enter the practice name" autoComplete="organization" />
            </Field>
          </div>
        </section>

        <section className="tenant-form-section" aria-labelledby="administrator-section-title">
          <div className="tenant-section-heading"><span><CircleUserRound aria-hidden="true" /></span><div><h2 id="administrator-section-title">Initial administrator</h2><p>Invite the person who will manage this Practice’s users, locations, and settings. They may be a clinician or a non-clinical practice manager.</p></div></div>
          <div className="tenant-field-grid">
            <Field id="tenant-administratorName" label="Administrator’s full name" required error={errors.administratorName?.message}>
              <input {...fieldProps('administratorName')} placeholder="Firstname Lastname" autoComplete="name" />
            </Field>
            <Field id="tenant-administratorEmail" label="Work email" required error={errors.administratorEmail?.message} hint="The Practice invitation will use this address once account invitation is connected.">
              <input {...fieldProps('administratorEmail', true)} type="email" placeholder="admin@practice.com" autoComplete="email" />
            </Field>
          </div>
          <div className="tenant-access-note" role="note"><ShieldCheck aria-hidden="true" /><p><strong>Administrative access is not clinical access.</strong> This role can manage the workspace but does not automatically grant access to patient charts.</p></div>
        </section>

        <section className="tenant-form-section" aria-labelledby="site-section-title">
          <div className="tenant-section-heading"><span><Hospital aria-hidden="true" /></span><div><h2 id="site-section-title">First clinic location</h2><p>Add the first place where this Practice will deliver care. More locations can be added later.</p></div></div>
          <div className="tenant-field-grid">
            <Field id="tenant-siteName" label="Clinic location name" required error={errors.siteName?.message}>
              <input {...fieldProps('siteName')} placeholder="e.g. Makati clinic" />
            </Field>
            <Field id="tenant-facilityName" label="Facility or building" error={errors.facilityName?.message} hint="A shared hospital or building is a physical location, not the tenant.">
              <input {...fieldProps('facilityName', true)} placeholder="e.g. Medical Arts Building, Room 402" autoComplete="organization" />
            </Field>
            <Field id="tenant-contactNumber" label="Practice contact number" error={errors.contactNumber?.message}>
              <input {...fieldProps('contactNumber')} placeholder="+63" type="tel" autoComplete="tel" />
            </Field>
            <Field id="tenant-cityMunicipality" label="City or municipality" required error={errors.cityMunicipality?.message}>
              <input {...fieldProps('cityMunicipality')} placeholder="Enter city or municipality" autoComplete="address-level2" />
            </Field>
            <Field id="tenant-province" label="Province or region" required error={errors.province?.message}>
              <input {...fieldProps('province')} placeholder="Enter province or region" autoComplete="address-level1" />
            </Field>
          </div>
        </section>

        <footer className="tenant-form-footer">
          <p><ShieldCheck aria-hidden="true" />No patient records are created during this setup.</p>
          <Button type="submit">Review practice <ChevronRight aria-hidden="true" /></Button>
        </footer>
      </div>

      <aside className="tenant-setup-aside" aria-label="Practice setup guidance">
        <div className="tenant-workspace-preview" aria-live="polite">
          <span className="tenant-preview-icon"><Building2 aria-hidden="true" /></span>
          <p>Practice workspace</p>
          <strong>{values.practiceName || 'Practice name'}</strong>
          <span>{values.administratorName ? `Admin: ${values.administratorName}` : 'Initial administrator'}</span>
        </div>
        <div className="tenant-aside-content">
          <h2>What this setup establishes</h2>
          <ul>
            <li><Check aria-hidden="true" /><span><strong>A Practice record boundary</strong>The tenant that governs its patient records.</span></li>
            <li><Check aria-hidden="true" /><span><strong>An initial administrator</strong>The person invited to manage users, locations, and settings.</span></li>
            <li><Check aria-hidden="true" /><span><strong>A first clinic location</strong>The first service point for schedules, queues, and care.</span></li>
            <li><Check aria-hidden="true" /><span><strong>A team that can grow</strong>Add clinicians and staff later without changing the tenant.</span></li>
          </ul>
        </div>
        <div className="tenant-scope-note"><MapPin aria-hidden="true" /><p><strong>A facility is a physical place, not a tenant.</strong>Independent Practices may operate in the same hospital or building without sharing charts.</p></div>
      </aside>
    </form>
  </div>
}
