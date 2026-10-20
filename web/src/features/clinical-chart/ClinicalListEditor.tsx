import { useEffect, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useFieldArray, useForm, type FieldValues, type UseFormRegister, type UseFormSetValue } from 'react-hook-form'
import { CircleAlert, Info, Plus, Save, Trash2 } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { Sheet } from '../../components/ui/sheet'
import { createUuid } from '../../lib/create-uuid'
import { ClinicalRequestError, type Patient, type Scope } from '../encounters/services/encounter-api'
import type * as Wire from '../encounters/services/api.generated'
import { clinicalListApi, type ClinicalListKind, type ClinicalRecord } from './clinical-list-api'
import { allergyDraftSchema, commandFor, draftFor, medicationDraftSchema, problemDraftSchema, type AllergyDraft, type ClinicalDraft, type MedicationDraft, type ProblemDraft } from './clinical-list-model'

type TerminologyCode = { system: string; code: string; version: string | null; display: string | null }
type EditorProps = { scope: Scope; patient: Patient; kind: ClinicalListKind; record?: ClinicalRecord; terminology: TerminologyCode[]; onClose: () => void; onSaved: () => void; onRestoreFocus?: () => void }
type Command = Wire.CreateAllergy | Wire.CreateMedicationUse | Wire.CreateProblem | Wire.ReviseAllergy | Wire.ReviseMedicationUse | Wire.ReviseProblem

const labels = { ALLERGY: 'allergy or adverse reaction', MEDICATION_USE: 'reported medication', PROBLEM: 'ongoing problem' } as const
const precisionOptions = <><option value="UNKNOWN">Unknown</option><option value="EXACT">Exact date</option><option value="MONTH">Month known</option><option value="YEAR">Year known</option><option value="APPROXIMATE">Approximate</option></>
const required = <span aria-hidden="true"> *</span>

function ErrorText({ message }: { message?: string }) { return message ? <small className="clinical-field-error">{message}</small> : null }
function Field({ label, requiredField = false, optional = false, hint, error, children }: { label: string; requiredField?: boolean; optional?: boolean; hint?: string; error?: string; children: React.ReactNode }) {
  return <label className={`clinical-list-field${error ? ' invalid' : ''}`}><span><span>{label}{requiredField ? required : null}</span>{optional ? <small>Optional</small> : null}</span>{children}{hint ? <small className="clinical-field-hint">{hint}</small> : null}<ErrorText message={error} /></label>
}

function TerminologyPicker<T extends FieldValues & { codeSystem: string; codeValue: string; codeVersion: string }>({ terminology, displayName, register, setValue }: { terminology: TerminologyCode[]; displayName: keyof T; register: UseFormRegister<T>; setValue: UseFormSetValue<T> }) {
  return <Field label="Terminology match" optional hint="Synthetic local catalog only. Free text is saved but does not participate in coded safety matching."><select {...register('codeValue' as never)} onChange={event => {
    const selected = terminology.find(item => item.code === event.target.value)
    setValue('codeValue' as never, (selected?.code ?? '') as never, { shouldDirty: true })
    setValue('codeSystem' as never, (selected?.system ?? '') as never, { shouldDirty: true })
    setValue('codeVersion' as never, (selected?.version ?? '') as never, { shouldDirty: true })
    if (selected?.display) setValue(displayName as never, selected.display as never, { shouldDirty: true })
  }}><option value="">No coded match — save as free text</option>{terminology.map(item => <option key={`${item.system}/${item.code}`} value={item.code}>{item.display} · {item.code}</option>)}</select></Field>
}

