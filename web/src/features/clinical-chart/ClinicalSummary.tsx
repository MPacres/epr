import { Activity, CircleAlert, ClipboardPlus, Pill, RotateCw, ShieldAlert } from 'lucide-react'
import { Button } from '../../components/ui/button'
import type { ClinicalListKind, ClinicalSummaryData, ListSummary } from './clinical-list-api'

const copy = {
  ALLERGY: { title: 'Allergies & adverse reactions', description: 'Allergies, intolerances, criticality, reactions, and their source evidence.', icon: ShieldAlert, tone: 'warning' },
  MEDICATION_USE: { title: 'Current medications', description: 'Patient-reported medication use, kept separate from issued prescriptions.', icon: Pill, tone: 'blue' },
  PROBLEM: { title: 'Problems & diagnoses', description: 'Ongoing problems, kept separate from diagnoses recorded for a single encounter.', icon: ClipboardPlus, tone: 'violet' },
} as const
const stateCopy = { UNAVAILABLE: 'Unavailable', NOT_REVIEWED: 'Not reviewed', EMPTY_REVIEWED: 'Reviewed · none known', AVAILABLE: 'Current', STALE: 'Review due' } as const

function SummaryCard({ item, kind, primary = false, onManage }: { item: ListSummary; kind: ClinicalListKind; primary?: boolean; onManage: (kind: ClinicalListKind) => void }) {
  const details = copy[kind], Icon = details.icon
  return <article className={primary ? 'clinical-summary-allergies' : 'clinical-summary-card'} aria-labelledby={`${kind.toLowerCase()}-summary-title`}>
    {primary ? <div className={`clinical-summary-icon ${details.tone}`}><Icon aria-hidden="true" /></div> : null}
    <div className={primary ? 'clinical-summary-copy' : 'clinical-summary-card-body'}>
      <div className={primary ? 'clinical-summary-title-row' : 'clinical-summary-card-heading'}>
        {!primary ? <div className={`clinical-summary-icon ${details.tone}`}><Icon aria-hidden="true" /></div> : <h3 id={`${kind.toLowerCase()}-summary-title`}>{details.title}</h3>}
        <span className={`clinical-state-badge ${item.state === 'AVAILABLE' || item.state === 'EMPTY_REVIEWED' ? 'success' : item.state === 'STALE' || item.state === 'NOT_REVIEWED' ? 'warning' : 'neutral'}`}><CircleAlert aria-hidden="true" />{stateCopy[item.state]}</span>
      </div>
      {!primary ? <h3 id={`${kind.toLowerCase()}-summary-title`}>{details.title}</h3> : null}
      <p>{item.actions.read ? details.description : 'This source is not available in your current Practice, site, and role.'}</p>
      {item.actions.read ? <div className={primary ? 'clinical-summary-facts' : 'clinical-summary-preview'}>{item.preview.length ? item.preview.map(record => <span key={record.id}>{record.display}</span>) : <span>{item.state === 'EMPTY_REVIEWED' ? 'No known items recorded at the last review.' : 'No active entries recorded.'}</span>}</div> : null}
      <div className="clinical-summary-card-footer"><strong>{item.actions.read ? `${item.activeCount} active` : 'Source unavailable'}</strong>{item.actions.read ? <Button variant="outline" size="sm" onClick={() => onManage(kind)}>Manage list</Button> : null}</div>
    </div>
  </article>
}

export function ClinicalSummary({ practiceName, siteName, data, pending, error, onRetry, onManage }: { practiceName: string; siteName: string; data?: ClinicalSummaryData; pending: boolean; error?: Error | null; onRetry: () => void; onManage: (kind: ClinicalListKind) => void }) {
  return (
    <section id="clinical-overview" className="clinical-summary" aria-labelledby="clinical-summary-title">
      <header className="clinical-summary-heading">
        <div>
          <h2 id="clinical-summary-title" tabIndex={-1}>Clinical overview</h2>
          <p>Longitudinal sources for {practiceName} · {siteName}</p>
        </div>
        <span className="clinical-summary-state"><Activity aria-hidden="true" />Connected patient chart</span>
      </header>

      {pending ? <div className="clinical-summary-loading" role="status">Loading allergies, medications, and problems…</div> : error ? <div className="clinical-error" role="alert"><span>{error.message}</span><Button variant="outline" onClick={onRetry}><RotateCw aria-hidden="true" />Retry clinical lists</Button></div> : data ? <><SummaryCard item={data.allergies} kind="ALLERGY" primary onManage={onManage} /><div className="clinical-summary-grid"><SummaryCard item={data.medications} kind="MEDICATION_USE" onManage={onManage} /><SummaryCard item={data.problems} kind="PROBLEM" onManage={onManage} /></div><p className="clinical-summary-boundary" role="note"><ShieldAlert aria-hidden="true" />Chart review and list maintenance do not create an encounter. Confirmed absence is shown only after an explicit review.</p></> : null}
    </section>
  )
}
