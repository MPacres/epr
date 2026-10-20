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
import type { SourceRecord } from '../interventions/services/intervention-api'
import { latestEligibleSafetyReview, safetyReviewSchema } from './prescription-safety'
import { matchMedicine, medicationCatalogSchema } from './medication-entry'

const itemSchema = z.object({
  itemId: z.string(), mode: z.enum(['NEW', 'REFILL']), sourceChoice: z.string(),
  sourcePrescriptionId: z.string().nullable(), sourceItemId: z.string().nullable(),
  catalogCode: z.string(), catalogVersion: z.string(), ingredient: z.string().trim().min(1, 'Enter a medicine.').max(200),
  brand: z.string(), strength: z.string(), formulation: z.string(), dose: z.string(), doseUnit: z.string(),
  route: z.string(), frequency: z.string(), duration: z.string(), quantity: z.string(), quantityUnit: z.string(),
  instructions: z.string(), repeats: z.number(),
}).superRefine((item, context) => {
  if (item.mode === 'REFILL' && (!item.sourcePrescriptionId || !item.sourceItemId))
    context.addIssue({ code: 'custom', path: ['sourceChoice'], message: 'Select a previous issued medicine.' })
})
const schema = z.object({ indication: z.string().max(4000), instructions: z.string().max(4000), items: z.array(itemSchema).min(1).max(30) })
type Values = z.infer<typeof schema>
type Medicine = Values['items'][number]
const blankItem = (): Medicine => ({ itemId: createUuid(), mode: 'NEW', sourceChoice: '', sourcePrescriptionId: null, sourceItemId: null, catalogCode: '', catalogVersion: '', ingredient: '', brand: '', strength: '', formulation: '', dose: '', doseUnit: '', route: '', frequency: '', duration: '', quantity: '', quantityUnit: '', instructions: '', repeats: 0 })
const fromRecord = (record: Record<string, unknown>): Medicine => ({ ...blankItem(), ...record, mode: record.sourcePrescriptionId ? 'REFILL' : 'NEW', sourceChoice: record.sourcePrescriptionId && record.sourceItemId ? `${record.sourcePrescriptionId}/${record.sourceItemId}` : '', catalogCode: String(record.catalogCode ?? ''), catalogVersion: String(record.catalogVersion ?? ''), dose: String(record.dose ?? ''), quantity: String(record.quantity ?? '') })
type RefillSource = { prescriptionId: string; itemId: string; label: string; item: Record<string, unknown> }

function safetySummary(item: ListSummary) {
  if (!item.actions.read || item.state === 'UNAVAILABLE') return 'Unavailable'
  if (item.state === 'NOT_REVIEWED') return 'Not reviewed'
  if (item.state === 'EMPTY_REVIEWED') return 'None known · reviewed'
  const preview = item.preview.slice(0, 2).map(record => record.display).join(', ')
  const remaining = item.activeCount > 2 ? ` +${item.activeCount - 2} more` : ''
  return `${item.state === 'STALE' ? 'Review due · ' : ''}${preview || 'No active entries'}${remaining}`
}

