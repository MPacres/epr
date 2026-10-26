import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Field, ComposerActions } from '../interventions/components/ComposerFields'
const schema = z.object({ destination: z.string().min(1, 'Enter the receiving doctor or service.').max(4000), clinicalQuestion: z.string().max(4000), clinicalSummary: z.string().max(4000), priority: z.string(), requestedDate: z.string(), disclosureReason: z.string().max(4000) })
export function ReferralComposer({ initial, busy, onDirty, onSave, onCancel }: { initial?: Record<string, unknown>; busy: boolean; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>) => void; onCancel: () => void }) {
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { destination: '', clinicalQuestion: '', clinicalSummary: '', priority: 'ROUTINE', requestedDate: '', disclosureReason: '', ...initial } })
  useEffect(() => { onDirty(form.formState.isDirty) }, [onDirty, form.formState.isDirty])
  return <form data-clinical-autosave className="intervention-composer" onSubmit={form.handleSubmit(values => onSave({ ...values, requestedDate: values.requestedDate || undefined }))}><p>Select the information appropriate for this referral. Review it with the note before signing. Signing issues the letter; delivery and receipt remain separate.</p><fieldset disabled={busy}>
    <Field label="Receiving doctor / service / facility" error={form.formState.errors.destination?.message}><input {...form.register('destination')} required /></Field><Field label="Clinical question"><textarea {...form.register('clinicalQuestion')} maxLength={4000} /></Field><Field label="Selected clinical summary"><textarea {...form.register('clinicalSummary')} maxLength={4000} /></Field>
    <div className="intervention-field-grid"><Field label="Intended date"><input type="date" {...form.register('requestedDate')} /></Field><Field label="Priority"><select {...form.register('priority')}><option value="ROUTINE">Routine</option><option value="URGENT">Urgent · clinician selected</option></select></Field></div>
    <Field label="Disclosure review / reason"><textarea {...form.register('disclosureReason')} maxLength={4000} /></Field></fieldset><ComposerActions busy={busy} onCancel={onCancel} label="Add referral" addAnotherLabel="Add referral and add another" /></form>
}
