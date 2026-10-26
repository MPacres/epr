import { z } from 'zod'

const labOrderDraftSchema = z.object({
  catalogCode: z.string().nullish(),
  indication: z.string().trim().min(1, 'Enter the reason or clinical question.').max(4000),
  destination: z.string().trim().max(4000),
  priority: z.string().trim().min(1, 'Select a priority.'),
  requestedDate: z.string().trim().min(1, 'Choose a requested date.'),
  specimen: z.string(),
  bodySite: z.string(),
  laterality: z.string(),
  instructions: z.string().max(4000),
  fasting: z.boolean(),
})

const imagingOrderDraftSchema = labOrderDraftSchema.extend({
  destination: z.string().trim().min(1, 'Enter the receiving imaging facility.').max(4000),
})

export const orderDraftSchema = (kind: 'LAB_ORDER' | 'IMAGING_ORDER') => kind === 'LAB_ORDER' ? labOrderDraftSchema : imagingOrderDraftSchema

export type OrderDraftValues = z.infer<typeof labOrderDraftSchema>
