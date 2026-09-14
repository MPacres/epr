import { z } from 'zod'
import type { PortalSurface } from './portal-surface'

const authRoleSchema = z.enum(['SUPERADMIN', 'PROVIDER_SUPPORT', 'PRACTICE_STAFF', 'CLINICIAN'])
const authUserSchema = z.object({
  username: z.string(),
  displayName: z.string(),
  roles: z.array(authRoleSchema),
  passwordResetRequired: z.boolean(),
})
const csrfSchema = z.object({ headerName: z.string(), token: z.string() })
const loginErrorSchema = z.object({ message: z.string() })

export type AuthRole = z.infer<typeof authRoleSchema>
export type AuthUser = z.infer<typeof authUserSchema>
export type LoginCommand = { username: string; password: string; remember: boolean }
export type PasswordChangeCommand = { newPassword: string; confirmPassword: string; idempotencyKey: string }

export class AuthRequestError extends Error {
  readonly kind: 'credentials' | 'authorization' | 'network' | 'server'

  constructor(kind: 'credentials' | 'authorization' | 'network' | 'server', message: string) {
    super(message)
    this.kind = kind
  }
}

export async function fetchCsrfToken(): Promise<z.infer<typeof csrfSchema>> {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new AuthRequestError('server', 'Secure sign-in could not be prepared. Try again.')
  return csrfSchema.parse(await response.json())
}

export async function fetchSession(signal?: AbortSignal): Promise<AuthUser | null> {
  let response: Response
  try {
    response = await fetch('/api/v1/auth/session', { credentials: 'include', signal })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new AuthRequestError('network', 'The EPR service could not be reached. Check that the API is running and try again.')
  }
  if (response.status === 401) return null
  if (!response.ok) throw new AuthRequestError('server', 'Your session could not be verified. Try again.')
  return authUserSchema.parse(await response.json())
}

export async function createSession(command: LoginCommand): Promise<AuthUser> {
  try {
    const csrf = await fetchCsrfToken()
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify(command),
    })
    if (response.status === 401) {
      const error = loginErrorSchema.safeParse(await response.json())
      throw new AuthRequestError('credentials', error.success ? error.data.message : 'The username or password is incorrect.')
    }
    if (response.status === 403) {
      const error = loginErrorSchema.safeParse(await response.json())
      throw new AuthRequestError('authorization', error.success ? error.data.message : 'This account cannot use this portal.')
    }
    if (!response.ok) throw new AuthRequestError('server', 'Sign-in could not be completed. Try again.')
    return authUserSchema.parse(await response.json())
  } catch (error) {
    if (error instanceof AuthRequestError) throw error
    if (error instanceof TypeError) throw new AuthRequestError('network', 'The EPR service could not be reached. Check that the API is running and try again.')
    throw new AuthRequestError('server', 'Sign-in could not be completed. Try again.')
  }
}

export async function deleteSession(): Promise<void> {
  try {
    const csrf = await fetchCsrfToken()
    const response = await fetch('/api/v1/auth/logout', {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new AuthRequestError('server', 'Sign-out could not be completed. Try again.')
  } catch (error) {
    if (error instanceof AuthRequestError) throw error
    throw new AuthRequestError('network', 'The EPR service could not be reached. Try again.')
  }
}

export async function changeRequiredPassword(command: PasswordChangeCommand): Promise<AuthUser> {
  try {
    const csrf = await fetchCsrfToken()
    const response = await fetch('/api/v1/auth/password-change', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': command.idempotencyKey,
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify({ newPassword: command.newPassword, confirmPassword: command.confirmPassword }),
    })
    if (!response.ok) {
      const problem = z.object({ detail: z.string().optional() }).safeParse(await response.json().catch(() => ({})))
      throw new AuthRequestError('server', problem.success ? problem.data.detail ?? 'The password could not be changed.' : 'The password could not be changed.')
    }
    return authUserSchema.parse(await response.json())
  } catch (error) {
    if (error instanceof AuthRequestError) throw error
    if (error instanceof TypeError) throw new AuthRequestError('network', 'The EPR service could not be reached. Check the connection and try again.')
    throw new AuthRequestError('server', 'The password change response could not be verified.')
  }
}

export function homePathFor(user: AuthUser, surface: PortalSurface = 'combined'): string | null {
  const isProvider = user.roles.some(role => role === 'SUPERADMIN' || role === 'PROVIDER_SUPPORT')
  const isTenant = user.roles.some(role => role === 'PRACTICE_STAFF' || role === 'CLINICIAN')
  if (surface === 'support') return isProvider ? '/provider' : null
  if (surface === 'clinical') return isTenant ? '/dashboard' : null
  if (isProvider) return '/provider'
  return isTenant ? '/dashboard' : null
}
