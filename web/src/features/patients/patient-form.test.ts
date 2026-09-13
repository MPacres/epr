import { describe, expect, it } from 'vitest'
import { patientDraftSchema } from './patient-form-schema'
import { changedFields, changeValue, duplicateCandidates, effectiveDraft, emptyDraft, seedPatientRecord, type PatientDraft } from './patient-form-model'
import { PatientPreviewRepository, duplicateReviewKey, type DemoSaveCommand } from './preview-repository'
import { directoryPatients } from './demo-data'
const valid: PatientDraft = { ...emptyDraft, firstName: 'Isabel', lastName: 'Cruz', birthDate: '1994-04-18' }
const command = (draft = valid): DemoSaveCommand => ({ practiceId:'practice-a', patientId:'new-test-patient', expectedVersion:null, idempotencyKey:'request-1', draft, duplicateReview:null })
describe('patient form validation and identity', () => {
  it('accepts minimum registration without optional phone, address, PIN or other IDs', () => { expect(patientDraftSchema.safeParse(valid).success).toBe(true) })
  it('supports single-name provisional identities and an unknown birth date without invented values', () => {
    const draft = {...valid,firstName:'Alex',lastName:'',provisional:true,birthAccuracy:'unknown' as const,birthDate:''}
    expect(patientDraftSchema.safeParse(draft).success).toBe(true)
    expect(patientDraftSchema.safeParse({...draft,firstName:''}).success).toBe(false)
  })
  it('validates exact/estimated dates, rejects impossible and future dates, and preserves original input', () => {
    for (const birthDate of ['', '2026-02-30', '2999-01-01']) expect(patientDraftSchema.safeParse({...valid,birthDate}).success).toBe(false)
    expect(patientDraftSchema.safeParse({...valid,birthAccuracy:'estimated'}).success).toBe(true)
    expect(patientDraftSchema.parse({...valid,firstName:' Isabel '}).firstName).toBe(' Isabel ')
  })
  it('allows unavailable PINs and validates entered identifiers without asserting eligibility', () => {
    expect(patientDraftSchema.safeParse({...valid,pinUnavailable:false,pin:'bad-pin'}).success).toBe(false)
    expect(patientDraftSchema.safeParse({...valid,pinUnavailable:true,pin:'bad-pin'}).success).toBe(true)
    expect(patientDraftSchema.safeParse({...valid,pinUnavailable:false,pin:'DEMO-PIN-4821'}).success).toBe(true)
    expect(patientDraftSchema.safeParse({...valid,philhealthRole:'Dependent',principalPin:'bad'}).success).toBe(false)
    expect(patientDraftSchema.safeParse({...valid,philhealthRole:'Dependent'}).success).toBe(true)
  })
  it('validates optional contacts and requires provenance for other supplied IDs', () => {
    for(const patch of [{email:'not-email'},{phone:'letters'},{otherIdValue:'DEMO-EXTERNAL-ID'}]) expect(patientDraftSchema.safeParse({...valid,...patch}).success).toBe(false)
    expect(patientDraftSchema.safeParse({...valid,otherIdType:'External MRN',otherIdIssuer:'Demo clinic',otherIdValue:'DEMO-EXTERNAL-ID'}).success).toBe(true)
  })
  it('keeps disabled draft values until save, and excludes inapplicable values from the saved payload', () => {
    const draft = {...valid,birthAccuracy:'unknown' as const,pinUnavailable:true,pin:'DEMO-PIN-4821',principalName:'Principal',principalPin:'DEMO-PIN-4822'}
    expect(effectiveDraft(draft)).toMatchObject({birthDate:'',pin:'',principalName:'',principalPin:''})
    expect(draft.pin).toBe('DEMO-PIN-4821')
  })
  it('scopes duplicate candidates to a practice and excludes the current record on edit', () => {
    const draft = {...valid,firstName:'Maria',lastName:'Dela Cruz',birthDate:'1981-06-14'}
    expect(duplicateCandidates(directoryPatients,draft,'practice-a').map(match=>match.patient.mrn)).toEqual(['HA-02481'])
    expect(duplicateCandidates(directoryPatients,draft,'practice-a','patient-maria')).toEqual([])
    expect(duplicateCandidates(directoryPatients,draft,'practice-b').map(match=>match.patient.mrn)).toEqual(['CB-01024'])
  })
  it('does not count untouched fields or reveal secret values in the change summary', () => {
    const maria=seedPatientRecord(directoryPatients[0])
    expect(changedFields(maria.draft,{...maria.draft,city:'Pasig City'})).toEqual(['city'])
    expect(changeValue('pin','DEMO-PIN-4821')).not.toContain('4821')
  })
})
describe('in-memory fixture save behavior (not server authorization)', () => {
  it('retries/concurrent saves create one patient and one history entry', async () => {
    const repo = new PatientPreviewRepository(['practice-a'])
    const [a,b] = await Promise.all([repo.save(command()),repo.save(command())])
    expect(a).toBe(b)
    expect(repo.snapshot().filter(record=>record.id===a.id)).toHaveLength(1)
    expect(repo.historyFor('practice-a',a.id)).toHaveLength(1)
    expect(a.lastVisit).toBeNull()
    expect(a.mrn).toMatch(/^DEMO-HA-/)
  })
  it('rejects unavailable scopes and wrong-practice record access', async () => {
    const repo=new PatientPreviewRepository(['practice-a'])
    await expect(repo.save({...command(),practiceId:'practice-b'})).rejects.toMatchObject({code:'forbidden'})
    await expect(repo.save({...command(),patientId:'patient-sofia',expectedVersion:1})).rejects.toMatchObject({code:'missing'})
    expect(repo.historyFor('practice-b','patient-sofia')).toEqual([])
  })
  it('retains original snapshots and identifiers, rejecting stale edits instead of overwriting them', async () => {
    const repo=new PatientPreviewRepository(['practice-a'])
    const original=repo.snapshot()[0]
    const edit={...command({...original.draft,city:'Pasig City',changeNote:'Patient reported'}),patientId:original.id,expectedVersion:original.version}
    const saved=await repo.save(edit)
    expect(saved.mrn).toBe(original.mrn)
    expect(saved.city).toBe('Pasig City')
    expect(original.city).toBe('Quezon City')
    expect(repo.historyFor('practice-a',original.id)[0]).toMatchObject({before:original,after:saved,note:'Patient reported'})
    await expect(repo.save({...edit,idempotencyKey:'second-edit'})).rejects.toMatchObject({code:'conflict'})
  })
  it('preserves state after simulated failure or conflict and allows an original-key retry', async () => {
    const repo=new PatientPreviewRepository(['practice-a'])
    const before=repo.snapshot()
    await expect(repo.save(command(),'unavailable')).rejects.toMatchObject({code:'unavailable'})
    await expect(repo.save(command(),'conflict')).rejects.toMatchObject({code:'conflict'})
    expect(repo.snapshot()).toBe(before)
    expect((await repo.save(command())).name).toBe('Isabel Cruz')
  })
  it('requires human duplicate review, invalidates old reviews and never auto-merges', async () => {
    const repo=new PatientPreviewRepository(['practice-a'])
    const maria=repo.snapshot()[0]
    const duplicate={...command(maria.draft),duplicateReview:null}
    await expect(repo.save(duplicate)).rejects.toMatchObject({code:'duplicate-review'})
    const review=duplicateReviewKey(repo.snapshot(),maria.draft,'practice-a')
    expect(review).not.toBeNull()
    expect(duplicateReviewKey(repo.snapshot(),{...maria.draft,birthDate:'1980-01-01'},'practice-a')).not.toBe(review)
    const saved=await repo.save({...duplicate,duplicateReview:review})
    expect(saved.id).not.toBe(maria.id)
    expect(repo.snapshot().filter(record=>record.practiceId==='practice-a' && record.name==='Maria Dela Cruz')).toHaveLength(2)
  })
  it('starts a fresh fixture workspace with no browser persistence', async () => {
    const repo=new PatientPreviewRepository(['practice-a'])
    await repo.save(command())
    expect(new PatientPreviewRepository(['practice-a']).snapshot().some(record=>record.id==='new-test-patient')).toBe(false)
  })
})
