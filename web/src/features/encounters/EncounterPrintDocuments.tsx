import { useEffect, useRef, useState } from 'react'
import { Printer, RotateCw } from 'lucide-react'
import { z } from 'zod'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { createUuid } from '../../lib/create-uuid'
import { atSite, ClinicalRequestError, request, type Encounter, type Scope } from './services/encounter-api'
import { listInterventions, sourcePath, sourceSchema } from '../interventions/services/intervention-api'
import { groupedLabDocuments, interventionDocuments, isCurrentHandoffSource, type PrintDocument } from './encounter-print-model'

const signatureSchema = z.object({ signatureId: z.uuid(), signedAt: z.string(), amendmentOf: z.uuid().nullable() })
export function EncounterPrintDocuments({ scope, encounter, onClose, restoreFocus }: {
  scope: Scope
  encounter: Encounter
  onClose: () => void
  restoreFocus: () => void
}) {
  const [documents, setDocuments] = useState<PrintDocument[]>([])
  const [selected, setSelected] = useState<PrintDocument | null>(null)
  const [showDocuments, setShowDocuments] = useState(true)
  const [html, setHtml] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [documentLoading, setDocumentLoading] = useState(false)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [documentError, setDocumentError] = useState<Error | null>(null)
  const [groupStale, setGroupStale] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const documentAbort = useRef<AbortController | null>(null)
  const groupCommands = useRef(new Map<string, { key: string; documentId: string; path?: string }>())
  const frame = useRef<HTMLIFrameElement>(null)
  const previewHeading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    const abort = new AbortController()
    const patientId = encounter.patientId
    const encounterId = encounter.encounterId
    const base = `/patients/${patientId}/encounters/${encounterId}`
    async function load() {
      const [signatures, first] = await Promise.all([
        request(atSite(scope, `${base}/signatures`), z.array(signatureSchema), { signal: abort.signal }),
        listInterventions(scope, patientId, encounterId, 0, abort.signal),
      ])
      const pageCount = Math.ceil(first.total / first.pageSize)
      const remaining = await Promise.all(Array.from({ length: Math.max(0, pageCount - 1) }, (_, index) => listInterventions(scope, patientId, encounterId, index + 1, abort.signal)))
      const ordered = [...signatures].sort((a, b) => b.signedAt.localeCompare(a.signedAt))
      if (!ordered.length) throw new Error('The signed note could not be found. Refresh this encounter and try again.')
      const notes: PrintDocument[] = ordered.map((signature, index) => ({
        id: signature.signatureId,
        type: 'note',
        label: signature.amendmentOf ? index === 0 ? 'Latest signed amendment' : 'Signed amendment' : ordered.length > 1 ? 'Original signed note' : 'Signed note',
        detail: `Verified archived copy · ${new Intl.DateTimeFormat('en-PH', { timeZone: 'Asia/Manila', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(signature.signedAt))} PHT`,
        path: atSite(scope, `${base}/signatures/${signature.signatureId}/artifact`),
      }))
      const items = [first, ...remaining].flatMap(page => page.items)
      if (!abort.signal.aborted) setDocuments([...notes, ...groupedLabDocuments(items), ...interventionDocuments(scope, patientId, items)])
    }
    void load().catch(value => { if (!abort.signal.aborted) setError(value instanceof Error ? value : new Error('Documents could not be loaded.')) }).finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [scope, encounter, attempt])

  useEffect(() => () => documentAbort.current?.abort(), [])

  useEffect(() => {
    if (selected && !showDocuments) previewHeading.current?.focus()
  }, [selected, showDocuments])

  async function openDocument(document: PrintDocument) {
    documentAbort.current?.abort()
    const abort = new AbortController()
    documentAbort.current = abort
    setSelected(document); setShowDocuments(false); setHtml(null); setReady(false); setDocumentError(null); setGroupStale(false); setDocumentLoading(true)
    try {
      let path: string
      if (document.groupId) {
        const command = groupCommands.current.get(document.groupId) ?? { key: createUuid(), documentId: createUuid() }
        groupCommands.current.set(document.groupId, command)
        if (!command.path) {
          const result = await request(atSite(scope, `${sourcePath(encounter.patientId, 'LAB_ORDER')}/groups/${document.groupId}/documents`),
            z.object({ groupId: z.uuid(), documentId: z.uuid() }),
            { method: 'POST', key: command.key, body: { documentId: command.documentId }, signal: abort.signal })
          if (result.groupId !== document.groupId || result.documentId !== command.documentId) throw new ClinicalRequestError(503, 'GROUP_DOCUMENT_MISMATCH')
          command.path = atSite(scope, `${sourcePath(encounter.patientId, 'LAB_ORDER')}/groups/${result.groupId}/documents/${result.documentId}`)
        }
        if (!command.path) throw new ClinicalRequestError(503, 'GROUP_DOCUMENT_UNAVAILABLE')
        path = command.path
      } else {
        if (!document.path) throw new ClinicalRequestError(503, 'DOCUMENT_PATH_UNAVAILABLE')
        if (document.sourceKind && document.sourceId) {
          const source = await request(atSite(scope, sourcePath(encounter.patientId, document.sourceKind, document.sourceId)),
            sourceSchema.extend({ artifactId: z.uuid().nullable() }), { signal: abort.signal })
          if (!isCurrentHandoffSource(document, source, encounter.patientId, encounter.encounterId, scope.siteId))
            throw new ClinicalRequestError(409, 'HANDOUT_SOURCE_CHANGED')
        }
        path = document.path
      }
      const response = await fetch(path, { credentials: 'include', cache: 'no-store', signal: abort.signal })
      if (!response.ok) throw new ClinicalRequestError(response.status, 'ARCHIVED_ARTIFACT_UNAVAILABLE')
      const bytes = await response.text()
      if (!abort.signal.aborted) setHtml(bytes)
    } catch (value) {
      if (!abort.signal.aborted) {
        if (document.groupId && value instanceof ClinicalRequestError && value.status === 409) {
          groupCommands.current.delete(document.groupId)
          setGroupStale(true)
          setDocumentError(new Error('The laboratory group changed. Close and reopen this panel to refresh the authorized tests.'))
        } else setDocumentError(value instanceof Error ? value : new Error('This document could not be opened.'))
      }
    } finally {
      if (!abort.signal.aborted) setDocumentLoading(false)
    }
  }

  function close() {
    documentAbort.current?.abort()
    onClose()
  }

  const sections = [
    { title: 'Notes', entries: documents.filter(document => document.type === 'note'), empty: 'No signed note available.' },
    { title: 'Prescriptions and refills', entries: documents.filter(document => document.sourceKind === 'PRESCRIPTION'), empty: 'No signed prescription or refill.' },
    { title: 'Laboratory', entries: documents.filter(document => !!document.groupId), empty: 'No signed laboratory order.' },
    { title: 'Imaging', entries: documents.filter(document => document.sourceKind === 'IMAGING_ORDER'), empty: 'No signed imaging request.' },
    { title: 'Referrals', entries: documents.filter(document => document.sourceKind === 'REFERRAL'), empty: 'No referral letter to print.' },
  ]

  return <Sheet open onClose={close} onRestoreFocus={restoreFocus} className="encounter-print-sheet" title="Print encounter documents" description={`${encounter.patient.displayName} · ${encounter.patient.recordNumber} · ${scope.practiceName} · ${scope.siteName}`}>
    <div className="encounter-print">
      <p>Print the note and available patient handouts from this encounter. Laboratory tests print together on one Lab Order Form. Printing does not record delivery or fulfillment.</p>
      {loading ? <p role="status">Loading available documents…</p> : error ? <div className="clinical-error" role="alert"><span>{error.message}</span><Button variant="outline" onClick={() => { setError(null); setLoading(true); setAttempt(value => value + 1) }}><RotateCw aria-hidden="true" />Retry</Button></div> : <div className="encounter-print-layout">
        {selected ? <Button variant="outline" className="encounter-print-chooser-toggle" aria-controls="encounter-print-documents" aria-expanded={showDocuments} onClick={() => setShowDocuments(value => !value)}>{showDocuments ? 'Hide document list' : 'Change document'}</Button> : null}
        <div id="encounter-print-documents" className="encounter-print-list" role="group" aria-label="Available documents" hidden={!showDocuments}>
          {sections.map(section => <section key={section.title} className="encounter-print-section" aria-label={section.title}>
            <h3>{section.title}</h3>
            {section.entries.length ? section.entries.map(document => <Button key={document.id} variant="outline" className={selected?.id === document.id ? 'is-selected' : ''} onClick={() => void openDocument(document)} aria-pressed={selected?.id === document.id}><span><strong>{document.label}</strong><small>{document.detail}</small></span></Button>) : <p>{section.empty}</p>}
          </section>)}
        </div>
        <div className="encounter-print-preview">
          {selected ? <><div className="encounter-print-preview-heading"><h3 ref={previewHeading} tabIndex={-1}>{selected.label}</h3><Button variant="outline" disabled={!ready || !html} onClick={() => frame.current?.contentWindow?.print()}><Printer aria-hidden="true" />{selected.type === 'note' ? 'Print note' : 'Print patient handout'}</Button></div>
            {documentError ? <div className="clinical-error" role="alert"><span>{documentError.message}</span>{!groupStale ? <Button variant="outline" onClick={() => void openDocument(selected)}><RotateCw aria-hidden="true" />Retry document</Button> : null}</div> : null}
            {documentLoading ? <p role="status">Loading verified document…</p> : null}
            {html ? <iframe ref={frame} title={`${selected.label} archived document`} sandbox="allow-same-origin allow-modals" srcDoc={html} onLoad={() => setReady(true)} /> : null}
          </> : <p>Select a document to preview and print.</p>}
        </div>
      </div>}
    </div>
  </Sheet>
}
