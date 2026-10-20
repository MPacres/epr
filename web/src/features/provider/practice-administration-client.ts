import { z } from 'zod'
import { fetchCsrfToken } from '../auth/auth-client'
import { clinicalReadinessSchema } from '../practices/team-api'

const practiceSchema = z.object({
  practiceId: z.uuid(),
  practiceCode: z.string(),
  displayName: z.string(),
  initialAdministratorUserId: z.uuid(),
  administratorName: z.string().nullable(),
  administratorEmail: z.string().nullable(),
  administratorSetupStatus: z.enum(['PASSWORD_NOT_ISSUED', 'TEMPORARY_PASSWORD_ISSUED', 'PASSWORD_SET', 'EXISTING_ACCOUNT']).nullable(),
  status: z.enum(['PROVISIONING', 'ACTIVE', 'QUARANTINED']),
  serviceStatus: z.enum(['ENABLED', 'SUSPENDED']),
  schemaVersion: z.string().nullable(),
  clinicalReadiness: clinicalReadinessSchema,
  provisioningAttempts: z.number().int().positive(),
  failureCode: z.string().nullable(),
  failureMessage: z.string().nullable(),
  suspendedAt: z.string().nullable(),
  suspensionReason: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number().int().nonnegative(),
})

const directorySchema = z.object({
  items: z.array(practiceSchema),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
})

const temporaryPasswordIssueSchema = z.object({
  practice: practiceSchema,
  temporaryPassword: z.string().nullable(),
  newlyIssued: z.boolean(),
})

const problemSchema = z.object({ detail: z.string().optional() })

export type PracticeAdministration = z.infer<typeof practiceSchema>
export type PracticeDirectoryPage = z.infer<typeof directorySchema>
export type PracticeProvisioningStatus = PracticeAdministration['status']
export type PracticeServiceStatus = PracticeAdministration['serviceStatus']
export type TemporaryPasswordIssueResult = z.infer<typeof temporaryPasswordIssueSchema>

export class PracticeAdministrationError extends Error {
  readonly kind: 'session' | 'forbidden' | 'not-found' | 'conflict' | 'validation' | 'network' | 'server'

  constructor(kind: PracticeAdministrationError['kind'], message: string) {
    super(message)
    this.kind = kind
  }
}

type DirectoryQuery = {
  search?: string
  provisioningStatus?: PracticeProvisioningStatus
  serviceStatus?: PracticeServiceStatus
  page?: number
  size?: number
}

async function verifiedResponse<T>(response: Response, schema: z.ZodType<T>): Promise<T> {
  if (!response.ok) {
    const problem = problemSchema.safeParse(await response.json().catch(() => ({})))
    const detail = problem.success ? problem.data.detail : undefined
    if (response.status === 401) throw new PracticeAdministrationError('session', 'Your provider session expired. Sign in again to continue.')
    if (response.status === 403) throw new PracticeAdministrationError('forbidden', 'Your account is not authorized to manage Practice workspaces.')
    if (response.status === 404) throw new PracticeAdministrationError('not-found', 'This Practice is no longer available.')
    if (response.status === 409) throw new PracticeAdministrationError('conflict', detail ?? 'This Practice changed after it was loaded. Refresh it and try again.')
    if (response.status === 422) throw new PracticeAdministrationError('validation', detail ?? 'Review the reason and try again.')
    throw new PracticeAdministrationError('server', detail ?? 'Practice administration is temporarily unavailable.')
  }
  return schema.parse(await response.json())
}

async function request<T>(url: string, schema: z.ZodType<T>, init?: RequestInit): Promise<T> {
  try {
    return await verifiedResponse(await fetch(url, { credentials: 'include', cache: 'no-store', ...init }), schema)
  } catch (error) {
    if (error instanceof PracticeAdministrationError) throw error
    if (error instanceof TypeError) throw new PracticeAdministrationError('network', 'The EPR service could not be reached. Check the connection and try again.')
    throw new PracticeAdministrationError('server', 'The Practice response could not be verified.')
  }
}

