import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { atSite, request, clinicalKey, type Scope } from '../encounters/services/encounter-api'
import { Field, ComposerActions } from '../interventions/components/ComposerFields'
const schema = z.object({ catalogCode: z.string().min(1, 'Select a service.'), indication: z.string().max(4000), destination: z.string().max(4000), priority: z.string(), requestedDate: z.string(), specimen: z.string(), bodySite: z.string(), laterality: z.string(), instructions: z.string().max(4000) })
type Values = z.infer<typeof schema>
export function OrderComposer({ scope, patient, kind, initial, busy, onDirty, onSave, onCancel }: { scope: Scope; patient: string; kind: 'LAB_ORDER' | 'IMAGING_ORDER'; initial?: Record<string, unknown>; busy: boolean; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>) => void; onCancel: () => void }) {
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { catalogCode: '', indication: '', destination: '', priority: 'ROUTINE', requestedDate: '', specimen: '', bodySite: '', laterality: '', instructions: '', ...initial } })
  const [additional, setAdditional] = useState<string[]>([])
  const primary = useWatch({ control: form.control, name: 'catalogCode' })
  const catalog = useQuery({ queryKey: [...clinicalKey(scope, patient), 'service-catalog'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patient}/service-requests/catalog`), z.array(z.object({ code: z.string(), version: z.string(), kind: z.string(), label: z.string() })), { signal }), retry: false })
  useEffect(() => { onDirty(form.formState.isDirty || additional.length > 0) }, [onDirty, form.formState.isDirty, additional.length])
  return <form data-clinical-autosave className="intervention-composer" onSubmit={form.handleSubmit(values => { const entry = catalog.data?.find(x => x.code === values.catalogCode && x.kind === kind); if (entry) onSave({ ...values, kind, serviceName: entry.label, catalogVersion: entry.version, requestedDate: values.requestedDate || undefined, groupId: initial?.groupId, additionalServices: catalog.data?.filter(x => x.kind === kind && additional.includes(x.code) && x.code !== values.catalogCode) }) })}>
    <p>Choose a synthetic service, then record this patient’s request. Authorization and result review are separate.</p>
    {catalog.isPending ? <p role="status">Loading permitted services…</p> : catalog.error ? <p role="alert">The service catalog is unavailable. Your inputs remain here.</p> : null}
    <fieldset disabled={busy || !catalog.data}><Field label="Service" error={form.formState.errors.catalogCode?.message}><select {...form.register('catalogCode')} required><option value="">Select a synthetic service</option>{catalog.data?.filter(x => x.kind === kind).map(x => <option key={x.code} value={x.code}>{x.label}</option>)}</select></Field>
      {!initial ? <details><summary>Add services to the same draft group</summary><p>Each selected service retains its own authorization, result and review.</p>{catalog.data?.filter(x => x.kind === kind && x.code !== primary).map(x => <label key={x.code} className="clinical-checkbox"><input type="checkbox" checked={additional.includes(x.code)} onChange={e => setAdditional(values => e.target.checked ? [...values, x.code] : values.filter(code => code !== x.code))} />{x.label}</label>)}</details> : null}
      <Field label="Clinical question / indication"><textarea {...form.register('indication')} maxLength={4000} /></Field>
      <div className="intervention-field-grid"><Field label="Destination"><input {...form.register('destination')} /></Field><Field label="Requested date"><input type="date" {...form.register('requestedDate')} /></Field><Field label="Priority"><select {...form.register('priority')}><option value="ROUTINE">Routine</option><option value="URGENT">Urgent · clinician selected</option></select></Field>
      {kind === 'LAB_ORDER' ? <Field label="Specimen, if applicable"><input {...form.register('specimen')} /></Field> : <><Field label="Body site"><input {...form.register('bodySite')} /></Field><Field label="Laterality, if applicable"><input {...form.register('laterality')} /></Field></>}</div>
      <Field label="Patient / service instructions"><textarea {...form.register('instructions')} maxLength={4000} /></Field>
    </fieldset><p className="intervention-help">The responsible clinician retains ownership of the result until an accepted handoff or recorded disposition.</p><ComposerActions busy={busy || !catalog.data} onCancel={onCancel} />
  </form>
}
