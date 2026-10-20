import { useEffect, useState } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { Plus, X } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { clinicalListApi, clinicalListKey, type ListSummary } from '../clinical-chart/clinical-list-api'
import { atSite, request, clinicalKey, type Scope } from '../encounters/services/encounter-api'
import { Field, ComposerActions } from '../interventions/components/ComposerFields'
import { latestEligibleSafetyReview, safetyReviewSchema } from './prescription-safety'

const itemSchema = z.object({ itemId: z.string(), catalogCode: z.string().min(1, 'Select a medicine.'), ingredient: z.string(), brand: z.string(), strength: z.string(), formulation: z.string(), dose: z.string(), doseUnit: z.string(), route: z.string(), frequency: z.string(), duration: z.string(), quantity: z.string(), quantityUnit: z.string(), instructions: z.string(), catalogVersion: z.string(), repeats: z.number() })
const schema = z.object({ indication: z.string().max(4000), instructions: z.string().max(4000), items: z.array(itemSchema).min(1).max(30) })
type Values = z.infer<typeof schema>
const blankItem = (): Values['items'][number] => ({ itemId: createUuid(), catalogCode: '', ingredient: '', brand: '', strength: '', formulation: '', dose: '', doseUnit: '', route: '', frequency: '', duration: '', quantity: '', quantityUnit: '', instructions: '', catalogVersion: '', repeats: 0 })
function safetySummary(item: ListSummary) {
  if (!item.actions.read || item.state === 'UNAVAILABLE') return 'Unavailable'
  if (item.state === 'NOT_REVIEWED') return 'Not reviewed'
  if (item.state === 'EMPTY_REVIEWED') return 'None known · reviewed'
  const preview = item.preview.slice(0, 2).map(record => record.display).join(', ')
  const remaining = item.activeCount > 2 ? ` +${item.activeCount - 2} more` : ''
  return `${item.state === 'STALE' ? 'Review due · ' : ''}${preview || 'No active entries'}${remaining}`
}

