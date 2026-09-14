import { z } from 'zod'
import {
  getLocality,
  getProvinceArea,
  getRegion,
  PHILIPPINES_COUNTRY_CODE,
} from './philippine-locations'

export const tenantCreationSchema = z.object({
  practiceName: z.string().trim().min(2, 'Enter the name patients and staff know this practice by.').max(120, 'Keep the practice name under 120 characters.'),
  administratorName: z.string().trim().min(2, 'Enter the initial administrator’s full name.').max(120, 'Keep the administrator’s name under 120 characters.'),
  administratorEmail: z.email('Enter a valid work email address.'),
  siteName: z.string().trim().min(2, 'Enter the first clinic or site name.').max(120, 'Keep the site name under 120 characters.'),
  facilityName: z.string().trim().max(160, 'Keep the facility or building name under 160 characters.'),
  countryCode: z.literal(PHILIPPINES_COUNTRY_CODE, { error: 'Select Philippines as the country.' }),
  regionCode: z.string({ error: 'Select a region.' }).min(1, 'Select a region.'),
  provinceCode: z.string({ error: 'Select a province or independent area.' }).min(1, 'Select a province or independent area.'),
  cityMunicipalityCode: z.string({ error: 'Select a city or municipality.' }).min(1, 'Select a city or municipality.'),
  contactNumber: z.string().trim().max(30, 'Keep the contact number under 30 characters.'),
}).superRefine((values, context) => {
  const region = getRegion(values.regionCode)
  const provinceArea = getProvinceArea(values.provinceCode)
  const locality = getLocality(values.cityMunicipalityCode)

  if (values.regionCode && !region) {
    context.addIssue({ code: 'custom', path: ['regionCode'], message: 'Select a region from the Philippine location list.' })
  }
  if (values.provinceCode && provinceArea?.regionCode !== values.regionCode) {
    context.addIssue({ code: 'custom', path: ['provinceCode'], message: 'Select a province or independent area within the chosen region.' })
  }
  if (values.cityMunicipalityCode && locality?.provinceAreaCode !== values.provinceCode) {
    context.addIssue({ code: 'custom', path: ['cityMunicipalityCode'], message: 'Select a city or municipality within the chosen province or area.' })
  }
})

export type TenantCreationValues = z.infer<typeof tenantCreationSchema>
