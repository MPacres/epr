import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useBlocker, useNavigate } from 'react-router-dom'
import { ArrowLeft, Check, KeyRound, ShieldCheck } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { createUuid } from '../../lib/create-uuid'
import { accessSchema, memberSchema, roleLabels, teamApi, teamKeys, TeamRequestError, type TeamAccess, type TeamCreate, type TeamMember, type TeamResolution, type TeamResult, type TeamRole, type TeamScope, type TeamSite } from './team-api'
import { MemberStatus, TeamError } from './PracticeTeam'

export function TeamMemberEditor({ scope, userId, sites, sitesReady }: { scope: TeamScope; userId?: string; sites: TeamSite[]; sitesReady: boolean }) {
  const member = useQuery({ queryKey: [...teamKeys.practice(scope.practiceId), 'member', userId], queryFn: ({ signal }) => teamApi.member(scope.practiceId, userId!, signal), enabled: !!userId, retry: false })
  const [reload, setReload] = useState(0)
  if (userId && member.isPending) return <p role="status">Loading member access…</p>
  if (userId && member.error) return <TeamError error={member.error} retry={() => void member.refetch()} />
  return <AccessForm key={`${userId ?? 'new'}:${reload}`} scope={scope} member={member.data} sites={sites} sitesReady={sitesReady} onReload={async () => { const fresh = await member.refetch(); if (fresh.isSuccess) setReload(v => v + 1) }} />
}
function AccessForm({ scope, member, sites, sitesReady, onReload }: { scope: TeamScope; member?: TeamMember; sites: TeamSite[]; sitesReady: boolean; onReload: () => Promise<void> }) {
  const navigate = useNavigate()
  const cache = useQueryClient()
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [resolution, setResolution] = useState<TeamResolution | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [resolving, setResolving] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [result, setResult] = useState<TeamResult | null>(null)
  const [credential, setCredential] = useState<string | null>(null)
  const inFlight = useRef(false)
  const pending = useRef<{ key: string; run: () => Promise<TeamResult> } | null>(null)
  const form = useForm<TeamAccess>({ resolver: zodResolver(accessSchema), defaultValues: { expectedVersion: member?.version ?? 0, enabled: member?.enabled ?? true, roles: member?.roles ?? [], siteGrants: member?.siteGrants ?? [], reason: '' } })
  const roles = useWatch({ control: form.control, name: 'roles' })
  const grants = useWatch({ control: form.control, name: 'siteGrants' })
  const dirty = !result && (form.formState.isDirty || !!email || !!name)
  const blocker = useBlocker(dirty || busy || uncertain || !!credential)
  useEffect(() => {
    const prevent = (event: BeforeUnloadEvent) => { if (dirty || busy || uncertain || credential) event.preventDefault() }
    window.addEventListener('beforeunload', prevent)
    return () => window.removeEventListener('beforeunload', prevent)
  }, [dirty, busy, uncertain, credential])
  const base = `/practices/${scope.practiceId}/team`
  const locked = busy || uncertain || !!result || (error instanceof TeamRequestError && [401, 403].includes(error.status))
  const heading = member ? member.displayName : 'Add team member'
  async function resolve() {
    if (resolving) return
    setResolving(true); setError(null)
    try { const found = await teamApi.resolve(scope.practiceId, email); setResolution(found); setConfirmed(false); if (found.existing) setName(found.displayName) }
    catch (e) { setError(e as Error) }
    finally { setResolving(false) }
  }
  async function execute() {
    if (inFlight.current || !pending.current) return
    inFlight.current = true; setBusy(true); setError(null)
    try {
      const saved = await pending.current.run()
      pending.current = null; setUncertain(false); setResult({ ...saved, temporaryPassword: null }); setCredential(saved.temporaryPassword)
      await cache.cancelQueries({ queryKey: ['clinical'] }); cache.removeQueries({ queryKey: ['clinical'] })
      await cache.invalidateQueries({ queryKey: teamKeys.practice(scope.practiceId) })
      await cache.invalidateQueries({ queryKey: teamKeys.scopes })
    } catch (e) {
      const failure = e as Error
      setError(failure)
      if (failure instanceof TeamRequestError && failure.status > 0 && failure.status < 500) {
        pending.current = null; setUncertain(false)
        if ([401, 403].includes(failure.status)) { await cache.cancelQueries({ queryKey: teamKeys.practice(scope.practiceId) }); cache.removeQueries({ queryKey: teamKeys.practice(scope.practiceId) }) }
      } else setUncertain(true)
    } finally { inFlight.current = false; setBusy(false) }
  }
  async function save(values: TeamAccess) {
    if (inFlight.current || uncertain || result) return
    if (!member && (!resolution || (resolution.existing && (!confirmed || !resolution.userId)))) { setError(new Error('Check the account email and confirm the existing identity before saving.')); return }
    const key = createUuid()
    if (member) pending.current = { key, run: () => teamApi.update(scope.practiceId, member.userId, values, key) }
    else {
      const command: TeamCreate = { email: resolution!.username, displayName: name.trim(), confirmedUserId: resolution!.userId, roles: values.roles, siteGrants: values.siteGrants, reason: values.reason }
      pending.current = { key, run: () => teamApi.create(scope.practiceId, command, key) }
    }
    await execute()
  }
  function toggleRole(role: TeamRole, checked: boolean) {
    form.setValue('roles', checked ? [...roles, role] : roles.filter(r => r !== role), { shouldDirty: true, shouldValidate: true })
    if (role === 'CLINICIAN' && !checked) form.setValue('siteGrants', [], { shouldDirty: true })
  }
  async function reissue() {
    if (!member || inFlight.current || uncertain || result) return
    const reason = form.getValues('reason').trim()
    if (!reason) { form.setError('reason', { message: 'Enter a reason before replacing the temporary password.' }); return }
    const key = createUuid(); const body = { expectedVersion: member.version, reason }
    pending.current = { key, run: () => teamApi.reissue(scope.practiceId, member.userId, body, key) }
    await execute()
  }
  return <>
    <Button variant="ghost" onClick={() => navigate(base)}><ArrowLeft />Team & access</Button>
    <div className="page-heading"><div><h1>{heading}</h1><p>{scope.practiceName}{member ? ` · ${member.username}` : ' · Create an account or link an existing person'}</p></div>{member ? <div><MemberStatus member={member} /></div> : null}</div>
    {result ? <section className="surface team-success" role="status"><Check /><h2>{result.replayed ? 'Previous request confirmed' : 'Member access saved'}</h2><p>{result.member.displayName} is {result.member.enabled ? 'an enabled' : 'a suspended'} member of {scope.practiceName}.</p>{credential ? <><p>This temporary password is shown once. Share it securely with the account owner. No email was sent; they must choose a private password at first sign-in.</p><label>Temporary password<input readOnly autoComplete="off" value={credential} onFocus={e => e.target.select()} /></label><Button variant="outline" onClick={() => setCredential(null)}>I have recorded it securely</Button></> : result.replayed && result.member.canReissue ? <p>The password cannot be replayed. Open the member and issue a replacement if the original response was lost.</p> : null}<div className="team-actions"><Button disabled={!!credential} onClick={() => navigate(base)}>Back to team</Button><Button disabled={!!credential} variant="outline" onClick={() => { if (member?.userId === result.member.userId) void onReload(); else navigate(`${base}/${result.member.userId}`) }}>Review saved member</Button><Button disabled={!!credential} variant="outline" onClick={() => navigate('/patients')}>Open clinical workspace</Button></div></section> : null}
    {!result ? <form className="surface team-editor" onSubmit={event => { void form.handleSubmit(save)(event) }}>
      <fieldset disabled={locked}>
        {!member ? <section><h2>Account identity</h2><p>Use the person’s exact account email. Existing accounts keep their name, password and memberships elsewhere.</p><div className="team-identity-fields"><label>Account email (required)<input type="email" maxLength={160} disabled={resolving} value={email} required autoComplete="off" onChange={e => { setEmail(e.target.value); setResolution(null); setConfirmed(false) }} /></label><Button variant="outline" disabled={resolving || !email.includes('@')} onClick={() => void resolve()}>{resolving ? 'Checking…' : 'Check account'}</Button></div>{resolution ? resolution.existing ? resolution.userId ? <div className="team-identity-confirm"><strong>{resolution.displayName}</strong><span>{resolution.username}</span><label className="team-check"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />I verified this is the person to add to this Practice.</label></div> : <p role="alert" className="team-error">{resolution.displayName}</p> : <><p className="team-hint">No existing account. A new local account will receive a one-time password.</p><label>Full name (required)<input required maxLength={160} value={name} autoComplete="off" onChange={e => setName(e.target.value)} /></label></> : null}</section> : null}
        <section><h2>Practice roles</h2><p>Select the responsibilities this person holds here. Administrative roles do not grant chart access.</p><div className="team-role-options">{Object.entries(roleLabels).map(([value, label]) => <label className="team-check" key={value}><input type="checkbox" checked={roles.includes(value as TeamRole)} disabled={member?.clinicalRemovalBlocked && value === 'CLINICIAN'} onChange={e => toggleRole(value as TeamRole, e.target.checked)} /><span><strong>{label}</strong><small>{value === 'PRACTICE_ADMINISTRATOR' ? 'Manage the team and Practice access' : value === 'CLINICIAN' ? 'Clinical workflows at explicitly selected sites' : 'Role recorded; staff clinical workflows are not yet available'}</small></span></label>)}</div>{form.formState.errors.roles ? <p className="team-field-error" role="alert">{form.formState.errors.roles.message}</p> : null}</section>
        {roles.includes('CLINICIAN') ? <section><h2>Clinical site access</h2><p>Ordinary doctor access includes patient registration, authorized directory and demographic editing. Patient care assignments still apply. Template approval is managed separately.</p>{!sitesReady ? <p role="status">Clinical sites must load before you can assign access.</p> : sites.length === 0 ? <p>No existing sites are available for this Practice.</p> : sites.map(site => { const grant = grants.find(g => g.siteId === site.siteId); const original = member?.siteGrants.find(g => g.siteId === site.siteId); return <div className="team-site-choice" key={site.siteId}><label className="team-check"><input type="checkbox" checked={!!grant} disabled={!!original} onChange={e => form.setValue('siteGrants', e.target.checked ? [...grants, { siteId: site.siteId, reproductiveAccess: false }] : grants.filter(g => g.siteId !== site.siteId), { shouldDirty: true, shouldValidate: true })} /><strong>{site.name}</strong></label>{grant ? <label className="team-check team-reproductive"><input type="checkbox" checked={grant.reproductiveAccess} disabled={!!original?.reproductiveAccess} onChange={e => form.setValue('siteGrants', grants.map(g => g.siteId === site.siteId ? { ...g, reproductiveAccess: e.target.checked } : g), { shouldDirty: true })} />Allow reproductive-health records within authorized care</label> : null}</div> })}{form.formState.errors.siteGrants ? <p className="team-field-error" role="alert">{form.formState.errors.siteGrants.message}</p> : null}<p className="team-hint">Clinical capability, published templates and patient assignments are separate requirements for starting care.</p></section> : null}
        {member ? <section><h2>Membership status</h2><label className="team-check"><input type="checkbox" {...form.register('enabled')} disabled={member.clinicalRemovalBlocked} />Enable membership in this Practice</label><p>Suspension preserves membership history and does not change access in other Practices. Reactivation requires reviewing the roles and sites above.</p>{member.clinicalRemovalBlocked ? <p className="team-boundary"><ShieldCheck />Clinical access reduction and suspension await the care-continuity handoff workflow. Existing clinical access is protected.</p> : null}</section> : null}
        <section><h2>Review and save</h2><p>{roles.length ? roles.map(role => roleLabels[role]).join(' · ') : 'No roles selected'}{grants.length ? ` · ${grants.length} clinical ${grants.length === 1 ? 'site' : 'sites'}` : ''}</p><label>Reason for this change (required)<textarea rows={3} maxLength={500} {...form.register('reason')} aria-invalid={!!form.formState.errors.reason} aria-describedby="team-reason-error" /></label>{form.formState.errors.reason ? <p id="team-reason-error" className="team-field-error" role="alert">{form.formState.errors.reason.message}</p> : null}</section>
      </fieldset>
      {error ? <TeamError error={error} /> : null}
      {error instanceof TeamRequestError && error.status === 409 ? <Button variant="outline" onClick={() => { if (window.confirm('Discard your edits and load the latest member access?')) void onReload() }}>Discard edits and load latest access</Button> : null}
      {uncertain ? <div className="team-boundary"><p>The outcome is unconfirmed. Retry the original request before changing these details.</p><Button disabled={busy} onClick={() => void execute()}>Retry original request</Button></div> : null}
      <footer className="team-actions"><Button type="submit" disabled={locked || resolving || (roles.includes('CLINICIAN') && !sitesReady)}>{busy ? 'Saving…' : member ? 'Save access' : 'Add team member'}</Button><Button variant="outline" disabled={busy} onClick={() => navigate(base)}>Cancel</Button>{member?.canReissue ? <Button variant="outline" disabled={locked || form.formState.dirtyFields.roles !== undefined || form.formState.dirtyFields.siteGrants !== undefined || form.formState.dirtyFields.enabled !== undefined} onClick={() => void reissue()}><KeyRound />Replace temporary password</Button> : null}</footer>
    </form> : null}
    {member ? <MemberHistory practice={scope.practiceId} user={member.userId} /> : null}
    <ConfirmationDialog open={blocker.state === 'blocked'} title={credential ? 'Leave this one-time password?' : 'Leave unsaved member changes?'} description={credential ? 'This password will disappear when you leave. You can issue a replacement only while the account is unclaimed.' : uncertain || busy ? 'The request may already have saved. Stay here and confirm its outcome before leaving.' : 'Your unsaved entries will be discarded.'} onCancel={() => blocker.reset?.()} onConfirm={() => blocker.proceed?.()} confirmLabel="Leave page" />
  </>
}
function MemberHistory({ practice, user }: { practice: string; user: string }) {
  const [page, setPage] = useState(0)
  const events = useQuery({ queryKey: [...teamKeys.practice(practice), 'events', user, page], queryFn: ({ signal }) => teamApi.events(practice, user, page, signal), retry: false })
  return <section className="surface team-history"><h2>Access history</h2>{events.isPending ? <p role="status">Loading access history…</p> : events.error ? <TeamError error={events.error} retry={() => void events.refetch()} /> : !events.data?.length ? <p>No access events on this page. Earlier provisioning evidence is retained separately.</p> : <ol>{events.data.map(event => <li key={event.id}><strong>{event.action === 'CREATE' ? 'Member added' : event.action === 'PASSWORD' ? 'Temporary password replaced' : event.action === 'PASSWORD_SET' ? 'Private password set' : 'Access updated'}</strong><span>{event.actorName} · {new Date(event.occurredAt).toLocaleString('en-PH', { timeZone: 'Asia/Manila' })} PHT</span><p>{event.reason}</p><details><summary>Access recorded with this change</summary><AccessEvidence value={event.beforeAccess} label="Before" /><AccessEvidence value={event.afterAccess} label="After" /></details></li>)}</ol>}<div className="team-actions"><Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous events</Button><Button variant="outline" disabled={!events.data || events.data.length < 25} onClick={() => setPage(page + 1)}>More events</Button></div></section>
}
function AccessEvidence({ value, label }: { value: string; label: string }) {
  let member: TeamMember | undefined
  try { const parsed = memberSchema.safeParse(JSON.parse(value)); if (parsed.success) member = parsed.data } catch { /* Historical evidence may contain only setup status. */ }
  return <p><strong>{label}: </strong>{member ? `${member.enabled ? 'Enabled' : 'Suspended'} · ${member.roles.map(r => roleLabels[r]).join(', ')} · ${member.siteGrants.length} clinical sites · version ${member.version}` : 'No membership snapshot'}</p>
}
