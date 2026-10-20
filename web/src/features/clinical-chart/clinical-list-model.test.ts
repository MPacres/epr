import { describe, expect, it, vi } from 'vitest'
import { allergyDraftSchema, commandFor, draftFor, medicationDraftSchema, problemDraftSchema, type AllergyDraft } from './clinical-list-model'
import type { Allergy, Problem } from './clinical-list-api'

describe('clinical list command mapping', () => {
  it('creates a coded allergy without inventing a reviewed absence', () => {
    const draft = allergyDraftSchema.parse({
      ...draftFor('ALLERGY'), substanceDisplay: 'Amoxicillin', codeSystem: 'urn:epr:synthetic:allergen', codeValue: 'amoxicillin', codeVersion: '2026-10', category: 'MEDICATION', reactionType: 'ALLERGY', verificationStatus: 'CONFIRMED', criticality: 'HIGH', onset: '', onsetPrecision: 'UNKNOWN', lastOccurrence: '', informationSource: 'Patient interview', asserter: 'Patient', notes: '', reactions: [{ reactionId: '', manifestation: 'Rash', severity: 'MODERATE', onset: '', exposureRoute: '' }], correctionReason: '',
    })
    const command = commandFor('ALLERGY', draft, '1a0a5c84-2dfa-4f3e-bca1-34738628835f')
    expect(command).toMatchObject({ id: '1a0a5c84-2dfa-4f3e-bca1-34738628835f', data: { substanceDisplay: 'Amoxicillin', code: { code: 'amoxicillin' }, reactions: [{ manifestation: 'Rash' }] } })
    expect(command).not.toHaveProperty('outcome')
  })

  it('creates a reaction identity when randomUUID is unavailable', () => {
    let next = 0
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => {
        for (let index = 0; index < bytes.length; index++) bytes[index] = next++
        return bytes
      },
    })

    try {
      const draft = allergyDraftSchema.parse({
        ...draftFor('ALLERGY'),
        substanceDisplay: 'Shellfish',
        informationSource: 'Patient interview',
        reactions: [{ reactionId: '', manifestation: 'Hives', severity: 'UNKNOWN', onset: '', exposureRoute: '' }],
      })

      expect(commandFor('ALLERGY', draft, '1a0a5c84-2dfa-4f3e-bca1-34738628835f')).toMatchObject({
        data: { reactions: [{ reactionId: '00010203-0405-4607-8809-0a0b0c0d0e0f' }] },
      })
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('requires dose and unit together for reported medication use', () => {
    const draft = { ...draftFor('MEDICATION_USE'), medicationDisplay: 'Losartan', dose: '50', informationSource: 'Patient interview' }
    expect(medicationDraftSchema.safeParse(draft).success).toBe(false)
    expect(medicationDraftSchema.safeParse({ ...draft, doseUnit: 'mg' }).success).toBe(true)
  })

  it('uses the current version and correction reason for a problem revision', () => {
    const record: Problem = {
      id: '1a0a5c84-2dfa-4f3e-bca1-34738628835f', patientId: '86b2579c-33de-4596-b1a5-6b55263ce03c', version: 4, clinicalStatus: 'ACTIVE', priority: 2, createdBy: 'e1251ba1-76f1-4e79-a332-047642dcfdfc', createdAt: '2026-10-08T01:00:00Z', revisions: [], encounterIds: [], duplicateCandidate: false,
      data: { problemDisplay: 'Asthma', code: null, verificationStatus: 'CONFIRMED', onset: null, onsetPrecision: 'UNKNOWN', abatement: null, abatementPrecision: 'UNKNOWN', severity: null, informationSource: 'Prior chart', notes: null, clinicalTime: null, encounterId: null },
    }
    const draft = problemDraftSchema.parse({ ...draftFor('PROBLEM', record), severity: 'Moderate', correctionReason: 'Patient clarified severity' })
    expect(commandFor('PROBLEM', draft, record.id, record)).toMatchObject({ expectedVersion: 4, reason: 'Patient clarified severity', priority: 2, data: { severity: 'Moderate' } })
  })

  it('retains an existing reaction identity while appending an allergy correction', () => {
    const record: Allergy = {
      id: '1a0a5c84-2dfa-4f3e-bca1-34738628835f', patientId: '86b2579c-33de-4596-b1a5-6b55263ce03c', version: 1, clinicalStatus: 'ACTIVE', createdBy: 'e1251ba1-76f1-4e79-a332-047642dcfdfc', createdAt: '2026-10-08T01:00:00Z', revisions: [], duplicateCandidate: false,
      data: { substanceDisplay: 'Penicillin', code: null, category: 'MEDICATION', reactionType: 'ALLERGY', verificationStatus: 'CONFIRMED', criticality: 'HIGH', onset: null, onsetPrecision: 'UNKNOWN', lastOccurrence: null, informationSource: 'Patient', asserter: 'Patient', notes: null, clinicalTime: null, encounterId: null, reactions: [{ reactionId: 'f9859851-94a2-4239-916b-59348623f744', manifestation: 'Rash', severity: 'MILD', onset: null, exposureRoute: null }] },
    }
    const existingDraft = draftFor('ALLERGY', record) as AllergyDraft
    const draft = allergyDraftSchema.parse({ ...existingDraft, reactions: [{ ...existingDraft.reactions[0], severity: 'MODERATE' }], correctionReason: 'Severity clarified' })
    expect(commandFor('ALLERGY', draft, record.id, record)).toMatchObject({ expectedVersion: 1, data: { clinicalTime: null, reactions: [{ reactionId: 'f9859851-94a2-4239-916b-59348623f744', severity: 'MODERATE' }] } })
  })

  it('retains every recorded reaction and its event detail during a correction', () => {
    const record: Allergy = {
      id: '1a0a5c84-2dfa-4f3e-bca1-34738628835f', patientId: '86b2579c-33de-4596-b1a5-6b55263ce03c', version: 2, clinicalStatus: 'ACTIVE', createdBy: 'e1251ba1-76f1-4e79-a332-047642dcfdfc', createdAt: '2026-10-08T01:00:00Z', revisions: [], duplicateCandidate: false,
      data: { substanceDisplay: 'Shellfish', code: null, category: 'FOOD', reactionType: 'ALLERGY', verificationStatus: 'UNCONFIRMED', criticality: 'UNABLE_TO_ASSESS', onset: '2020', onsetPrecision: 'YEAR', lastOccurrence: null, informationSource: 'Patient interview', asserter: 'Patient', notes: null, clinicalTime: '2026-10-08T01:00:00Z', encounterId: null, reactions: [
        { reactionId: 'f9859851-94a2-4239-916b-59348623f744', manifestation: 'Hives', severity: 'MODERATE', onset: '2024-05', exposureRoute: 'Oral' },
        { reactionId: '0bd4f896-7d58-4ebc-bab9-a0d97214d374', manifestation: 'Wheeze', severity: 'SEVERE', onset: '2024-05', exposureRoute: 'Oral' },
      ] },
    }
    const draft = allergyDraftSchema.parse({ ...draftFor('ALLERGY', record), correctionReason: 'Source confirmed both reactions' })
    expect(commandFor('ALLERGY', draft, record.id, record)).toMatchObject({ expectedVersion: 2, data: { clinicalTime: '2026-10-08T01:00:00Z', reactions: [
      { reactionId: 'f9859851-94a2-4239-916b-59348623f744', manifestation: 'Hives', onset: '2024-05', exposureRoute: 'Oral' },
      { reactionId: '0bd4f896-7d58-4ebc-bab9-a0d97214d374', manifestation: 'Wheeze', onset: '2024-05', exposureRoute: 'Oral' },
    ] } })
  })

  it('requires date precision only when an allergy onset is recorded', () => {
    const base = { ...(draftFor('ALLERGY') as AllergyDraft), substanceDisplay: 'Penicillin', informationSource: 'Patient interview' }
    expect(allergyDraftSchema.safeParse({ ...base, onset: '2020', onsetPrecision: 'UNKNOWN' }).success).toBe(false)
    expect(allergyDraftSchema.safeParse({ ...base, onset: '2020', onsetPrecision: 'YEAR' }).success).toBe(true)
    expect(allergyDraftSchema.safeParse({ ...base, onset: '', onsetPrecision: 'YEAR' }).success).toBe(false)
  })
})
