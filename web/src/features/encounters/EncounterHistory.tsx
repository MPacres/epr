import { useRef, useState } from 'react'
import { ArrowRight, FileCheck2, FilePenLine, Printer, RotateCw, Trash2 } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/ui/button'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { createUuid } from '../../lib/create-uuid'
import { ClinicalRequestError, clinicalApi, clinicalKey, type Encounter, type Scope } from './services/encounter-api'
import { canDeleteDraft, groupEncounterHistory } from './encounter-history-model'
import { EncounterPrintDocuments } from './EncounterPrintDocuments'

function encounterDate(value: string) {
  return new Intl.DateTimeFormat('en-PH', {
    timeZone: 'Asia/Manila', day: 'numeric', month: 'short', year: 'numeric',
    hour: 'numeric', minute: '2-digit', hour12: true,
  }).format(new Date(value)) + ' PHT'
}

function EncounterRow({ encounter, onOpen, onPrint, onDelete }: { encounter: Encounter; onOpen: (id: string) => void; onPrint: (encounter: Encounter, trigger: HTMLButtonElement) => void; onDelete: (encounter: Encounter) => void }) {
  const signed = encounter.noteState === 'SIGNED'
  const careLabel = encounter.careState === 'FINISHED' ? 'Care finished' : encounter.careState === 'PAUSED' ? 'Care paused' : 'Care in progress'
  const noteLabel = signed ? 'Signed note' : encounter.noteState === 'READY_TO_SIGN' ? 'Ready for review' : 'Unsigned draft'
  return <div className="encounter-history-row">
    <span className={`encounter-history-icon ${signed ? 'signed' : ''}`}>{signed ? <FileCheck2 aria-hidden="true" /> : <FilePenLine aria-hidden="true" />}</span>
    <span className="encounter-history-copy">
      <strong>{encounter.release.definition.title}</strong>
      <span className="encounter-history-meta"><time dateTime={encounter.clinicalTime}>{encounterDate(encounter.clinicalTime)}</time><span>{encounter.careMode.replaceAll('_', ' ').toLowerCase()}</span><span>Release {encounter.release.definition.businessVersion}</span></span>
      <span className="encounter-history-statuses"><span className={`badge ${signed ? 'green' : 'amber'}`}>{noteLabel}</span><span className={`badge ${encounter.careState === 'FINISHED' ? 'neutral' : 'blue'}`}>{careLabel}</span></span>
    </span>
    <span className="encounter-history-actions">{signed ? <Button variant="outline" onClick={event => onPrint(encounter, event.currentTarget)}><Printer aria-hidden="true" />Print documents</Button> : null}{canDeleteDraft(encounter) ? <Button className="encounter-history-delete" variant="ghost" onClick={() => onDelete(encounter)}><Trash2 aria-hidden="true" />Delete draft</Button> : null}<Button variant="ghost" onClick={() => onOpen(encounter.encounterId)}>{signed ? 'View encounter' : 'Continue draft'}<ArrowRight aria-hidden="true" /></Button></span>
  </div>
}

