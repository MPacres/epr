import vectorText from '../../../../../api/src/main/resources/encounter-templates/rule-v1-vectors.json?raw'
import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import { address, answerSchema, evaluate, ruleSchema } from './contracts'
const vectors = z.array(z.object({ name: z.string(), rule: ruleSchema, answers: z.array(answerSchema), expected: z.enum(['TRUE', 'FALSE', 'UNKNOWN']) })).parse(JSON.parse(vectorText))
describe('shared Java and TypeScript rule version 1', () => {
  for (const vector of vectors) it(vector.name, () => {
    expect(evaluate(vector.rule, Object.fromEntries(vector.answers.map(a => [address(a.fieldId, a.groupItemId), a])))).toBe(vector.expected)
  })
})
