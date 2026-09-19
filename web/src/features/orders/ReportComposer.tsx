import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { Button } from '../../components/ui/button'
import { Field } from '../interventions/components/ComposerFields'
import { atSite, clinicalKey, request, type Scope } from '../encounters/services/encounter-api'
import { reportSchema } from './report-schema'
const schema = z.object({ source: z.string().max(1000), externalReference: z.string().max(300), content: z.string().max(10000), occurredAt: z.string(), reason: z.string().min(1, 'Record your source and matching review.').max(2000), correctsId: z.string() })
export function ReportComposer({ scope, kind, patientName, patientId, sourceId, busy, onDirty, onSave }: { scope: Scope; kind: 'LAB_ORDER' | 'IMAGING_ORDER'; patientName: string; patientId: string; sourceId: string; busy: boolean; onDirty: (v: boolean) => void; onSave: (data: Record<string, unknown>, match: boolean, existing?: z.infer<typeof reportSchema>) => void }) {
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { source: '', externalReference: '', content: '', occurredAt: '', reason: '', correctsId: '' } })
  const [match, setMatch] = useState(false)
  const [existingId, setExistingId] = useState('')
  const unmatched = useQuery({ queryKey: [...clinicalKey(scope), 'unmatched-reports'], queryFn: ({ signal }) => request(atSite(scope, '/incoming-service-reports'), z.array(reportSchema), { signal }), retry: false })
  const originals = useQuery({ queryKey: [...clinicalKey(scope, patientId), sourceId, 'reports'], queryFn: ({ signal }) => request(atSite(scope, `/patients/${patientId}/service-requests/${sourceId}/reports`), z.array(reportSchema), { signal }), retry: false })
  useEffect(() => { onDirty(form.formState.isDirty || match || !!existingId) }, [onDirty, form.formState.isDirty, match, existingId])
  return <form className="intervention-composer" data-clinical-autosave onSubmit={form.handleSubmit(values => onSave({ ...values, occurredAt: values.occurredAt ? new Date(values.occurredAt).toISOString() : undefined, correctsId: values.correctsId || undefined }, match, unmatched.data?.find(r => r.id === existingId)))}>
    <p>Original report content stays unchanged. A correction receives a new identity and a new review obligation.</p><fieldset disabled={busy}>
      <Field label="Previously received unmatched report"><select value={existingId} onChange={e => setExistingId(e.target.value)}><option value="">Receive a new report</option>{unmatched.data?.filter(r => r.kind === kind).map(r => <option key={r.id} value={r.id}>{r.source} · {r.externalReference}</option>)}</select></Field>
      {existingId ? <p>{unmatched.data?.find(r => r.id === existingId)?.content}</p> : <><Field label="Reporting source"><input required {...form.register('source')} /></Field><Field label="External report reference"><input required {...form.register('externalReference')} /></Field><Field label="Report content"><textarea required maxLength={10000} {...form.register('content')} /></Field><Field label="Report occurrence time · device local time"><input required type="datetime-local" {...form.register('occurredAt')} /></Field><Field label="Corrects an existing matched report, if applicable"><select {...form.register('correctsId')}><option value="">Original report</option>{originals.data?.map(r => <option key={r.id} value={r.id}>{r.externalReference} · {r.source}</option>)}</select></Field></>}
      <Field label="Receipt / patient and source matching evidence" error={form.formState.errors.reason?.message}><textarea required maxLength={2000} {...form.register('reason')} /></Field><label className="clinical-checkbox"><input type="checkbox" checked={match} onChange={e => setMatch(e.target.checked)} />I verified that this report belongs to {patientName} and this exact request.</label>
    </fieldset><Button type="submit" disabled={busy || !!existingId && !match}>{match ? 'Receive and match reviewed report' : 'Receive as unmatched'}</Button><p>Unmatched reports remain with their receiving clinician until explicitly matched. Names are never matched automatically.</p></form>
}
