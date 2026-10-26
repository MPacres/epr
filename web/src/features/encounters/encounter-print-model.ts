import { atSite, type Scope } from './services/encounter-api'
import { sourcePath, type Item } from '../interventions/services/intervention-api'

export type PrintDocument = { id: string; type: 'note' | 'intervention'; label: string; detail: string; path: string }

export function interventionDocuments(scope: Scope, patientId: string, items: Item[]): PrintDocument[] {
  return items.flatMap(({ summary }) => {
    if (!summary.artifactId || summary.kind === 'FOLLOW_UP' || summary.clinicalState === 'DRAFT' || summary.clinicalState === 'ABANDONED') return []
    const label = summary.kind === 'PRESCRIPTION' ? 'Prescription / refill' : summary.kind === 'LAB_ORDER' ? 'Laboratory order' : summary.kind === 'IMAGING_ORDER' ? 'Imaging order' : 'Referral'
    return [{ id: `${summary.kind}-${summary.sourceId}`, type: 'intervention' as const, label, detail: `${summary.title} · ${summary.clinicalState.toLowerCase().replaceAll('_', ' ')}`, path: atSite(scope, `${sourcePath(patientId, summary.kind, summary.sourceId)}/artifact`) }]
  })
}
