import { useLayoutEffect, useRef, type ReactNode } from 'react'
import { TriangleAlert } from 'lucide-react'
import type { Patient } from './services/encounter-api'

function ageOnDate(birthDate: string | null, today: string): number | null {
  if (!birthDate || !/^\d{4}-\d{2}-\d{2}$/.test(birthDate)) return null
  const [birthYear, birthMonth, birthDay] = birthDate.split('-').map(Number)
  const [year, month, day] = today.split('-').map(Number)
  const age = year - birthYear - (month < birthMonth || (month === birthMonth && day < birthDay) ? 1 : 0)
  return age >= 0 ? age : null
}

function patientAge(birthDate: string | null): string {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  const age = ageOnDate(birthDate, today)
  return age === null ? 'Unknown' : age === 0 ? 'Under 1 year' : `${age} years`
}

export function PatientContextHeader({ patient, sex, allergyStatus = 'Allergies/alerts unavailable', actions }: { patient: Patient; sex?: string; allergyStatus?: string; actions?: ReactNode }) {
  const headerRef = useRef<HTMLElement>(null)
  useLayoutEffect(() => {
    const header = headerRef.current
    const workspace = header?.closest<HTMLElement>('.connected-clinical')
    const topbar = document.querySelector<HTMLElement>('.topbar')
    if (!workspace || !topbar || !header) return
    const updateOffsets = () => {
      workspace.style.setProperty('--clinical-header-height', `${Math.ceil(topbar.getBoundingClientRect().height)}px`)
      workspace.style.setProperty('--patient-context-height', `${Math.ceil(header.getBoundingClientRect().height)}px`)
    }
    updateOffsets()
    const observer = new ResizeObserver(updateOffsets)
    observer.observe(topbar)
    observer.observe(header)
    return () => { observer.disconnect(); workspace.style.removeProperty('--clinical-header-height'); workspace.style.removeProperty('--patient-context-height') }
  }, [])
  const knownBirthDate = patient.birthAccuracy === 'unknown' ? null : patient.birthDate
  const ageLabel = patient.birthAccuracy === 'estimated' && knownBirthDate
    ? `Approx. ${patientAge(knownBirthDate)}`
    : patientAge(knownBirthDate)
  return <header ref={headerRef} className="patient-context" aria-label="Current patient">
    <div className="patient-context-main">
      <div className="patient-context-identity">
        <h1>{patient.displayName}</h1>
        {patient.identityStatus === 'PROVISIONAL' ? <span className="badge amber">Provisional identity</span> : null}
        <span className="badge blue patient-context-demo">Demo data</span>
      </div>
      <div className="patient-context-facts" aria-label="Patient identifiers, demographics, and alerts">
        <span><strong>Patient ID</strong> {patient.recordNumber}</span>
        <span><strong>Age</strong> {ageLabel}</span>
        <span><strong>Sex</strong> {sex ?? 'Unavailable'}</span>
        <span
          className="patient-context-safety"
          role="note"
          aria-label={allergyStatus}
          title={allergyStatus}
        >
          <TriangleAlert aria-hidden="true" />
          {allergyStatus}
        </span>
      </div>
    </div>
    {actions ? <div className="patient-context-actions">{actions}</div> : null}
  </header>
}
