import { Plus } from 'lucide-react'
import './epr-mark.css'

export function EprMark({ className = '' }: { className?: string }) {
  return <span className={`epr-mark ${className}`} aria-hidden="true"><Plus /></span>
}

