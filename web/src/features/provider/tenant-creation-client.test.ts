import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPractice, createUuid, practiceCodeFor, TenantCreationError } from './tenant-creation-client'

const values = {
  practiceName: 'Santos Family Medicine',
  administratorName: 'Alex Reyes',
  administratorEmail: 'Alex.Reyes@Example.Test',
  siteName: 'Santos Clinic · Makati',
  facilityName: 'Medical Arts Building',
  contactNumber: '+63 917 000 0000',
  countryCode: 'PH' as const,
  regionCode: '1300000000',
  provinceCode: '1300000000',
  cityMunicipalityCode: '1380300000',
}

afterEach(() => vi.unstubAllGlobals())

describe('tenant creation client', () => {
  it('creates a stable safe practice code', () => {
    expect(practiceCodeFor('  Clínica Santos & Family  ', '8fb6b6e2-0e49-4879-80d7-e48ef5ab1259'))
      .toBe('clinica-santos-family-8fb6b6e2')
  })

  it('creates RFC 4122 version 4 identifiers', () => {
    expect(createUuid()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('sends the reviewed setup with CSRF and the original idempotency key', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        practiceId: '8fb6b6e2-0e49-4879-80d7-e48ef5ab1259',
        practiceCode: 'santos-family-medicine-8fb6b6e2',
        displayName: values.practiceName,
        initialAdministratorUserId: '2a570acc-39a0-4b50-91bc-8b6ca2df0f2d',
        administratorSetupStatus: 'TEMPORARY_PASSWORD_ISSUED',
        temporaryPassword: 'Temporary1!Password',
        initialSiteId: '85a572b4-f3a4-49b0-99ba-1a819598c395',
        status: 'ACTIVE',
        serviceStatus: 'ENABLED',
        schemaVersion: '2',
        provisioningAttempts: 1,
        failureCode: null,
        failureMessage: null,
        createdAt: '2026-09-14T06:00:00Z',
        updatedAt: '2026-09-14T06:00:01Z',
        version: 1,
      }), { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await createPractice({
      practiceId: '8fb6b6e2-0e49-4879-80d7-e48ef5ab1259',
      siteId: '85a572b4-f3a4-49b0-99ba-1a819598c395',
      idempotencyKey: '313b5410-3fab-4b2c-8eaa-2c5ae6b619d9',
      values,
    })

    expect(result.status).toBe('ACTIVE')
    expect(result.temporaryPassword).toBe('Temporary1!Password')
    const [, request] = fetchMock.mock.calls[1]
    expect(request.headers).toMatchObject({ 'Idempotency-Key': '313b5410-3fab-4b2c-8eaa-2c5ae6b619d9', 'X-XSRF-TOKEN': 'csrf' })
    expect(JSON.parse(request.body)).toMatchObject({
      administratorEmail: 'alex.reyes@example.test',
      initialSite: { localityCode: '1380300000' },
    })
  })

  it('preserves a safe conflict explanation for recovery', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ detail: 'The administrator email belongs to an existing account under a different name.' }), { status: 409 })))

    await expect(createPractice({
      practiceId: crypto.randomUUID(),
      siteId: crypto.randomUUID(),
      idempotencyKey: crypto.randomUUID(),
      values,
    })).rejects.toEqual(expect.objectContaining({ kind: 'conflict' } satisfies Partial<TenantCreationError>))
  })
})
