import { z } from 'zod'
import { createUuid } from '../../lib/create-uuid'
import type * as Wire from '../encounters/services/api.generated'
import type { Allergy, ClinicalListKind, ClinicalRecord, MedicationUse, Problem } from './clinical-list-api'

const optionalText = z.string().max(2000)
const coded = {
  codeSystem: z.string().max(200),
  codeValue: z.string().max(120),
  codeVersion: z.string().max(80),
}
const common = {
  informationSource: z.string().trim().min(1, 'Enter where this information came from.').max(200),
  notes: optionalText,
  correctionReason: z.string().max(500),
}
const allergyReactionDraftSchema = z.object({
  reactionId: z.string(),
  manifestation: z.string().trim().min(1, 'Describe the reaction or remove this reaction.').max(300),
  severity: z.enum(['MILD', 'MODERATE', 'SEVERE', 'UNKNOWN']),
  onset: z.string().max(120),
  exposureRoute: z.string().max(120),
})

export const allergyDraftSchema = z.object({
  substanceDisplay: z.string().trim().min(1, 'Enter the substance or agent.').max(300), ...coded,
  category: z.enum(['MEDICATION', 'FOOD', 'ENVIRONMENT', 'BIOLOGIC', 'OTHER']),
  reactionType: z.enum(['ALLERGY', 'INTOLERANCE', 'UNCERTAIN']),
  verificationStatus: z.enum(['UNCONFIRMED', 'CONFIRMED', 'REFUTED', 'ENTERED_IN_ERROR']),
  criticality: z.enum(['LOW', 'HIGH', 'UNABLE_TO_ASSESS']), onset: z.string().max(120), onsetPrecision: z.enum(['EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN']),
  lastOccurrence: z.string(), asserter: z.string().max(200), reactions: z.array(allergyReactionDraftSchema).max(50), ...common,
}).superRefine((value, context) => {
  if (value.onset.trim() && value.onsetPrecision === 'UNKNOWN') {
    context.addIssue({ code: 'custom', path: ['onsetPrecision'], message: 'Choose how precise the onset is.' })
  }
  if (!value.onset.trim() && value.onsetPrecision !== 'UNKNOWN') {
    context.addIssue({ code: 'custom', path: ['onset'], message: 'Enter the onset, or set precision to unknown.' })
  }
  const patterns = { EXACT: /^\d{4}-\d{2}-\d{2}$/, MONTH: /^\d{4}-\d{2}$/, YEAR: /^\d{4}$/ } as const
  const pattern = value.onsetPrecision === 'EXACT' || value.onsetPrecision === 'MONTH' || value.onsetPrecision === 'YEAR' ? patterns[value.onsetPrecision] : null
  if (pattern && value.onset.trim() && !pattern.test(value.onset.trim())) {
    const example = value.onsetPrecision === 'EXACT' ? 'YYYY-MM-DD' : value.onsetPrecision === 'MONTH' ? 'YYYY-MM' : 'YYYY'
    context.addIssue({ code: 'custom', path: ['onset'], message: `Use ${example}.` })
  }
})
export type AllergyDraft = z.infer<typeof allergyDraftSchema>

export const medicationDraftSchema = z.object({
  medicationDisplay: z.string().trim().min(1, 'Enter the medication.').max(300), ...coded,
  brand: z.string().max(300), strength: z.string().max(300), formulation: z.string().max(300), dose: z.string(), doseUnit: z.string().max(100), route: z.string().max(200), frequency: z.string().max(300), prn: z.boolean(), effectiveStart: z.string(), effectiveEnd: z.string(), effectivePrecision: z.enum(['EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN']), prescriber: z.string().max(300), adherence: z.enum(['TAKING_AS_REPORTED', 'TAKING_DIFFERENTLY', 'NOT_TAKING', 'UNKNOWN']), ...common,
}).superRefine((value, context) => {
  if ((value.dose.trim() === '') !== (value.doseUnit.trim() === '')) context.addIssue({ code: 'custom', path: ['dose'], message: 'Enter both the dose and its unit, or leave both blank.' })
  if (value.dose && (!Number.isFinite(Number(value.dose)) || Number(value.dose) <= 0)) context.addIssue({ code: 'custom', path: ['dose'], message: 'Dose must be greater than zero.' })
})
export type MedicationDraft = z.infer<typeof medicationDraftSchema>

