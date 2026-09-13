import { describe, expect, it } from 'vitest'
import { loginSchema } from './login-schema'

describe('loginSchema', () => {
  it('requires both credentials and keeps entered values available for recovery', () => {
    const result = loginSchema.safeParse({ username: 'ana.santos', password: '', remember: true })

    expect(result.success).toBe(false)
    if (!result.success) {
      expect(result.error.flatten().fieldErrors.password).toEqual(['Enter your password.'])
      expect(result.error.flatten().fieldErrors.username).toBeUndefined()
    }
  })

  it('accepts a username and password without changing their values', () => {
    const result = loginSchema.safeParse({ username: 'ana.santos', password: 'demo-password', remember: false })

    expect(result).toMatchObject({
      success: true,
      data: { username: 'ana.santos', password: 'demo-password', remember: false },
    })
  })
})
