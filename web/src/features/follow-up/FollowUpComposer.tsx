import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Field, ComposerActions } from '../interventions/components/ComposerFields'
const schema = z.object({ instruction: z.string().min(1, 'Record the follow-up instruction.').max(2000), dueDate: z.string().min(1, 'Choose a due date.') })
export function FollowUpComposer({ busy, owner, onDirty, onSave, onCancel }: { busy: boolean; owner: string; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>) => void; onCancel: () => void }) {
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { instruction: '', dueDate: '' } })
  useEffect(() => { onDirty(form.formState.isDirty) }, [onDirty, form.formState.isDirty])
  return <form data-clinical-autosave className="intervention-composer" onSubmit={form.handleSubmit(values => onSave(values))}><p>{owner} owns this follow-up. Note signing and appointment booking do not resolve it.</p><fieldset disabled={busy}><Field label="Follow-up instruction" error={form.formState.errors.instruction?.message}><textarea required maxLength={2000} {...form.register('instruction')} /></Field><Field label="Due date" error={form.formState.errors.dueDate?.message}><input required type="date" {...form.register('dueDate')} /></Field></fieldset><ComposerActions busy={busy} onCancel={onCancel} label="Create follow-up task" /></form>
}
