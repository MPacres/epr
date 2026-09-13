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
  Plus,
  ShieldCheck,
  Stethoscope,
  UserRound,
  UsersRound,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Button } from '../../components/ui/button'
import { type LoginValues, loginSchema } from './login-schema'
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

function Brand({ inverse = false }: { inverse?: boolean }) {
  return <div className={`login-brand ${inverse ? 'inverse' : ''}`} aria-label="EPR Clinical workspace">
    <span className="login-brand-mark"><Plus aria-hidden="true" /></span>
    <span className="login-brand-copy"><strong>EPR</strong><span>Clinical workspace</span></span>
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

function WorkflowPreview() {
  return <section className="login-visual" aria-labelledby="workflow-title">
    <Brand inverse />
    <FilePlus2 className="document-motif" aria-hidden="true" />
    <div className="workflow-canvas">
      <header className="workflow-heading"><h2 id="workflow-title">A connected clinical day</h2><p>From scheduling to follow-up, all in one place.</p></header>
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
      <header><h2>A connected clinical day</h2><p>From scheduling to follow-up, all in one place.</p></header>
      <WorkflowJourney mobile />
    </div>
  </section>
}

export function Login() {
  const [showPassword, setShowPassword] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const usernameRef = useRef<HTMLInputElement | null>(null)
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '', remember: false },
  })
  const { ref: usernameRegisterRef, ...usernameField } = register('username')

  useEffect(() => {
    document.title = 'Sign in · EPR'
    usernameRef.current?.focus({ preventScroll: true })
  }, [])

  function submitPreview() {
    setNotice('Authentication is not connected in this interface preview. Your credentials were not sent.')
  }

  function showUnavailable(label: string) {
    setNotice(`${label} is not connected in this interface preview.`)
  }

  return <main className="login-page">
    <section className="login-panel" aria-labelledby="login-title">
      <div className="desktop-login-brand"><Brand /></div>
      <div className="login-form-wrap">
        <header className="login-heading">
          <h1 id="login-title">Welcome back</h1>
          <p>Sign in to continue to your clinical workspace.</p>
        </header>
        <form className="login-form" onSubmit={handleSubmit(submitPreview)} noValidate>
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
          {notice ? <p className="login-notice" role="status"><ShieldCheck aria-hidden="true" />{notice}</p> : <p className="login-security"><Info aria-hidden="true" />Interface preview only. Authentication and session protection are not connected.</p>}
        </form>
      </div>
      <footer className="login-footer">
        <div><button type="button" className="login-link" onClick={() => showUnavailable('Privacy information')}>Privacy</button><span aria-hidden="true" />
        <button type="button" className="login-link" onClick={() => showUnavailable('Help')}>Help</button></div>
        <small>Illustrative interface concept</small>
      </footer>
    </section>
    <WorkflowPreview />
  </main>
}
