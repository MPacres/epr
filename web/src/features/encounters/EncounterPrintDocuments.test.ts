import { describe, expect, it } from 'vitest'
import { groupedLabDocuments, interventionDocuments, isCurrentHandoffSource } from './encounter-print-model'
import type { Item } from '../interventions/services/intervention-api'
import type { Scope } from './services/encounter-api'

const scope = { practiceId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', siteId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', practiceName: 'Demo Practice', siteName: 'Demo Site', clinicianName: 'Demo Clinician' } satisfies Scope
const patientId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
function item(kind: Item['summary']['kind'], state: string, artifactId: string | null): Item {
  return { linkId: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', summaryRevision: 0, summary: { kind, sourceId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', sourceVersion: 1, title: 'Synthetic request', details: [], clinicalState: state, deliveryState: 'PENDING', fulfillmentState: 'PENDING', reviewState: 'PENDING', ownerId: 'ffffffff-ffff-4fff-8fff-ffffffffffff', dueDate: null, occurredAt: '2026-10-26T00:00:00Z', artifactId, artifactHash: artifactId ? 'hash' : null } }
}

describe('signed encounter printable documents', () => {
  it('offers only issued archived intervention copies from the selected patient and Practice', () => {
    const artifactId = '11111111-1111-4111-8111-111111111111'
    const documents = interventionDocuments(scope, patientId, [
      item('PRESCRIPTION', 'ISSUED', artifactId),
      item('LAB_ORDER', 'AUTHORIZED', artifactId),
      item('IMAGING_ORDER', 'AUTHORIZED', artifactId),
      item('REFERRAL', 'RELEASED', artifactId),
      item('PRESCRIPTION', 'DRAFT', artifactId),
      item('LAB_ORDER', 'DRAFT', null),
      item('IMAGING_ORDER', 'CANCELLED', artifactId),
      item('PRESCRIPTION', 'SUPERSEDED', artifactId),
      item('FOLLOW_UP', 'OPEN', artifactId),
    ])
    expect(documents.map(document => document.label)).toEqual(['Medication prescription / refill', 'Imaging request form', 'Referral letter'])
    expect(documents.every(document => document.path?.includes(`/practices/${scope.practiceId}/patients/${patientId}/`))).toBe(true)
    expect(documents.every(document => document.path?.endsWith(`/artifact?siteId=${scope.siteId}`))).toBe(true)
    expect(documents.every(document => document.sourceId && document.artifactId === artifactId)).toBe(true)
    expect(interventionDocuments(scope, patientId, [item('LAB_ORDER', 'AUTHORIZED', artifactId)])).toEqual([])
  })
  it('offers one Lab Order Form for a complete authorized batch, including a single-test batch', () => {
    const groupId = '99999999-9999-4999-8999-999999999999'
    const authorized = { ...item('LAB_ORDER', 'AUTHORIZED', '11111111-1111-4111-8111-111111111111'), summary: { ...item('LAB_ORDER', 'AUTHORIZED', '11111111-1111-4111-8111-111111111111').summary, groupId } }
    const second = { ...authorized, summary: { ...authorized.summary, sourceId: '22222222-2222-4222-8222-222222222222' } }
    expect(groupedLabDocuments([authorized, second])).toEqual([{ id: `lab-group-${groupId}`, type: 'intervention', label: 'Lab Order Form', detail: '2 tests · One form', groupId }])
    expect(groupedLabDocuments([authorized])).toEqual([{ id: `lab-group-${groupId}`, type: 'intervention', label: 'Lab Order Form', detail: '1 test · One form', groupId }])
    expect(groupedLabDocuments([authorized, { ...second, summary: { ...second.summary, clinicalState: 'DRAFT' } }])).toEqual([])
  })
  it('rejects a stale, cancelled or retargeted patient handout before loading its archive', () => {
    const document = interventionDocuments(scope, patientId, [item('IMAGING_ORDER', 'AUTHORIZED', '11111111-1111-4111-8111-111111111111')])[0]
    const source = { id: document.sourceId!, patientId, encounterId: '33333333-3333-4333-8333-333333333333', siteId: scope.siteId,
      version: 2, state: 'AUTHORIZED', occurredAt: '2026-10-26T00:00:00Z', data: {}, artifactId: document.artifactId! }
    expect(isCurrentHandoffSource(document, source, patientId, source.encounterId, scope.siteId)).toBe(true)
    expect(isCurrentHandoffSource(document, { ...source, state: 'CANCELLED' }, patientId, source.encounterId, scope.siteId)).toBe(false)
    expect(isCurrentHandoffSource(document, { ...source, patientId: '44444444-4444-4444-8444-444444444444' }, patientId, source.encounterId, scope.siteId)).toBe(false)
    expect(isCurrentHandoffSource(document, { ...source, artifactId: '55555555-5555-4555-8555-555555555555' }, patientId, source.encounterId, scope.siteId)).toBe(false)
  })
})
