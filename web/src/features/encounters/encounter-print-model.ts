import { atSite, type Scope } from './services/encounter-api'
import { sourcePath, type Item, type SourceRecord } from '../interventions/services/intervention-api'

export type PrintDocument = { id: string; type: 'note' | 'intervention'; label: string; detail: string;
  sourceKind?: Exclude<Item['summary']['kind'], 'FOLLOW_UP'>; sourceId?: string; artifactId?: string } &
  ({ path: string; groupId?: never } | { groupId: string; path?: never })

export function interventionDocuments(scope: Scope, patientId: string, items: Item[]): PrintDocument[] {
  return items.flatMap(({ summary }) => {
    const handoffState = summary.kind === 'PRESCRIPTION' ? 'ISSUED' : summary.kind === 'REFERRAL' ? 'RELEASED' : 'AUTHORIZED'
    if (!summary.artifactId || summary.kind === 'FOLLOW_UP' || summary.kind === 'LAB_ORDER' || summary.clinicalState !== handoffState) return []
    const label = summary.kind === 'PRESCRIPTION' ? 'Medication prescription / refill' : summary.kind === 'IMAGING_ORDER' ? 'Imaging request form' : 'Referral letter'
    return [{ id: `${summary.kind}-${summary.sourceId}`, type: 'intervention' as const, label, detail: `${summary.title} · Ready to print`,
      sourceKind: summary.kind, sourceId: summary.sourceId, artifactId: summary.artifactId,
      path: atSite(scope, `${sourcePath(patientId, summary.kind, summary.sourceId)}/artifact`) }]
  })
}

export function groupedLabDocuments(items: Item[]): PrintDocument[] {
  const groups = new Map<string, Item[]>()
  for (const item of items) {
    if (item.summary.kind !== 'LAB_ORDER' || !item.summary.groupId) continue
    const members = groups.get(item.summary.groupId) ?? []
    members.push(item)
    groups.set(item.summary.groupId, members)
  }
  return [...groups].flatMap(([groupId, members]) => members.every(item => item.summary.clinicalState === 'AUTHORIZED' && !!item.summary.artifactId)
    ? [{ id: `lab-group-${groupId}`, type: 'intervention' as const, label: 'Lab Order Form', detail: `${members.length} ${members.length === 1 ? 'test' : 'tests'} · One form`, groupId }]
    : [])
}

export function isCurrentHandoffSource(document: PrintDocument, source: SourceRecord & { artifactId: string | null },
                                       patientId: string, encounterId: string, siteId: string): boolean {
  if (!document.sourceKind || !document.sourceId || !document.artifactId) return false
  const activeState = document.sourceKind === 'PRESCRIPTION' ? 'ISSUED' : document.sourceKind === 'REFERRAL' ? 'RELEASED' : 'AUTHORIZED'
  return source.id === document.sourceId && source.patientId === patientId && source.encounterId === encounterId &&
    source.siteId === siteId && source.state === activeState && source.artifactId === document.artifactId
}