function AllergyForm({ initial, terminology, busy, pending, editing, onDirty, onSubmit, onClose }: FormProps<AllergyDraft>) {
  const form = useForm<AllergyDraft>({ defaultValues: initial, resolver: zodResolver(allergyDraftSchema) })
  const reactions = useFieldArray({ control: form.control, name: 'reactions', keyName: 'formKey' })
  useEffect(() => onDirty(form.formState.isDirty), [form.formState.isDirty, onDirty])
  return <form className="clinical-list-form" noValidate onSubmit={form.handleSubmit(onSubmit)}>
    <fieldset disabled={busy || pending}>
      <p className="clinical-required-note"><span aria-hidden="true">*</span> Required for this record. Optional details can be added when they are known.</p>
      <section className="clinical-form-section" aria-labelledby="allergy-assessment-heading">
        <div className="clinical-form-section-heading"><div><h3 id="allergy-assessment-heading">Substance and assessment</h3><p>Identify the suspected agent, then record what is currently known without inferring certainty.</p></div></div>
        <div className="clinical-form-grid">
          <Field label="Substance or agent" requiredField hint="Enter one suspected substance or class per record." error={form.formState.errors.substanceDisplay?.message}><input {...form.register('substanceDisplay')} maxLength={300} autoFocus /></Field>
          <TerminologyPicker terminology={terminology} displayName="substanceDisplay" register={form.register} setValue={form.setValue} />
          <Field label="Category" requiredField><select {...form.register('category')}><option value="MEDICATION">Medication</option><option value="FOOD">Food</option><option value="ENVIRONMENT">Environment</option><option value="BIOLOGIC">Biologic</option><option value="OTHER">Other / not classified</option></select></Field>
          <Field label="Classification" requiredField hint="Use not yet determined when allergy versus intolerance is unclear."><select {...form.register('reactionType')}><option value="UNCERTAIN">Not yet determined</option><option value="ALLERGY">Allergy</option><option value="INTOLERANCE">Intolerance</option></select></Field>
          <Field label="Verification status" requiredField><select {...form.register('verificationStatus')}><option value="UNCONFIRMED">Unconfirmed</option><option value="CONFIRMED">Confirmed</option>{editing && initial.verificationStatus === 'REFUTED' ? <option value="REFUTED">Refuted</option> : null}{editing && initial.verificationStatus === 'ENTERED_IN_ERROR' ? <option value="ENTERED_IN_ERROR">Entered in error</option> : null}</select></Field>
          <Field label="Potential criticality" requiredField hint="Future harm potential; this is separate from the severity of a past reaction."><select {...form.register('criticality')}><option value="UNABLE_TO_ASSESS">Unable to assess</option><option value="LOW">Low</option><option value="HIGH">High</option></select></Field>
        </div>
      </section>

      <section className="clinical-form-section" aria-labelledby="allergy-reactions-heading">
        <div className="clinical-form-section-heading clinical-form-section-heading-action"><div><h3 id="allergy-reactions-heading">Previous reactions <span>Optional</span></h3><p>Add each known reaction separately. A reaction description is required only after a reaction is added.</p></div><Button type="button" variant="outline" size="sm" onClick={() => reactions.append({ reactionId: '', manifestation: '', severity: 'UNKNOWN', onset: '', exposureRoute: '' })}><Plus aria-hidden="true" />Add reaction</Button></div>
        {reactions.fields.length === 0 ? <div className="clinical-reactions-empty"><Info aria-hidden="true" /><p>No previous reaction details added. The allergy or intolerance record can still be saved.</p></div> : <div className="clinical-reaction-list">{reactions.fields.map((reaction, index) => <div className="clinical-reaction" key={reaction.formKey}>
          <div className="clinical-reaction-heading"><h4>Reaction {index + 1}</h4><button type="button" className="clinical-remove-reaction" aria-label={`Remove reaction ${index + 1}`} onClick={() => reactions.remove(index)}><Trash2 aria-hidden="true" />Remove</button></div>
          <div className="clinical-form-grid">
            <Field label="Reaction or symptom" requiredField error={form.formState.errors.reactions?.[index]?.manifestation?.message}><input {...form.register(`reactions.${index}.manifestation`)} maxLength={300} placeholder="e.g. hives, wheeze, vomiting" /></Field>
            <Field label="Reaction severity" requiredField hint="Severity of this past reaction, not future risk."><select {...form.register(`reactions.${index}.severity`)}><option value="UNKNOWN">Unknown</option><option value="MILD">Mild</option><option value="MODERATE">Moderate</option><option value="SEVERE">Severe</option></select></Field>
            <Field label="Reaction onset" optional hint="When this reaction occurred, if known."><input {...form.register(`reactions.${index}.onset`)} maxLength={120} placeholder="YYYY-MM-DD or descriptive timing" /></Field>
            <Field label="Exposure route" optional><input {...form.register(`reactions.${index}.exposureRoute`)} maxLength={120} placeholder="e.g. oral, injection, skin contact" /></Field>
          </div>
        </div>)}</div>}
      </section>

      <section className="clinical-form-section" aria-labelledby="allergy-timing-heading">
        <div className="clinical-form-section-heading"><div><h3 id="allergy-timing-heading">Timing <span>Optional</span></h3><p>Record when the allergy or intolerance was first identified and the last known reaction date.</p></div></div>
        <div className="clinical-form-grid">
          <Field label="First identified" optional error={form.formState.errors.onset?.message}><input {...form.register('onset')} maxLength={120} placeholder="YYYY-MM-DD, YYYY-MM, YYYY, or descriptive timing" /></Field>
          <Field label="Date precision" error={form.formState.errors.onsetPrecision?.message}><select {...form.register('onsetPrecision')}>{precisionOptions}</select></Field>
          <Field label="Last known occurrence" optional><input type="date" {...form.register('lastOccurrence')} /></Field>
        </div>
      </section>

      <section className="clinical-form-section" aria-labelledby="allergy-source-heading">
        <div className="clinical-form-section-heading"><div><h3 id="allergy-source-heading">Source and notes</h3><p>The signed-in clinician is recorded automatically. Enter who or what supplied the information separately.</p></div></div>
        <div className="clinical-form-grid">
          <Field label="Information source" requiredField hint="Where this information came from, such as a patient interview or prior chart." error={form.formState.errors.informationSource?.message}><input {...form.register('informationSource')} maxLength={200} placeholder="e.g. Patient interview" /></Field>
          <Field label="Reported by" optional hint="Person who supplied the information, if known."><input {...form.register('asserter')} maxLength={200} placeholder="e.g. Patient, parent, prior clinician" /></Field>
          <div className="clinical-form-span"><Field label="Additional notes" optional><textarea {...form.register('notes')} maxLength={2000} rows={3} /></Field></div>
        </div>
      </section>
      <CorrectionField register={form.register} error={form.formState.errors.correctionReason?.message} visible={editing} />
    </fieldset>
    <EditorFooter busy={busy} pending={pending} editing={editing} onCancel={onClose} />
  </form>
}

