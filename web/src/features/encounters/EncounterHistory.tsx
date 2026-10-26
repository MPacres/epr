import { useRef, useState } from 'react'
import { ArrowRight, FileCheck2, FilePenLine, Printer, RotateCw } from 'lucide-react'
import { Button } from '../../components/ui/button'
import type { Encounter, Scope } from './services/encounter-api'
import { groupEncounterHistory } from './encounter-history-model'
import { EncounterPrintDocuments } from './EncounterPrintDocuments'

function encounterDate(value: string) {
  return new Intl.DateTimeFormat('en-PH', {
    timeZone: 'Asia/Manila', day: 'numeric', month: 'short', year: 'numeric',
    hour: 'numeric', minute: '2-digit', hour12: true,
  }).format(new Date(value)) + ' PHT'
}

function EncounterRow({ encounter, onOpen, onPrint }: { encounter: Encounter; onOpen: (id: string) => void; onPrint: (encounter: Encounter, trigger: HTMLButtonElement) => void }) {
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
    <span className="encounter-history-actions">{signed ? <Button variant="outline" onClick={event => onPrint(encounter, event.currentTarget)}><Printer aria-hidden="true" />Print documents</Button> : null}<Button variant="ghost" onClick={() => onOpen(encounter.encounterId)}>{signed ? 'View encounter' : 'Continue draft'}<ArrowRight aria-hidden="true" /></Button></span>
  </div>
}

export function EncounterHistory({ scope, encounters, pending, error, onRetry, onOpen }: {
  scope: Scope
  encounters?: Encounter[]
  pending: boolean
  error?: Error | null
  onRetry: () => void
  onOpen: (id: string) => void
}) {
  const { drafts, signed } = groupEncounterHistory(encounters ?? [])
  const [printEncounter, setPrintEncounter] = useState<Encounter | null>(null)
  const printTrigger = useRef<HTMLButtonElement | null>(null)
  function openPrint(encounter: Encounter, trigger: HTMLButtonElement) { printTrigger.current = trigger; setPrintEncounter(encounter) }
  return <section className="encounter-history" aria-labelledby="chart-history-title">
    <header className="encounter-history-heading"><div><h2 id="chart-history-title">Encounters</h2><p>Visit notes for {scope.practiceName} · {scope.siteName}</p></div></header>
    {pending ? <div className="encounter-history-message" role="status">Loading encounter history…</div> : error ? <div className="clinical-error" role="alert"><span>Encounter history could not be loaded. {error.message}</span><Button variant="outline" onClick={onRetry}><RotateCw aria-hidden="true" />Retry</Button></div> : <>
      <div className="encounter-history-group"><div className="encounter-history-group-heading"><h3>Drafts & unfinished notes <span>{drafts.length}</span></h3><p>Continue documentation or review a note before signing.</p></div>
        {drafts.length ? <div className="encounter-history-list">{drafts.map(item => <EncounterRow key={item.encounterId} encounter={item} onOpen={onOpen} onPrint={openPrint} />)}</div> : <p className="encounter-history-empty">No unsigned encounter notes in this Practice and site.</p>}
      </div>
      <div className="encounter-history-group"><div className="encounter-history-group-heading"><h3>Signed encounters <span>{signed.length}</span></h3><p>Signed records remain available to view with their original release.</p></div>
        {signed.length ? <div className="encounter-history-list">{signed.map(item => <EncounterRow key={item.encounterId} encounter={item} onOpen={onOpen} onPrint={openPrint} />)}</div> : <p className="encounter-history-empty">No signed encounters in this Practice and site.</p>}
      </div>
      {!drafts.length && !signed.length ? <p className="encounter-history-note">Chart review has not created an encounter.</p> : null}
    </>}
    {printEncounter ? <EncounterPrintDocuments scope={scope} encounter={printEncounter} onClose={() => setPrintEncounter(null)} restoreFocus={() => printTrigger.current?.focus()} /> : null}
  </section>
}
