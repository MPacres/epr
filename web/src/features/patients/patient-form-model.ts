import type { DirectoryPatient } from './model'
import type { PatientDraft } from './patient-form-schema'
export type { PatientDraft } from './patient-form-schema'

export type FormSection = 'demographics' | 'contact' | 'address' | 'philhealth' | 'representatives' | 'identifiers'
export const emptyDraft: PatientDraft = {
  firstName: '', middleName: '', lastName: '', suffix: '', birthDate: '', birthAccuracy: 'exact', sex: 'Not recorded', provisional: false,
  phone: '', email: '', language: '', communication: 'Not recorded', street: '', barangay: '', city: '', province: '', postalCode: '',
  philhealthRole: 'Unknown', pin: '', pinUnavailable: true, contributor: 'Unknown', informationSource: 'Not recorded',
  principalName: '', principalPin: '', principalRelationship: '', representativeName: '', representativeRelationship: '', representativePhone: '', representativeAuthority: 'Not recorded',
  emergencyName: '', emergencyRelationship: '', emergencyPhone: '', otherIdType: '', otherIdValue: '', otherIdIssuer: '', changeNote: '',
}
export const draftFields: Record<keyof PatientDraft, { label: string; section: FormSection }> = {
  firstName:{label:'First name',section:'demographics'}, middleName:{label:'Middle name',section:'demographics'}, lastName:{label:'Last name',section:'demographics'}, suffix:{label:'Suffix',section:'demographics'}, birthDate:{label:'Date of birth',section:'demographics'}, birthAccuracy:{label:'Birth date accuracy',section:'demographics'}, sex:{label:'Sex',section:'demographics'}, provisional:{label:'Provisional identity',section:'demographics'},
  phone:{label:'Mobile / phone',section:'contact'}, email:{label:'Email',section:'contact'}, language:{label:'Preferred language',section:'contact'}, communication:{label:'Communication preference',section:'contact'},
  street:{label:'Street / house number',section:'address'},barangay:{label:'Barangay',section:'address'},city:{label:'City / municipality',section:'address'},province:{label:'Province / region',section:'address'},postalCode:{label:'Postal code',section:'address'},
  philhealthRole:{label:'Patient role',section:'philhealth'},pin:{label:'PhilHealth PIN',section:'philhealth'},pinUnavailable:{label:'PIN unavailable',section:'philhealth'},contributor:{label:'Contributor category',section:'philhealth'},informationSource:{label:'Information source',section:'philhealth'},principalName:{label:'Principal member name',section:'philhealth'},principalPin:{label:'Principal member PIN',section:'philhealth'},principalRelationship:{label:'Relationship to principal member',section:'philhealth'},
  representativeName:{label:'Representative name',section:'representatives'},representativeRelationship:{label:'Representative relationship',section:'representatives'},representativePhone:{label:'Representative phone',section:'representatives'},representativeAuthority:{label:'Representative authority',section:'representatives'},emergencyName:{label:'Emergency contact name',section:'representatives'},emergencyRelationship:{label:'Emergency contact relationship',section:'representatives'},emergencyPhone:{label:'Emergency contact phone',section:'representatives'},
  otherIdType:{label:'Other identifier type',section:'identifiers'},otherIdValue:{label:'Other identifier value',section:'identifiers'},otherIdIssuer:{label:'Identifier issuer',section:'identifiers'},changeNote:{label:'Change note',section:'identifiers'},
}
export function fullPatientName(draft: PatientDraft) { return [draft.firstName, draft.middleName, draft.lastName, draft.suffix].map(value => value.trim()).filter(Boolean).join(' ') }
// Normalize for display/matching only; retain the entered names in the draft.
const normalized = (value: string) => value.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase().replace(/[^\p{L}\p{N}]/gu, '')
export function duplicateCandidates(records: readonly DirectoryPatient[], draft: PatientDraft, practiceId: string, excludeId?: string) {
  const name = normalized(fullPatientName(draft))
  if (name.length < 2) return []
  return records.filter(record => record.practiceId === practiceId && record.id !== excludeId).flatMap(record => {
    const sameName = normalized(record.name) === name
    const sameBirth = draft.birthAccuracy === 'exact' && !!draft.birthDate && record.birthDate === draft.birthDate
    const sharesName = normalized(record.name).includes(normalized(draft.lastName || draft.firstName))
    return sameName || (sameBirth && sharesName) ? [{ patient: record, reason: sameName && sameBirth ? 'Name and birth date match' : sameName ? 'Same name' : 'Similar name and same birth date' }] : []
  })
}
export function effectiveDraft(draft: PatientDraft): PatientDraft {
  return { ...draft, birthDate: draft.birthAccuracy === 'unknown' ? '' : draft.birthDate,
    pin: draft.pinUnavailable ? '' : draft.pin,
    principalName: draft.philhealthRole === 'Dependent' ? draft.principalName : '',
    principalPin: draft.philhealthRole === 'Dependent' ? draft.principalPin : '',
    principalRelationship: draft.philhealthRole === 'Dependent' ? draft.principalRelationship : '',
  }
}
export function changedFields(before: PatientDraft, after: PatientDraft) {
  const original = effectiveDraft(before), next = effectiveDraft(after)
  return (Object.keys(draftFields) as (keyof PatientDraft)[]).filter(key => key !== 'changeNote' && original[key] !== next[key])
}
export function changeValue(field: keyof PatientDraft, value: PatientDraft[keyof PatientDraft]) {
  if (['pin', 'principalPin', 'otherIdValue'].includes(field)) return value ? 'Recorded · masked' : 'Not recorded'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return value || 'Not recorded'
}
export interface DemoPatientRecord extends DirectoryPatient { draft: PatientDraft; version: number }
export function seedPatientRecord(patient: DirectoryPatient): DemoPatientRecord {
  const [firstName, ...surname] = patient.name.split(' ')
  const maria = patient.id === 'patient-maria' && patient.practiceId === 'practice-a'
  return { ...patient, version: 1, draft: { ...emptyDraft, firstName, lastName: surname.join(' '), birthDate: patient.birthDate ?? '', birthAccuracy: patient.birthDate ? 'exact' : 'unknown', sex: patient.sex, provisional: patient.identity === 'provisional', city: patient.city ?? '',
    ...(maria ? { language: 'Filipino', communication: 'In person' as const, province: 'Metro Manila', philhealthRole: 'Member' as const, pin: 'DEMO-PIN-4821', pinUnavailable: false, informationSource: 'Patient reported' as const } : {}),
  } }
}
