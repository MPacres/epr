import { z } from 'zod'

export const tenantCreationSchema = z.object({
  practiceName: z.string().trim().min(2, 'Enter the name patients and staff know this practice by.').max(120, 'Keep the practice name under 120 characters.'),
  administratorName: z.string().trim().min(2, 'Enter the initial administrator’s full name.').max(120, 'Keep the administrator’s name under 120 characters.'),
  administratorEmail: z.email('Enter a valid work email address.'),
  siteName: z.string().trim().min(2, 'Enter the first clinic or site name.').max(120, 'Keep the site name under 120 characters.'),
  facilityName: z.string().trim().max(160, 'Keep the facility or building name under 160 characters.'),
  cityMunicipality: z.string().trim().min(2, 'Enter the city or municipality.').max(100, 'Keep the city or municipality under 100 characters.'),
  province: z.string().trim().min(2, 'Enter the province or region.').max(100, 'Keep the province or region under 100 characters.'),
  contactNumber: z.string().trim().max(30, 'Keep the contact number under 30 characters.'),
})

export type TenantCreationValues = z.infer<typeof tenantCreationSchema>
