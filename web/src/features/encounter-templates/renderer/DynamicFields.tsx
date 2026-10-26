import { useState } from 'react'
import { Plus, Trash2, TriangleAlert } from 'lucide-react'
import { Button } from '../../../components/ui/button'
import { ConfirmationDialog } from '../../../components/ui/confirmation-dialog'
import { createUuid } from '../../../lib/create-uuid'
import { address, emptyValue, evaluate, newAnswer, type Answer, type Field, type Value } from '../schema/contracts'
import { displayAnswer, type DraftAnswer, type DraftValues } from '../../encounters/services/draft-values'
import { QuickFindingEditor } from './QuickFindingEditor'

const label = (value: string) => value.replaceAll('_', ' ').toLowerCase().replace(/^./, c => c.toUpperCase())
type Props = { field: Field; item?: string; index?: number; values: DraftValues; onChange: (values: DraftValues) => void; disabled?: boolean }
export function DynamicField({ field, item, index, values, onChange, disabled }: Props) {
  const key = address(field.id, item ?? null)
  const answer = values[key]
  const visible = evaluate(field.visibleWhen, values, item ?? null) === 'TRUE'
  const [removeItem, setRemoveItem] = useState<string | null>(null)
  const [pendingState, setPendingState] = useState<Answer['missingState'] | null>(null)
  const [resolution, setResolution] = useState('')
  const id = `field-${key}`
  const caption = `${field.label}${index === undefined ? '' : ` · item ${index + 1}`}`
  const conciseFinding = field.concept === 'visit.ros' || field.concept === 'visit.physical-examination'
  function set(next?: DraftAnswer) { const updated = { ...values }; if (next) updated[key] = next; else delete updated[key]; onChange(updated) }
  function value(next: Partial<Value>, rawNumber?: string) {
    const base = answer ?? newAnswer(field.id, item ?? null)
    set({ ...base, value: { ...emptyValue(), ...next }, missingState: null, review: base.source.kind === 'CLINICIAN_ENTERED' ? 'REVIEWED' : 'NOT_REVIEWED', hiddenResolution: null, resolutionReason: null, ...(rawNumber === undefined ? {} : { rawNumber }) })
  }
  function metadata(next: Partial<Answer>) { set({ ...(answer ?? newAnswer(field.id, item ?? null)), ...next }) }
  function chooseState(state: Answer['missingState']) {
    if (answer?.value?.text?.trim() || answer?.value?.findings?.length) { setPendingState(state); return }
    metadata({ value: null, missingState: state, review: answer?.source.kind === 'PATIENT_REPORTED' || answer?.source.kind === 'RECORD_REFERENCE' ? 'NOT_REVIEWED' : 'REVIEWED' })
  }
  if (!visible && !answer) return null
  if (field.type === 'GROUP') {
    const items = answer?.value?.items ?? []
    return <fieldset data-field-id={field.id} className="dynamic-group" disabled={disabled}><legend>{field.label}</legend><p className="field-help">Record what is known. Each item retains its identity across edits.</p>
      {items.map((itemId, itemIndex) => <div className="dynamic-repeat" key={itemId}><div className="repeat-heading"><h3>History item {itemIndex + 1}</h3><Button variant="ghost" aria-label={`Remove history item ${itemIndex + 1}`} onClick={() => setRemoveItem(itemId)}><Trash2 aria-hidden="true" />Remove</Button></div>
        {field.fields.map(child => <DynamicField key={child.id} field={child} item={itemId} index={itemIndex} values={values} onChange={onChange} disabled={disabled} />)}
      </div>)}
      <Button variant="outline" disabled={items.length >= field.maxItems} onClick={() => value({ items: [...items, createUuid()] })}><Plus aria-hidden="true" />Add history item</Button>
      <ConfirmationDialog open={removeItem !== null} title="Remove this history item from the draft?" description="The next saved revision will remove this item. Earlier server-saved revisions remain in the record history." onCancel={() => setRemoveItem(null)} onConfirm={() => {
        const updated = { ...values }; for (const [addressKey, a] of Object.entries(updated)) if (a.groupItemId === removeItem) delete updated[addressKey]
        updated[key] = { ...(answer ?? newAnswer(field.id)), value: { ...emptyValue(), items: items.filter(value => value !== removeItem) }, missingState: null }
        onChange(updated); setRemoveItem(null)
      }} />
    </fieldset>
  }
  const common = { id, disabled, 'aria-label': caption, 'aria-describedby': `${id}-help` }
  let control
  if (!visible) control = <div className="retained-answer"><p><TriangleAlert aria-hidden="true" />This answer is retained from a hidden question.</p><blockquote>{displayAnswer(answer)}</blockquote>
    {answer.hiddenResolution ? <p>Reviewed: {answer.resolutionReason}</p> : <><label htmlFor={`${id}-reason`}>Reason to retain this answer</label><input id={`${id}-reason`} value={resolution} maxLength={500} onChange={event => setResolution(event.target.value)} /><Button variant="outline" disabled={!resolution.trim() || disabled} onClick={() => metadata({ hiddenResolution: 'RETAIN_RELEVANT', resolutionReason: resolution })}>Retain as relevant</Button></>}
  </div>
  else switch (field.type) {
    case 'MULTILINE_TEXT': control = field.quickFindings?.length ? <QuickFindingEditor field={field} answer={answer} disabled={disabled} onChange={set} /> : <textarea {...common} rows={conciseFinding ? 4 : 3} className={conciseFinding ? 'encounter-finding-input' : undefined} maxLength={field.maxLength} value={answer?.value?.text ?? ''} onChange={e => e.target.value ? value({ text: e.target.value }) : set()} />; break
    case 'TEXT': case 'DATE': case 'DATETIME': control = <input {...common} type={field.type === 'DATE' ? 'date' : 'text'} placeholder={field.type === 'DATETIME' ? 'YYYY-MM-DDTHH:mm:ss+08:00' : undefined} maxLength={field.maxLength} value={answer?.value?.text ?? ''} onChange={e => e.target.value ? value({ text: e.target.value }) : set()} />; break
    case 'INTEGER': case 'DECIMAL': case 'QUANTITY': control = <div className="quantity-input"><input {...common} type="text" inputMode="decimal" value={answer?.rawNumber ?? answer?.value?.number ?? ''} onChange={e => e.target.value ? value({ number: null, unit: field.type === 'QUANTITY' ? answer?.value?.unit ?? field.units[0] : null }, e.target.value) : set()} />
      {field.type === 'QUANTITY' ? <select aria-label={`${caption} unit`} value={answer?.value?.unit ?? field.units[0]} disabled={disabled} onChange={e => value({ number: answer?.value?.number ?? null, unit: e.target.value }, answer?.rawNumber)}>{field.units.map(unit => <option key={unit}>{unit}</option>)}</select> : null}</div>; break
    case 'SINGLE_CHOICE': case 'ASSESSMENT_STATE': control = <select {...common} value={answer?.value?.choices[0] ?? ''} onChange={e => e.target.value ? value({ choices: [e.target.value] }) : set()}><option value="">Select when assessed…</option>{field.options.map(option => <option key={option} value={option}>{label(option)}</option>)}</select>; break
    case 'MULTIPLE_CHOICE': control = <div id={id}>{field.options.map(option => <label className="choice-option" key={option}><input type="checkbox" checked={answer?.value?.choices.includes(option) ?? false} disabled={disabled} onChange={e => value({ choices: e.target.checked ? [...(answer?.value?.choices ?? []), option] : (answer?.value?.choices ?? []).filter(v => v !== option) })} />{label(option)}</label>)}</div>; break
    case 'REFERENCE': control = <p id={id}>This reference requires its owning chart workflow. No reference has been inserted.</p>; break
  }
  return <div className={`dynamic-field${conciseFinding ? ' encounter-finding' : ''}`} data-field-id={field.id}><div className="field-heading">{field.quickFindings?.length ? <strong>{caption}</strong> : <label htmlFor={id}>{caption}</label>}{field.requiredAtSign ? <span>Required for signing</span> : <span>Optional</span>}</div>
    {control}<p id={`${id}-help`} className="field-help">{field.quickFindings?.length ? field.concept === 'visit.ros' ? 'Review each selected symptom statement before signing. Untouched systems are not documented.' : 'Select only findings actually observed or examined. Vitals remain separate source records.' : conciseFinding ? field.concept === 'visit.ros' ? 'Record pertinent symptoms and relevant negatives in your own words. No findings are assumed.' : 'Record only findings observed or assessed today. Vitals remain separate source records.' : field.type === 'QUANTITY' ? 'Provisional note value. Saving this draft does not confirm a chart observation.' : 'Use a recorded value or an explicit assessment state.'}</p>
    {conciseFinding ? <div className="finding-assessment"><span>Or record an assessment state</span><div className="finding-assessment-actions">{field.allowedMissingStates.map(state => <button key={state} type="button" className={answer?.missingState === state ? 'selected' : ''} aria-pressed={answer?.missingState === state} disabled={disabled} onClick={() => chooseState(state)}>{state === 'DECLINED' ? 'Patient declined' : label(state)}</button>)}{answer?.missingState ? <button type="button" disabled={disabled} onClick={() => set()}>Clear state</button> : null}</div></div> : null}
    {!conciseFinding ? <div className="field-metadata"><label>Assessment state<select aria-label={`${caption} assessment state`} value={answer?.missingState ?? ''} disabled={disabled} onChange={e => e.target.value ? metadata({ value: null, missingState: e.target.value as Answer['missingState'], review: answer?.source.kind === 'PATIENT_REPORTED' || answer?.source.kind === 'RECORD_REFERENCE' ? 'NOT_REVIEWED' : 'REVIEWED' }) : set()}><option value="">{answer?.value ? 'Value entered' : 'Not recorded'}</option>{field.allowedMissingStates.map(state => <option key={state} value={state}>{label(state)}</option>)}</select></label></div> : null}
    {answer?.review === 'NOT_REVIEWED' ? <p className="finding-review-state">{answer.source.kind === 'CLINICIAN_ENTERED' ? 'This earlier entry still needs review before signing.' : 'Review this reported source before signing.'}<button type="button" disabled={disabled} onClick={() => metadata({ review: 'REVIEWED' })}>Mark reviewed</button></p> : null}
    {answer ? <details className="field-provenance"><summary>Source & clinical time</summary><label>Source<select aria-label={`${caption} source`} value={answer.source.kind} disabled={disabled || answer.source.kind === 'RECORD_REFERENCE'} onChange={e => metadata({ source: { ...answer.source, kind: e.target.value as Answer['source']['kind'] }, review: 'NOT_REVIEWED' })}><option value="CLINICIAN_ENTERED">Clinician entered</option><option value="PATIENT_REPORTED">Patient reported</option>{answer.source.kind === 'RECORD_REFERENCE' ? <option value="RECORD_REFERENCE">Record reference</option> : null}</select></label><label>Source description<input aria-label={`${caption} source description`} value={answer.source.description} maxLength={500} disabled={disabled} onChange={e => metadata({ source: { ...answer.source, description: e.target.value } })} /></label><label>Original clinical time (optional)<input aria-label={`${caption} original clinical time`} placeholder="YYYY-MM-DDTHH:mm:ss+08:00" value={answer.source.clinicalTime ?? ''} disabled={disabled} onChange={e => metadata({ source: { ...answer.source, clinicalTime: e.target.value || null } })} /></label><p className="field-help">Clinical time: {answer.source.clinicalTime ?? 'Not recorded'}. Authorship and receipt time are recorded by the server.</p></details> : null}
    <ConfirmationDialog open={pendingState !== null} title="Replace entered findings?" description="Choosing an assessment state removes the current text from this draft. Earlier server-saved revisions remain in the record history." onCancel={() => setPendingState(null)} onConfirm={() => { metadata({ value: null, missingState: pendingState, review: answer?.source.kind === 'CLINICIAN_ENTERED' ? 'REVIEWED' : 'NOT_REVIEWED' }); setPendingState(null) }} />
  </div>
}
