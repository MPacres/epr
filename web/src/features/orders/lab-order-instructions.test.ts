import { describe, expect, it } from 'vitest'
import { combineLabInstructions, splitLabInstructions } from './lab-order-instructions'

describe('lab fasting instructions', () => {
  it('round trips fasting and alerts without losing the original alert text', () => {
    const alert = 'Call the clinician before collection.\nUse the requested specimen.'
    expect(splitLabInstructions(combineLabInstructions(true, alert))).toEqual({ fasting: true, instructions: alert })
    expect(splitLabInstructions(combineLabInstructions(false, alert))).toEqual({ fasting: false, instructions: alert })
  })

  it('keeps historical instructions without a fasting directive as written', () => {
    expect(splitLabInstructions('Fasting may be considered.')).toEqual({ fasting: false, instructions: 'Fasting may be considered.' })
  })
})
