import type { PracticeAdministration, PracticeProvisioningStatus, PracticeServiceStatus } from './practice-administration-client'

export type PracticeFilter = 'ALL' | 'ACTION_REQUIRED' | 'ACTIVE' | 'SUSPENDED'

export function filterQuery(filter: PracticeFilter): {
  provisioningStatus?: PracticeProvisioningStatus
  serviceStatus?: PracticeServiceStatus
} {
  if (filter === 'ACTION_REQUIRED') return { provisioningStatus: 'QUARANTINED' }
  if (filter === 'ACTIVE') return { provisioningStatus: 'ACTIVE', serviceStatus: 'ENABLED' }
  if (filter === 'SUSPENDED') return { serviceStatus: 'SUSPENDED' }
  return {}
}

export function practiceDisplayStatus(practice: Pick<PracticeAdministration, 'status' | 'serviceStatus'>) {
  if (practice.status === 'QUARANTINED') return { label: 'Needs attention', tone: 'danger' as const }
  if (practice.status === 'PROVISIONING') return { label: 'Setting up', tone: 'pending' as const }
  if (practice.serviceStatus === 'SUSPENDED') return { label: 'Suspended', tone: 'muted' as const }
  return { label: 'Active', tone: 'active' as const }
}

export function formatPracticeDate(value: string) {
  return new Intl.DateTimeFormat('en-PH', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Manila',
  }).format(new Date(value))
}
