import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Building2, Plus, Search, ShieldCheck, Users } from 'lucide-react'
import { matchPath, useLocation, useNavigate } from 'react-router-dom'
import { WorkspaceShell } from '../../components/layout/workspace-shell'
import { Button } from '../../components/ui/button'
import { useAuth } from '../auth/auth-context'
import { roleLabels, teamApi, teamKeys, type TeamMember, type TeamScope, type TeamSite } from './team-api'
import { TeamMemberEditor } from './TeamMemberEditor'
import '../dashboard/dashboard.css'
import './team.css'

export function PracticeTeam() {
  const location = useLocation()
  const navigate = useNavigate()
  const auth = useAuth()
  const cache = useQueryClient()
  const route = matchPath('/practices/:practiceId/team/:userId', location.pathname) ?? matchPath('/practices/:practiceId/team', location.pathname)
  const practice = route?.params.practiceId
  const user = matchPath('/practices/:practiceId/team/:userId', location.pathname)?.params.userId
  const scopes = useQuery({ queryKey: teamKeys.scopes, queryFn: ({ signal }) => teamApi.scopes(signal), retry: false, refetchInterval: 30000 })
  const scope = scopes.data?.find(s => s.practiceId === practice)
  useEffect(() => {
    if (practice && scopes.isSuccess && !scope) {
      void cache.cancelQueries({ queryKey: teamKeys.practice(practice) })
      cache.removeQueries({ queryKey: teamKeys.practice(practice) })
    }
  }, [practice, scopes.isSuccess, scope, cache])
  useEffect(() => { document.title = `${user ? user === 'new' ? 'Add team member' : 'Member access' : practice ? 'Team & access' : 'Practices'} · EPR`; document.getElementById('main-content')?.focus(); window.scrollTo(0, 0) }, [practice, user])
  return <WorkspaceShell activeDestination="practices" readyCount={0} inboxCount={0} context={{ practiceName: scope?.practiceName ?? 'Your authorized Practices', siteName: 'Practice administration', clinicianName: auth.user?.displayName ?? 'Signed-in user' }} administration onNavigate={destination => navigate(destination === 'more' || destination === 'profile' || destination === 'practices' ? '/practices' : destination === 'dashboard' ? '/dashboard' : '/patients')} onSearchOpen={() => navigate('/patients')} onSignOut={() => navigate('/logout')}>
    <main id="main-content" className="dashboard team-workspace" tabIndex={-1}>
      <p className="team-signed-in">Signed in as {auth.user?.displayName}</p>
      {scopes.isPending ? <p role="status">Loading your administration access…</p> : scopes.error ? <TeamError error={scopes.error} retry={() => void scopes.refetch()} /> : practice && !scope ? <section className="surface team-empty"><ShieldCheck /><h1>Practice access unavailable</h1><p>You need a current Practice administrator role to manage this team.</p><Button onClick={() => navigate('/practices')}>View your Practices</Button></section> : scope ? <TeamWorkspace key={practice} scope={scope} user={user} /> : <>
        <div className="page-heading"><div><h1>Practices</h1><p>Manage your team and access within each Practice.</p></div><Button variant="outline" onClick={() => navigate('/patients')}>Open clinical workspace<ArrowRight /></Button></div>
        <p className="team-boundary"><ShieldCheck aria-hidden="true" />Practice administration does not provide access to patient charts.</p>
        {scopes.data?.length ? <section className="surface team-practices" aria-label="Practices you administer">{scopes.data.map(s => <button key={s.practiceId} onClick={() => navigate(`/practices/${s.practiceId}/team`)}><Building2 aria-hidden="true" /><span><strong>{s.practiceName}</strong><small>Practice administrator · Team & access</small></span><ArrowRight aria-hidden="true" /></button>)}</section> : <section className="surface team-empty"><Users /><h2>No administrable Practices</h2><p>Your account has no enabled Practice administrator memberships. Your clinical access is managed separately.</p><Button variant="outline" onClick={() => navigate('/patients')}>Open clinical workspace</Button></section>}
      </>}
    </main>
  </WorkspaceShell>
}
function TeamWorkspace({ scope, user }: { scope: TeamScope; user?: string }) {
  const navigate = useNavigate()
  const sites = useQuery({ queryKey: [...teamKeys.practice(scope.practiceId), 'sites'], queryFn: ({ signal }) => teamApi.sites(scope.practiceId, signal), retry: false })
  return <>
    <Button variant="ghost" onClick={() => navigate('/practices')}><ArrowLeft />All Practices</Button>
    {!scope.clinicalEnabled ? <p className="team-boundary">Clinical workflows are not enabled for this Practice. Adding a doctor does not activate clinical services.</p> : null}
    {user ? <TeamMemberEditor key={user} scope={scope} userId={user === 'new' ? undefined : user} sites={sites.data ?? []} sitesReady={sites.isSuccess} /> : <TeamList scope={scope} sites={sites.data ?? []} />}
    {sites.error ? <TeamError error={sites.error} retry={() => void sites.refetch()} /> : null}
  </>
}
function TeamList({ scope, sites }: { scope: TeamScope; sites: TeamSite[] }) {
  const navigate = useNavigate()
  const [search, setSearch] = useState('')
  const [role, setRole] = useState('ALL')
  const [status, setStatus] = useState('ALL')
  const [site, setSite] = useState('')
  const [page, setPage] = useState(0)
  const filters = new URLSearchParams({ search, role, status, page: String(page), size: '25', ...(site ? { site } : {}) })
  const members = useQuery({ queryKey: [...teamKeys.practice(scope.practiceId), 'members', filters.toString()], queryFn: ({ signal }) => teamApi.members(scope.practiceId, filters, signal), retry: false })
  return <>
    <div className="page-heading"><div><h1>Team & access</h1><p>{scope.practiceName} · Practice administrator</p></div><Button onClick={() => navigate(`/practices/${scope.practiceId}/team/new`)}><Plus />Add team member</Button></div>
    <p className="team-boundary"><ShieldCheck aria-hidden="true" />Each person uses their own account. Roles and clinical sites apply only to this Practice.</p>
    <section className="surface team-list">
      <div className="team-filters"><label className="team-search"><span>Search team</span><div><Search aria-hidden="true" /><input value={search} maxLength={160} placeholder="Name or account email" onChange={e => { setSearch(e.target.value); setPage(0) }} /></div></label><label>Role<select value={role} onChange={e => { setRole(e.target.value); setPage(0) }}><option value="ALL">All roles</option>{Object.entries(roleLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>Membership<select value={status} onChange={e => { setStatus(e.target.value); setPage(0) }}><option value="ALL">All memberships</option><option value="ENABLED">Enabled</option><option value="SUSPENDED">Suspended</option></select></label><label>Clinical site<select value={site} onChange={e => { setSite(e.target.value); setPage(0) }}><option value="">All sites</option>{sites.map(s => <option key={s.siteId} value={s.siteId}>{s.name}</option>)}</select></label></div>
      {members.isPending ? <p className="team-empty" role="status">Loading team members…</p> : members.error ? <TeamError error={members.error} retry={() => void members.refetch()} /> : members.data?.items.length ? <><div className="team-table-scroll"><table className="team-table"><thead><tr><th>Team member</th><th>Practice roles</th><th>Clinical sites</th><th>Account / membership</th><th><span className="sr-only">Actions</span></th></tr></thead><tbody>{members.data.items.map(member => <tr key={member.userId}><td data-label="Team member"><strong>{member.displayName}</strong><small>{member.username}</small></td><td data-label="Practice roles">{member.roles.map(role => <span className="team-role" key={role}>{roleLabels[role]}</span>)}</td><td data-label="Clinical sites">{member.siteGrants.length ? member.siteGrants.map(g => <span className="team-site-label" key={g.siteId}>{sites.find(s => s.siteId === g.siteId)?.name ?? 'Assigned site'}</span>) : 'Not assigned'}</td><td data-label="Account / membership"><MemberStatus member={member} /></td><td><Button variant="outline" onClick={() => navigate(`/practices/${scope.practiceId}/team/${member.userId}`)} aria-label={`Manage ${member.displayName}`}>Manage<ArrowRight /></Button></td></tr>)}</tbody></table></div><footer className="team-pagination"><span>{members.data.total} {members.data.total === 1 ? 'member' : 'members'} · Page {page + 1}</span><div><Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button><Button variant="outline" disabled={(page + 1) * 25 >= members.data.total} onClick={() => setPage(page + 1)}>Next</Button></div></footer></> : <div className="team-empty"><Users /><h2>No matching members</h2><p>Try another name or adjust the filters.</p><Button variant="outline" onClick={() => { setSearch(''); setRole('ALL'); setStatus('ALL'); setSite(''); setPage(0) }}>Clear filters</Button></div>}
    </section>
  </>
}
export function MemberStatus({ member }: { member: TeamMember }) { return <><span className={`team-status ${!member.accountEnabled || member.setupRequired ? 'pending' : ''}`}>{!member.accountEnabled ? 'Account disabled' : member.setupRequired ? 'Password setup required' : 'Account ready'}</span><small>{member.enabled ? 'Membership enabled' : 'Membership suspended'}</small></> }
export function TeamError({ error, retry }: { error: Error; retry?: () => void }) { return <div className="team-error" role="alert"><p>{error.message}</p>{retry ? <Button variant="outline" onClick={retry}>Retry</Button> : null}</div> }
