import { answerSchema, address, type Answer } from '../../encounter-templates/schema/contracts'
export class DraftValidationError extends Error { readonly status = 422 }
export type DraftAnswer = Answer & { rawNumber?: string }
export type DraftValues = Record<string, DraftAnswer>
export const fromAnswers = (answers: Answer[]): DraftValues => Object.fromEntries(answers.map(a => [address(a.fieldId, a.groupItemId), a]))
const canonicalDecimal = (raw: string) => {
  const negative = raw.startsWith('-'); const [integer, fraction = ''] = raw.replace(/^[+-]/, '').split('.')
  const whole = integer.replace(/^0+/, '') || '0'; const decimals = fraction.replace(/0+$/, '')
  return `${negative && (whole !== '0' || decimals) ? '-' : ''}${whole}${decimals ? `.${decimals}` : ''}`
}
export function validatedAnswers(values: DraftValues): Answer[] {
  return Object.values(values).map(({ rawNumber, ...answer }) => {
    if (rawNumber !== undefined && answer.value) {
      if (rawNumber.trim() === '' || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(rawNumber)) throw new DraftValidationError('Enter a valid number or choose an assessment state.')
      const number = Number(rawNumber)
      if (!Number.isFinite(number) || canonicalDecimal(String(number)) !== canonicalDecimal(rawNumber)) throw new DraftValidationError('This number exceeds the supported precision. Review the original value.')
      answer = { ...answer, value: { ...answer.value, number } }
    }
    const parsed = answerSchema.safeParse(answer)
    if (!parsed.success) throw new DraftValidationError(parsed.error.issues[0]?.message ?? 'Review the entered value and source.')
    return parsed.data
  })
}
export function diffAnswers(before: DraftValues, after: DraftValues) {
  const valid = fromAnswers(validatedAnswers(after))
  const original = fromAnswers(validatedAnswers(before))
  return [...new Set([...Object.keys(original), ...Object.keys(valid)])].sort().flatMap(key => {
    if (JSON.stringify(original[key]) === JSON.stringify(valid[key])) return []
    const answer = valid[key] ?? null
    return [{ fieldId: (answer ?? original[key]).fieldId, groupItemId: (answer ?? original[key]).groupItemId, answer }]
  })
}
export function displayAnswer(answer?: Answer) {
  if (!answer) return 'Not recorded'
  if (answer.missingState) return answer.missingState.replaceAll('_', ' ').toLowerCase()
  const v = answer.value!
  if (v.findings?.length) return v.findings.map(finding => [...finding.phrases, finding.customText].filter(Boolean).join(' ')).join('; ')
  return v.text ?? (v.number !== null ? `${v.number} ${v.unit ?? ''}` : v.choices.length ? v.choices.join(', ') : v.referenceId ?? `${v.items.length} history items`)
}
