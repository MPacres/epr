import { useEffect, useId, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { Plus, Search, X } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { atSite, request, clinicalKey, type Scope } from '../encounters/services/encounter-api'
import { Field, ComposerActions } from '../interventions/components/ComposerFields'
import { combineLabInstructions, splitLabInstructions } from './lab-order-instructions'

const schema = z.object({ catalogCode: z.string().nullish(), indication: z.string().max(4000), destination: z.string().max(4000), priority: z.string(), requestedDate: z.string(), specimen: z.string(), bodySite: z.string(), laterality: z.string(), instructions: z.string().max(4000), fasting: z.boolean() })
type Values = z.infer<typeof schema>
type CatalogEntry = { code: string; version: string; kind: string; label: string }
type LabTest = { id: string; label: string; code: string | null; version: string | null }
const testIdentity = (test: Pick<LabTest, 'label' | 'code'>) => `${test.code ?? ''}\u0000${test.label.trim().toLocaleLowerCase()}`

export function OrderComposer({ scope, patient, kind, initial, busy, onDirty, onSave, onCancel }: { scope: Scope; patient: string; kind: 'LAB_ORDER' | 'IMAGING_ORDER'; initial?: Record<string, unknown>; busy: boolean; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>) => void; onCancel: () => void }) {
  const isLab = kind === 'LAB_ORDER'
  const initialText = (field: keyof Values) => typeof initial?.[field] === 'string' ? initial[field] as string : ''
  const existingInstructions = typeof initial?.instructions === 'string' ? initial.instructions : ''
  const parsedInstructions = isLab ? splitLabInstructions(existingInstructions) : { instructions: existingInstructions, fasting: false }
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { catalogCode: initialText('catalogCode'), indication: initialText('indication'), destination: initialText('destination'), priority: initialText('priority') || 'ROUTINE', requestedDate: initialText('requestedDate'), specimen: initialText('specimen'), bodySite: initialText('bodySite'), laterality: initialText('laterality'), ...parsedInstructions } })
  const priority = useWatch({ control: form.control, name: 'priority' })
  const [tests, setTests] = useState<LabTest[]>(() => typeof initial?.serviceName === 'string' && initial.serviceName.trim() ? [{ id: createUuid(), label: initial.serviceName, code: typeof initial.catalogCode === 'string' ? initial.catalogCode : null, version: typeof initial.catalogVersion === 'string' ? initial.catalogVersion : null }] : [])
  const [search, setSearch] = useState('')
  const [showSuggestions, setShowSuggestions] = useState(false)
  const [testError, setTestError] = useState('')
  const searchId = useId()
  const catalog = useQuery({ queryKey: [...clinicalKey(scope, patient), 'service-catalog'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patient}/service-requests/catalog`), z.array(z.object({ code: z.string(), version: z.string(), kind: z.string(), label: z.string() })), { signal }), retry: false })
  const services = catalog.data?.filter(entry => entry.kind === kind) ?? []
  const initialTestIdentity = typeof initial?.serviceName === 'string' && initial.serviceName.trim() ? testIdentity({ label: initial.serviceName, code: typeof initial.catalogCode === 'string' ? initial.catalogCode : null }) : ''
  const testsChanged = isLab && tests.map(testIdentity).join('|') !== initialTestIdentity
  useEffect(() => { onDirty(form.formState.isDirty || testsChanged) }, [onDirty, form.formState.isDirty, testsChanged])

  const matching = tests.length >= 30 ? [] : services.filter(entry => entry.label.toLowerCase().includes(search.trim().toLowerCase()) && !tests.some(test => test.code === entry.code || test.label.trim().toLowerCase() === entry.label.trim().toLowerCase()))
  const typedName = search.trim()
  const alreadyAdded = tests.some(test => test.label.trim().toLowerCase() === typedName.toLowerCase())

  function addTest(entry?: CatalogEntry) {
    const exact = entry ?? services.find(service => service.label.trim().toLowerCase() === typedName.toLowerCase())
    const label = exact?.label ?? typedName
    if (!label) return
    if (tests.length >= 30) { setTestError('An order can contain up to 30 tests.'); return }
    if (tests.some(test => test.label.trim().toLowerCase() === label.trim().toLowerCase())) { setTestError('This test is already in the order.'); return }
    const selected = { id: createUuid(), label, code: exact?.code ?? null, version: exact?.version ?? null }
    setTests(current => current.some(test => test.label.trim().toLowerCase() === label.trim().toLowerCase()) ? current : initial ? [selected] : [...current, selected])
    setSearch(''); setShowSuggestions(false); setTestError('')
  }

  function save(values: Values) {
    const { fasting, ...fields } = values
    if (isLab) {
      if (!tests.length) { setTestError('Add at least one test.'); return }
      const [first, ...rest] = tests
      onSave({ ...fields, kind, catalogCode: first.code, catalogVersion: first.version, serviceName: first.label, requestedDate: values.requestedDate || undefined, instructions: combineLabInstructions(fasting, values.instructions), groupId: initial?.groupId, additionalServices: rest.map(test => ({ code: test.code, version: test.version, label: test.label })) })
      return
    }
    const entry = services.find(service => service.code === values.catalogCode)
    if (!entry) { form.setError('catalogCode', { message: 'Select an available imaging request.' }); return }
    onSave({ ...fields, kind, serviceName: entry.label, catalogVersion: entry.version, requestedDate: values.requestedDate || undefined, groupId: initial?.groupId })
  }

  return <form data-clinical-autosave className="intervention-composer" onSubmit={form.handleSubmit(save)}>
    {catalog.isPending ? <p role="status">Loading {isLab ? 'test suggestions' : 'permitted services'}…</p> : catalog.error ? <p role="alert">{isLab ? 'Test suggestions are unavailable. You can still add a test by name.' : 'The service catalog is unavailable. Your inputs remain here.'}</p> : null}
    <fieldset disabled={busy || (!isLab && !catalog.data)}>
      {isLab ? <>
        <Field label="Laboratory / location"><input {...form.register('destination')} maxLength={4000} autoComplete="off" /></Field>
        <div className="lab-test-entry" onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setShowSuggestions(false) }}>
          <label htmlFor={searchId}>Test name</label>
          <div className="lab-test-search"><Search aria-hidden="true" /><input id={searchId} type="search" autoComplete="off" maxLength={200} disabled={tests.length >= 30} placeholder="Search or enter a test" value={search} onChange={event => { setSearch(event.target.value); setShowSuggestions(true); setTestError('') }} onFocus={() => setShowSuggestions(true)} onKeyDown={event => { if (event.key === 'Escape') setShowSuggestions(false); if (event.key === 'ArrowDown' && matching.length) { event.preventDefault(); event.currentTarget.closest('.lab-test-entry')?.querySelector<HTMLButtonElement>('.lab-test-suggestions button')?.focus() } if (event.key === 'Enter' && typedName) { event.preventDefault(); addTest() } }} /><Button type="button" variant="outline" disabled={!typedName || alreadyAdded || tests.length >= 30} onClick={() => addTest()}><Plus aria-hidden="true" />Add</Button></div>
          {showSuggestions && matching.length ? <ul className="lab-test-suggestions" aria-label="Matching saved tests">{matching.slice(0, 12).map(entry => <li key={`${entry.code}:${entry.version}`}><button type="button" onClick={() => addTest(entry)}>{entry.label}</button></li>)}</ul> : null}
          {tests.length >= 30 ? <p className="lab-test-search-note">Maximum 30 tests per order.</p> : showSuggestions && typedName && !matching.length && !alreadyAdded ? <p className="lab-test-search-note">No saved match. Use Add to enter this test.</p> : null}
        </div>
        <div className="lab-selected"><h3>Tests in this order</h3>{tests.length ? <ul>{tests.map(test => <li key={test.id}><span>{test.label}</span><Button type="button" variant="ghost" aria-label={`Remove ${test.label}`} onClick={() => setTests(current => current.filter(item => item.id !== test.id))}><X aria-hidden="true" /></Button></li>)}</ul> : <p>No tests added yet.</p>}{testError ? <p role="alert" className="intervention-field-error">{testError}</p> : null}</div>
        <div className="intervention-field-grid"><Field label="Requested test date"><input type="date" {...form.register('requestedDate')} /></Field><Field label="Reason for test"><input {...form.register('indication')} maxLength={4000} /></Field></div>
        <Field label="Instructions / alerts"><textarea {...form.register('instructions')} maxLength={3980} rows={3} /></Field>
        <div className="lab-order-flags"><label className="clinical-checkbox"><input type="checkbox" checked={priority === 'STAT' || priority === 'URGENT'} onChange={event => form.setValue('priority', event.target.checked ? 'STAT' : 'ROUTINE', { shouldDirty: true })} />STAT</label><label className="clinical-checkbox"><input type="checkbox" {...form.register('fasting')} />Fasting required</label></div>
        <details className="lab-more-details"><summary>Specimen details</summary><Field label="Specimen, if applicable"><input {...form.register('specimen')} /></Field></details>
      </> : <>
        <Field label="Imaging request" error={form.formState.errors.catalogCode?.message}><select {...form.register('catalogCode')} required><option value="">Select a request</option>{services.map(entry => <option key={entry.code} value={entry.code}>{entry.label}</option>)}</select></Field>
        <Field label="Clinical question / indication"><textarea {...form.register('indication')} maxLength={4000} /></Field>
        <div className="intervention-field-grid"><Field label="Destination"><input {...form.register('destination')} /></Field><Field label="Requested date"><input type="date" {...form.register('requestedDate')} /></Field><Field label="Priority"><select {...form.register('priority')}><option value="ROUTINE">Routine</option><option value="URGENT">Urgent · clinician selected</option></select></Field><Field label="Body site"><input {...form.register('bodySite')} /></Field><Field label="Laterality, if applicable"><input {...form.register('laterality')} /></Field></div>
        <Field label="Patient / service instructions"><textarea {...form.register('instructions')} maxLength={4000} /></Field>
      </>}
    </fieldset><p className="intervention-help">Follow up each result until it is reviewed or handed over.</p><ComposerActions busy={busy || (!isLab && !catalog.data)} onCancel={onCancel} allowAddAnother={!isLab} />
  </form>
}
