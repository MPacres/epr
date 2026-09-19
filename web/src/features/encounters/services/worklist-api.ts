import { z } from 'zod'
import { request, atSite, type Scope } from './encounter-api'
export const taskSchema = z.object({ sourceId: z.uuid(), patientId: z.uuid(), patientName: z.string(), recordNumber: z.string(), encounterId: z.uuid(), ownerId: z.uuid(), kind: z.enum(['UNSIGNED_NOTE', 'FOLLOW_UP', 'LAB_ORDER', 'IMAGING_ORDER', 'REFERRAL']), instruction: z.string(), dueDate: z.string().nullable(), state: z.enum(['OPEN', 'RESOLVED']), version: z.number(), acknowledgedAt: z.string().nullable(), currentOwner: z.boolean().default(true) })
export const loadTasks = (scope: Scope, signal?: AbortSignal) => request(atSite(scope, '/clinical-worklist'), z.array(taskSchema), { signal })
