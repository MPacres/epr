import { describe, expect, it } from 'vitest'
import { interventionDocuments } from './encounter-print-model'
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
      item('FOLLOW_UP', 'OPEN', artifactId),
    ])
    expect(documents.map(document => document.label)).toEqual(['Prescription / refill', 'Laboratory order', 'Imaging order', 'Referral'])
    expect(documents.every(document => document.path.includes(`/practices/${scope.practiceId}/patients/${patientId}/`))).toBe(true)
    expect(documents.every(document => document.path.endsWith(`/artifact?siteId=${scope.siteId}`))).toBe(true)
  })
})
