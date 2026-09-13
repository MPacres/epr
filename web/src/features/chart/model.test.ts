import { describe, expect, it } from 'vitest'
import { directoryPatients } from '../patients/demo-data'
import { chartEvents, filterEvents, findChartPatient, findEncounter, hasClinicalFixture, initialNote, noteChanged, patientEncounters } from './model'
const maria = directoryPatients.find(patient => patient.id === 'patient-maria')!
describe('Practice-scoped clinical demo records', () => {
  it('requires both a permitted practice and a matching patient identity', () => {
    expect(findChartPatient(directoryPatients, ['practice-a'], 'practice-a', maria.id)).toEqual(maria)
    expect(findChartPatient(directoryPatients, ['practice-a'], 'practice-b', 'demo-maria-b')).toBeUndefined()
    expect(findChartPatient(directoryPatients, ['practice-a', 'practice-b'], 'practice-b', maria.id)).toBeUndefined()
  })
  it('does not share clinical data for matching demographics in another practice', () => {
    const other = directoryPatients.find(patient => patient.id === 'demo-maria-b')!
    expect(other.name).toBe(maria.name)
    expect(hasClinicalFixture(other)).toBe(false)
    expect(patientEncounters(other)).toEqual([])
    expect(chartEvents(other)).toEqual([])
    expect(findEncounter(other, 'EN-DEMO-006')).toBeUndefined()
  })
  it('rejects a mismatched encounter and preserves independent care and documentation states', () => {
    expect(findEncounter(maria, 'missing')).toBeUndefined()
    expect(findEncounter(maria, 'EN-DEMO-006')).toMatchObject({care: 'Finished', documentation: 'Draft'})
    expect(findEncounter(maria, 'EN-DEMO-005')).toMatchObject({care: 'Finished', documentation: 'Signed', template: 'General outpatient · v1.1'})
  })
  it('leaves unassessed fields and visit diagnoses empty without copying ongoing problems', () => {
    const note = initialNote(findEncounter(maria, 'EN-DEMO-006')!)
    expect(note).toMatchObject({history:'', examination:'', assessment:'', plan:'', disposition:'', followup:'', precautions:'', diagnoses:[]})
    const changed = {...note, examination:'Synthetic assessed finding'}
    expect(noteChanged(changed, note)).toBe(true)
    expect(noteChanged(initialNote(findEncounter(maria, 'EN-DEMO-006')!), note)).toBe(false)
    expect(note.examination).toBe('')
  })
  it('filters activity without acknowledging a result or changing source records', () => {
    const events = chartEvents(maria)
    expect(filterEvents(events, 'uploaded clinician', 'Results').map(item=>item.id)).toEqual(['result-maria'])
    expect(filterEvents(events, 'no matching record', 'All')).toEqual([])
    expect(events[0].detail).toContain('Awaiting clinician review')
  })
})