export function PrescriptionComposer({ scope, patient, initial, previous, previousLoading, previousError, busy, onDirty, onSave, onCancel }: { scope: Scope; patient: string; initial?: Record<string, unknown>; previous: SourceRecord[]; previousLoading: boolean; previousError: boolean; busy: boolean; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>) => void; onCancel: () => void }) {
  const [openedAt] = useState(() => Date.now())
  const oldItems = initial?.items as Array<Record<string, unknown>> | undefined
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { indication: String(initial?.indication ?? ''), instructions: String(initial?.instructions ?? ''), items: oldItems?.map(fromRecord) ?? [blankItem()] } })
  const items = useFieldArray({ control: form.control, name: 'items' })
  const itemValues = form.watch('items')
  const catalog = useQuery({ queryKey: [...clinicalKey(scope, patient), 'medication-catalog'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patient}/prescriptions/catalog`), z.array(medicationCatalogSchema), { signal }), retry: false })
  const clinicalSummary = useQuery({ queryKey: [...clinicalListKey(scope, patient), 'summary'], queryFn: ({ signal }) => clinicalListApi.summary(scope, patient, signal), retry: false })
  const reviews = useQuery({ queryKey: [...clinicalListKey(scope, patient), 'reviews'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patient}/clinical-list-reviews`), z.array(safetyReviewSchema), { signal }), retry: false, refetchOnMount: 'always' })
  const currentReview = latestEligibleSafetyReview(reviews.data ?? [], scope.siteId, openedAt)
  const refillSources: RefillSource[] = previous.filter(source => source.state === 'ISSUED').flatMap(source => (Array.isArray(source.data.items) ? source.data.items as Array<Record<string, unknown>> : []).map(item => ({ prescriptionId: source.id, itemId: String(item.itemId), label: `${String(item.ingredient)} · ${String(item.strength ?? '')} · ${source.occurredAt.slice(0, 10)}`, item })))
  useEffect(() => { onDirty(form.formState.isDirty) }, [onDirty, form.formState.isDirty])
  useEffect(() => {
    if (!catalog.data) return
    form.getValues('items').forEach((item, index) => {
      if (item.mode !== 'NEW' || item.catalogCode || !item.ingredient) return
      const matched = matchMedicine(item.ingredient, catalog.data)
      if (!matched) return
      form.setValue(`items.${index}.catalogCode`, matched.code, { shouldDirty: true })
      form.setValue(`items.${index}.catalogVersion`, matched.version, { shouldDirty: true })
      for (const field of ['strength', 'formulation', 'doseUnit', 'quantityUnit'] as const)
        if (!item[field]) form.setValue(`items.${index}.${field}`, matched[field] ?? '', { shouldDirty: true })
    })
  }, [catalog.data, form])

  function medicineChanged(index: number, value: string) {
    const matched = matchMedicine(value, catalog.data ?? [])
    const oldCode = form.getValues(`items.${index}.catalogCode`)
    form.setValue(`items.${index}.catalogCode`, matched?.code ?? '', { shouldDirty: true })
    form.setValue(`items.${index}.catalogVersion`, matched?.version ?? '', { shouldDirty: true })
    if (matched) {
      form.setValue(`items.${index}.strength`, matched.strength ?? '', { shouldDirty: true })
      form.setValue(`items.${index}.formulation`, matched.formulation ?? '', { shouldDirty: true })
      form.setValue(`items.${index}.doseUnit`, matched.doseUnit ?? '', { shouldDirty: true })
      form.setValue(`items.${index}.quantityUnit`, matched.quantityUnit ?? '', { shouldDirty: true })
    } else if (oldCode) {
      for (const field of ['strength', 'formulation', 'doseUnit', 'quantityUnit'] as const)
        form.setValue(`items.${index}.${field}`, '', { shouldDirty: true })
    }
  }

  function selectRefill(index: number, value: string) {
    const source = refillSources.find(entry => `${entry.prescriptionId}/${entry.itemId}` === value)
    const itemId = form.getValues(`items.${index}.itemId`)
    if (!source) { items.update(index, { ...blankItem(), itemId, mode: 'REFILL' }); return }
    items.update(index, { ...fromRecord(source.item), itemId, mode: 'REFILL', sourceChoice: value, sourcePrescriptionId: source.prescriptionId, sourceItemId: source.itemId })
  }

  function save(values: Values) {
    onSave({ indication: values.indication, instructions: values.instructions, reviewId: currentReview?.reviewId ?? initial?.reviewId ?? undefined,
      items: values.items.map(({ mode: _mode, sourceChoice: _choice, ...item }) => {
        void _mode; void _choice
        return { ...item, catalogCode: item.catalogCode || null, catalogVersion: item.catalogVersion || null, dose: item.dose ? Number(item.dose) : undefined, quantity: item.quantity ? Number(item.quantity) : undefined }
      }) })
  }

  return <form data-clinical-autosave className="intervention-composer prescription-composer" onSubmit={form.handleSubmit(save)}>
    <div className="medication-safety-review" role="note"><strong>Medication safety</strong>{clinicalSummary.isPending ? <p role="status">Loading allergies and current medicines…</p> : clinicalSummary.error ? <p role="alert">Allergy and medication status unavailable.</p> : clinicalSummary.data ? <><p><b>Allergies:</b> {safetySummary(clinicalSummary.data.allergies)}</p><p><b>Current medicines:</b> {safetySummary(clinicalSummary.data.medications)}</p></> : null}<p>{currentReview ? 'Recent review linked. The server checks it again before issuing.' : 'Review both lists before issuing.'} Interactions are not checked.</p></div>
    {catalog.isPending ? <p role="status">Loading medicine suggestions…</p> : catalog.error ? <p role="alert">Medicine suggestions are unavailable. You can still enter a name manually.</p> : null}
    <fieldset disabled={busy}>
      <Field label="Reason"><input {...form.register('indication')} maxLength={4000} /></Field>
      {items.fields.map((item, index) => <section className="prescription-item" key={item.id} aria-labelledby={`medicine-${item.id}`}>
        <div className="prescription-item-heading"><h3 id={`medicine-${item.id}`}>Medicine {index + 1}</h3>{items.fields.length > 1 ? <Button type="button" variant="ghost" aria-label={`Remove medicine ${index + 1}`} onClick={() => items.remove(index)}><X aria-hidden="true" />Remove</Button> : null}</div>
        <Field label="Entry type"><select {...form.register(`items.${index}.mode`, { onChange: event => items.update(index, { ...blankItem(), itemId: form.getValues(`items.${index}.itemId`), mode: event.target.value as Medicine['mode'] }) })}><option value="NEW">Add new medication</option><option value="REFILL">Medication refill</option></select></Field>
        {itemValues[index]?.mode === 'REFILL' ? <><Field label="Previous issued medicine" error={form.formState.errors.items?.[index]?.sourceChoice?.message}><select value={itemValues[index]?.sourceChoice ?? ''} onChange={event => selectRefill(index, event.target.value)}><option value="">Select a previous medicine</option>{refillSources.map(source => <option key={`${source.prescriptionId}/${source.itemId}`} value={`${source.prescriptionId}/${source.itemId}`}>{source.label}</option>)}{itemValues[index]?.sourceChoice && !refillSources.some(source => `${source.prescriptionId}/${source.itemId}` === itemValues[index]?.sourceChoice) ? <option value={itemValues[index].sourceChoice}>Previously linked medicine · currently unavailable</option> : null}</select></Field>{previousLoading ? <p className="prescription-guidance" role="status">Loading previous medicines…</p> : previousError ? <p className="prescription-guidance" role="alert">Previous medicines are unavailable. Try again before saving a refill.</p> : refillSources.length === 0 ? <p className="prescription-guidance">No issued medicines are available in the recent record.</p> : null}<p className="prescription-guidance">A refill creates a new authorization linked to the original item. Check the copied directions and quantity.</p></> : null}
        <Field label="Medicine" error={form.formState.errors.items?.[index]?.ingredient?.message}><input list={`medicine-options-${item.id}`} placeholder="Type a medicine or choose a suggestion" readOnly={itemValues[index]?.mode === 'REFILL'} maxLength={200} {...form.register(`items.${index}.ingredient`, { onChange: event => medicineChanged(index, event.target.value) })} /></Field><datalist id={`medicine-options-${item.id}`}>{catalog.data?.map(entry => <option key={`${entry.code}/${entry.version}`} value={entry.ingredient} />)}</datalist>
        {itemValues[index]?.mode === 'NEW' ? <p className="prescription-guidance">Catalog matches fill strength, form and units. Check all fields before saving.</p> : null}
        <div className="intervention-field-grid"><Field label="Strength"><input {...form.register(`items.${index}.strength`)} /></Field><Field label="Form"><input {...form.register(`items.${index}.formulation`)} placeholder="Tablet, syrup…" /></Field></div>
        <div className="prescription-pair"><Field label="Dose"><input type="number" min="0.000001" step="any" {...form.register(`items.${index}.dose`)} /></Field><Field label="Unit"><input {...form.register(`items.${index}.doseUnit`)} placeholder="mg, mL…" /></Field></div>
        <div className="intervention-field-grid"><Field label="How to take"><input {...form.register(`items.${index}.route`)} placeholder="By mouth…" /></Field><Field label="How often"><input {...form.register(`items.${index}.frequency`)} placeholder="Once daily…" /></Field></div>
        <div className="intervention-field-grid"><Field label="For how long"><input {...form.register(`items.${index}.duration`)} placeholder="7 days…" /></Field><div className="prescription-pair"><Field label="Amount to give"><input type="number" min="0.000001" step="any" {...form.register(`items.${index}.quantity`)} /></Field><Field label="Unit"><input {...form.register(`items.${index}.quantityUnit`)} placeholder="tablets, mL…" /></Field></div></div>
        <Field label="Patient directions"><textarea {...form.register(`items.${index}.instructions`)} maxLength={2000} rows={2} /></Field>
        <details className="prescription-more" open={Boolean(item.brand)}><summary>Brand, if needed</summary><Field label="Brand"><input {...form.register(`items.${index}.brand`)} /></Field></details>
      </section>)}
      <Button variant="outline" type="button" disabled={items.fields.length >= 30} onClick={() => items.append(blankItem())}><Plus aria-hidden="true" />Add another medicine</Button>
      <details className="prescription-more" open={Boolean(initial?.instructions)}><summary>Prescription note</summary><Field label="Additional note"><textarea {...form.register('instructions')} maxLength={4000} rows={2} /></Field></details>
    </fieldset><ComposerActions busy={busy} onCancel={onCancel} allowAddAnother={false} />
  </form>
}
