import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { ClinicalRequestError, type Encounter, type Scope } from './services/encounter-api'
import { vitalApi, vitalKey, type VitalCommand, type VitalSet, type VitalValues } from './services/vital-api'

type NumericField = keyof VitalValues
type Inputs = Record<NumericField, string>
type Draft = { measuredAt: string; values: Inputs; reason: string }
type Pending = { id: string | null; key: string; body: VitalCommand & { vitalSetId?: string; expectedVersion?: number; reason?: string } }
export type VitalPendingState = { dirty: boolean; unresolvedOutcome: boolean; mutationPending: boolean }
const vitalSource = 'Clinic (manual)'
const fields: { key: NumericField; label: string; unit: string; integerDigits: number; decimals: number }[] = [
  { key: 'systolicMmhg', label: 'Systolic', unit: 'mmHg', integerDigits: 5, decimals: 1 },
  { key: 'diastolicMmhg', label: 'Diastolic', unit: 'mmHg', integerDigits: 5, decimals: 1 },
  { key: 'pulseBpm', label: 'Pulse', unit: 'beats/min', integerDigits: 5, decimals: 1 },
  { key: 'respiratoryRateBpm', label: 'Respiratory rate', unit: 'breaths/min', integerDigits: 5, decimals: 1 },
  { key: 'temperatureC', label: 'Temperature', unit: '°C', integerDigits: 3, decimals: 2 },
  { key: 'oxygenSaturationPercent', label: 'Oxygen saturation', unit: '%', integerDigits: 3, decimals: 2 },
  { key: 'weightKg', label: 'Weight', unit: 'kg', integerDigits: 5, decimals: 2 },
  { key: 'heightCm', label: 'Height', unit: 'cm', integerDigits: 5, decimals: 2 },
]
const localTime = (value: string | Date) => new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
}).format(new Date(value)).replace(' ', 'T')
const displayTime = (value: string) => new Intl.DateTimeFormat('en-PH', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Manila',
}).format(new Date(value)) + ' PHT'
const blank = (): Draft => ({ measuredAt: localTime(new Date()), reason: '',
  values: Object.fromEntries(fields.map(field => [field.key, ''])) as Inputs })
const fromSet = (set: VitalSet): Draft => ({ measuredAt: localTime(set.current.measuredAt),
  reason: '', values: Object.fromEntries(fields.map(field =>
    [field.key, set.current.values[field.key]?.toString() ?? ''])) as Inputs })
const summary = (values: VitalValues) => fields.flatMap(field => {
  if (field.key === 'diastolicMmhg') return []
  if (field.key === 'systolicMmhg') return values.systolicMmhg == null ? [] : [`BP ${values.systolicMmhg}/${values.diastolicMmhg} mmHg`]
  return values[field.key] == null ? [] : [`${field.label} ${values[field.key]} ${field.unit}`]
}).join(' · ')
function command(draft: Draft): VitalCommand {
  return { measuredAt: `${draft.measuredAt}:00+08:00`, source: vitalSource,
    values: Object.fromEntries(fields.map(field => [field.key,
      draft.values[field.key].trim() === '' ? null : Number(draft.values[field.key])])) as VitalValues }
}

