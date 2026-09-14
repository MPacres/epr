import { Check, ChevronDown, Search } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { specialtyOptions } from './specialty-options'

export function SpecialtyCombobox({ value, onChange, invalid, describedBy }: {
  value: string
  onChange: (value: string) => void
  invalid: boolean
  describedBy?: string
}) {
  const listId = useId()
  const inputRef = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const selected = specialtyOptions.find(option => option.value === value)
  const normalizedQuery = query.trim().toLocaleLowerCase()
  const filtered = specialtyOptions.filter(option => !normalizedQuery || option.label.toLocaleLowerCase().includes(normalizedQuery))

  function choose(nextValue: string) {
    const option = specialtyOptions.find(item => item.value === nextValue)
    onChange(nextValue)
    setQuery(option?.label ?? '')
    setOpen(false)
    inputRef.current?.focus()
  }

  function moveActive(direction: 1 | -1) {
    if (!filtered.length) return
    setActiveIndex(index => (index + direction + filtered.length) % filtered.length)
  }

  return <div className="specialty-combobox">
    <div className="specialty-combobox-control">
      <Search aria-hidden="true" />
      <input
        ref={inputRef}
        id="tenant-specialty"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={open && filtered[activeIndex] ? `${listId}-${filtered[activeIndex].value}` : undefined}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        value={open ? query : selected?.label ?? ''}
        placeholder="Search specialties"
        autoComplete="off"
        onFocus={() => { setOpen(true); setQuery(''); setActiveIndex(0) }}
        onChange={event => { setQuery(event.target.value); onChange(''); setOpen(true); setActiveIndex(0) }}
        onBlur={() => setOpen(false)}
        onKeyDown={event => {
          if (event.key === 'ArrowDown') { event.preventDefault(); setOpen(true); moveActive(1) }
          if (event.key === 'ArrowUp') { event.preventDefault(); setOpen(true); moveActive(-1) }
          if (event.key === 'Enter' && open && filtered[activeIndex]) { event.preventDefault(); choose(filtered[activeIndex].value) }
          if (event.key === 'Escape') { event.preventDefault(); setOpen(false); setQuery(selected?.label ?? '') }
        }}
      />
      <ChevronDown className={open ? 'open' : ''} aria-hidden="true" />
    </div>
    {open ? <ul id={listId} className="specialty-options" role="listbox" aria-label="Primary specialty options">
      {filtered.length ? filtered.map((option, index) => <li
        id={`${listId}-${option.value}`}
        key={option.value}
        role="option"
        aria-selected={option.value === value}
        className={index === activeIndex ? 'active' : ''}
        onMouseDown={event => event.preventDefault()}
        onMouseEnter={() => setActiveIndex(index)}
        onClick={() => choose(option.value)}
      ><span>{option.label}</span>{option.value === value ? <Check aria-hidden="true" /> : null}</li>) : <li className="specialty-no-results" role="option" aria-disabled="true">No matching specialty</li>}
    </ul> : null}
  </div>
}