export async function listPractices(query: DirectoryQuery = {}): Promise<PracticeDirectoryPage> {
  const params = new URLSearchParams()
  if (query.search?.trim()) params.set('search', query.search.trim())
  if (query.provisioningStatus) params.set('provisioningStatus', query.provisioningStatus)
  if (query.serviceStatus) params.set('serviceStatus', query.serviceStatus)
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? 25))
  return request(`/api/v1/admin/practices?${params}`, directorySchema)
}

export async function getPractice(practiceId: string): Promise<PracticeAdministration> {
  return request(`/api/v1/admin/practices/${encodeURIComponent(practiceId)}`, practiceSchema)
}

async function mutatePractice(
  url: string,
  method: 'PATCH' | 'POST',
  body: { version: number; displayName?: string; reason?: string },
  idempotencyKey: string,
): Promise<PracticeAdministration> {
  try {
    const csrf = await fetchCsrfToken()
    return request(url, practiceSchema, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify(body),
    })
  } catch (error) {
    if (error instanceof PracticeAdministrationError) throw error
    if (error instanceof TypeError) throw new PracticeAdministrationError('network', 'The EPR service could not be reached. Check the connection and try again.')
    throw new PracticeAdministrationError('server', 'The Practice change could not be prepared securely.')
  }
}

export function updatePracticeName(command: {
  practiceId: string
  displayName: string
  version: number
  idempotencyKey: string
}) {
  return mutatePractice(
    `/api/v1/admin/practices/${encodeURIComponent(command.practiceId)}`,
    'PATCH',
    { displayName: command.displayName.trim(), version: command.version },
    command.idempotencyKey,
  )
}

export function changePracticeServiceStatus(command: {
  practiceId: string
  targetStatus: PracticeServiceStatus
  reason: string
  version: number
  idempotencyKey: string
}) {
  const action = command.targetStatus === 'SUSPENDED' ? 'suspension' : 'reactivation'
  return mutatePractice(
    `/api/v1/admin/practices/${encodeURIComponent(command.practiceId)}/${action}`,
    'POST',
    { reason: command.reason.trim(), version: command.version },
    command.idempotencyKey,
  )
}

export async function issueAdministratorTemporaryPassword(command: {
  practiceId: string
  reason: string
  version: number
  idempotencyKey: string
}): Promise<TemporaryPasswordIssueResult> {
  try {
    const csrf = await fetchCsrfToken()
    return request(
      `/api/v1/admin/practices/${encodeURIComponent(command.practiceId)}/administrator/temporary-password`,
      temporaryPasswordIssueSchema,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': command.idempotencyKey,
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({ reason: command.reason.trim(), version: command.version }),
      },
    )
  } catch (error) {
    if (error instanceof PracticeAdministrationError) throw error
    if (error instanceof TypeError) throw new PracticeAdministrationError('network', 'The EPR service could not be reached. Check the connection and try again.')
    throw new PracticeAdministrationError('server', 'The temporary password could not be prepared securely.')
  }
}

const schemaUpgradeReceiptSchema = z.object({ state: z.string(), schemaVersion: z.string(), attempt: z.number().int().positive() })
const clinicalEnablementReceiptSchema = z.object({ enabled: z.boolean(), version: z.number().int().nonnegative(), replayed: z.boolean() })

async function mutateClinicalReadiness<T>(command: {
  practiceId: string
  reason: string
  version: number
  idempotencyKey: string
}, action: 'schema-upgrade' | 'synthetic-clinical-capability', schema: z.ZodType<T>) {
  const csrf = await fetchCsrfToken()
  return request(`/api/v1/admin/practices/${encodeURIComponent(command.practiceId)}/${action}`, schema, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': command.idempotencyKey,
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify({ expectedVersion: command.version, reason: command.reason.trim() }),
  })
}

export function upgradeClinicalSchema(command: { practiceId: string; reason: string; version: number; idempotencyKey: string }) {
  return mutateClinicalReadiness(command, 'schema-upgrade', schemaUpgradeReceiptSchema)
}

export function enableSyntheticClinicalWorkflows(command: { practiceId: string; reason: string; version: number; idempotencyKey: string }) {
  return mutateClinicalReadiness(command, 'synthetic-clinical-capability', clinicalEnablementReceiptSchema)
}