export function PrescriptionComposer({ scope, patient, initial, busy, onDirty, onSave, onCancel }: { scope: Scope; patient: string; initial?: Record<string, unknown>; busy: boolean; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>) => void; onCancel: () => void }) {
  const [openedAt] = useState(() => Date.now())
  const oldItems = initial?.items as Array<Record<string, unknown>> | undefined
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { indication: String(initial?.indication ?? ''), instructions: String(initial?.instructions ?? ''), items: oldItems?.map(item => ({ ...blankItem(), ...item, dose: String(item.dose ?? ''), quantity: String(item.quantity ?? '') })) ?? [blankItem()] } })
  const items = useFieldArray({ control: form.control, name: 'items' })
  const catalog = useQuery({ queryKey: [...clinicalKey(scope, patient), 'medication-catalog'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patient}/prescriptions/catalog`), z.array(z.object({ code: z.string(), version: z.string(), ingredient: z.string() })), { signal }), retry: false })
  const clinicalSummary = useQuery({ queryKey: [...clinicalListKey(scope, patient), 'summary'], queryFn: ({ signal }) => clinicalListApi.summary(scope, patient, signal), retry: false })
  const reviews = useQuery({ queryKey: [...clinicalListKey(scope, patient), 'reviews'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patient}/clinical-list-reviews`), z.array(safetyReviewSchema), { signal }), retry: false, refetchOnMount: 'always' })
  const currentReview = latestEligibleSafetyReview(reviews.data ?? [], scope.siteId, openedAt)
  useEffect(() => { onDirty(form.formState.isDirty) }, [onDirty, form.formState.isDirty])

  function save(values: Values) {
    onSave({ ...values, reviewId: currentReview?.reviewId ?? initial?.reviewId ?? undefined, items: values.items.map(item => ({ ...item, dose: item.dose ? Number(item.dose) : undefined, quantity: item.quantity ? Number(item.quantity) : undefined })) })
  }

  return <form data-clinical-autosave className="intervention-composer prescription-composer" onSubmit={form.handleSubmit(save)}>
    <div className="medication-safety-review" role="note"><strong>Medication safety</strong>{clinicalSummary.isPending ? <p role="status">Loading allergies and current medicines…</p> : clinicalSummary.error ? <p role="alert">Allergy and medication status unavailable.</p> : clinicalSummary.data ? <><p><b>Allergies:</b> {safetySummary(clinicalSummary.data.allergies)}</p><p><b>Current medicines:</b> {safetySummary(clinicalSummary.data.medications)}</p></> : null}<p>{currentReview ? 'Recent review linked. The server checks it again before issuing.' : 'Review both lists before issuing.'} Interactions are not checked.</p></div>
    {catalog.isPending ? <p role="status">Loading medicines…</p> : catalog.error ? <p role="alert">Medicines could not be loaded. Your draft stays here.</p> : null}
    <fieldset disabled={busy || !catalog.data}>
      <Field label="Reason"><input {...form.register('indication')} maxLength={4000} /></Field>
      {items.fields.map((item, index) => <section className="prescription-item" key={item.id} aria-labelledby={`medicine-${item.id}`}>
        <div className="prescription-item-heading"><h3 id={`medicine-${item.id}`}>Medicine {index + 1}</h3>{items.fields.length > 1 ? <Button type="button" variant="ghost" aria-label={`Remove medicine ${index + 1}`} onClick={() => items.remove(index)}><X aria-hidden="true" />Remove</Button> : null}</div>
        <Field label="Medicine" error={form.formState.errors.items?.[index]?.catalogCode?.message}><select required {...form.register(`items.${index}.catalogCode`, { onChange: event => { const entry = catalog.data?.find(value => value.code === event.target.value); form.setValue(`items.${index}.ingredient`, entry?.ingredient ?? '', { shouldDirty: true }); form.setValue(`items.${index}.catalogVersion`, entry?.version ?? '', { shouldDirty: true }) } })}><option value="">Select a medicine</option>{catalog.data?.map(entry => <option key={entry.code} value={entry.code}>{entry.ingredient}</option>)}</select></Field>
        <div className="intervention-field-grid"><Field label="Strength"><input {...form.register(`items.${index}.strength`)} /></Field><Field label="Form"><input {...form.register(`items.${index}.formulation`)} placeholder="Tablet, syrup…" /></Field></div>
        <div className="prescription-pair"><Field label="Dose"><input type="number" min="0.000001" step="any" {...form.register(`items.${index}.dose`)} /></Field><Field label="Unit"><input {...form.register(`items.${index}.doseUnit`)} placeholder="mg, mL…" /></Field></div>
        <div className="intervention-field-grid"><Field label="How to take"><input {...form.register(`items.${index}.route`)} placeholder="By mouth…" /></Field><Field label="How often"><input {...form.register(`items.${index}.frequency`)} placeholder="Once daily…" /></Field></div>
        <div className="intervention-field-grid"><Field label="For how long"><input {...form.register(`items.${index}.duration`)} placeholder="7 days…" /></Field><div className="prescription-pair"><Field label="Amount to give"><input type="number" min="0.000001" step="any" {...form.register(`items.${index}.quantity`)} /></Field><Field label="Unit"><input {...form.register(`items.${index}.quantityUnit`)} placeholder="tablets, mL…" /></Field></div></div>
        <Field label="Patient directions"><textarea {...form.register(`items.${index}.instructions`)} maxLength={2000} rows={2} /></Field>
        <details className="prescription-more" open={Boolean(item.brand)}><summary>Brand, if needed</summary><Field label="Brand"><input {...form.register(`items.${index}.brand`)} /></Field></details>
      </section>)}
      <Button variant="outline" type="button" disabled={items.fields.length >= 30} onClick={() => items.append(blankItem())}><Plus aria-hidden="true" />Add medicine</Button>
      <details className="prescription-more" open={Boolean(initial?.instructions)}><summary>Prescription note</summary><Field label="Additional note"><textarea {...form.register('instructions')} maxLength={4000} rows={2} /></Field></details>
    </fieldset><ComposerActions busy={busy || !catalog.data} onCancel={onCancel} allowAddAnother={false} />
  </form>
}
