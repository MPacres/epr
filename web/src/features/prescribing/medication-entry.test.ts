import { describe, expect, it } from 'vitest'
import { matchMedicine, type MedicationCatalogEntry } from './medication-entry'

const catalog: MedicationCatalogEntry[] = [{ code: 'SYN-MED-A', version: 'synthetic-1', ingredient: 'Synthetic ingredient A', strength: '100 synthetic units', formulation: 'Synthetic tablet', doseUnit: 'synthetic dose units', quantityUnit: 'synthetic tablets' }]

describe('medicine entry matching', () => {
  it('finds an exact catalog name regardless of case or surrounding spaces', () => {
    expect(matchMedicine(' synthetic INGREDIENT a ', catalog)).toEqual(catalog[0])
  })

  it('keeps a different typed medicine outside the catalog', () => {
    expect(matchMedicine('Synthetic ingredient A extended', catalog)).toBeUndefined()
  })
})
