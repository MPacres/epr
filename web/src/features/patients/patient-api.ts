import { z } from 'zod'
import { atSite, clinicalKey, patientSchema, request, type Scope } from '../encounters/services/encounter-api'
import type * as Wire from '../encounters/services/api.generated'
import { effectiveDraft, emptyDraft, type PatientDraft } from './patient-form-model'
import { patientDraftSchema } from './patient-form-schema'
export const patientDataSchema = z.object(patientDraftSchema.shape).omit({ pin: true, principalPin: true, otherIdValue: true, changeNote: true }).extend({ birthDate: z.string().nullable() })
const metadataSchema = z.object({ pin:z.string(), principalPin:z.string(), otherIdValue:z.string(), verification:z.literal('NOT_VERIFIED') })
export const patientDetailSchema = z.object({ patient:patientSchema, data:patientDataSchema, identifiers:metadataSchema, originalDisplayName:z.string().nullable(), canEdit:z.boolean() })
export type PatientDetail = z.infer<typeof patientDetailSchema>
const directoryItemSchema = z.object({ patient:patientSchema, birthAccuracy:z.enum(['exact','estimated','unknown']), sex:z.string(), city:z.string(), lastVisit:z.string().nullable() })
export type DirectoryItem = z.infer<typeof directoryItemSchema>
const directorySchema = z.object({ items:z.array(directoryItemSchema), total:z.number(), page:z.number(), pageSize:z.number(), visitHistoryState:z.enum(['CURRENT','UNAVAILABLE']), refreshedAt:z.string() })
const duplicateSchema = z.object({ reviewId:z.uuid(), candidates:z.array(z.object({patient:patientSchema,reason:z.string()})), expiresAt:z.string() })
export type DuplicateReview = z.infer<typeof duplicateSchema>
export type SensitiveField = 'pin' | 'principalPin' | 'otherIdValue'
export type IdentifierModes = Record<SensitiveField, 'UNCHANGED' | 'REPLACE' | 'REMOVE'>
export const newIdentifierModes: IdentifierModes = {pin:'REPLACE',principalPin:'REPLACE',otherIdValue:'REPLACE'}
export const editIdentifierModes: IdentifierModes = {pin:'UNCHANGED',principalPin:'UNCHANGED',otherIdValue:'UNCHANGED'}
export const patientKeys = (scope:Scope) => [...clinicalKey(scope), 'registry'] as const
export type DirectoryQuery = { search:string; identity:string; sex:string; sort:string; page:number; recent:boolean; visit?:string }
export const patientApi = {
  refreshVisits:(scope:Scope,signal?:AbortSignal)=>request(atSite(scope,'/patient-directory/visit-summary-refresh'),z.object({refreshed:z.boolean()}),{method:'POST',body:{},signal}),
  detail:(scope:Scope,id:string,signal?:AbortSignal)=>request(atSite(scope,`/patients/${id}`),patientDetailSchema,{signal}),
  directory:(scope:Scope,query:DirectoryQuery,signal?:AbortSignal)=>request(`${atSite(scope,'/patient-directory')}&${new URLSearchParams(Object.entries({...query,pageSize:20}).map(([key,value])=>[key,String(value)]))}`,directorySchema,{signal}),
  check:(scope:Scope,body:Wire.DuplicateCheck)=>request(atSite(scope,'/patients/duplicate-checks'),duplicateSchema,{method:'POST',body}),
  create:(scope:Scope,body:Wire.PatientRegistration,key:string)=>request(atSite(scope,'/patients'),patientDetailSchema,{method:'POST',body,key}),
  edit:(scope:Scope,id:string,body:Wire.PatientEdit,key:string)=>request(atSite(scope,`/patients/${id}`),patientDetailSchema,{method:'PATCH',body,key}),
  open:(scope:Scope,id:string,key:string,duplicateReviewId?:string)=>request(atSite(scope,`/patients/${id}/chart-opens`),z.object({recorded:z.boolean()}),{method:'POST',key,body:{duplicateReviewId}}),
}
export function registrationData(draft:PatientDraft):Wire.PatientData {
  const { pin,principalPin,otherIdValue,changeNote,...data }=effectiveDraft(draft)
  void pin;void principalPin;void otherIdValue;void changeNote
  return {...data,birthDate:data.birthDate || undefined}
}
export function identifierChanges(draft:PatientDraft,modes:IdentifierModes):Wire.IdentifierChanges {
  const operation=(field:SensitiveField):Wire.IdentifierOperation=>{
    if ((field==='pin' && draft.pinUnavailable) || (field==='principalPin' && draft.philhealthRole!=='Dependent') || modes[field]==='REMOVE') return {operation:'REMOVE'}
    if (modes[field]==='UNCHANGED') return {operation:'UNCHANGED'}
    return draft[field].trim() ? {operation:'REPLACE',value:draft[field]} : {operation:'REMOVE'}
  }
  return {pin:operation('pin'),principalPin:operation('principalPin'),otherIdValue:operation('otherIdValue')}
}
export function detailDraft(detail:PatientDetail):PatientDraft { return {...emptyDraft,...detail.data,birthDate:detail.data.birthDate??''} }
