import type { ReactNode } from 'react'
import { ArrowLeftRight, Bell, Building2, CalendarDays, ChevronDown, ChevronRight, FileText, House, Inbox, Keyboard, ListOrdered, LogOut, Menu, Search, Settings, Users } from 'lucide-react'
import { EprMark } from '../brand/epr-mark'
import { Button } from '../ui/button'

export type Destination = 'dashboard' | 'queue' | 'patients' | 'schedule' | 'inbox' | 'practices' | 'more' | 'profile'
const navigation = [
  { id: 'dashboard', label: 'Dashboard', icon: House },
  { id: 'queue', label: 'Queue', icon: ListOrdered },
  { id: 'patients', label: 'My Patients', icon: Users },
  { id: 'schedule', label: 'Schedule', icon: CalendarDays },
  { id: 'inbox', label: 'Clinical Inbox', icon: Inbox },
  { id: 'referrals', label: 'Referrals', icon: ArrowLeftRight, disabled: true },
  { id: 'documents', label: 'Documents', icon: FileText, disabled: true },
  { id: 'practices', label: 'Practices', icon: Building2 },
  { id: 'settings', label: 'Settings', icon: Settings, disabled: true },
] as const
export function Logo() {
  return <div className="brand"><EprMark /><div><strong>EPR</strong><span className="brand-caption">Clinical workspace</span></div></div>
}
export function WorkspaceShell({ children, readyCount, inboxCount, onNavigate, onSearchOpen, onSignOut, activeDestination = 'dashboard', context, administration = false }: {
  children: ReactNode; readyCount: number; inboxCount: number; onNavigate: (destination: Destination) => void
  onSearchOpen: () => void; onSignOut: () => void
  administration?: boolean
  context?: { practiceName: string; siteName: string; clinicianName: string }
  activeDestination?: Destination
}) {
  function navButton(item: typeof navigation[number], mobile = false) {
    const Icon = item.icon
    const count = item.id === 'queue' ? readyCount : item.id === 'inbox' ? inboxCount : 0
    const disabled = 'disabled' in item && item.disabled
    return <button key={item.id} className={`nav-item ${item.id === activeDestination ? 'selected' : ''} ${item.id === 'practices' ? 'nav-divider' : ''}`}
      aria-current={item.id === activeDestination ? 'page' : undefined} disabled={disabled}
      title={disabled ? `${item.label} is not available in this dashboard preview` : item.label}
      onClick={() => { if (!disabled) onNavigate(item.id as Destination) }}>
      <span className="nav-icon"><Icon aria-hidden="true" />{mobile && count > 0 ? <span className="nav-count">{count}</span> : null}</span>
      <span>{mobile && item.id === 'patients' ? 'Patients' : mobile && item.id === 'inbox' ? 'Inbox' : item.label}</span>
      {!mobile && count > 0 ? <span className="nav-count">{count}</span> : null}
    </button>
  }
  return <div className={`workspace${administration ? ' administration-workspace' : ''}`}>
    <a href="#main-content" className="skip-link" onClick={event => {
      event.preventDefault()
      const main = document.getElementById('main-content')
      main?.focus({ preventScroll: true })
      window.scrollTo({ top: 0, behavior: 'instant' })
    }}>Skip to main content</a>
    <aside className="sidebar">
      <Logo />
      <nav aria-label="Main navigation" className="desktop-nav">{navigation.map(item => navButton(item))}</nav>
      <div className="sidebar-footer"><span className="workspace-label"><span className="demo-dot" />{context ? 'Connected · synthetic data' : 'Demo workspace'}</span>
        <button className="profile" onClick={() => onNavigate('profile')}><span className="avatar blue">{administration ? 'PA' : context ? 'MD' : 'AS'}</span><span className="profile-copy"><strong>{context?.clinicianName ?? 'Dr. Ana Santos'}</strong><small>{administration ? 'Practice administration' : context ? 'Clinical workspace' : 'Internal Medicine'}</small></span><ChevronRight aria-hidden="true" /></button>
      </div>
    </aside>
    <div className="workspace-main">
      <header className="topbar">
        <div className="mobile-brand"><Logo /></div>
        <button className="practice-selector" onClick={() => onNavigate('practices')} aria-label={context ? `View authorized sites; ${context.siteName}` : "View practice sessions; Hospital A is active"}>
          <span className="icon-tile blue"><Building2 aria-hidden="true" /></span>
          <span><span className="practice-name">{context?.siteName ?? 'Hospital A'} <ChevronDown aria-hidden="true" /><span className="current-tag">{administration ? 'Administration' : context ? 'Selected site' : 'Current session'}</span></span>{context ? <span className="connected-practice-mobile">{context.practiceName}</span> : null}<span className="practice-subtitle">{context?.practiceName ?? 'Outpatient Clinic'} {!context ? <><span>·</span> 8:00 AM–12:00 PM</> : null}</span></span>
        </button>
        {!administration ? <button className="global-search search-trigger" onClick={onSearchOpen} aria-label="Search patients across your clinics" aria-haspopup="dialog" aria-keyshortcuts="Meta+K Control+K">
          <Search aria-hidden="true" /><span>Search patients…</span><span className="search-shortcut" aria-hidden="true"><Keyboard /></span>
        </button> : null}
        <div className="topbar-actions">
          <span className="header-demo"><span className="demo-dot" />{context ? 'Synthetic data' : 'Demo data'}</span>
          <Button variant="ghost" size="icon" className="notification-button" aria-label={`Open clinical inbox, ${inboxCount} items need attention`} onClick={() => onNavigate('inbox')}><Bell aria-hidden="true" />{inboxCount > 0 ? <span className="notification-dot" /> : null}</Button>
          <Button variant="ghost" className="topbar-sign-out" onClick={onSignOut} aria-label="Sign out"><LogOut aria-hidden="true" /><span>Sign out</span></Button>
        </div>
      </header>
      {children}
    </div>
    <nav className="mobile-nav" aria-label="Mobile navigation">
      {navigation.filter(item => ['dashboard', 'queue', 'patients', 'inbox'].includes(item.id)).map(item => navButton(item, true))}
      <button className="nav-item" onClick={() => onNavigate('more')}><Menu aria-hidden="true" /><span>More</span></button>
    </nav>
  </div>
}
