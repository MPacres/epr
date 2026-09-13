import { useRef, useState } from 'react'
import type { SourceKind } from './ChartShared'

export function useSourceSheet() {
  const [source, setSource] = useState<SourceKind | null>(null)
  const opener = useRef<HTMLElement | null>(null)
  function openSource(kind: SourceKind) {
    if (document.activeElement instanceof HTMLElement) opener.current = document.activeElement
    setSource(kind)
  }
  function closeSource() {
    setSource(null)

  }
  return { source, openSource, closeSource, restoreSourceFocus: () => opener.current?.focus() }
}
