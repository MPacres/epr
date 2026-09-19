import { expect, it } from 'vitest'
import { address, emptyValue, newAnswer } from '../../encounter-templates/schema/contracts'
import { validatedAnswers } from './draft-values'
const field = '18f57041-8ad9-4c2c-adcf-332a5f047e92'
const values = (rawNumber: string) => ({ [address(field)]: { ...newAnswer(field), missingState: null, value: { ...emptyValue(), number: 0, unit: 'kg' }, rawNumber } })
it('preserves decimal clinical meaning without silently rounding unsafe numbers', () => {
  expect(validatedAnswers(values('+065.2000'))[0].value?.number).toBe(65.2)
  expect(() => validatedAnswers(values('99999999999999991'))).toThrow('supported precision')
  expect(() => validatedAnswers(values('123456789012.123456'))).toThrow('supported precision')
})