export const problemDraftSchema = z.object({
  problemDisplay: z.string().trim().min(1, 'Enter the problem or diagnosis.').max(300), ...coded,
  verificationStatus: z.enum(['SUSPECTED', 'CONFIRMED', 'REFUTED', 'ENTERED_IN_ERROR']), onset: z.string(), onsetPrecision: z.enum(['EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN']), abatement: z.string(), abatementPrecision: z.enum(['EXACT', 'MONTH', 'YEAR', 'APPROXIMATE', 'UNKNOWN']), severity: z.string().max(120), priority: z.number().int().min(0), ...common,
})
export type ProblemDraft = z.infer<typeof problemDraftSchema>
export type ClinicalDraft = AllergyDraft | MedicationDraft | ProblemDraft

const text = (value: string) => value.trim() || null
const code = (draft: { codeSystem: string; codeValue: string; codeVersion: string }, display: string) => draft.codeSystem && draft.codeValue ? { system: draft.codeSystem, code: draft.codeValue, version: text(draft.codeVersion), display } : null

export function draftFor(kind: ClinicalListKind, record?: ClinicalRecord): ClinicalDraft {
  if (kind === 'ALLERGY') {
    const data = (record as Allergy | undefined)?.data
    return {
      substanceDisplay: data?.substanceDisplay ?? '', codeSystem: data?.code?.system ?? '', codeValue: data?.code?.code ?? '', codeVersion: data?.code?.version ?? '',
      category: (data?.category as AllergyDraft['category']) ?? 'OTHER', reactionType: (data?.reactionType as AllergyDraft['reactionType']) ?? 'UNCERTAIN',
      verificationStatus: (data?.verificationStatus as AllergyDraft['verificationStatus']) ?? 'UNCONFIRMED', criticality: (data?.criticality as AllergyDraft['criticality']) ?? 'UNABLE_TO_ASSESS',
      onset: data?.onset ?? '', onsetPrecision: (data?.onsetPrecision as AllergyDraft['onsetPrecision']) ?? 'UNKNOWN', lastOccurrence: data?.lastOccurrence?.slice(0, 10) ?? '',
      informationSource: data?.informationSource ?? '', asserter: data?.asserter ?? '', notes: data?.notes ?? '',
      reactions: data?.reactions.map(reaction => ({ reactionId: reaction.reactionId, manifestation: reaction.manifestation, severity: reaction.severity as AllergyDraft['reactions'][number]['severity'], onset: reaction.onset ?? '', exposureRoute: reaction.exposureRoute ?? '' })) ?? [],
      correctionReason: '',
    }
  }
  if (kind === 'MEDICATION_USE') {
    const data = (record as MedicationUse | undefined)?.data
    return { medicationDisplay: data?.medicationDisplay ?? '', codeSystem: data?.code?.system ?? '', codeValue: data?.code?.code ?? '', codeVersion: data?.code?.version ?? '', brand: data?.brand ?? '', strength: data?.strength ?? '', formulation: data?.formulation ?? '', dose: data?.dose?.toString() ?? '', doseUnit: data?.doseUnit ?? '', route: data?.route ?? '', frequency: data?.frequency ?? '', prn: data?.prn ?? false, effectiveStart: data?.effectiveStart ?? '', effectiveEnd: data?.effectiveEnd ?? '', effectivePrecision: (data?.effectivePrecision as MedicationDraft['effectivePrecision']) ?? 'UNKNOWN', informationSource: data?.informationSource ?? '', prescriber: data?.prescriber ?? '', adherence: (data?.adherence as MedicationDraft['adherence']) ?? 'UNKNOWN', notes: data?.notes ?? '', correctionReason: '' }
  }
  const data = (record as Problem | undefined)?.data
  return { problemDisplay: data?.problemDisplay ?? '', codeSystem: data?.code?.system ?? '', codeValue: data?.code?.code ?? '', codeVersion: data?.code?.version ?? '', verificationStatus: (data?.verificationStatus as ProblemDraft['verificationStatus']) ?? 'SUSPECTED', onset: data?.onset ?? '', onsetPrecision: (data?.onsetPrecision as ProblemDraft['onsetPrecision']) ?? 'UNKNOWN', abatement: data?.abatement ?? '', abatementPrecision: (data?.abatementPrecision as ProblemDraft['abatementPrecision']) ?? 'UNKNOWN', severity: data?.severity ?? '', priority: (record as Problem | undefined)?.priority ?? 0, informationSource: data?.informationSource ?? '', notes: data?.notes ?? '', correctionReason: '' }
}

