import { createContext, useContext } from 'react'
import type { PatientPreviewRepository, DemoOutcome, DemoSaveCommand } from './preview-repository'
import type { DemoPatientRecord } from './patient-form-model'
export interface PatientPreviewContextValue {
  records: DemoPatientRecord[]
  save: (command: DemoSaveCommand, outcome: DemoOutcome) => Promise<DemoPatientRecord>
  repository: PatientPreviewRepository
}
export const PatientPreviewContext = createContext<PatientPreviewContextValue | null>(null)
export function usePatientPreview() {
  const context = useContext(PatientPreviewContext)
  if (!context) throw new Error('Patient preview requires its provider')
  return context
}
