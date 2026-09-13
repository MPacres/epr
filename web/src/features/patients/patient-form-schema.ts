import { z } from 'zod'

const text = z.string().max(200, 'Use 200 characters or fewer.')
export const patientDraftSchema = z.object({
  firstName: text, middleName: text, lastName: text, suffix: text,
  birthDate: z.string(), birthAccuracy: z.enum(['exact', 'estimated', 'unknown']),
  sex: z.enum(['Female', 'Male', 'Not recorded']), provisional: z.boolean(),
  phone: text, email: text, language: text, communication: z.enum(['Not recorded', 'In person', 'Phone call', 'SMS', 'Email']),
  street: text, barangay: text, city: text, province: text, postalCode: text,
  philhealthRole: z.enum(['Unknown', 'Member', 'Dependent']), pin: text, pinUnavailable: z.boolean(),
  contributor: z.enum(['Unknown', 'Direct contributor', 'Indirect contributor']),
  informationSource: z.enum(['Not recorded', 'Patient reported', 'Representative reported', 'Document presented']),
  principalName: text, principalPin: text, principalRelationship: text,
  representativeName: text, representativeRelationship: text, representativePhone: text,
  representativeAuthority: z.enum(['Not recorded', 'Patient nominated', 'Parent / guardian', 'Other — requires review']),
  emergencyName: text, emergencyRelationship: text, emergencyPhone: text,
  otherIdType: text, otherIdValue: text, otherIdIssuer: text,
  changeNote: z.string().max(1000, 'Use 1,000 characters or fewer.'),
}).superRefine((draft, ctx) => {
  const issue = (path: string, message: string) => ctx.addIssue({ code: 'custom', path: [path], message })
  if (!draft.firstName.trim() && !draft.lastName.trim()) issue('firstName', 'Enter at least one patient name. A provisional name is supported.')
  if (draft.birthAccuracy !== 'unknown') {
    const date = new Date(`${draft.birthDate}T00:00:00Z`)
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.birthDate) || !Number.isFinite(date.getTime()) || date.toISOString().slice(0,10) !== draft.birthDate) issue('birthDate', 'Enter a valid birth date or choose Unknown.')
    else if (draft.birthDate > today) issue('birthDate', 'Birth date cannot be in the future.')
  }
  if (draft.email.trim() && !z.email().safeParse(draft.email.trim()).success) issue('email', 'Enter a valid email address or leave this blank.')
  for (const field of ['phone', 'representativePhone', 'emergencyPhone'] as const) {
    if (draft[field].trim() && !/^\+?[\d\s().-]{7,25}$/.test(draft[field].trim())) issue(field, 'Use a phone number with digits and an optional country code, or leave blank.')
  }
  const validPin = (pin: string) => !pin.trim() || /^\d{12}$/.test(pin.replace(/[\s-]/g, '')) || /^DEMO-PIN-\d{4}$/.test(pin)
  if (!draft.pinUnavailable && !validPin(draft.pin)) issue('pin', 'Enter a 12-digit PIN, or mark it unavailable.')
  if (draft.philhealthRole === 'Dependent' && !validPin(draft.principalPin)) issue('principalPin', 'Enter a 12-digit principal member PIN, or leave blank.')
  if (draft.otherIdValue.trim() && !draft.otherIdType.trim()) issue('otherIdType', 'Name the type of identifier.')
  if (draft.otherIdValue.trim() && !draft.otherIdIssuer.trim()) issue('otherIdIssuer', 'Record who issued this identifier.')
})
export type PatientDraft = z.infer<typeof patientDraftSchema>
