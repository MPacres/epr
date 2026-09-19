import { z } from 'zod'
import type * as Api from '../../encounters/services/api.generated'
const uuid = z.uuid()
const missingSchema = z.enum(['NOT_ASSESSED', 'UNKNOWN', 'DECLINED', 'NOT_APPLICABLE'])
export const ruleSchema: z.ZodType<Rule> = z.lazy(() => z.object({
  op: z.enum(['EQ', 'IN', 'PRESENT', 'ALL', 'ANY', 'NOT']), fieldId: uuid.nullable(), values: z.array(z.string()), rules: z.array(ruleSchema),
}).strict())
export type Rule = { op: NonNullable<Api.Rule['op']>; fieldId: string | null; values: string[]; rules: Rule[] }
export const fieldSchema: z.ZodType<Field> = z.lazy(() => z.object({
  id: uuid, key: z.string(), concept: z.string(), label: z.string(),
  type: z.enum(['TEXT', 'MULTILINE_TEXT', 'INTEGER', 'DECIMAL', 'QUANTITY', 'DATE', 'DATETIME', 'SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'ASSESSMENT_STATE', 'REFERENCE', 'GROUP']),
  options: z.array(z.string()), units: z.array(z.string()), allowedMissingStates: z.array(missingSchema),
  maxLength: z.number().int().min(1).max(10000), maxItems: z.number().int().min(0).max(30), precision: z.number().int().min(0).max(6),
  requiredAtSign: z.boolean(), sensitivity: z.enum(['CLINICAL', 'RESTRICTED_REPRODUCTIVE']),
  visibleWhen: ruleSchema.nullable(), requiredWhen: ruleSchema.nullable(), fields: z.array(fieldSchema),
}).strict())
export type Field = Omit<Required<Api.Field>, 'visibleWhen' | 'requiredWhen' | 'fields'> & { visibleWhen: Rule | null; requiredWhen: Rule | null; fields: Field[] }
export const definitionSchema = z.object({
  schemaVersion: z.literal('1.0'), ruleVersion: z.literal('1'), rendererVersion: z.literal('1'), extractionVersion: z.literal('none'), terminologyVersion: z.literal('uncoded-1'),
  templateKey: z.string(), title: z.string(), specialty: z.string(), encounterType: z.string(), businessVersion: z.string(),
  requiresPregnancyEpisode: z.boolean(), syntheticOnly: z.literal(true), sections: z.array(z.object({ id: uuid, key: z.string(), label: z.string(), fields: z.array(fieldSchema) }).strict()),
}).strict()
export type Definition = z.infer<typeof definitionSchema>
export const valueSchema = z.object({ text: z.string().nullable(), number: z.number().finite().nullable(), unit: z.string().nullable(), choices: z.array(z.string()), referenceId: uuid.nullable(), referenceVersion: z.number().nullable(), items: z.array(uuid) }).strict()
export const answerSchema = z.object({
  fieldId: uuid, groupItemId: uuid.nullable(), value: valueSchema.nullable(), missingState: missingSchema.nullable(),
  source: z.object({ kind: z.enum(['PATIENT_REPORTED', 'CLINICIAN_ENTERED', 'RECORD_REFERENCE']), recordId: uuid.nullable(), version: z.number().nullable(), clinicalTime: z.string().nullable(), description: z.string().min(1).max(500) }).strict(),
  review: z.enum(['NOT_REVIEWED', 'REVIEWED']), hiddenResolution: z.enum(['RETAIN_RELEVANT', 'SUPERSEDED']).nullable(), resolutionReason: z.string().nullable(),
}).strict().refine(a => (a.value === null) !== (a.missingState === null), 'Enter a value or choose an assessment state.')
export type Answer = z.infer<typeof answerSchema>
export type Value = z.infer<typeof valueSchema>
export const address = (field: string, item: string | null = null) => `${field}__${item ?? 'root'}`
export const emptyValue = (): Value => ({ text: null, number: null, unit: null, choices: [], referenceId: null, referenceVersion: null, items: [] })
export const newAnswer = (field: string, item: string | null = null): Answer => ({ fieldId: field, groupItemId: item, value: null, missingState: 'NOT_ASSESSED', source: { kind: 'CLINICIAN_ENTERED', recordId: null, version: null, clinicalTime: null, description: 'Entered during this encounter' }, review: 'NOT_REVIEWED', hiddenResolution: null, resolutionReason: null })
export type Truth = 'TRUE' | 'FALSE' | 'UNKNOWN'
export function evaluate(rule: Rule | null, answers: Record<string, Answer>, item: string | null = null, depth = 0): Truth {
  if (!rule) return 'TRUE'
  if (depth > 6) throw new Error('Unsupported template rule depth.')
  if (rule.op === 'NOT') { const value = evaluate(rule.rules[0], answers, item, depth + 1); return value === 'UNKNOWN' ? value : value === 'TRUE' ? 'FALSE' : 'TRUE' }
  if (rule.op === 'ALL' || rule.op === 'ANY') {
    const values = rule.rules.map(child => evaluate(child, answers, item, depth + 1))
    if (rule.op === 'ALL' && values.includes('FALSE')) return 'FALSE'
    if (rule.op === 'ANY' && values.includes('TRUE')) return 'TRUE'
    if (values.includes('UNKNOWN')) return 'UNKNOWN'
    return rule.op === 'ALL' ? 'TRUE' : 'FALSE'
  }
  const answer = answers[address(rule.fieldId!, item)] ?? answers[address(rule.fieldId!)]
  if (!answer || answer.missingState !== null) return 'UNKNOWN'
  if (rule.op === 'PRESENT') return 'TRUE'
  return answer.value!.choices.some(value => rule.values.includes(value)) ? 'TRUE' : 'FALSE'
}
export function fieldsOf(definition: Definition) { return definition.sections.flatMap(s => s.fields.flatMap(f => [f, ...f.fields])) }
