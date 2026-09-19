import { createContext } from 'react'
import type { IdentifierModes, PatientDetail, SensitiveField } from './patient-api'
export const PatientIdentifierContext = createContext<{
  masks:PatientDetail['identifiers']; modes:IdentifierModes; change:(field:SensitiveField,mode:IdentifierModes[SensitiveField])=>void
} | null>(null)
