import { describe, expect, it } from 'vitest'
import { tenantCreationSchema } from './tenant-creation-schema'

const validPractice = {
  practiceName: 'Santos Family Medicine',
  administratorName: 'Alex Reyes',
  administratorEmail: 'alex.reyes@example.test',
  siteName: 'Santos Clinic · Makati',
  facilityName: 'Makati Medical Arts Building',
  cityMunicipality: 'Makati City',
  province: 'Metro Manila',
  contactNumber: '+63 917 000 0000',
}

describe('tenantCreationSchema', () => {
  it('accepts a practice setup with a non-clinical initial administrator', () => {
    expect(tenantCreationSchema.safeParse(validPractice)).toMatchObject({ success: true })
  })

  it('requires the administrator and first site details that establish workspace context', () => {
    const result = tenantCreationSchema.safeParse({
      ...validPractice,
      administratorName: '',
      administratorEmail: 'not-an-email',
      siteName: '',
    })

    expect(result.success).toBe(false)
    if (!result.success) {
      const errors = result.error.flatten().fieldErrors
      expect(errors.administratorName).toEqual(['Enter the initial administrator’s full name.'])
      expect(errors.administratorEmail).toEqual(['Enter a valid work email address.'])
      expect(errors.siteName).toEqual(['Enter the first clinic or site name.'])
    }
  })

  it('keeps the shared physical facility optional', () => {
    expect(tenantCreationSchema.safeParse({ ...validPractice, facilityName: '' })).toMatchObject({ success: true })
  })
})
