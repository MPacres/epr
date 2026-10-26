import { describe, expect, it } from 'vitest'
import { orderDraftSchema } from './order-draft-validation'

const readyOrder = {
  indication: 'Assess the reported concern',
  destination: 'Main Clinic laboratory',
  priority: 'ROUTINE',
  requestedDate: '2026-10-26',
  specimen: '',
  bodySite: '',
  laterality: '',
  instructions: '',
  fasting: false,
}

describe('lab and imaging request save requirements', () => {
  it.each(['LAB_ORDER', 'IMAGING_ORDER'] as const)('blocks saving %s without a reason or requested date', kind => {
    for (const field of ['indication', 'requestedDate'] as const) {
      const result = orderDraftSchema(kind).safeParse({ ...readyOrder, [field]: '   ' })
      expect(result.success).toBe(false)
      if (!result.success) expect(result.error.issues.some(issue => issue.path[0] === field)).toBe(true)
    }
  })

  it('accepts a laboratory request without a location', () => {
    expect(orderDraftSchema('LAB_ORDER').parse({ ...readyOrder, destination: '   ' }).destination).toBe('')
  })

  it('requires an imaging destination', () => {
    const result = orderDraftSchema('IMAGING_ORDER').safeParse({ ...readyOrder, destination: '   ' })
    expect(result.success).toBe(false)
    if (!result.success) expect(result.error.issues.some(issue => issue.path[0] === 'destination')).toBe(true)
  })

  it('keeps the entered request while accepting complete details', () => {
    expect(orderDraftSchema('LAB_ORDER').parse(readyOrder)).toMatchObject(readyOrder)
  })
})
