import { describe, expect, it } from 'vitest'
import { passwordResetSchema } from './password-reset-schema'

describe('required password reset validation', () => {
  it('requires a strong confirmed replacement', () => {
    expect(passwordResetSchema.safeParse({ newPassword: 'short', confirmPassword: 'different' }).success).toBe(false)
    expect(passwordResetSchema.safeParse({ newPassword: 'Private-Workspace9!', confirmPassword: 'Private-Workspace9!' }).success).toBe(true)
  })
})
