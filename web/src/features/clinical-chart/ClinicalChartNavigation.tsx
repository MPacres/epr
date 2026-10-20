import { ClipboardList, FileClock, Pill, UserRound } from 'lucide-react'
import { useState } from 'react'

export function ClinicalChartNavigation({
  patientDetailsEnabled,
  onOpenPatientDetails,
}: {
  patientDetailsEnabled: boolean
  onOpenPatientDetails: () => void
}) {
  const [currentSection, setCurrentSection] = useState<'overview' | 'lists' | 'encounters'>('overview')

  function scrollToSection(sectionId: string, section: 'overview' | 'lists' | 'encounters') {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const destination = document.getElementById(sectionId)
    if (!destination) return
    setCurrentSection(section)
    destination.focus({ preventScroll: true })
    destination.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
  }

  return (
    <nav className="chart-navigation" aria-label="Patient chart">
      <button className={`chart-navigation-item ${currentSection === 'overview' ? 'active' : ''}`} type="button" aria-current={currentSection === 'overview' ? 'location' : undefined} onClick={() => scrollToSection('clinical-summary-title', 'overview')}>
        <ClipboardList aria-hidden="true" />
        <span>Overview</span>
      </button>
      <button className={`chart-navigation-item ${currentSection === 'encounters' ? 'active' : ''}`} type="button" aria-current={currentSection === 'encounters' ? 'location' : undefined} onClick={() => scrollToSection('chart-history-title', 'encounters')}>
        <FileClock aria-hidden="true" />
        <span>Encounters</span>
      </button>
      <button className={`chart-navigation-item ${currentSection === 'lists' ? 'active' : ''}`} type="button" aria-current={currentSection === 'lists' ? 'location' : undefined} onClick={() => scrollToSection('clinical-lists-title', 'lists')}>
        <Pill aria-hidden="true" />
        <span>Clinical lists</span>
      </button>
      <button
        className="chart-navigation-item"
        type="button"
        disabled={!patientDetailsEnabled}
        onClick={onOpenPatientDetails}
      >
        <UserRound aria-hidden="true" />
        <span>Patient details</span>
      </button>
    </nav>
  )
}
