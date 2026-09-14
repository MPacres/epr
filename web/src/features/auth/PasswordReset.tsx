import { zodResolver } from '@hookform/resolvers/zod'
import { CheckCircle2, Circle, Eye, EyeOff, KeyRound, LockKeyhole, LogOut, ShieldCheck, TriangleAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Navigate } from 'react-router-dom'
import { EprMark } from '../../components/brand/epr-mark'
import { Button } from '../../components/ui/button'
import { AuthRequestError, homePathFor } from './auth-client'
import { useAuth } from './auth-context'
import { passwordResetSchema, type PasswordResetValues } from './password-reset-schema'
import { portalLabel, type PortalSurface } from './portal-surface'
import './password-reset.css'

const requirements = [
  { label: '12 or more characters', valid: (value: string) => value.length >= 12 },
  { label: 'Uppercase and lowercase letters', valid: (value: string) => /[A-Z]/.test(value) && /[a-z]/.test(value) },
  { label: 'At least one number', valid: (value: string) => /[0-9]/.test(value) },
  { label: 'At least one symbol', valid: (value: string) => /[^A-Za-z0-9]/.test(value) },
]

function createIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function PasswordReset({ surface = 'combined' }: { surface?: PortalSurface }) {
  const auth = useAuth()
  const [showPasswords, setShowPasswords] = useState(false)
  const [completed, setCompleted] = useState(false)
  const [signOutPending, setSignOutPending] = useState(false)
  const [signOutError, setSignOutError] = useState<string | null>(null)
  const [idempotencyKey] = useState(createIdempotencyKey)
  const form = useForm<PasswordResetValues>({
    resolver: zodResolver(passwordResetSchema),
    defaultValues: { newPassword: '', confirmPassword: '' },
  })
  const newPassword = useWatch({ control: form.control, name: 'newPassword' })

  useEffect(() => {
    document.title = `Create a private password · EPR ${portalLabel(surface)}`
  }, [surface])

  async function submit(values: PasswordResetValues) {
    try {
      await auth.changePassword({ ...values, idempotencyKey })
      setCompleted(true)
    } catch (error) {
      form.setError('root', { message: error instanceof AuthRequestError ? error.message : 'Your password could not be changed. Try again.' })
    }
  }

  async function signOutSecurely() {
    setSignOutPending(true)
    setSignOutError(null)
    try {
      await auth.signOut()
    } catch {
      setSignOutError('Sign out could not be completed. Check the connection and try again.')
      setSignOutPending(false)
    }
  }

  if (auth.status === 'loading') return <main className="auth-status-page"><span className="auth-status-mark" aria-hidden="true" /><h1>Checking your account</h1><p role="status">Preparing secure password setup…</p></main>
  if (auth.status === 'unavailable') return <main className="auth-status-page"><TriangleAlert aria-hidden="true" /><h1>Account check unavailable</h1><p role="alert">{auth.message}</p><Button onClick={() => void auth.retry()}>Try again</Button></main>
  if (auth.status === 'unauthenticated') return <Navigate to="/login" replace />
  if (!homePathFor(auth.user, surface)) return <Navigate to="/login" replace />
  if (!auth.user.passwordResetRequired && !completed) return <Navigate to={homePathFor(auth.user, surface) ?? '/login'} replace />

  if (completed) return <main className="password-reset-page">
    <section className="password-reset-sheet password-reset-complete" aria-labelledby="password-complete-title">
      <div className="password-reset-brand"><EprMark /><span><strong>EPR</strong><small>{portalLabel(surface)}</small></span></div>
      <span className="password-complete-mark"><CheckCircle2 aria-hidden="true" /></span>
      <h1 id="password-complete-title">Your private password is set</h1>
      <p>The temporary password no longer works. Your administrator account is ready, but the Practice administration workspace is not connected yet.</p>
      <div className="password-reset-next"><ShieldCheck aria-hidden="true" /><span><strong>Setup continues with support</strong><small>Your support representative can complete the remaining Practice configuration with you.</small></span></div>
      {signOutError ? <p className="password-reset-alert" role="alert"><TriangleAlert aria-hidden="true" />{signOutError}</p> : null}
      <Button onClick={() => void signOutSecurely()} disabled={signOutPending}><LogOut aria-hidden="true" />{signOutPending ? 'Signing out…' : 'Sign out securely'}</Button>
    </section>
  </main>

  return <main className="password-reset-page">
    <section className="password-reset-sheet" aria-labelledby="password-reset-title">
      <div className="password-reset-brand"><EprMark /><span><strong>EPR</strong><small>{portalLabel(surface)}</small></span></div>
      <div className="password-reset-heading">
        <span><KeyRound aria-hidden="true" /></span>
        <div><h1 id="password-reset-title">Create your private password</h1><p>Welcome, {auth.user.displayName}. Replace the temporary password before continuing.</p></div>
      </div>
      <div className="password-reset-account"><span>Account</span><strong>{auth.user.username}</strong></div>
      <form onSubmit={form.handleSubmit(submit)} noValidate>
        <div className="password-reset-field">
          <label htmlFor="new-password">New password</label>
          <div className={form.formState.errors.newPassword ? 'password-reset-input invalid' : 'password-reset-input'}>
            <LockKeyhole aria-hidden="true" />
            <input id="new-password" type={showPasswords ? 'text' : 'password'} autoComplete="new-password" aria-invalid={!!form.formState.errors.newPassword} aria-describedby="password-requirements new-password-error" {...form.register('newPassword')} />
            <button type="button" aria-label={showPasswords ? 'Hide passwords' : 'Show passwords'} aria-pressed={showPasswords} onClick={() => setShowPasswords(value => !value)}>{showPasswords ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}</button>
          </div>
          {form.formState.errors.newPassword ? <p className="password-reset-error" id="new-password-error">{form.formState.errors.newPassword.message}</p> : null}
        </div>
        <ul className="password-requirements" id="password-requirements" aria-label="Password requirements">
          {requirements.map(requirement => {
            const met = requirement.valid(newPassword)
            return <li className={met ? 'met' : ''} key={requirement.label} aria-label={`${met ? 'Met' : 'Not met'}: ${requirement.label}`}>{met ? <CheckCircle2 aria-hidden="true" /> : <Circle aria-hidden="true" />}{requirement.label}</li>
          })}
        </ul>
        <div className="password-reset-field">
          <label htmlFor="confirm-password">Confirm new password</label>
          <div className={form.formState.errors.confirmPassword ? 'password-reset-input invalid' : 'password-reset-input'}><LockKeyhole aria-hidden="true" /><input id="confirm-password" type={showPasswords ? 'text' : 'password'} autoComplete="new-password" aria-invalid={!!form.formState.errors.confirmPassword} aria-describedby={form.formState.errors.confirmPassword ? 'confirm-password-error' : undefined} {...form.register('confirmPassword')} /></div>
          {form.formState.errors.confirmPassword ? <p className="password-reset-error" id="confirm-password-error">{form.formState.errors.confirmPassword.message}</p> : null}
        </div>
        {form.formState.errors.root ? <p className="password-reset-alert" role="alert"><TriangleAlert aria-hidden="true" />{form.formState.errors.root.message}</p> : null}
        <Button type="submit" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? 'Saving private password…' : 'Set private password'}</Button>
      </form>
      <p className="password-reset-security"><ShieldCheck aria-hidden="true" />The temporary password will be invalidated immediately after this change.</p>
    </section>
  </main>
}
