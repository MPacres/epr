import { ClipboardList, FileClock, Pill, UserRound } from 'lucide-react'

export type ClinicalChartSection = 'overview' | 'encounters' | 'lists'

const sections = [
  { id: 'overview', label: 'Overview', icon: ClipboardList },
  { id: 'encounters', label: 'Encounters', icon: FileClock },
  { id: 'lists', label: 'Clinical lists', icon: Pill },
] as const

export function ClinicalChartNavigation({
  section,
  onChange,
  patientDetailsEnabled,
  onOpenPatientDetails,
}: {
  section: ClinicalChartSection
  onChange: (section: ClinicalChartSection, reveal?: boolean) => void
  patientDetailsEnabled: boolean
  onOpenPatientDetails: () => void
}) {
  return (
    <nav className="chart-navigation" aria-label="Patient chart">
      <div className="chart-navigation-tabs" role="tablist" aria-label="Chart sections">
      {sections.map(({ id, label, icon: Icon }) => <button
        key={id}
        id={`chart-tab-${id}`}
        className={`chart-navigation-item ${section === id ? 'active' : ''}`}
        type="button"
        role="tab"
        aria-selected={section === id}
        aria-controls={`chart-panel-${id}`}
        tabIndex={section === id ? 0 : -1}
        onClick={() => onChange(id, true)}
        onKeyDown={event => {
          if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
          event.preventDefault()
          const index = sections.findIndex(item => item.id === id)
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? sections.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + sections.length) % sections.length
          const target = sections[next].id
          onChange(target)
          document.getElementById(`chart-tab-${target}`)?.focus()
        }}
      ><Icon aria-hidden="true" /><span>{label}</span></button>)}
      </div>
      <button className="chart-navigation-item chart-navigation-details" type="button" disabled={!patientDetailsEnabled} onClick={onOpenPatientDetails}>
        <UserRound aria-hidden="true" /><span>Patient details</span>
      </button>
    </nav>
  )
}
