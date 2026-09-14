import { useEffect, useState } from 'react'
import { Building2, CircleCheck, FileKey2, Headphones, House, LockKeyhole, LogOut, Menu, Plus, Search, ShieldCheck, Stethoscope } from 'lucide-react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { EprMark } from '../../components/brand/epr-mark'
import { useAuth } from '../auth/auth-context'
import { TenantCreation } from './TenantCreation'
import './provider-portal.css'

const summary = [
  { label: 'Practice directory', value: 'Not connected', icon: Building2, tone: 'blue' },
  { label: 'Support cases', value: 'Not connected', icon: Headphones, tone: 'amber' },
  { label: 'Access grants', value: 'Not connected', icon: FileKey2, tone: 'purple' },
] as const

export function ProviderPortal() {
  const auth = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [signOutError, setSignOutError] = useState<string | null>(null)
  const [signingOut, setSigningOut] = useState(false)
  const user = auth.status === 'authenticated' ? auth.user : null
  const initials = user?.displayName.split(/\s+/).map(part => part[0]).join('').slice(0, 2).toUpperCase() ?? 'SA'
  const creatingTenant = location.pathname === '/provider/tenants/new'

  useEffect(() => {
    document.title = creatingTenant ? 'Create practice · EPR provider support' : 'Provider support · EPR'
    document.getElementById('main-content')?.focus({ preventScroll: true })
    window.scrollTo({ top: 0, behavior: 'instant' })
  }, [creatingTenant])

  async function signOut() {
    setSignOutError(null)
    setSigningOut(true)
    try {
      await auth.signOut()
      navigate('/login', { replace: true })
    } catch (error) {
      setSignOutError(error instanceof Error ? error.message : 'Sign-out could not be completed.')
      setSigningOut(false)
    }
  }

  return <div className="provider-workspace">
    <aside className="provider-sidebar">
      <div className="provider-brand"><EprMark className="provider-brand-mark" /><span><strong>EPR</strong><small>Provider support</small></span></div>
      <nav aria-label="Provider navigation">
        <button className={`provider-nav-item ${creatingTenant ? '' : 'selected'}`} aria-current={creatingTenant ? undefined : 'page'} onClick={() => navigate('/provider')}><House aria-hidden="true" />Overview</button>
        <button className={`provider-nav-item ${creatingTenant ? 'selected' : ''}`} aria-current={creatingTenant ? 'page' : undefined} onClick={() => navigate('/provider/tenants/new')}><Building2 aria-hidden="true" />Practices</button>
        <button className="provider-nav-item" disabled><Headphones aria-hidden="true" />Support cases</button>
        <button className="provider-nav-item" disabled><FileKey2 aria-hidden="true" />Access grants</button>
      </nav>
      <div className="provider-profile"><span className="provider-avatar">{initials}</span><span><strong>{user?.displayName}</strong><small>Superadmin · Local development</small></span></div>
    </aside>
    <div className="provider-main">
      <header className="provider-topbar">
        <span className="provider-scope"><ShieldCheck aria-hidden="true" />Global platform view</span>
        <div className="provider-search" aria-disabled="true" aria-label="Practice search is not connected"><Search aria-hidden="true" /><span>Search practices</span><strong>Not connected</strong></div>
        <span className="provider-health"><CircleCheck aria-hidden="true" />Local session API connected</span>
        <Button variant="ghost" onClick={() => void signOut()} disabled={signingOut}><LogOut aria-hidden="true" />{signingOut ? 'Signing out…' : 'Sign out'}</Button>
      </header>
      <main id="main-content" className="provider-content" tabIndex={-1}>
        {creatingTenant ? <TenantCreation onBack={() => navigate('/provider')} /> : <>
          <div className="provider-heading"><div><h1>Provider operations</h1><p>Secure access for platform administration and delegated tenant support.</p></div><span className="provider-session"><LockKeyhole aria-hidden="true" />Authenticated local session</span></div>
          <div className="provider-boundary" role="note"><ShieldCheck aria-hidden="true" /><p><strong>Global view contains no patient records.</strong><span>Practice access will require an active managed-administration grant and a recorded support reason.</span></p></div>
          {signOutError ? <p className="provider-error" role="alert">{signOutError}</p> : null}
          <section className="provider-summary" aria-label="Provider feature connection status">
            {summary.map(({ label, value, icon: Icon, tone }) => <div className="provider-summary-item" key={label}><span className={`provider-icon ${tone}`}><Icon aria-hidden="true" /></span><span><small>{label}</small><strong>{value}</strong></span></div>)}
          </section>
          <section className="provider-empty provider-start" aria-labelledby="provider-ready-title">
            <span className="provider-empty-icon"><Stethoscope aria-hidden="true" /></span>
            <h2 id="provider-ready-title">Start with a Practice workspace</h2>
            <p>Establish the record-owning Practice, its initial administrator, and first clinic location. Clinicians and staff can be added later without changing the tenant.</p>
            <Button onClick={() => navigate('/provider/tenants/new')}><Plus aria-hidden="true" />Create a practice</Button>
            <dl><div><dt>Signed in as</dt><dd>{user?.displayName}</dd></div><div><dt>Username</dt><dd>{user?.username}</dd></div><div><dt>Role</dt><dd>SUPERADMIN</dd></div></dl>
          </section>
        </>}
      </main>
      <nav className="provider-mobile-nav" aria-label="Provider mobile navigation"><button className={creatingTenant ? '' : 'selected'} aria-current={creatingTenant ? undefined : 'page'} onClick={() => navigate('/provider')}><House aria-hidden="true" />Overview</button><button className={creatingTenant ? 'selected' : ''} aria-current={creatingTenant ? 'page' : undefined} onClick={() => navigate('/provider/tenants/new')}><Building2 aria-hidden="true" />Practices</button><button disabled><Headphones aria-hidden="true" />Cases</button><button disabled><Menu aria-hidden="true" />More</button></nav>
    </div>
  </div>
}
