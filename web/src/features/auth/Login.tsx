import { zodResolver } from '@hookform/resolvers/zod'
import {
  Building2,
  CalendarDays,
  ChevronRight,
  ClipboardCheck,
  ClipboardList,
  Eye,
  EyeOff,
  FilePlus2,
  FileText,
  FlaskConical,
  Inbox,
  Info,
  LockKeyhole,
  Mail,
  Pill,
  ShieldCheck,
  Stethoscope,
  TriangleAlert,
  UserRound,
  UsersRound,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { EprMark } from '../../components/brand/epr-mark'
import { AuthRequestError, homePathFor } from './auth-client'
import { useAuth } from './auth-context'
import { type LoginValues, loginSchema } from './login-schema'
import { portalLabel, type PortalSurface } from './portal-surface'
import './login.css'

const journey = [
  { label: 'Schedule', icon: CalendarDays, tone: 'blue' },
  { label: 'Queue', icon: UsersRound, tone: 'teal' },
  { label: 'Consultation', icon: Stethoscope, tone: 'violet' },
  { label: 'Follow-up', icon: ClipboardCheck, tone: 'amber' },
] as const

const chartRows = [
  { label: 'Overview', icon: FileText, tone: 'blue' },
  { label: 'Medications', icon: Pill, tone: 'teal' },
  { label: 'Orders & Results', icon: FlaskConical, tone: 'violet' },
  { label: 'Care Plan', icon: ClipboardList, tone: 'amber' },
] as const

function Brand({ inverse = false, surface }: { inverse?: boolean; surface: PortalSurface }) {
  const label = portalLabel(surface)
  return <div className={`login-brand ${inverse ? 'inverse' : ''}`} aria-label={`EPR ${label}`}>
    <EprMark className="login-brand-mark" />
    <span className="login-brand-copy"><strong>EPR</strong><span>{label}</span></span>
  </div>
}

function AbstractLines({ short = false }: { short?: boolean }) {
  return <span className={`abstract-lines ${short ? 'short' : ''}`} aria-hidden="true"><i /><i /></span>
}

function WorkflowJourney({ mobile = false }: { mobile?: boolean }) {
  return <div className={mobile ? 'mobile-journey' : 'workflow-journey'} aria-label="Schedule, queue, consultation, and follow-up">
    <span className="journey-line" aria-hidden="true" />
    {journey.map(({ label, icon: Icon, tone }) => <div className={`journey-stage ${tone}`} key={label}>
      <span className="journey-icon"><Icon aria-hidden="true" /></span>
      <strong>{label}</strong>
      {mobile ? null : <AbstractLines short={label === 'Follow-up'} />}
    </div>)}
  </div>
}

function WorkflowPreview({ surface }: { surface: PortalSurface }) {
  return <section className="login-visual" aria-labelledby="workflow-title">
    <Brand inverse surface={surface} />
    <FilePlus2 className="document-motif" aria-hidden="true" />
    <div className="workflow-canvas">
      <header className="workflow-heading"><h2 id="workflow-title">Illustrative clinical workflow</h2><p>A preview of scheduling through follow-up.</p></header>
      <WorkflowJourney />
      <div className="workflow-previews">
        <section className="preview-region chart-preview" aria-labelledby="chart-preview-title">
          <h3 id="chart-preview-title">Patient chart</h3>
          <div className="chart-preview-rows">
            {chartRows.map(({ label, icon: Icon, tone }) => <div className="preview-row" key={label}>
              <span className={`preview-icon ${tone}`}><Icon aria-hidden="true" /></span>
              <span className="preview-row-copy"><strong>{label}</strong><AbstractLines /></span>
              <ChevronRight aria-hidden="true" />
            </div>)}
          </div>
        </section>
        <div className="preview-stack">
          <section className="preview-region inbox-preview" aria-labelledby="inbox-preview-title">
            <h3 id="inbox-preview-title">Clinical inbox</h3>
            {[['Review', 'blue'], ['Today', 'teal'], ['Follow-up', 'amber']].map(([label, tone], index) => <div className="inbox-preview-row" key={label}>
              {index === 0 ? <Mail aria-hidden="true" /> : index === 1 ? <FileText aria-hidden="true" /> : <Inbox aria-hidden="true" />}
              <AbstractLines short />
              <span className={`preview-chip ${tone}`}>{label}</span>
            </div>)}
          </section>
          <section className="preview-region practice-preview" aria-labelledby="practice-preview-title">
            <h3 id="practice-preview-title">Practice context</h3>
            {[0, 1].map(index => <div className="practice-preview-row" key={index}>
              <span className={`preview-icon ${index === 0 ? 'blue' : 'teal'}`}><Building2 aria-hidden="true" /></span>
              <AbstractLines short />
              <ChevronRight aria-hidden="true" />
            </div>)}
          </section>
        </div>
      </div>
    </div>
    <div className="mobile-workflow">
      <header><h2>Illustrative clinical workflow</h2><p>A preview of scheduling through follow-up.</p></header>
      <WorkflowJourney mobile />
    </div>
  </section>
}

function wrongPortalMessage(surface: PortalSurface): string {
  return surface === 'clinical'
    ? 'This account cannot use the physician portal. Sign in at support.epr.test instead.'
    : 'This account cannot use provider support. Sign in at epr.test instead.'
}

export function Login({ surface = 'combined' }: { surface?: PortalSurface }) {
  const auth = useAuth()
  const navigate = useNavigate()
  const [showPassword, setShowPassword] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'info' | 'error'; message: string } | null>(null)
  const usernameRef = useRef<HTMLInputElement | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '', remember: false },
  })
  const { ref: usernameRegisterRef, ...usernameField } = register('username')

  useEffect(() => {
    document.title = `${portalLabel(surface)} sign in · EPR`
    if (auth.status === 'authenticated' && !isSubmitting) {
      const destination = homePathFor(auth.user, surface)
      if (destination) navigate(destination, { replace: true })
      else if (!notice) {
        void auth.signOut()
          .then(() => setNotice({ tone: 'error', message: wrongPortalMessage(surface) }))
          .catch(() => setNotice({ tone: 'error', message: 'This account is not authorized for this portal. Sign out and use the correct portal.' }))
      }
    } else {
      usernameRef.current?.focus({ preventScroll: true })
    }
  }, [auth, auth.status, auth.user, isSubmitting, navigate, notice, surface])

  async function submit(values: LoginValues) {
    setNotice(null)
    try {
      const user = await auth.signIn(values)
      const destination = homePathFor(user, surface)
      if (!destination) {
        await auth.signOut()
        setNotice({ tone: 'error', message: wrongPortalMessage(surface) })
        return
      }
      navigate(destination, { replace: true })
    } catch (error) {
      const message = error instanceof AuthRequestError ? error.message : 'Sign-in could not be completed. Try again.'
      setNotice({ tone: 'error', message })
      requestAnimationFrame(() => document.getElementById('login-feedback')?.focus())
    }
  }

  function showUnavailable(label: string) {
    setNotice({ tone: 'info', message: `${label} is not connected yet. Contact your EPR administrator for help.` })
  }

  return <main className="login-page">
    <section className="login-panel" aria-labelledby="login-title">
      <div className="desktop-login-brand"><Brand surface={surface} /></div>
      <div className="login-form-wrap">
        <header className="login-heading">
          <h1 id="login-title">Welcome back</h1>
          <p>{surface === 'clinical' ? 'Sign in to continue to your EPR clinical workspace.' : surface === 'support' ? 'Sign in to continue to EPR provider support.' : 'Sign in to continue to the EPR provider portal.'}</p>
        </header>
        <form className="login-form" onSubmit={handleSubmit(submit)} noValidate>
          <div className="login-field">
            <label htmlFor="username">Username</label>
            <div className={`login-input ${errors.username ? 'invalid' : ''}`}>
              <UserRound aria-hidden="true" />
              <input id="username" autoComplete="username" placeholder="Enter your username" aria-invalid={!!errors.username} aria-describedby={errors.username ? 'username-error' : undefined}
                {...usernameField} ref={element => { usernameRegisterRef(element); usernameRef.current = element }} />
            </div>
            {errors.username ? <p className="field-error" id="username-error">{errors.username.message}</p> : null}
          </div>
          <div className="login-field">
            <label htmlFor="password">Password</label>
            <div className={`login-input ${errors.password ? 'invalid' : ''}`}>
              <LockKeyhole aria-hidden="true" />
              <input id="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" aria-invalid={!!errors.password}
                aria-describedby={errors.password ? 'password-error' : undefined} {...register('password')} />
              <button type="button" className="password-toggle" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>
                {showPassword ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
              </button>
            </div>
            {errors.password ? <p className="field-error" id="password-error">{errors.password.message}</p> : null}
          </div>
          <div className="login-options">
            <label className="remember-option"><input type="checkbox" {...register('remember')} /><span>Keep me signed in<span className="remember-device"> on this device</span></span></label>
            <button type="button" className="login-link" onClick={() => showUnavailable('Password recovery')}>Forgot password?</button>
          </div>
          <Button className="login-submit" type="submit" disabled={isSubmitting}>{isSubmitting ? 'Signing in…' : 'Sign in'}</Button>
          {notice ? <p className={`login-notice ${notice.tone}`} id="login-feedback" role={notice.tone === 'error' ? 'alert' : 'status'} tabIndex={-1}>{notice.tone === 'error' ? <TriangleAlert aria-hidden="true" /> : <Info aria-hidden="true" />}{notice.message}</p> : <p className="login-security"><ShieldCheck aria-hidden="true" />Your session is protected and activity may be audited.</p>}
        </form>
      </div>
      <footer className="login-footer">
        <div><button type="button" className="login-link" onClick={() => showUnavailable('Privacy information')}>Privacy</button><span aria-hidden="true" />
        <button type="button" className="login-link" onClick={() => showUnavailable('Help')}>Help</button></div>
        <small>{surface === 'clinical' ? 'Secure clinical access' : 'Secure provider access'}</small>
      </footer>
    </section>
    <WorkflowPreview surface={surface} />
  </main>
}
