import { describe, expect, it } from 'vitest'
import { tenantCreationSchema } from './tenant-creation-schema'

const validPractice = {
  practiceName: 'Santos Family Medicine',
  administratorName: 'Alex Reyes',
  administratorEmail: 'alex.reyes@example.test',
  siteName: 'Santos Clinic · Makati',
  facilityName: 'Makati Medical Arts Building',
  countryCode: 'PH',
  regionCode: '1300000000',
  provinceCode: '1300000000',
  cityMunicipalityCode: '1380300000',
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

  it('rejects a city or municipality outside the selected province area', () => {
    const result = tenantCreationSchema.safeParse({
      ...validPractice,
      regionCode: '0300000000',
      provinceCode: '0304900000',
    })

    expect(result.success).toBe(false)
    if (!result.success) {
      expect(result.error.flatten().fieldErrors.cityMunicipalityCode).toEqual([
        'Select a city or municipality within the chosen province or area.',
      ])
    }
  })

  it('rejects a province area outside the selected region', () => {
    const result = tenantCreationSchema.safeParse({
      ...validPractice,
      regionCode: '0300000000',
    })

    expect(result.success).toBe(false)
    if (!result.success) {
      expect(result.error.flatten().fieldErrors.provinceCode).toEqual([
        'Select a province or independent area within the chosen region.',
      ])
    }
  })
})
