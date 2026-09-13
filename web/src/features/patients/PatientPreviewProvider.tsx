import { useState, type ReactNode } from 'react'
import { doctorClinics } from '../dashboard/demo-data'
import { PatientPreviewRepository, type DemoOutcome, type DemoSaveCommand } from './preview-repository'
import { PatientPreviewContext } from './patient-preview-context'

export function PatientPreviewProvider({ children }: { children: ReactNode }) {
  const [repository] = useState(() => new PatientPreviewRepository(doctorClinics.map(clinic => clinic.practiceId)))
  const [records, setRecords] = useState(repository.snapshot())
  async function save(command: DemoSaveCommand, outcome: DemoOutcome) {
    const record = await repository.save(command, outcome)
    setRecords(repository.snapshot())
    return record
  }
  return <PatientPreviewContext.Provider value={{ records, save, repository }}>{children}</PatientPreviewContext.Provider>
}
