import { useEffect, useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/ui/button'
import { createUuid } from '../../lib/create-uuid'
import { ClinicalRequestError, type Encounter, type Scope } from './services/encounter-api'
import { vitalApi, vitalKey, type VitalCommand, type VitalReading, type VitalValues } from './services/vital-api'

type NumericField = keyof VitalValues
type Inputs = Record<NumericField, string>
type Draft = { measuredAt: string; values: Inputs }
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
const blank = (): Draft => ({ measuredAt: localTime(new Date()),
  values: Object.fromEntries(fields.map(field => [field.key, ''])) as Inputs })
const fromSet = (set: VitalReading): Draft => ({ measuredAt: localTime(set.current.measuredAt),
  values: Object.fromEntries(fields.map(field =>
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
  const sets = useInfiniteQuery({ queryKey: key,
    queryFn: ({ pageParam, signal }) => vitalApi.timeline(scope, record.patientId, record.encounterId, pageParam, signal),
    initialPageParam: 0, getNextPageParam: last => last.hasMore ? last.page + 1 : undefined, retry: false })
  const readings = sets.data?.pages.flatMap(page => page.items) ?? []
  const [editing, setEditing] = useState<string | 'new' | null>(null)
  const [draft, setDraft] = useState<Draft>(blank)
  const [pending, setPending] = useState<Pending | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const [removeTarget, setRemoveTarget] = useState<string | null>(null)
  const [removePending, setRemovePending] = useState<{ id: string; key: string; version: number } | null>(null)
  const [removeBusy, setRemoveBusy] = useState(false)
  const [removeError, setRemoveError] = useState<string | null>(null)
  const recorder = (id: string) => id === record.responsibleClinicianId ? scope.clinicianName : `Recorder ID ${id}`
  useEffect(() => onPendingChange({ dirty: editing !== null || removeTarget !== null,
    unresolvedOutcome: pending !== null && !busy || removePending !== null && !removeBusy,
    mutationPending: busy || removeBusy }), [editing, pending, busy, removeTarget, removePending, removeBusy, onPendingChange])
  function begin(id: string | 'new', set?: VitalReading) {
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
    return null
  }
  async function save() {
    if (busy || !editing || conflict) return
    const problem = pending ? null : validate()
    setFieldError(problem)
    if (problem) return
    const selected = editing === 'new' ? null : readings.find(set => set.vitalSetId === editing && set.encounterId === record.encounterId)
    if (editing !== 'new' && !selected && !pending) { setError('This vital set changed. Reload it before correcting.'); return }
    const action = pending ?? { id: selected?.vitalSetId ?? null, key: createUuid(), body: selected
      ? { ...command(draft), expectedVersion: selected.current.version, reason: 'Corrected entry' }
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
        ? editing === 'new' ? 'A vital sign identifier was already used. Your entries remain here; save again with a new request.' : 'This reading changed. Your entries remain here. Review the current record before editing again.'
        : cause instanceof Error ? cause.message : 'The vital sign change could not be confirmed.')
      if (cause instanceof ClinicalRequestError && [403, 404, 409, 422].includes(cause.status)) setPending(null)
      if (cause instanceof ClinicalRequestError && cause.status === 409 && editing !== 'new') { setConflict(true); void query.invalidateQueries({ queryKey: key }) }
    } finally { setBusy(false) }
  }
  async function removeReading() {
    if (!removeTarget || removeBusy) return
    const selected = readings.find(item => item.vitalSetId === removeTarget && item.encounterId === record.encounterId)
    if (!selected && !removePending) { setRemoveError('This reading changed. Reload before removing it.'); return }
    const action = removePending ?? { id: removeTarget, key: createUuid(), version: selected!.current.version }
    setRemovePending(action); setRemoveBusy(true); setRemoveError(null)
    try {
      await vitalApi.remove(scope, record.patientId, record.encounterId, action.id, action.key, action.version)
      await query.invalidateQueries({ queryKey: key })
      setRemovePending(null); setRemoveTarget(null); onSaved()
    } catch (cause) {
      setRemoveError(cause instanceof ClinicalRequestError && cause.status === 409
        ? 'This reading changed. Review the latest reading before removing it.'
        : cause instanceof Error ? cause.message : 'Removal could not be confirmed. Retry the original request.')
      if (cause instanceof ClinicalRequestError && [403, 404, 409, 422].includes(cause.status)) setRemovePending(null)
      if (cause instanceof ClinicalRequestError && cause.status === 409) void query.invalidateQueries({ queryKey: key })
    } finally { setRemoveBusy(false) }
  }
  return <section id="encounter-vitals" className="encounter-form-section encounter-vitals" aria-labelledby="encounter-vitals-title">
    <div className="encounter-vitals-heading"><div><h2 id="encounter-vitals-title">Vital signs</h2><p>Measurements from this patient’s visits at this Practice site, newest first.</p></div>
      {record.noteState !== 'SIGNED' && !editing && !removeTarget ? <Button variant="outline" onClick={() => begin('new')}>Add vital signs</Button> : null}</div>
    {sets.isPending ? <p role="status">Loading vital signs…</p> : null}
    {sets.isError ? <div role="alert" className="clinical-error"><p>Vital signs could not be loaded.</p><Button variant="outline" onClick={() => void sets.refetch()}>Retry</Button></div> : null}
    {readings.length ? <div className="encounter-vitals-list">{readings.map(reading => <article key={reading.vitalSetId} className="encounter-vitals-record">
      <div className="encounter-vitals-record-heading"><strong>{displayTime(reading.current.measuredAt)}</strong><span>{reading.encounterId === record.encounterId ? 'This encounter' : 'Earlier visit'}</span></div>
      <p>{summary(reading.current.values)}</p><small>Source: {reading.current.source} · recorded by {recorder(reading.current.recordedBy)} at {displayTime(reading.current.receivedAt)}</small>
      {record.noteState !== 'SIGNED' && reading.encounterId === record.encounterId && !editing && !removeTarget ? <div className="clinical-buttons"><Button variant="ghost" onClick={() => begin(reading.vitalSetId, reading)}>Edit</Button><Button variant="ghost" onClick={() => { setRemoveTarget(reading.vitalSetId); setRemoveError(null) }}>Remove</Button></div> : null}
      {removeTarget === reading.vitalSetId ? <div className="encounter-vital-remove" role="group" aria-label="Remove vital signs"><p>Remove this mistaken reading from the vital-sign timeline and unsigned note?</p>
        {removeError ? <p className="clinical-error" role="alert">{removeError}</p> : null}
        <div className="clinical-buttons"><Button variant="outline" disabled={removeBusy || removePending !== null} onClick={() => { setRemoveTarget(null); setRemoveError(null) }}>Keep reading</Button><Button disabled={removeBusy} onClick={() => void removeReading()}>{removeBusy ? 'Removing…' : removePending ? 'Retry original removal' : 'Remove reading'}</Button></div>
        {removePending ? <p role="status">Removal is awaiting confirmation. Retry the original request before leaving.</p> : null}
      </div> : null}
    </article>)}</div> : !sets.isPending && !sets.isError ? <p>No vital signs recorded for this patient at this site.</p> : null}
    {sets.hasNextPage ? <Button variant="outline" disabled={sets.isFetchingNextPage} onClick={() => void sets.fetchNextPage()}>{sets.isFetchingNextPage ? 'Loading older measurements…' : 'Load older measurements'}</Button> : null}
    {editing ? <div className="encounter-vitals-editor"><h3>{editing === 'new' ? 'Add measured values' : 'Edit measured values'}</h3><p>Leave measurements blank when they were not taken. Recorded in clinic manually; time uses Philippine time.</p>
      <div className="encounter-vitals-meta"><label>Measured at (PHT)<input type="datetime-local" value={draft.measuredAt} disabled={busy || pending !== null} onChange={event => setDraft(previous => ({ ...previous, measuredAt: event.target.value }))} /></label></div>
      <div className="encounter-vitals-inputs">{fields.map(field => <label key={field.key}>{field.label} <span>({field.unit})</span><input type="text" inputMode="decimal" value={draft.values[field.key]} disabled={busy || pending !== null} onChange={event => setDraft(previous => ({ ...previous, values: { ...previous.values, [field.key]: event.target.value } }))} /></label>)}</div>
      {fieldError ? <p className="clinical-error" role="alert">{fieldError}</p> : null}{error ? <p className="clinical-error" role="alert">{error}</p> : null}
      {conflict ? <Button variant="outline" disabled={sets.isFetching} onClick={() => { const current = readings.find(set => set.vitalSetId === editing); if (current) { setDraft(fromSet(current)); setConflict(false); setError(null) } }}>Reload current values before editing</Button> : null}
      <div className="clinical-buttons"><Button disabled={busy || conflict} onClick={() => void save()}>{busy ? 'Saving…' : pending ? 'Retry original save' : editing === 'new' ? 'Save vital signs' : 'Save changes'}</Button><Button variant="ghost" disabled={busy || pending !== null} onClick={() => { setEditing(null); setError(null); setConflict(false) }}>Cancel</Button></div>
      {pending ? <p role="status">The original request is awaiting confirmation. Retry it before changing these values.</p> : null}
    </div> : null}
  </section>
}