export function EncounterVitals({ scope, record, onPendingChange, onSaved }: {
  scope: Scope; record: Encounter; onPendingChange: (pending: VitalPendingState) => void; onSaved: () => void
}) {
  const query = useQueryClient()
  const key = vitalKey(scope, record.patientId, record.encounterId)
  const sets = useQuery({ queryKey: key, queryFn: ({ signal }) => vitalApi.list(scope, record.patientId, record.encounterId, signal), retry: false })
  const [editing, setEditing] = useState<string | 'new' | null>(null)
  const [draft, setDraft] = useState<Draft>(blank)
  const [pending, setPending] = useState<Pending | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const recorder = (id: string) => id === record.responsibleClinicianId ? scope.clinicianName : `Recorder ID ${id}`
  useEffect(() => onPendingChange({ dirty: editing !== null, unresolvedOutcome: pending !== null && !busy, mutationPending: busy }), [editing, pending, busy, onPendingChange])
  function begin(id: string | 'new', set?: VitalSet) {
    setEditing(id); setDraft(set ? fromSet(set) : blank()); setError(null); setFieldError(null); setPending(null); setConflict(false)
    requestAnimationFrame(() => document.getElementById('encounter-vitals')?.scrollIntoView({ block: 'start' }))
  }
  function validate(): string | null {
    if (!draft.measuredAt || Number.isNaN(Date.parse(`${draft.measuredAt}:00+08:00`))) return 'Enter the measurement time.'
    const values = command(draft).values
    if (Object.values(values).every(value => value === null)) return 'Enter at least one measured value.'
    if ((values.systolicMmhg === null) !== (values.diastolicMmhg === null)) return 'Enter both blood pressure values.'
    for (const field of fields) {
      const raw = draft.values[field.key].trim()
      if (raw && (!new RegExp(`^\\d{1,${field.integerDigits}}(\\.\\d{1,${field.decimals}})?$`).test(raw)
        || !Number.isFinite(values[field.key]) || values[field.key]! <= 0))
        return `Enter a positive ${field.label.toLowerCase()} value with up to ${field.integerDigits} digits before and ${field.decimals} after the decimal point.`
    }
    if (values.oxygenSaturationPercent !== null && values.oxygenSaturationPercent > 100) return 'Oxygen saturation cannot exceed 100%.'
    if (editing !== 'new' && !draft.reason.trim()) return 'Enter a reason for this correction.'
    if (draft.reason.length > 500) return 'Keep the correction reason within 500 characters.'
    return null
  }
  async function save() {
    if (busy || !editing || conflict) return
    const problem = pending ? null : validate()
    setFieldError(problem)
    if (problem) return
    const selected = editing === 'new' ? null : sets.data?.find(set => set.vitalSetId === editing)
    if (editing !== 'new' && !selected && !pending) { setError('This vital set changed. Reload it before correcting.'); return }
    const action = pending ?? { id: selected?.vitalSetId ?? null, key: createUuid(), body: selected
      ? { ...command(draft), expectedVersion: selected.current.version, reason: draft.reason.trim() }
      : { ...command(draft), vitalSetId: createUuid() } }
    setPending(action); setBusy(true); setError(null)
    try {
      if (action.id) await vitalApi.correct(scope, record.patientId, record.encounterId, action.id, action.key,
        action.body as VitalCommand & { expectedVersion: number; reason: string })
      else await vitalApi.create(scope, record.patientId, record.encounterId, action.key,
        action.body as VitalCommand & { vitalSetId: string })
      await query.invalidateQueries({ queryKey: key })
      setPending(null); setEditing(null); setDraft(blank()); setConflict(false); onSaved()
    } catch (cause) {
      setError(cause instanceof ClinicalRequestError && cause.status === 409
        ? editing === 'new' ? 'A vital set identifier was already used. Your entries remain here; save again with a new request.' : 'This vital set changed. Your entries remain here. Review the current record before making a new correction.'
        : cause instanceof Error ? cause.message : 'The vital sign change could not be confirmed.')
      if (cause instanceof ClinicalRequestError && [403, 404, 409, 422].includes(cause.status)) setPending(null)
      if (cause instanceof ClinicalRequestError && cause.status === 409 && editing !== 'new') { setConflict(true); void query.invalidateQueries({ queryKey: key }) }
    } finally { setBusy(false) }
  }
  return <section id="encounter-vitals" className="encounter-form-section encounter-vitals" aria-labelledby="encounter-vitals-title">
    <div className="encounter-vitals-heading"><div><h2 id="encounter-vitals-title">Vital signs</h2><p>Measurements are saved as clinical records. Corrections retain the earlier value, recorder and time.</p></div>
      {record.noteState !== 'SIGNED' && !editing ? <Button variant="outline" onClick={() => begin('new')}>Add vital signs</Button> : null}</div>
    {sets.isPending ? <p role="status">Loading vital signs…</p> : sets.isError ? <div role="alert" className="clinical-error"><p>Vital signs could not be loaded.</p><Button variant="outline" onClick={() => void sets.refetch()}>Retry</Button></div>
      : sets.data?.length ? <div className="encounter-vitals-list">{sets.data.map(set => {
        const earlier = set.history.filter(revision => revision.version < set.current.version)
        return <article key={set.vitalSetId} className="encounter-vitals-record"><div className="encounter-vitals-record-heading"><strong>{displayTime(set.current.measuredAt)}</strong><span>Version {set.current.version}</span></div><p>{summary(set.current.values)}</p><small>Source: {set.current.source} · recorded by {recorder(set.current.recordedBy)} at {displayTime(set.current.receivedAt)}{set.current.correctionReason ? ` · correction reason: ${set.current.correctionReason}` : ''}</small>
          {earlier.length ? <details><summary>View earlier readings ({earlier.length})</summary>{earlier.map(revision => <div key={revision.version} className="encounter-vital-history"><strong>Version {revision.version} · measured {displayTime(revision.measuredAt)}</strong><p>{summary(revision.values)}</p><small>Source: {revision.source} · recorded by {recorder(revision.recordedBy)} at {displayTime(revision.receivedAt)}{revision.correctionReason ? ` · reason: ${revision.correctionReason}` : ''}</small></div>)}</details> : null}
          {record.noteState !== 'SIGNED' && !editing ? <Button variant="ghost" onClick={() => begin(set.vitalSetId, set)}>Correct vital signs</Button> : null}</article>
      })}</div> : <p>No vital signs recorded for this encounter.</p>}
    {editing ? <div className="encounter-vitals-editor"><h3>{editing === 'new' ? 'Add measured values' : 'Correct recorded values'}</h3><p>Leave measurements blank when they were not taken. Recorded in clinic manually; time uses Philippine time.</p>
      <div className="encounter-vitals-meta"><label>Measured at (PHT)<input type="datetime-local" value={draft.measuredAt} disabled={busy || pending !== null} onChange={event => setDraft(previous => ({ ...previous, measuredAt: event.target.value }))} /></label></div>
      <div className="encounter-vitals-inputs">{fields.map(field => <label key={field.key}>{field.label} <span>({field.unit})</span><input type="text" inputMode="decimal" value={draft.values[field.key]} disabled={busy || pending !== null} onChange={event => setDraft(previous => ({ ...previous, values: { ...previous.values, [field.key]: event.target.value } }))} /></label>)}</div>
      {editing !== 'new' ? <label>Reason for correction<textarea rows={2} value={draft.reason} maxLength={500} disabled={busy || pending !== null} onChange={event => setDraft(previous => ({ ...previous, reason: event.target.value }))} /></label> : null}
      {fieldError ? <p className="clinical-error" role="alert">{fieldError}</p> : null}{error ? <p className="clinical-error" role="alert">{error}</p> : null}
      {conflict ? <Button variant="outline" disabled={sets.isFetching} onClick={() => { const current = sets.data?.find(set => set.vitalSetId === editing); if (current) { setDraft(fromSet(current)); setConflict(false); setError(null) } }}>Reload current values before correcting</Button> : null}
      <div className="clinical-buttons"><Button disabled={busy || conflict} onClick={() => void save()}>{busy ? 'Saving…' : pending ? 'Retry original save' : editing === 'new' ? 'Save vital signs' : 'Save correction'}</Button><Button variant="ghost" disabled={busy || pending !== null} onClick={() => { setEditing(null); setError(null); setConflict(false) }}>Cancel</Button></div>
      {pending ? <p role="status">The original request is awaiting confirmation. Retry it before changing these values.</p> : null}
    </div> : null}
  </section>
}
