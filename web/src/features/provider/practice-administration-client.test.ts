import { afterEach, describe, expect, it, vi } from 'vitest'
import { changePracticeServiceStatus, issueAdministratorTemporaryPassword, listPractices, updatePracticeName } from './practice-administration-client'

const practice = {
  practiceId: '8fb6b6e2-0e49-4879-80d7-e48ef5ab1259',
  practiceCode: 'santos-clinic',
  displayName: 'Santos Clinic',
  initialAdministratorUserId: '2a570acc-39a0-4b50-91bc-8b6ca2df0f2d',
  administratorName: 'Alex Reyes',
  administratorEmail: 'alex.reyes@example.test',
  administratorSetupStatus: 'TEMPORARY_PASSWORD_ISSUED',
  status: 'ACTIVE',
  serviceStatus: 'ENABLED',
  schemaVersion: '2',
  provisioningAttempts: 1,
  failureCode: null,
  failureMessage: null,
  suspendedAt: null,
  suspensionReason: null,
  createdAt: '2026-09-14T06:00:00Z',
  updatedAt: '2026-09-14T06:00:01Z',
  version: 1,
} as const

afterEach(() => vi.unstubAllGlobals())

describe('practice administration client', () => {
  it('scopes directory search and status filters through query parameters', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [practice], totalElements: 1, totalPages: 1, page: 0, size: 25,
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await listPractices({ search: ' Santos ', provisioningStatus: 'ACTIVE', serviceStatus: 'ENABLED' })

    expect(result.items).toHaveLength(1)
    expect(fetchMock.mock.calls[0][0]).toContain('search=Santos')
    expect(fetchMock.mock.calls[0][0]).toContain('serviceStatus=ENABLED')
  })

  it('sends version and the caller-owned idempotency key when editing', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...practice, displayName: 'Santos Family Clinic', version: 2 }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await updatePracticeName({
      practiceId: practice.practiceId,
      displayName: ' Santos Family Clinic ',
      version: 1,
      idempotencyKey: '313b5410-3fab-4b2c-8eaa-2c5ae6b619d9',
    })

    const [, request] = fetchMock.mock.calls[1]
    expect(request.method).toBe('PATCH')
    expect(request.headers).toMatchObject({ 'Idempotency-Key': '313b5410-3fab-4b2c-8eaa-2c5ae6b619d9' })
    expect(JSON.parse(request.body)).toEqual({ displayName: 'Santos Family Clinic', version: 1 })
  })

  it('uses an explicit suspension command rather than deleting the Practice', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...practice, serviceStatus: 'SUSPENDED', version: 2 }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await changePracticeServiceStatus({
      practiceId: practice.practiceId,
      targetStatus: 'SUSPENDED',
      reason: 'Practice requested temporary closure.',
      version: 1,
      idempotencyKey: '3df2b2e2-7d6f-4af3-a181-0b9fde7e79fd',
    })

    expect(fetchMock.mock.calls[1][0]).toContain('/suspension')
    expect(fetchMock.mock.calls[1][0]).not.toContain('delete')
  })

  it('issues a one-time temporary password with version and reason', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        practice: { ...practice, version: 2 }, temporaryPassword: 'Temporary1!Password', newlyIssued: true,
      }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await issueAdministratorTemporaryPassword({
      practiceId: practice.practiceId,
      reason: 'Personal setup with the physician.',
      version: 1,
      idempotencyKey: '02d67680-eef2-47da-8ae2-1b9974e377a5',
    })

    expect(result.temporaryPassword).toBe('Temporary1!Password')
    expect(fetchMock.mock.calls[1][0]).toContain('/administrator/temporary-password')
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ reason: 'Personal setup with the physician.', version: 1 })
  })
})
