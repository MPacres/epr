import { z } from 'zod'
import { fetchCsrfToken } from '../auth/auth-client'
import type { TenantCreationValues } from './tenant-creation-schema'

const tenantCreationResponseSchema = z.object({
  practiceId: z.uuid(),
  practiceCode: z.string(),
  displayName: z.string(),
  initialAdministratorUserId: z.uuid(),
  administratorSetupStatus: z.enum(['PASSWORD_NOT_ISSUED', 'TEMPORARY_PASSWORD_ISSUED', 'PASSWORD_SET', 'EXISTING_ACCOUNT']),
  temporaryPassword: z.string().nullable(),
  initialSiteId: z.uuid(),
  status: z.enum(['PROVISIONING', 'ACTIVE', 'QUARANTINED']),
  serviceStatus: z.enum(['ENABLED', 'SUSPENDED']),
  schemaVersion: z.string().nullable(),
  provisioningAttempts: z.number().int().positive(),
  failureCode: z.string().nullable(),
  failureMessage: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number().int().nonnegative(),
})

const problemSchema = z.object({
  code: z.string().optional(),
  detail: z.string().optional(),
})

export type TenantCreationResult = z.infer<typeof tenantCreationResponseSchema>
export type TenantCreationCommand = {
  practiceId: string
  siteId: string
  idempotencyKey: string
  values: TenantCreationValues
}

export class TenantCreationError extends Error {
  readonly kind: 'conflict' | 'forbidden' | 'session' | 'network' | 'server'

  constructor(kind: TenantCreationError['kind'], message: string) {
    super(message)
    this.kind = kind
  }
}

export function createUuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()

  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function practiceCodeFor(practiceName: string, practiceId: string): string {
  const slug = practiceName
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 23)
    .replace(/-+$/g, '') || 'practice'
  return `${slug}-${practiceId.replaceAll('-', '').slice(0, 8)}`
}

export async function createPractice(command: TenantCreationCommand): Promise<TenantCreationResult> {
  try {
    const csrf = await fetchCsrfToken()
    const response = await fetch('/api/v1/admin/practices', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': command.idempotencyKey,
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify({
        practiceId: command.practiceId,
        practiceCode: practiceCodeFor(command.values.practiceName, command.practiceId),
        displayName: command.values.practiceName.trim(),
        administratorName: command.values.administratorName.trim(),
        administratorEmail: command.values.administratorEmail.trim().toLowerCase(),
        initialSite: {
          siteId: command.siteId,
          name: command.values.siteName.trim(),
          facilityName: command.values.facilityName.trim(),
          contactNumber: command.values.contactNumber.trim(),
          countryCode: command.values.countryCode,
          regionCode: command.values.regionCode,
          provinceAreaCode: command.values.provinceCode,
          localityCode: command.values.cityMunicipalityCode,
        },
      }),
    })

    if (!response.ok) {
      const problem = problemSchema.safeParse(await response.json().catch(() => ({})))
      const detail = problem.success ? problem.data.detail : undefined
      if (response.status === 401) throw new TenantCreationError('session', 'Your provider session expired. Sign in again, then retry this setup.')
      if (response.status === 403) throw new TenantCreationError('forbidden', 'Your account is not authorized to create Practice workspaces.')
      if (response.status === 409) throw new TenantCreationError('conflict', detail ?? 'This Practice setup conflicts with an existing account or workspace. Review the details and try again.')
      throw new TenantCreationError('server', detail ?? 'The Practice workspace could not be created. The setup remains available to retry.')
    }

    return tenantCreationResponseSchema.parse(await response.json())
  } catch (error) {
    if (error instanceof TenantCreationError) throw error
    if (error instanceof TypeError) {
      throw new TenantCreationError('network', 'The EPR service could not be reached. Check the connection and retry this setup.')
    }
    throw new TenantCreationError('server', 'The Practice workspace response could not be verified. The setup remains available to retry.')
  }
}