function MedicationForm({ initial, terminology, busy, pending, editing, onDirty, onSubmit, onClose }: FormProps<MedicationDraft>) {
  const form = useForm<MedicationDraft>({ defaultValues: initial, resolver: zodResolver(medicationDraftSchema) })
  useEffect(() => onDirty(form.formState.isDirty), [form.formState.isDirty, onDirty])
  return <form className="clinical-list-form" noValidate onSubmit={form.handleSubmit(onSubmit)}>
    <fieldset disabled={busy || pending}><div className="clinical-form-grid">
      <Field label="Medication" requiredField error={form.formState.errors.medicationDisplay?.message}><input {...form.register('medicationDisplay')} maxLength={300} /></Field>
      <TerminologyPicker terminology={terminology} displayName="medicationDisplay" register={form.register} setValue={form.setValue} />
      <Field label="Brand"><input {...form.register('brand')} maxLength={300} /></Field><Field label="Strength"><input {...form.register('strength')} maxLength={300} /></Field>
      <Field label="Formulation"><input {...form.register('formulation')} maxLength={300} /></Field><Field label="Route"><input {...form.register('route')} maxLength={200} /></Field>
      <Field label="Dose" error={form.formState.errors.dose?.message}><input inputMode="decimal" {...form.register('dose')} /></Field><Field label="Dose unit"><input {...form.register('doseUnit')} maxLength={100} /></Field>
      <Field label="Frequency"><input {...form.register('frequency')} maxLength={300} /></Field><label className="clinical-checkbox clinical-prn"><input type="checkbox" {...form.register('prn')} /><span>Taken as needed (PRN)</span></label>
      <Field label="Effective start"><input {...form.register('effectiveStart')} placeholder="YYYY-MM-DD, month, or year" /></Field><Field label="Effective end"><input {...form.register('effectiveEnd')} placeholder="Leave blank if current" /></Field>
      <Field label="Date precision"><select {...form.register('effectivePrecision')}>{precisionOptions}</select></Field>
      <Field label="Adherence"><select {...form.register('adherence')}><option value="TAKING_AS_REPORTED">Taking as reported</option><option value="TAKING_DIFFERENTLY">Taking differently</option><option value="NOT_TAKING">Not taking</option><option value="UNKNOWN">Unknown</option></select></Field>
      <Field label="Prescriber"><input {...form.register('prescriber')} maxLength={300} /></Field><Field label="Information source" requiredField error={form.formState.errors.informationSource?.message}><input {...form.register('informationSource')} maxLength={200} /></Field>
      <Field label="Notes"><textarea {...form.register('notes')} maxLength={2000} rows={3} /></Field>
    </div><CorrectionField register={form.register} error={form.formState.errors.correctionReason?.message} visible={editing} /></fieldset>
    <EditorFooter busy={busy} pending={pending} editing={editing} onCancel={onClose} />
  </form>
}

