import { z } from 'zod'

export const passwordResetSchema = z.object({
  newPassword: z.string()
    .min(12, 'Use at least 12 characters.')
    .max(128, 'Use no more than 128 characters.')
    .regex(/[A-Z]/, 'Add an uppercase letter.')
    .regex(/[a-z]/, 'Add a lowercase letter.')
    .regex(/[0-9]/, 'Add a number.')
    .regex(/[^A-Za-z0-9]/, 'Add a symbol.'),
  confirmPassword: z.string().min(1, 'Confirm the new password.'),
}).refine(values => values.newPassword === values.confirmPassword, {
  path: ['confirmPassword'],
  message: 'The passwords do not match.',
})

export type PasswordResetValues = z.infer<typeof passwordResetSchema>
