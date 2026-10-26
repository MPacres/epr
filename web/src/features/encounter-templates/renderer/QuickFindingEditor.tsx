import { useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { ConfirmationDialog } from '../../../components/ui/confirmation-dialog'
import { emptyValue, newAnswer, type Answer, type Field } from '../schema/contracts'
import type { DraftAnswer } from '../../encounters/services/draft-values'

type Finding = NonNullable<NonNullable<Answer['value']>['findings']>[number]
type Props = { field: Field; answer?: DraftAnswer; disabled?: boolean; onChange: (answer?: DraftAnswer) => void }

export function QuickFindingEditor({ field, answer, disabled, onChange }: Props) {
  const [openSystem, setOpenSystem] = useState<string | null>(null)
  const [narrative, setNarrative] = useState(false)
  const [pendingMode, setPendingMode] = useState<'narrative' | 'template' | null>(null)
  const groups = field.quickFindings ?? []
  const isRos = field.concept === 'visit.ros'
  const current = answer?.value?.findings ?? []
  const narrativeMode = narrative || Boolean(answer?.value?.text)

  function commitFindings(findings: Finding[]) {
    if (!findings.length) { onChange(); return }
    onChange({ ...(answer ?? newAnswer(field.id)), value: { ...emptyValue(), findings }, missingState: null,
      review: answer?.source.kind === 'PATIENT_REPORTED' || answer?.source.kind === 'RECORD_REFERENCE' ? 'NOT_REVIEWED' : 'REVIEWED', hiddenResolution: null, resolutionReason: null })
  }
  function update(systemKey: string, phrases: string[], customText: string | null) {
    const next = current.filter(finding => finding.systemKey !== systemKey)
    if (phrases.length || customText?.trim()) next.push({ systemKey, phrases, customText: customText?.trim() ? customText : null })
    commitFindings(groups.flatMap(group => next.filter(finding => finding.systemKey === group.key)))
  }
  function setMode(mode: 'narrative' | 'template') {
    const hasContent = Boolean(answer?.value?.text?.trim() || current.length)
    if (hasContent) { setPendingMode(mode); return }
    setNarrative(mode === 'narrative')
  }
  function confirmMode() {
    onChange()
    setNarrative(pendingMode === 'narrative')
    setPendingMode(null)
  }

  return <div className="quick-finding-editor">
    {answer?.missingState ? <div className="quick-finding-state"><strong>{answer.missingState === 'DECLINED' ? 'Patient declined' : answer.missingState.replaceAll('_', ' ').toLowerCase().replace(/^./, letter => letter.toUpperCase())}</strong><p>No system findings are recorded for this section.</p><button type="button" disabled={disabled} onClick={() => onChange()}>Document findings instead</button></div>
      : narrativeMode ? <div className="quick-finding-narrative"><textarea id={`field-${field.id}__root`} aria-label={field.label} aria-describedby={`field-${field.id}__root-help`} rows={4} maxLength={field.maxLength} disabled={disabled} value={answer?.value?.text ?? ''} onChange={event => {
      const text = event.target.value
      onChange(text ? { ...(answer ?? newAnswer(field.id)), value: { ...emptyValue(), text }, missingState: null,
        review: answer?.source.kind === 'PATIENT_REPORTED' || answer?.source.kind === 'RECORD_REFERENCE' ? 'NOT_REVIEWED' : 'REVIEWED', hiddenResolution: null, resolutionReason: null } : undefined)
    }} /><button type="button" disabled={disabled} onClick={() => setMode('template')}>Use system template</button></div>
      : <><p className="quick-finding-instruction">Select only findings reviewed or observed today. Untouched systems stay out of the note.</p>
        <div className="quick-finding-rows">{groups.map(group => {
          const selected = current.find(finding => finding.systemKey === group.key)
          const open = openSystem === group.key
          const summary = selected ? [...selected.phrases, selected.customText].filter(Boolean).join(' ') : 'Not recorded'
          const panel = `finding-${field.id}-${group.key}`
          return <div key={group.key} className={`quick-finding-row${selected ? ' has-finding' : ''}`}>
            <button type="button" className="quick-finding-row-toggle" aria-expanded={open} aria-controls={panel} disabled={disabled} onClick={() => setOpenSystem(open ? null : group.key)}><span className="quick-finding-name">{group.label}</span><span className="quick-finding-summary">{summary}</span><ChevronDown aria-hidden="true" /></button>
            {open ? <div id={panel} className="quick-finding-detail"><span className="quick-finding-detail-label">Select findings documented today</span><div className="quick-finding-phrases">{group.phrases.map(phrase => <button key={phrase} type="button" aria-pressed={selected?.phrases.includes(phrase) ?? false} disabled={disabled} onClick={() => update(group.key, selected?.phrases.includes(phrase) ? selected.phrases.filter(value => value !== phrase) : [...(selected?.phrases ?? []), phrase], selected?.customText ?? null)}>{phrase}</button>)}</div>
              <label htmlFor={`${panel}-custom`}>{isRos ? 'Different or additional symptom' : 'Different or additional finding'}</label><textarea id={`${panel}-custom`} rows={2} maxLength={field.maxLength} disabled={disabled} value={selected?.customText ?? ''} onChange={event => update(group.key, selected?.phrases ?? [], event.target.value)} placeholder={isRos ? 'Describe symptoms actually reviewed with the patient' : 'Describe findings actually observed or examined'} />
              {selected ? <button type="button" className="quick-finding-clear" disabled={disabled} onClick={() => update(group.key, [], null)}>Clear {group.label}</button> : null}</div> : null}
          </div>
        })}</div><button type="button" className="quick-finding-mode" disabled={disabled} onClick={() => setMode('narrative')}>Write a narrative instead</button></>}
    <ConfirmationDialog open={pendingMode !== null} title="Replace these findings?" description="Switching entry modes clears the current ROS or examination answer from this draft. Earlier server-saved revisions remain available." onCancel={() => setPendingMode(null)} onConfirm={confirmMode} />
  </div>
}