function ProblemForm({ initial, terminology, busy, pending, editing, onDirty, onSubmit, onClose }: FormProps<ProblemDraft>) {
  const form = useForm<ProblemDraft>({ defaultValues: initial, resolver: zodResolver(problemDraftSchema) })
  useEffect(() => onDirty(form.formState.isDirty), [form.formState.isDirty, onDirty])
  return <form className="clinical-list-form" noValidate onSubmit={form.handleSubmit(onSubmit)}>
    <fieldset disabled={busy || pending}><div className="clinical-form-grid">
      <Field label="Problem or diagnosis" requiredField error={form.formState.errors.problemDisplay?.message}><input {...form.register('problemDisplay')} maxLength={300} /></Field>
      <TerminologyPicker terminology={terminology} displayName="problemDisplay" register={form.register} setValue={form.setValue} />
      <Field label="Verification"><select {...form.register('verificationStatus')}><option value="SUSPECTED">Suspected</option><option value="CONFIRMED">Confirmed</option><option value="REFUTED">Refuted</option></select></Field>
      <Field label="Priority"><input type="number" min="0" {...form.register('priority', { valueAsNumber: true })} /></Field>
      <Field label="Onset"><input {...form.register('onset')} placeholder="YYYY-MM-DD, month, or year" /></Field><Field label="Onset precision"><select {...form.register('onsetPrecision')}>{precisionOptions}</select></Field>
      <Field label="Abatement"><input {...form.register('abatement')} placeholder="Leave blank if ongoing" /></Field><Field label="Abatement precision"><select {...form.register('abatementPrecision')}>{precisionOptions}</select></Field>
      <Field label="Severity"><input {...form.register('severity')} maxLength={120} /></Field><Field label="Information source" requiredField error={form.formState.errors.informationSource?.message}><input {...form.register('informationSource')} maxLength={200} /></Field>
      <Field label="Notes"><textarea {...form.register('notes')} maxLength={2000} rows={3} /></Field>
    </div><CorrectionField register={form.register} error={form.formState.errors.correctionReason?.message} visible={editing} /></fieldset>
    <EditorFooter busy={busy} pending={pending} editing={editing} onCancel={onClose} />
  </form>
}

