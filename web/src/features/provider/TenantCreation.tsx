import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { ArrowLeft, Building2, Check, ChevronRight, CircleCheckBig, CircleUserRound, Clock3, Hospital, LoaderCircle, MapPin, ShieldCheck, TriangleAlert } from 'lucide-react'
import { useEffect, useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Button } from '../../components/ui/button'
import { createPractice, createUuid, type TenantCreationCommand } from './tenant-creation-client'
import {
  getLocalities,
  getLocality,
  getProvinceArea,
  getProvinceAreas,
  getRegion,
  PHILIPPINES_COUNTRY_CODE,
  PHILIPPINES_COUNTRY_NAME,
  PHILIPPINE_REGIONS,
  PSGC_RELEASE_LABEL,
} from './philippine-locations'
import { tenantCreationSchema, type TenantCreationValues } from './tenant-creation-schema'
import { TemporaryCredentialPanel } from './TemporaryCredentialPanel'
import './tenant-creation.css'

const defaultValues: TenantCreationValues = {
  practiceName: '',
  administratorName: '',
  administratorEmail: '',
  siteName: '',
  facilityName: '',
  countryCode: PHILIPPINES_COUNTRY_CODE,
  regionCode: '',
  provinceCode: '',
  cityMunicipalityCode: '',
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

export function TenantCreation({ onBack, onCreated }: { onBack: () => void; onCreated: (practiceId: string) => void }) {
  const [reviewing, setReviewing] = useState(false)
  const commandRef = useRef<{ fingerprint: string; command: TenantCreationCommand } | null>(null)
  const form = useForm<TenantCreationValues>({
    resolver: zodResolver(tenantCreationSchema),
    defaultValues,
  })
  const values = useWatch({ control: form.control }) as TenantCreationValues
  const creation = useMutation({ mutationFn: createPractice })
  const { register, handleSubmit, setValue, clearErrors, formState: { errors } } = form
  const provinceAreas = getProvinceAreas(values.regionCode)
  const localities = getLocalities(values.provinceCode)
  const selectedRegion = getRegion(values.regionCode)
  const selectedProvinceArea = getProvinceArea(values.provinceCode)
  const selectedLocality = getLocality(values.cityMunicipalityCode)
  const locationSummary = [
    selectedLocality?.name,
    selectedProvinceArea?.kind === 'province' ? selectedProvinceArea.name : undefined,
    selectedRegion?.name,
    PHILIPPINES_COUNTRY_NAME,
  ].filter((part): part is string => Boolean(part)).join(', ')
  const fieldProps = (name: keyof TenantCreationValues, hasHint = false) => ({
    ...register(name),
    id: `tenant-${name}`,
    'aria-invalid': !!errors[name],
    'aria-describedby': [hasHint ? `tenant-${name}-hint` : '', errors[name] ? `tenant-${name}-error` : ''].filter(Boolean).join(' ') || undefined,
  })
  const regionField = fieldProps('regionCode')
  const provinceField = fieldProps('provinceCode', true)

  function changeRegion(event: ChangeEvent<HTMLSelectElement>) {
    void regionField.onChange(event)
    setValue('provinceCode', '', { shouldDirty: true })
    setValue('cityMunicipalityCode', '', { shouldDirty: true })
    clearErrors(['provinceCode', 'cityMunicipalityCode'])
  }

  function changeProvince(event: ChangeEvent<HTMLSelectElement>) {
    void provinceField.onChange(event)
    setValue('cityMunicipalityCode', '', { shouldDirty: true })
    clearErrors('cityMunicipalityCode')
  }

  useEffect(() => {
    if (!reviewing) return
    const frame = requestAnimationFrame(() => {
      window.scrollTo({ top: 0, behavior: 'instant' })
      document.getElementById(creation.isSuccess ? 'tenant-created' : 'tenant-review')?.focus({ preventScroll: true })
    })
    return () => cancelAnimationFrame(frame)
  }, [reviewing, creation.isSuccess])

  function review() { setReviewing(true) }

  function provision() {
    const fingerprint = JSON.stringify(values)
    if (!commandRef.current || commandRef.current.fingerprint !== fingerprint) {
      commandRef.current = {
        fingerprint,
        command: {
          practiceId: createUuid(),
          siteId: createUuid(),
          idempotencyKey: createUuid(),
          values: { ...values },
        },
      }
    }
    creation.mutate(commandRef.current.command)
  }

  function editDetails() {
    creation.reset()
    setReviewing(false)
  }

  function createAnother() {
    commandRef.current = null
    creation.reset()
    form.reset(defaultValues)
    setReviewing(false)
  }

  if (reviewing && creation.isSuccess) {
    const result = creation.data
    const active = result.status === 'ACTIVE'
    return <div className="tenant-create-page tenant-result-page" id="tenant-created" tabIndex={-1}>
      <button className="tenant-back" type="button" onClick={onBack}><ArrowLeft aria-hidden="true" />Back to Practice directory</button>
      <div className="tenant-create-heading">
        <div><h1>{active ? 'Practice workspace created' : 'Practice setup is in progress'}</h1><p>{active ? 'The tenant boundary, initial administrator setup, and first clinic location are recorded.' : 'Another provisioning worker is still establishing this Practice workspace.'}</p></div>
        <span className={`tenant-result-status ${active ? 'active' : 'pending'}`}>{active ? <CircleCheckBig aria-hidden="true" /> : <Clock3 aria-hidden="true" />}{active ? 'Active' : 'Provisioning'}</span>
      </div>
      <div className="tenant-result-layout">
        <section className="tenant-result-sheet" aria-labelledby="created-practice-name">
          <div className="tenant-result-hero">
            <span><CircleCheckBig aria-hidden="true" /></span>
            <div><h2 id="created-practice-name">{result.displayName}</h2><p>{active ? 'Practice workspace is ready' : 'Practice workspace is being prepared'}</p></div>
          </div>
          <dl className="tenant-review-list">
            <div><dt>Practice code</dt><dd>{result.practiceCode}</dd></div>
            <div><dt>First clinic location</dt><dd>{values.siteName}<span>{locationSummary}</span></dd></div>
            <div><dt>Initial administrator</dt><dd>{values.administratorName}<span>{values.administratorEmail}</span></dd></div>
            <div><dt>Provisioning attempt</dt><dd>{result.provisioningAttempts}</dd></div>
          </dl>
        </section>
        <aside className="tenant-result-actions" aria-label="Practice creation result">
          {result.temporaryPassword ? <TemporaryCredentialPanel username={values.administratorEmail} temporaryPassword={result.temporaryPassword} /> : <div className={`tenant-setup-result ${result.administratorSetupStatus === 'EXISTING_ACCOUNT' ? 'linked' : 'pending'}`}>
            <CircleUserRound aria-hidden="true" />
            <div><strong>{result.administratorSetupStatus === 'EXISTING_ACCOUNT' ? 'Existing administrator account linked' : 'Temporary password already issued'}</strong><p>{result.administratorSetupStatus === 'EXISTING_ACCOUNT' ? 'The existing eligible Practice account received the administrator membership. No new credentials were created.' : 'For security, an earlier temporary password cannot be shown again. Issue a new one from Practice management if needed.'}</p></div>
          </div>}
          <div className="tenant-boundary-card"><ShieldCheck aria-hidden="true" /><div><h2>Clinical access remains separate</h2><p>Practice administration does not grant access to patient charts, signing, prescribing, or results review.</p></div></div>
          {!active ? <Button onClick={provision} disabled={creation.isPending}>{creation.isPending ? <LoaderCircle className="tenant-spinner" aria-hidden="true" /> : <Clock3 aria-hidden="true" />}{creation.isPending ? 'Checking setup…' : 'Check setup status'}</Button> : null}
          <Button variant={active ? 'default' : 'outline'} onClick={() => onCreated(result.practiceId)}>Manage this practice</Button>
          <Button variant="outline" onClick={createAnother}>Create another practice</Button>
          <button className="tenant-edit-link" type="button" onClick={onBack}>Return to Practice directory</button>
        </aside>
      </div>
    </div>
  }

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
            <div><dt>First clinic location</dt><dd>{values.siteName}<span>{locationSummary}</span></dd></div>
            <div><dt>Facility or building</dt><dd>{values.facilityName || 'Not provided'}</dd></div>
            <div><dt>Practice contact</dt><dd>{values.contactNumber || 'Not provided'}</dd></div>
          </dl>
        </section>
        <aside className="tenant-review-actions" aria-label="Provisioning status">
          <div className="tenant-boundary-card"><ShieldCheck aria-hidden="true" /><div><h2>One practice, one record boundary</h2><p>The Practice owns this workspace. Clinic locations may be inside shared facilities without sharing patient charts.</p></div></div>
          <div className="tenant-provisioning-note"><strong>Creates an isolated tenant database</strong><p>The first location is stored inside the Practice boundary. Administrator identity setup remains separate from clinical permissions.</p></div>
          {creation.isError ? <div className="tenant-create-error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Practice was not created</strong><p>{creation.error.message}</p></div></div> : null}
          <Button className="tenant-create-action" onClick={provision} disabled={creation.isPending}>{creation.isPending ? <LoaderCircle className="tenant-spinner" aria-hidden="true" /> : <Check aria-hidden="true" />}{creation.isPending ? 'Creating practice…' : creation.isError ? 'Retry creation' : 'Create practice'}</Button>
          <button className="tenant-edit-link" type="button" onClick={editDetails} disabled={creation.isPending}>Edit details</button>
        </aside>
      </div>
    </div>
  }

  return <div className="tenant-create-page">
    <button className="tenant-back" type="button" onClick={onBack}><ArrowLeft aria-hidden="true" />Back to Practice directory</button>
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
          <div className="tenant-section-heading"><span><CircleUserRound aria-hidden="true" /></span><div><h2 id="administrator-section-title">Initial administrator</h2><p>Prepare the person who will manage this Practice’s users, locations, and settings. They may be a clinician or a non-clinical practice manager.</p></div></div>
          <div className="tenant-field-grid">
            <Field id="tenant-administratorName" label="Administrator’s full name" required error={errors.administratorName?.message}>
              <input {...fieldProps('administratorName')} placeholder="Firstname Lastname" autoComplete="name" />
            </Field>
            <Field id="tenant-administratorEmail" label="Work email" required error={errors.administratorEmail?.message} hint="Used as the username. A temporary password is shown once after creation for personal handoff by support staff.">
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
            <Field id="tenant-countryCode" label="Country" required error={errors.countryCode?.message} hint="Philippines is the supported country for this setup.">
              <select {...fieldProps('countryCode', true)} autoComplete="country">
                <option value={PHILIPPINES_COUNTRY_CODE}>{PHILIPPINES_COUNTRY_NAME}</option>
              </select>
            </Field>
            <Field id="tenant-regionCode" label="Region" required error={errors.regionCode?.message}>
              <select {...regionField} onChange={changeRegion} autoComplete="address-level1">
                <option value="">Select a region</option>
                {PHILIPPINE_REGIONS.map((region) => <option key={region.code} value={region.code}>{region.name}</option>)}
              </select>
            </Field>
            <Field id="tenant-provinceCode" label="Province or independent area" required error={errors.provinceCode?.message} hint="NCR and independent cities appear as separate areas to preserve the official PSGC hierarchy.">
              <select {...provinceField} onChange={changeProvince} disabled={!values.regionCode} autoComplete="address-level1">
                <option value="">{values.regionCode ? 'Select a province or independent area' : 'Select a region first'}</option>
                {provinceAreas.map((area) => <option key={area.code} value={area.code}>{area.name}</option>)}
              </select>
            </Field>
            <Field id="tenant-cityMunicipalityCode" label="City or municipality" required error={errors.cityMunicipalityCode?.message} hint={`Location options follow the ${PSGC_RELEASE_LABEL}.`}>
              <select {...fieldProps('cityMunicipalityCode', true)} disabled={!values.provinceCode} autoComplete="address-level2">
                <option value="">{values.provinceCode ? 'Select a city or municipality' : 'Select a province or area first'}</option>
                {localities.map((locality) => <option key={locality.code} value={locality.code}>{locality.name}</option>)}
              </select>
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
            <li><Check aria-hidden="true" /><span><strong>An initial administrator</strong>The person prepared to manage users, locations, and settings.</span></li>
            <li><Check aria-hidden="true" /><span><strong>A first clinic location</strong>The first service point for schedules, queues, and care.</span></li>
            <li><Check aria-hidden="true" /><span><strong>A team that can grow</strong>Add clinicians and staff later without changing the tenant.</span></li>
          </ul>
        </div>
        <div className="tenant-scope-note"><MapPin aria-hidden="true" /><p><strong>A facility is a physical place, not a tenant.</strong>Independent Practices may operate in the same hospital or building without sharing charts.</p></div>
      </aside>
    </form>
  </div>
}
