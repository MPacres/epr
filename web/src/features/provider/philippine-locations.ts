import locationsCsv from './data/psgc-2026-q2-locations.csv?raw'

// Derived from the PSA PSGC publication for 30 June 2026. The normalized
// province-area handling follows https://github.com/fish-and-bear/psgc so NCR,
// highly urbanized cities, and special geographic areas are not assigned to a
// province they do not administratively belong to.
export const PHILIPPINES_COUNTRY_CODE = 'PH' as const
export const PHILIPPINES_COUNTRY_NAME = 'Philippines'
export const PSGC_RELEASE_LABEL = 'PSGC as of 30 June 2026'
export const PSGC_SOURCE_URL = 'https://psa.gov.ph/classification/psgc/'

export type ProvinceAreaKind = 'province' | 'independent_city' | 'regional_area' | 'special_area'
export type LocalityKind = 'city' | 'municipality'

export type PhilippineRegion = {
  code: string
  name: string
}

export type PhilippineProvinceArea = {
  code: string
  name: string
  regionCode: string
  kind: ProvinceAreaKind
}

export type PhilippineLocality = {
  code: string
  name: string
  regionCode: string
  provinceAreaCode: string
  kind: LocalityKind
}

type LocationRow = {
  regionCode: string
  regionName: string
  provinceCode: string
  provinceName: string
  provinceKind: ProvinceAreaKind
  localityCode: string
  localityName: string
  localityKind: LocalityKind
}

const collator = new Intl.Collator('en-PH', { sensitivity: 'base' })

function parseLocationRow(line: string): LocationRow {
  const [
    regionCode,
    regionName,
    provinceCode,
    provinceName,
    provinceKind,
    localityCode,
    localityName,
    localityKind,
  ] = line.split(',')

  return {
    regionCode,
    regionName,
    provinceCode,
    provinceName,
    provinceKind: provinceKind as ProvinceAreaKind,
    localityCode,
    localityName,
    localityKind: localityKind as LocalityKind,
  }
}

const locationRows = locationsCsv.trim().split('\n').slice(1).map(parseLocationRow)
const regionMap = new Map<string, PhilippineRegion>()
const provinceAreaMap = new Map<string, PhilippineProvinceArea>()
const localityMap = new Map<string, PhilippineLocality>()

export const PHILIPPINE_LOCALITIES: readonly PhilippineLocality[] = locationRows.map((row) => {
  regionMap.set(row.regionCode, { code: row.regionCode, name: row.regionName })
  provinceAreaMap.set(row.provinceCode, {
    code: row.provinceCode,
    name: row.provinceName,
    regionCode: row.regionCode,
    kind: row.provinceKind,
  })

  const locality = {
    code: row.localityCode,
    name: row.localityName,
    regionCode: row.regionCode,
    provinceAreaCode: row.provinceCode,
    kind: row.localityKind,
  }
  localityMap.set(locality.code, locality)
  return locality
})

export const PHILIPPINE_REGIONS: readonly PhilippineRegion[] = [...regionMap.values()]
export const PHILIPPINE_PROVINCE_AREAS: readonly PhilippineProvinceArea[] = [...provinceAreaMap.values()]

const provinceAreasByRegion = new Map<string, readonly PhilippineProvinceArea[]>()
const localitiesByProvinceArea = new Map<string, readonly PhilippineLocality[]>()

for (const region of PHILIPPINE_REGIONS) {
  provinceAreasByRegion.set(
    region.code,
    PHILIPPINE_PROVINCE_AREAS
      .filter((area) => area.regionCode === region.code)
      .toSorted((left, right) => collator.compare(left.name, right.name)),
  )
}

for (const area of PHILIPPINE_PROVINCE_AREAS) {
  localitiesByProvinceArea.set(
    area.code,
    PHILIPPINE_LOCALITIES
      .filter((locality) => locality.provinceAreaCode === area.code)
      .toSorted((left, right) => collator.compare(left.name, right.name)),
  )
}

export function getProvinceAreas(regionCode: string): readonly PhilippineProvinceArea[] {
  return provinceAreasByRegion.get(regionCode) ?? []
}

export function getLocalities(provinceAreaCode: string): readonly PhilippineLocality[] {
  return localitiesByProvinceArea.get(provinceAreaCode) ?? []
}

export function getRegion(regionCode: string): PhilippineRegion | undefined {
  return regionMap.get(regionCode)
}

export function getProvinceArea(provinceAreaCode: string): PhilippineProvinceArea | undefined {
  return provinceAreaMap.get(provinceAreaCode)
}

export function getLocality(localityCode: string): PhilippineLocality | undefined {
  return localityMap.get(localityCode)
}