export function EncounterHistory({ scope, patientId, encounters, pending, error, onRetry, onOpen }: {
  scope: Scope
  patientId: string
  encounters?: Encounter[]
  pending: boolean
  error?: Error | null
  onRetry: () => void
  onOpen: (id: string) => void
}) {
  const { drafts, signed } = groupEncounterHistory(encounters ?? [])
  const [printEncounter, setPrintEncounter] = useState<Encounter | null>(null)
  const [deletion, setDeletion] = useState<{ encounter: Encounter; key: string } | null>(null)
  const [deletionOpen, setDeletionOpen] = useState(false)
  const [deletionUncertain, setDeletionUncertain] = useState(false)
  const [deletionPending, setDeletionPending] = useState(false)
  const [deletionError, setDeletionError] = useState('')
  const queryClient = useQueryClient()
  const printTrigger = useRef<HTMLButtonElement | null>(null)
  function openPrint(encounter: Encounter, trigger: HTMLButtonElement) { printTrigger.current = trigger; setPrintEncounter(encounter) }
  function openDeletion(encounter: Encounter) {
    if (!deletionUncertain || deletion?.encounter.encounterId !== encounter.encounterId) {
      setDeletion({ encounter, key: createUuid() }); setDeletionUncertain(false)
    }
    setDeletionError(''); setDeletionOpen(true)
  }
  async function confirmDeletion() {
    if (!deletion || deletionPending) return
    setDeletionPending(true); setDeletionError('')
    try {
      await clinicalApi.deleteDraft(scope, patientId, deletion.encounter.encounterId,
        { expectedVersion: deletion.encounter.version, reason: 'Clinician deleted draft from patient chart' }, deletion.key)
      queryClient.setQueryData<Encounter[]>([...clinicalKey(scope, patientId), 'history'], previous => previous?.filter(item => item.encounterId !== deletion.encounter.encounterId))
      setDeletion(null); setDeletionOpen(false); setDeletionUncertain(false)
      void queryClient.invalidateQueries({ queryKey: clinicalKey(scope) })
    } catch (cause) {
      if (cause instanceof ClinicalRequestError && cause.code === 'DRAFT_HAS_LINKED_CLINICAL_WORK') setDeletionError('This draft has linked clinical work. Keep it available and review that work before closing care.')
      else if (cause instanceof ClinicalRequestError && cause.code === 'DRAFT_DELETION_NOT_ALLOWED') setDeletionError('This draft can no longer be deleted because care was finished, signed content was prepared, or it belongs to a queue.')
      else if (cause instanceof ClinicalRequestError && cause.status === 409) { setDeletionError('The draft changed since you opened this list. Refresh the encounter history and try again.'); void queryClient.invalidateQueries({ queryKey: [...clinicalKey(scope, patientId), 'history'] }) }
      else {
        setDeletionUncertain(cause instanceof ClinicalRequestError && (cause.status === 0 || cause.code === 'UNSUPPORTED_RESPONSE_OR_TEMPLATE'))
        setDeletionError(cause instanceof Error ? cause.message : 'Deletion could not be confirmed. Retry with this dialog open.')
      }
    } finally { setDeletionPending(false) }
  }
  return <section className="encounter-history" aria-labelledby="chart-history-title">
    <header className="encounter-history-heading"><div><h2 id="chart-history-title">Encounters</h2><p>Visit notes for {scope.practiceName} · {scope.siteName}</p></div></header>
    {pending ? <div className="encounter-history-message" role="status">Loading encounter history…</div> : error ? <div className="clinical-error" role="alert"><span>Encounter history could not be loaded. {error.message}</span><Button variant="outline" onClick={onRetry}><RotateCw aria-hidden="true" />Retry</Button></div> : <>
      <div className="encounter-history-group"><div className="encounter-history-group-heading"><h3>Drafts & unfinished notes <span>{drafts.length}</span></h3><p>Continue documentation or review a note before signing.</p></div>
        {drafts.length ? <div className="encounter-history-list">{drafts.map(item => <EncounterRow key={item.encounterId} encounter={item} onOpen={onOpen} onPrint={openPrint} onDelete={openDeletion} />)}</div> : <p className="encounter-history-empty">No unsigned encounter notes in this Practice and site.</p>}
      </div>
      <div className="encounter-history-group"><div className="encounter-history-group-heading"><h3>Signed encounters <span>{signed.length}</span></h3><p>Signed records remain available to view with their original release.</p></div>
        {signed.length ? <div className="encounter-history-list">{signed.map(item => <EncounterRow key={item.encounterId} encounter={item} onOpen={onOpen} onPrint={openPrint} onDelete={openDeletion} />)}</div> : <p className="encounter-history-empty">No signed encounters in this Practice and site.</p>}
      </div>
      {!drafts.length && !signed.length ? <p className="encounter-history-note">Chart review has not created an encounter.</p> : null}
    </>}
    {printEncounter ? <EncounterPrintDocuments scope={scope} encounter={printEncounter} onClose={() => setPrintEncounter(null)} restoreFocus={() => printTrigger.current?.focus()} /> : null}
    <ConfirmationDialog open={deletionOpen} title="Delete draft encounter?" description={deletion ? `${deletion.encounter.patient.displayName} (${deletion.encounter.patient.recordNumber}) · ${deletion.encounter.release.definition.title}, ${encounterDate(deletion.encounter.clinicalTime)}. This removes the draft from the chart and active work. Its saved history and audit record remain retained.` : ''} cancelLabel="Keep draft" confirmLabel="Delete draft" pendingLabel="Deleting…" destructive pending={deletionPending} error={deletionError} onCancel={() => { setDeletionOpen(false); setDeletionError(''); if (!deletionUncertain) setDeletion(null); else void queryClient.invalidateQueries({ queryKey: [...clinicalKey(scope, patientId), 'history'] }) }} onConfirm={() => void confirmDeletion()} />
  </section>
}
