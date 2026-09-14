import { CircleAlert, CircleCheck, CirclePause, Clock3 } from 'lucide-react'
import type { PracticeAdministration } from './practice-administration-client'
import { practiceDisplayStatus } from './practice-administration-model'

export function PracticeStatus({ practice }: { practice: Pick<PracticeAdministration, 'status' | 'serviceStatus'> }) {
  const status = practiceDisplayStatus(practice)
  const Icon = status.tone === 'active' ? CircleCheck
    : status.tone === 'danger' ? CircleAlert
      : status.tone === 'muted' ? CirclePause
        : Clock3
  return <span className={`practice-status ${status.tone}`}><Icon aria-hidden="true" />{status.label}</span>
}
