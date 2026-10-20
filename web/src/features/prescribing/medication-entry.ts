import { z } from 'zod'

export const medicationCatalogSchema = z.object({ code: z.string(), version: z.string(), ingredient: z.string(), strength: z.string().nullable(), formulation: z.string().nullable(), doseUnit: z.string().nullable(), quantityUnit: z.string().nullable() })
export type MedicationCatalogEntry = z.infer<typeof medicationCatalogSchema>

export function matchMedicine(input: string, catalog: MedicationCatalogEntry[]) {
  const name = input.trim().toLocaleLowerCase()
  return catalog.find(entry => entry.ingredient.trim().toLocaleLowerCase() === name)
}