type FormProps<T extends ClinicalDraft> = { initial: T; terminology: TerminologyCode[]; busy: boolean; pending: boolean; editing: boolean; onDirty: (dirty: boolean) => void; onSubmit: (draft: T) => void | Promise<void>; onClose: () => void }
function CorrectionField<T extends { correctionReason: string }>({ register, error, visible }: { register: UseFormRegister<T>; error?: string; visible: boolean }) {
  return visible ? <Field label="Correction reason" requiredField error={error}><textarea {...register('correctionReason' as never)} maxLength={500} rows={2} /><small className="clinical-field-hint">The original revision remains in history.</small></Field> : null
}
function EditorFooter({ busy, pending, editing, onCancel }: { busy: boolean; pending: boolean; editing: boolean; onCancel: () => void }) {
  return <footer className="clinical-list-form-actions"><Button type="button" variant="outline" disabled={busy} onClick={onCancel}>Cancel</Button><Button type="submit" disabled={busy}><Save aria-hidden="true" />{busy ? 'Saving…' : pending ? 'Retry original save' : editing ? 'Save correction' : 'Add to list'}</Button></footer>
}

export function ClinicalListEditor({ scope, patient, kind, record, terminology, onClose, onSaved, onRestoreFocus }: EditorProps) {
  const [dirty, setDirty] = useState(false)
  const [confirmClose, setConfirmClose] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [pending, setPending] = useState<{ body: Command; key: string } | null>(null)
  const initial = draftFor(kind, record)
  async function save(draft: ClinicalDraft) {
    if (busy) return
    if (record && !draft.correctionReason.trim()) { setError('Enter a correction reason. The original revision will remain in history.'); return }
    setBusy(true); setError('')
    const command = pending ?? { body: commandFor(kind, draft, createUuid(), record), key: createUuid() }
    if (!pending) setPending(command)
    try {
      if (record) await clinicalListApi.revise(scope, patient.patientId, kind, record.id, command.body, command.key)
      else await clinicalListApi.create(scope, patient.patientId, kind, command.body, command.key)
      setPending(null); setDirty(false); onSaved()
    } catch (caught) {
      if (caught instanceof ClinicalRequestError && caught.status >= 400 && caught.status < 500) setPending(null)
      setError(caught instanceof ClinicalRequestError ? `${caught.message} (${caught.code})` : 'The save outcome is unconfirmed. Retry the original save without changing this form.')
    } finally { setBusy(false) }
  }
  const close = () => dirty || pending ? setConfirmClose(true) : onClose()
  const shared = { terminology, busy, pending: Boolean(pending), editing: Boolean(record), onDirty: setDirty, onSubmit: save, onClose: close }
  return <><Sheet open onClose={close} onRestoreFocus={onRestoreFocus} title={record ? `Correct ${labels[kind]}` : `Add ${labels[kind]}`} description={`${patient.displayName} · ${patient.recordNumber} · ${scope.practiceName} · ${scope.siteName}`}>
    <div className="clinical-list-editor">
      <div className="clinical-source-note"><Info aria-hidden="true" /><p><strong>Longitudinal chart update</strong><span>This does not create an encounter. Corrections append a revision and never overwrite history.</span></p></div>
      {error ? <div className="clinical-error" role="alert"><CircleAlert aria-hidden="true" /><span>{error}</span></div> : null}
      {kind === 'ALLERGY' ? <AllergyForm initial={initial as AllergyDraft} {...shared} /> : kind === 'MEDICATION_USE' ? <MedicationForm initial={initial as MedicationDraft} {...shared} /> : <ProblemForm initial={initial as ProblemDraft} {...shared} />}
    </div>
  </Sheet><ConfirmationDialog open={confirmClose} title={pending ? 'Leave an unconfirmed save?' : 'Discard unsaved clinical changes?'} description={pending ? 'The server may have saved this record. Stay and retry the original save to confirm its outcome.' : 'These entries exist only in this open form. Closing it will discard them.'} confirmLabel={pending ? 'Keep editing' : 'Discard changes'} onCancel={() => setConfirmClose(false)} onConfirm={() => { if (pending) setConfirmClose(false); else onClose() }} /></>
}
