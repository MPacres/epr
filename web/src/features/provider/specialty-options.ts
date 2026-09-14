export const OTHER_SPECIALTY = 'OTHER_NOT_LISTED'

export const specialtyOptions = [
  { value: 'GENERAL_PRACTICE', label: 'General Practice' },
  { value: 'FAMILY_MEDICINE', label: 'Family Medicine' },
  { value: 'INTERNAL_MEDICINE', label: 'Internal Medicine' },
  { value: 'PEDIATRICS', label: 'Pediatrics' },
  { value: 'OBSTETRICS_GYNECOLOGY', label: 'Obstetrics and Gynecology' },
  { value: 'GENERAL_SURGERY', label: 'General Surgery' },
  { value: 'CARDIOLOGY', label: 'Cardiology' },
  { value: 'PULMONOLOGY', label: 'Pulmonology' },
  { value: 'GASTROENTEROLOGY', label: 'Gastroenterology' },
  { value: 'ENDOCRINOLOGY', label: 'Endocrinology' },
  { value: 'NEPHROLOGY', label: 'Nephrology' },
  { value: 'NEUROLOGY', label: 'Neurology' },
  { value: 'PSYCHIATRY', label: 'Psychiatry' },
  { value: 'DERMATOLOGY', label: 'Dermatology' },
  { value: 'OPHTHALMOLOGY', label: 'Ophthalmology' },
  { value: 'OTORHINOLARYNGOLOGY', label: 'Otorhinolaryngology (ENT)' },
  { value: 'ORTHOPEDIC_SURGERY', label: 'Orthopedic Surgery' },
  { value: 'UROLOGY', label: 'Urology' },
  { value: 'ANESTHESIOLOGY', label: 'Anesthesiology' },
  { value: 'RADIOLOGY', label: 'Radiology' },
  { value: 'PATHOLOGY', label: 'Pathology' },
  { value: 'REHABILITATION_MEDICINE', label: 'Rehabilitation Medicine' },
  { value: 'EMERGENCY_MEDICINE', label: 'Emergency Medicine' },
  { value: 'OCCUPATIONAL_MEDICINE', label: 'Occupational Medicine' },
  { value: OTHER_SPECIALTY, label: 'Other / Not listed' },
] as const

const specialtyValues = new Set<string>(specialtyOptions.map(option => option.value))

export function isSpecialtyValue(value: string) {
  return specialtyValues.has(value)
}

export function specialtyLabel(value: string, otherSpecialty: string) {
  if (value === OTHER_SPECIALTY) return otherSpecialty.trim()
  return specialtyOptions.find(option => option.value === value)?.label ?? ''
}

