import * as Dialog from '@radix-ui/react-dialog'
import { useId, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowDown, ArrowUp, Building2, CornerDownLeft, Search, Users, X } from 'lucide-react'
import { searchPatients, patientAgeLabel, type Patient } from './model'
import './patient-spotlight.css'

export function PatientSpotlight({ patients, clinics, onClose, onSelect }: {
  patients: Patient[]; clinics: readonly { practiceId: string; site: string }[]; onClose: () => void; onSelect: (patient: Patient) => void
}) {
  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const listId = useId()
  const hasQuery = query.trim().length > 0
  const results = hasQuery ? searchPatients(patients, clinics.map(clinic => clinic.practiceId), query) : []
  const activePatient = results[activeIndex]
  const optionId = (patient: Patient) => `${listId}-${patient.practiceId}-${patient.id}`

  function updateQuery(value: string) {
    setQuery(value)
    setActiveIndex(0)
    listRef.current?.scrollTo({ top: 0 })
  }
  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.nativeEvent.isComposing) return
    if (event.key === 'Enter' && activePatient) {
      event.preventDefault()
      onSelect(activePatient)
    } else if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && results.length) {
      event.preventDefault()
      const nextIndex = (activeIndex + (event.key === 'ArrowDown' ? 1 : -1) + results.length) % results.length
      setActiveIndex(nextIndex)
      document.getElementById(optionId(results[nextIndex]))?.scrollIntoView({ block: 'nearest' })
    }
  }

  return <Dialog.Root open onOpenChange={open => { if (!open) onClose() }}>
    <Dialog.Portal>
      <Dialog.Overlay className="spotlight-overlay" />
      <Dialog.Content className="patient-spotlight" onOpenAutoFocus={event => { event.preventDefault(); inputRef.current?.focus() }} onCloseAutoFocus={event => event.preventDefault()}>
        <Dialog.Title className="sr-only">Search patients</Dialog.Title>
        <Dialog.Description className="sr-only">Search synthetic patients by name or record number across your clinics. Use arrow keys to browse and Enter to open a patient overview.</Dialog.Description>
        <div className="spotlight-searchbar">
          <Search aria-hidden="true" />
          <input ref={inputRef} role="combobox" aria-label="Search patients across your clinics" aria-autocomplete="list" aria-expanded={hasQuery} aria-controls={listId} aria-activedescendant={activePatient ? optionId(activePatient) : undefined}
            placeholder="Search patients…" value={query} onChange={event => updateQuery(event.target.value)} onKeyDown={handleKeyDown} autoComplete="off" autoCorrect="off" spellCheck={false} />
          {query ? <button className="spotlight-clear" aria-label="Clear patient search" onClick={() => { updateQuery(''); inputRef.current?.focus() }}><X aria-hidden="true" /></button> : null}
          <Dialog.Close className="spotlight-close" aria-label="Close patient search"><span className="spotlight-escape">esc</span><span className="spotlight-close-label">Cancel</span></Dialog.Close>
        </div>
        <div className="spotlight-scope"><span><Building2 aria-hidden="true" />All your clinics</span><span>Synthetic patient data</span></div>
        <div className="spotlight-results-area">
          <p className="spotlight-results-label" role="status">{hasQuery ? `${results.length} ${results.length === 1 ? 'patient' : 'patients'} found` : 'Patient search'}</p>
          <div ref={listRef} className="spotlight-results" id={listId} role="listbox" aria-label="Matching patients" hidden={!results.length}>
            {results.map((patient, index) => <div role="option" aria-selected={index === activeIndex} id={optionId(patient)} key={`${patient.practiceId}:${patient.id}`}
              className="spotlight-result" onPointerMove={event => { if (event.pointerType === 'mouse') setActiveIndex(index) }} onMouseDown={event => event.preventDefault()} onClick={() => onSelect(patient)}>
              <span className={`avatar ${patient.tone}`} aria-hidden="true">{patient.initials}</span>
              <span className="spotlight-patient"><strong>{patient.name}</strong><small>{patient.mrn}<span>·</span>{patientAgeLabel(patient)}<span>·</span>{clinics.find(clinic => clinic.practiceId === patient.practiceId)?.site}</small></span>
              <CornerDownLeft className="spotlight-result-enter" aria-hidden="true" />
            </div>)}
          </div>
          {!results.length ? <div className="spotlight-empty"><span className="spotlight-empty-icon">{hasQuery ? <Search aria-hidden="true" /> : <Users aria-hidden="true" />}</span><h3>{hasQuery ? 'No matching patients' : 'Find the right patient'}</h3><p>{hasQuery ? 'Try another name or record number. Search includes all your clinics.' : 'Search by name or record number across all your clinics. Each result shows its clinic.'}</p>{!hasQuery ? <span className="spotlight-example">Try “Maria” or “HA”</span> : null}</div> : null}
        </div>
        <footer className="spotlight-footer"><span><kbd><ArrowUp aria-hidden="true" /></kbd><kbd><ArrowDown aria-hidden="true" /></kbd>to navigate</span><span><kbd><CornerDownLeft aria-hidden="true" /></kbd>to open</span><span className="spotlight-footer-hint">Patient overview only</span></footer>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
