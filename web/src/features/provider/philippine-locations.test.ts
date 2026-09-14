import { describe, expect, it } from 'vitest'
import {
  getLocalities,
  getProvinceAreas,
  PHILIPPINE_LOCALITIES,
  PHILIPPINE_PROVINCE_AREAS,
  PHILIPPINE_REGIONS,
} from './philippine-locations'

describe('Philippine PSGC location options', () => {
  it('contains the current official region, province, city, and municipality totals', () => {
    expect(PHILIPPINE_REGIONS).toHaveLength(18)
    expect(PHILIPPINE_PROVINCE_AREAS.filter((area) => area.kind === 'province')).toHaveLength(82)
    expect(PHILIPPINE_LOCALITIES.filter((locality) => locality.kind === 'city')).toHaveLength(149)
    expect(PHILIPPINE_LOCALITIES.filter((locality) => locality.kind === 'municipality')).toHaveLength(1493)
  })

  it('maps Nueva Ecija localities to their province', () => {
    const regionAreas = getProvinceAreas('0300000000')
    const nuevaEcija = regionAreas.find((area) => area.name === 'Nueva Ecija')

    expect(nuevaEcija).toBeDefined()
    expect(getLocalities(nuevaEcija?.code ?? '').some((locality) => locality.name === 'San Isidro')).toBe(true)
  })

  it('keeps NCR and highly urbanized cities outside fake province relationships', () => {
    const ncrArea = getProvinceAreas('1300000000')[0]
    const davaoIndependentArea = getProvinceAreas('1100000000').find((area) => area.kind === 'independent_city')

    expect(ncrArea).toMatchObject({ name: 'National Capital Region (NCR)', kind: 'regional_area' })
    expect(getLocalities(ncrArea.code).some((locality) => locality.name === 'City of Makati')).toBe(true)
    expect(davaoIndependentArea?.name).toBe('City of Davao (Independent City)')
    expect(getLocalities(davaoIndependentArea?.code ?? '')).toEqual([
      expect.objectContaining({ name: 'City of Davao', kind: 'city' }),
    ])
  })

  it('includes the second-quarter 2026 municipality name updates', () => {
    expect(PHILIPPINE_LOCALITIES).toContainEqual(expect.objectContaining({ code: '1102324000', name: 'Sawata' }))
    expect(PHILIPPINE_LOCALITIES).toContainEqual(expect.objectContaining({ code: '1004217000', name: 'Don Victoriano' }))
  })
})
