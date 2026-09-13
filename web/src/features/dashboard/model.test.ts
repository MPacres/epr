import { describe, expect, it } from 'vitest'
import { activeSession, inbox, patients, queue, sessions } from './demo-data'
import { distinctInbox, readyEntries, searchPatients } from './model'

describe('dashboard display boundaries', () => {
  it('counts ready patients only in the active practice and session', () => {
    const mixed = [...queue,
      { ...queue[0], id: 'other-session', sessionId: sessions[1].id },
      { ...queue[0], id: 'other-practice', patient: patients[4] },
      { ...queue[0], id: 'completed', state: 'COMPLETED' as const },
    ]
    expect(readyEntries(mixed, activeSession)).toEqual(queue)
    expect(readyEntries(mixed, undefined)).toEqual([])
  })
  it('keeps the active-session count when previewing an upcoming session', () => {
    expect(readyEntries(queue, sessions[1])).toHaveLength(0)
    expect(readyEntries(queue, activeSession)).toHaveLength(4)
  })
  it('deduplicates overlapping inbox filters without merging records across practices', () => {
    expect(distinctInbox([...inbox, ...inbox.filter(item => item.overdue)])).toHaveLength(7)
    expect(distinctInbox([inbox[0], { ...inbox[0], patient: patients[4] }])).toHaveLength(2)
  })
  it('searches physician clinic scopes and excludes other practices, including empty searches', () => {
    expect(searchPatients(patients, [activeSession.practiceId], ' Sofia ')).toEqual([])
    expect(searchPatients(patients, [activeSession.practiceId], '')).toHaveLength(4)
    expect(searchPatients(patients, [activeSession.practiceId], ' ha-02481 ')).toEqual([patients[0]])
    expect(searchPatients(patients, ['unknown-practice'], '')).toEqual([])
    expect(searchPatients(patients, ['practice-a', 'practice-b'], ' Sofia ')).toEqual([patients[4]])
    expect(searchPatients(patients, ['practice-a', 'practice-b'], ' cb-01023 ')).toEqual([patients[4]])
    expect(searchPatients(patients, [], '')).toEqual([])
    const outsideClinic = { ...patients[4], id: 'outside-clinic', practiceId: 'practice-c' }
    expect(searchPatients([...patients, outsideClinic], ['practice-a', 'practice-b'], '')).toEqual(patients)
  })
})
