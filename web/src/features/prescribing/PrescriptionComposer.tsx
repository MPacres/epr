import { useEffect, useState } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { Plus, Trash2 } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { atSite, request, clinicalKey, type Scope } from '../encounters/services/encounter-api'
import { Field, ComposerActions } from '../interventions/components/ComposerFields'
const itemSchema = z.object({ itemId: z.string(), catalogCode: z.string().min(1, 'Select an ingredient.'), ingredient: z.string(), brand: z.string(), strength: z.string(), formulation: z.string(), dose: z.string(), doseUnit: z.string(), route: z.string(), frequency: z.string(), duration: z.string(), quantity: z.string(), quantityUnit: z.string(), instructions: z.string(), catalogVersion: z.string(), repeats: z.number() })
const schema = z.object({ indication: z.string().max(4000), instructions: z.string().max(4000), items: z.array(itemSchema).min(1).max(30) })
const blankItem = (): z.infer<typeof itemSchema> => ({ itemId: createUuid(), catalogCode: '', ingredient: '', brand: '', strength: '', formulation: '', dose: '', doseUnit: '', route: '', frequency: '', duration: '', quantity: '', quantityUnit: '', instructions: '', catalogVersion: '', repeats: 0 })
export function PrescriptionComposer({ scope, patient, initial, busy, onDirty, onSave, onCancel }: { scope: Scope; patient: string; initial?: Record<string, unknown>; busy: boolean; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>, safety?: Record<string, unknown>) => void; onCancel: () => void }) {
  const oldItems = initial?.items as Array<Record<string, unknown>> | undefined
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { indication: String(initial?.indication ?? ''), instructions: String(initial?.instructions ?? ''), items: oldItems?.map(i => ({ ...blankItem(), ...i, dose: String(i.dose ?? ''), quantity: String(i.quantity ?? '') })) ?? [blankItem()] } })
  const items = useFieldArray({ control: form.control, name: 'items' })
  const [review, setReview] = useState(false)
  const [allergyState, setAllergyState] = useState('UNKNOWN')
  const [allergies, setAllergies] = useState('')
  const [medications, setMedications] = useState('')
  const [source, setSource] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [reviewId] = useState(createUuid)
  const catalog = useQuery({ queryKey: [...clinicalKey(scope, patient), 'medication-catalog'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patient}/prescriptions/catalog`), z.array(z.object({ code: z.string(), version: z.string(), ingredient: z.string() })), { signal }), retry: false })
  useEffect(() => { onDirty(form.formState.isDirty || review || !!source || !!allergies || !!medications || acknowledged) }, [onDirty, form.formState.isDirty, review, source, allergies, medications, acknowledged])
  return <form data-clinical-autosave className="intervention-composer" onSubmit={form.handleSubmit(values => onSave({ ...values, reviewId: review ? reviewId : initial?.reviewId ?? undefined, items: values.items.map(i => ({ ...i, dose: i.dose ? Number(i.dose) : undefined, quantity: i.quantity ? Number(i.quantity) : undefined })) }, review ? { reviewId, allergyState, allergies: allergies.split('\n').map(s => s.trim()).filter(Boolean), currentMedications: medications.split('\n').map(s => s.trim()).filter(Boolean), source, interactionsUnavailableAcknowledged: acknowledged } : undefined))}>
    <p className="clinical-alert">Synthetic medication data only. Interaction checking and clinical prescribing approval are unavailable.</p>
    {catalog.error ? <p role="alert">Medication sources are unavailable. Draft inputs remain here.</p> : null}<fieldset disabled={busy || !catalog.data}>
      <Field label="Indication"><textarea maxLength={4000} {...form.register('indication')} /></Field>
      {items.fields.map((item, index) => <section className="prescription-item" key={item.id}><div className="intervention-section-heading"><h3>Medicine {index + 1}</h3>{items.fields.length > 1 ? <Button type="button" variant="ghost" onClick={() => items.remove(index)}><Trash2 aria-hidden="true" />Remove draft item</Button> : null}</div>
        <Field label={`Generic ingredient · medicine ${index + 1}`} error={form.formState.errors.items?.[index]?.catalogCode?.message}><select required {...form.register(`items.${index}.catalogCode`, { onChange: e => { const entry = catalog.data?.find(x => x.code === e.target.value); if (entry) { form.setValue(`items.${index}.ingredient`, entry.ingredient, { shouldDirty: true }); form.setValue(`items.${index}.catalogVersion`, entry.version, { shouldDirty: true }) } } })}><option value="">Select a synthetic ingredient</option>{catalog.data?.map(x => <option key={x.code} value={x.code}>{x.ingredient}</option>)}</select></Field>
        <div className="intervention-field-grid">
          {(['brand', 'strength', 'formulation', 'dose', 'doseUnit', 'route', 'frequency', 'duration', 'quantity', 'quantityUnit'] as const).map(field => <Field key={field} label={`${({ brand: 'Brand (optional)', strength: 'Product strength', formulation: 'Formulation', dose: 'Dose', doseUnit: 'Dose unit', route: 'Route', frequency: 'Frequency / timing', duration: 'Duration', quantity: 'Quantity', quantityUnit: 'Quantity unit' })[field]} · medicine ${index + 1}`}><input type={field === 'dose' || field === 'quantity' ? 'number' : 'text'} min={field === 'dose' || field === 'quantity' ? '0.000001' : undefined} step="any" {...form.register(`items.${index}.${field}`)} /></Field>)}
        </div><Field label={`Patient instructions · medicine ${index + 1}`}><textarea maxLength={2000} {...form.register(`items.${index}.instructions`)} /></Field>
      </section>)}<Button variant="outline" type="button" disabled={items.fields.length >= 30} onClick={() => items.append(blankItem())}><Plus aria-hidden="true" />Add medicine</Button>
      <section className="medication-safety-review"><h3>Allergy and medication review</h3><p>Existing review: {initial?.reviewId ? 'A saved review is linked. The server will recheck its freshness and contents.' : 'Not recorded. Prescription issuance is blocked until reviewed.'}</p><label className="clinical-checkbox"><input type="checkbox" checked={review} onChange={e => setReview(e.target.checked)} />Record a new explicit synthetic source review with this draft</label>
        {review ? <><Field label="Allergy status"><select value={allergyState} onChange={e => setAllergyState(e.target.value)}><option value="UNKNOWN">Unknown / not reviewed</option><option value="NONE_KNOWN">Reviewed · none known in synthetic source</option><option value="KNOWN">Known allergies in synthetic source</option></select></Field><Field label="Allergy ingredients · one per line"><textarea value={allergies} onChange={e => setAllergies(e.target.value)} /></Field><Field label="Current medication ingredients · one per line"><textarea value={medications} onChange={e => setMedications(e.target.value)} /></Field><Field label="Reviewed source / evidence"><textarea required maxLength={2000} value={source} onChange={e => setSource(e.target.value)} /></Field><label className="clinical-checkbox"><input type="checkbox" checked={acknowledged} onChange={e => setAcknowledged(e.target.checked)} />I reviewed this synthetic source and acknowledge that interaction support is unavailable.</label></> : null}
      </section>
    </fieldset><ComposerActions busy={busy || !catalog.data} onCancel={onCancel} /></form>
}
