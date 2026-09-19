import { describe,expect,it } from 'vitest'
import { detailDraft,editIdentifierModes,identifierChanges,newIdentifierModes,patientDetailSchema,registrationData } from './patient-api'
import { emptyDraft } from './patient-form-model'
import { createPatientDraftSchema } from './patient-form-schema'
describe('saved patient registration mapping',()=>{
  it('sends unknown dates without fabricated values and keeps all six sections',()=>{
    const draft={...emptyDraft,firstName:'Synthetic',birthAccuracy:'unknown' as const,birthDate:'1990-01-01',street:'Supplied street',emergencyName:'Synthetic contact',otherIdIssuer:'Synthetic hospital',pin:'123456789012',changeNote:'Correction'}
    const result=registrationData(draft)
    expect(result.birthDate).toBeUndefined();expect(result.street).toBe(draft.street);expect(result.emergencyName).toBe(draft.emergencyName);expect(result.otherIdIssuer).toBe(draft.otherIdIssuer)
    expect(result).not.toHaveProperty('pin');expect(result).not.toHaveProperty('changeNote')
  })
  it('uses explicit unchanged, replace and remove operations without submitting masked text',()=>{
    const draft={...emptyDraft,pinUnavailable:false,philhealthRole:'Dependent' as const,pin:'123456789012',principalPin:'987654321012',otherIdValue:'Synthetic-001'}
    expect(identifierChanges(draft,editIdentifierModes)).toEqual({pin:{operation:'UNCHANGED'},principalPin:{operation:'UNCHANGED'},otherIdValue:{operation:'UNCHANGED'}})
    expect(identifierChanges(draft,newIdentifierModes).pin).toEqual({operation:'REPLACE',value:'123456789012'})
    expect(identifierChanges({...draft,pinUnavailable:true,philhealthRole:'Member'},editIdentifierModes)).toMatchObject({pin:{operation:'REMOVE'},principalPin:{operation:'REMOVE'}})
  })
  it('loads masked identifiers only as metadata and retains an unsplit legacy name',()=>{
    const detail=patientDetailSchema.parse({patient:{patientId:'9fd53af9-59e9-306d-b937-5aebb5b0c4db',recordNumber:'SYN-1',displayName:'Unsplit Original',birthDate:null,identityStatus:'PROVISIONAL',version:0},data:{...registrationData({...emptyDraft,birthAccuracy:'unknown'}),birthDate:null},identifiers:{pin:'•••• 9012',principalPin:'',otherIdValue:'',verification:'NOT_VERIFIED'},originalDisplayName:'Unsplit Original',canEdit:true})
    const draft=detailDraft(detail)
    expect(draft.pin).toBe('');expect(draft.firstName).toBe('');expect(draft.lastName).toBe('')
    expect(createPatientDraftSchema(detail.originalDisplayName!).safeParse(draft).success).toBe(true)
    expect(createPatientDraftSchema().safeParse(draft).success).toBe(false)
  })
})