export function commandFor(kind: ClinicalListKind, draft: ClinicalDraft, id: string, existing?: ClinicalRecord): Wire.CreateAllergy | Wire.CreateMedicationUse | Wire.CreateProblem | Wire.ReviseAllergy | Wire.ReviseMedicationUse | Wire.ReviseProblem {
  if (kind === 'ALLERGY') {
    const value = draft as AllergyDraft
    const existingAllergy = existing as Allergy | undefined
    const data: Wire.AllergyData = { substanceDisplay: value.substanceDisplay.trim(), code: code(value, value.substanceDisplay.trim()), category: value.category, reactionType: value.reactionType, verificationStatus: value.verificationStatus, criticality: value.criticality, onset: text(value.onset), onsetPrecision: value.onsetPrecision, lastOccurrence: value.lastOccurrence ? `${value.lastOccurrence}T00:00:00+08:00` : null, informationSource: value.informationSource.trim(), asserter: text(value.asserter), notes: text(value.notes), clinicalTime: existingAllergy ? existingAllergy.data.clinicalTime : new Date().toISOString(), encounterId: existingAllergy?.data.encounterId ?? null, reactions: value.reactions.map(reaction => ({ reactionId: reaction.reactionId || createUuid(), manifestation: reaction.manifestation.trim(), severity: reaction.severity, onset: text(reaction.onset), exposureRoute: text(reaction.exposureRoute) })) }
    return existing ? { expectedVersion: existing.version, reason: value.correctionReason.trim(), data } : { id, data }
  }
  if (kind === 'MEDICATION_USE') {
    const value = draft as MedicationDraft
    const data: Wire.MedicationUseData = { medicationDisplay: value.medicationDisplay.trim(), code: code(value, value.medicationDisplay.trim()), brand: text(value.brand), strength: text(value.strength), formulation: text(value.formulation), dose: value.dose ? Number(value.dose) : null, doseUnit: text(value.doseUnit), route: text(value.route), frequency: text(value.frequency), prn: value.prn, effectiveStart: text(value.effectiveStart), effectiveEnd: text(value.effectiveEnd), effectivePrecision: value.effectivePrecision, informationSource: value.informationSource.trim(), prescriber: text(value.prescriber), adherence: value.adherence, notes: text(value.notes), indicationProblemId: null, prescriptionId: null, clinicalTime: new Date().toISOString(), encounterId: null }
    return existing ? { expectedVersion: existing.version, reason: value.correctionReason.trim(), data } : { id, data }
  }
  const value = draft as ProblemDraft
  const data: Wire.ProblemData = { problemDisplay: value.problemDisplay.trim(), code: code(value, value.problemDisplay.trim()), verificationStatus: value.verificationStatus, onset: text(value.onset), onsetPrecision: value.onsetPrecision, abatement: text(value.abatement), abatementPrecision: value.abatementPrecision, severity: text(value.severity), informationSource: value.informationSource.trim(), notes: text(value.notes), clinicalTime: new Date().toISOString(), encounterId: null }
  return existing ? { expectedVersion: existing.version, reason: value.correctionReason.trim(), priority: value.priority, data } : { id, priority: value.priority, data }
}
