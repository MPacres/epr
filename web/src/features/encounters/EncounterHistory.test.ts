import { describe, expect, it } from 'vitest'
import { canDeleteDraft, groupEncounterHistory } from './encounter-history-model'

describe('encounter history groups', () => {
  it('keeps both draft states separate from signed notes and orders each group by service time', () => {
    const encounters = [
      { id: 'signed-old', noteState: 'SIGNED' as const, clinicalTime: '2026-09-18T08:00:00+08:00' },
      { id: 'draft-new', noteState: 'DRAFT' as const, clinicalTime: '2026-09-21T08:00:00+08:00' },
      { id: 'signed-new', noteState: 'SIGNED' as const, clinicalTime: '2026-09-20T08:00:00+08:00' },
      { id: 'ready', noteState: 'READY_TO_SIGN' as const, clinicalTime: '2026-09-19T08:00:00+08:00' },
    ]
    const groups = groupEncounterHistory(encounters)
    expect(groups.drafts.map(item => item.id)).toEqual(['draft-new', 'ready'])
    expect(groups.signed.map(item => item.id)).toEqual(['signed-new', 'signed-old'])
    expect(encounters[0].id).toBe('signed-old')
  })
})

describe('draft deletion eligibility', () => {
  it('shows deletion only for active standalone drafts', () => {
    expect(canDeleteDraft({ noteState: 'DRAFT', careState: 'IN_PROGRESS', queueEntryId: null })).toBe(true)
    expect(canDeleteDraft({ noteState: 'DRAFT', careState: 'PAUSED', queueEntryId: null })).toBe(true)
    expect(canDeleteDraft({ noteState: 'SIGNED', careState: 'FINISHED', queueEntryId: null })).toBe(false)
    expect(canDeleteDraft({ noteState: 'READY_TO_SIGN', careState: 'IN_PROGRESS', queueEntryId: null })).toBe(false)
    expect(canDeleteDraft({ noteState: 'DRAFT', careState: 'FINISHED', queueEntryId: null })).toBe(false)
    expect(canDeleteDraft({ noteState: 'DRAFT', careState: 'IN_PROGRESS', queueEntryId: 'queued' })).toBe(false)
  })
})
