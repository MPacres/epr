import { patientApi, patientKeys } from '../patients/patient-api'
import { useEffect, useRef, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useBlocker, useNavigate } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { Button } from '../../components/ui/button'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { createUuid } from '../../lib/create-uuid'
import { clinicalApi, clinicalKey, request, atSite, ClinicalRequestError, type Patient, type Scope } from './services/encounter-api'
import { patientPath } from './services/encounter-api'
import { clinicalTimeForService, encounterLocations, encounterStartSchema as startSchema, serviceDateTime } from './services/encounter-setup'
import { queueApi, type Entry } from './services/queue-api'
import { PatientContextHeader } from './PatientContextHeader'
import { PregnancyEpisodeList } from './PregnancyEpisodeList'
import type * as Wire from './services/api.generated'
const episodeFormSchema = z.object({ source: z.string().trim().min(1, 'Record the source of this pregnancy information.').max(500), status: z.enum(['UNCERTAIN', 'ONGOING', 'COMPLETED']) })
export function EncounterStart({ scope: initialScope, locations = [initialScope], patient, onCancel, queued }: { scope: Scope; locations?: readonly Scope[]; patient: Patient; onCancel: () => void; queued?: { sessionId: string; entry: Entry; sessionVersion: number } }) {
  const [siteId, setSiteId] = useState(initialScope.siteId)
  const allowedLocations = encounterLocations(locations, initialScope.practiceId)
  const selectedScope = allowedLocations.find(location => location.siteId === siteId)
  const scope = selectedScope ?? initialScope
  const patientAccess = useQuery({ queryKey: [...patientKeys(scope),patient.patientId], queryFn: ({ signal }) => patientApi.detail(scope,patient.patientId,signal), retry: false })
  const patientAvailable = !!selectedScope && !patientAccess.isError && patientAccess.data?.patient.patientId === patient.patientId
  const [cancelOpen, setCancelOpen] = useState(false)
  const allowLeave = useRef(false)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const catalog = useQuery({ queryKey: [...clinicalKey(scope), 'templates'], queryFn: ({ signal }) => clinicalApi.templates(scope, signal) })
  const episodes = useQuery({ queryKey: [...clinicalKey(scope, patient.patientId), 'episodes'], queryFn: ({ signal }) => clinicalApi.episodes(scope, patient.patientId, signal), retry: false })
  const encounters = useQuery({ queryKey: [...clinicalKey(scope, patient.patientId), 'history'], queryFn: ({ signal }) => clinicalApi.encounters(scope, patient.patientId, signal), retry: false })
  const activeEncounter = encounters.data?.find(encounter => encounter.careState !== 'FINISHED')
  const form = useForm<z.infer<typeof startSchema>>({ resolver: zodResolver(startSchema), defaultValues: { releaseId: '', episodeId: '', careMode: 'IN_PERSON', ...serviceDateTime() } })
  const episodeForm = useForm<z.infer<typeof episodeFormSchema>>({ resolver: zodResolver(episodeFormSchema), defaultValues: { source: '', status: 'UNCERTAIN' } })
  const selectedRelease = useWatch({ control: form.control, name: 'releaseId' })
  const selectedEpisode = useWatch({ control: form.control, name: 'episodeId' })
  const serviceDate = useWatch({ control: form.control, name: 'serviceDate' })
  const serviceTime = useWatch({ control: form.control, name: 'serviceTime' })
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [episodeOpen, setEpisodeOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [startCommand, setStartCommand] = useState<{ key: string; body: Wire.Create } | null>(null)
  const [episodeCommand, setEpisodeCommand] = useState<{ key: string; body: Wire.CreateEpisode } | null>(null)
  const dirty = form.formState.isDirty || episodeForm.formState.isDirty || startCommand !== null || episodeCommand !== null || siteId !== initialScope.siteId
  const blocker = useBlocker(() => !allowLeave.current && dirty)
  useEffect(() => { const warn = (event: BeforeUnloadEvent) => { if (dirty && !allowLeave.current) event.preventDefault() }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn) }, [dirty])
  const cancelCommand = useRef<{ key: string; body: { encounterId: string; reason: string } } | null>(null)
  async function cancelStart(destination?: string) {
    setBusy(true); setError(null)
    try {
      if (startCommand?.body.encounterId) {
        cancelCommand.current ??= { key: createUuid(), body: { encounterId: startCommand.body.encounterId, reason: 'Clinician cancelled an unconfirmed encounter start' } }
        await request(atSite(scope, `/patients/${patient.patientId}/encounters/cancel-uncommitted-start`), z.unknown(), { method: 'POST', ...cancelCommand.current })
      }
      allowLeave.current = true
      if (blocker.state === 'blocked') blocker.proceed(); else if (destination) navigate(destination); else onCancel()
    } catch (error) {
      if (error instanceof ClinicalRequestError && error.code === 'ENCOUNTER_COMMITTED_RESUME_REQUIRED' && startCommand?.body.encounterId) {
        allowLeave.current = true; if (blocker.state === 'blocked') blocker.reset()
        navigate(`${patientPath(scope, patient.patientId)}/encounters/${startCommand.body.encounterId}`)
      } else setError(error)
    } finally { setBusy(false); setCancelOpen(false) }
  }
  const releases = catalog.data ?? []
  const release = releases.find(r => r.releaseId === selectedRelease)
  useEffect(() => {
    if (catalog.data?.length === 1 && !selectedRelease) form.setValue('releaseId', catalog.data[0].releaseId, { shouldValidate: true })
  }, [catalog.data, selectedRelease, form])
  async function createEpisode(data: z.infer<typeof episodeFormSchema>) {
    const command = episodeCommand ?? { key: createUuid(), body: { episodeId: createUuid(), source: data.source, status: data.status } }
    setEpisodeCommand(command); setBusy(true); setError(null)
    try {
      const result = await clinicalApi.createEpisode(scope, patient.patientId, command.body, command.key)
      queryClient.setQueryData([...clinicalKey(scope, patient.patientId), 'episodes'], (current: typeof episodes.data) => current ? [result, ...current.filter(value => value.episodeId !== result.episodeId)] : [result])
      form.setValue('episodeId', result.episodeId, { shouldDirty: true, shouldValidate: true }); setEpisodeOpen(false); setEpisodeCommand(null)
      void queryClient.invalidateQueries({ queryKey: [...clinicalKey(scope, patient.patientId), 'episodes'] })
    } catch (error) { setError(error) } finally { setBusy(false) }
  }
  async function start(data: z.infer<typeof startSchema>) {
    if (!release || !patientAvailable || patientAccess.isFetching) return
    if (activeEncounter || encounters.isError || encounters.isPending) return
    if (release.definition.requiresPregnancyEpisode && (!episodes.data || !episodes.data.some(episode => episode.episodeId === data.episodeId) || episodes.isFetching)) { form.setError('episodeId', { message: 'Select a loaded pregnancy episode for this patient, or record one below.' }); return }
    // Date-only and local time are interpreted in the explicitly displayed Philippine practice zone.
    const clinicalTime = clinicalTimeForService(data.serviceDate, data.serviceTime)
    const command = startCommand ?? { key: createUuid(), body: { encounterId: createUuid(), releaseId: data.releaseId, episodeId: data.episodeId || undefined, careMode: data.careMode, clinicalTime } }
    setStartCommand(command); setBusy(true); setError(null)
    try {
      const created = queued ? await queueApi.entryCommand(scope, queued.sessionId, `/${queued.entry.queueEntryId}/start-consultation`, { ...command.body, expectedSessionVersion: queued.sessionVersion, expectedQueueVersion: queued.entry.version }, command.key) : await clinicalApi.create(scope, patient.patientId, command.body, command.key)
      allowLeave.current = true
      navigate(`${patientPath(scope, patient.patientId)}/encounters/${created.encounterId}`)
    } catch (error) {
      setError(error)
      if (error instanceof ClinicalRequestError && error.code === 'ACTIVE_CARE_REQUIRES_RECONCILIATION')
        void queryClient.invalidateQueries({ queryKey: [...clinicalKey(scope, patient.patientId), 'history'] })
    } finally { setBusy(false) }
  }
  const activeCareConflict = error instanceof ClinicalRequestError && error.code === 'ACTIVE_CARE_REQUIRES_RECONCILIATION'
  const activePath = activeEncounter ? `${patientPath(scope, patient.patientId)}/encounters/${activeEncounter.encounterId}` : null
  const visibleError = error && !activeCareConflict ? error : catalog.error ?? encounters.error
  return <section className="clinical-start"><PatientContextHeader patient={patient} sex={patientAccess.isPending ? 'Checking…' : patientAccess.data?.data.sex ?? 'Unavailable'} /><div className="clinical-start-body surface"><div className="clinical-start-heading"><div><h2>New encounter</h2><p>{scope.siteName} · {scope.clinicianName}</p></div><span className="badge blue">{queued ? 'Queued care' : 'Non-queued care'}</span></div><p className="clinical-start-explanation">{queued ? 'The server will revalidate queue readiness and link this encounter to the selected entry.' : 'No appointment or queue entry will be created.'}</p>
    {catalog.isPending ? <p role="status">Loading eligible templates…</p> : null}
    {activeEncounter ? <section className="clinical-alert clinical-start-active-care" role="status" aria-labelledby="active-care-title"><div><h3 id="active-care-title">Care already in progress for this patient</h3><p>{activeEncounter.release.definition.title} is {activeEncounter.careState === 'PAUSED' ? 'paused' : 'in progress'}. Resume it and finish care before starting another encounter. Pregnancy episodes remain available afterward.</p><Button variant="outline" disabled={busy} onClick={() => { if (activePath) void cancelStart(activePath) }}>Resume active encounter</Button></div></section> : null}
    {activeCareConflict && !activeEncounter ? <p className="clinical-error" role="alert">Another encounter is active for your account. Resume and finish that care before starting a new encounter. If it is not in this patient’s history, check your other authorized Practice or site.</p> : null}
    {visibleError ? <p className="clinical-error" role="alert">{visibleError instanceof Error ? visibleError.message : 'The service could not confirm this action.'}</p> : null}
    {!catalog.isPending && !catalog.error && catalog.data?.length === 0 ? <p>No approved encounter templates are available at this site.</p> : null}
    {!patientAvailable ? <p role={patientAccess.isPending ? 'status' : 'alert'}>{patientAccess.isPending ? 'Checking patient access at this location…' : patientAccess.error?.message ?? 'This patient is unavailable at the selected location. Choose another authorized location.'}</p> : null}
    {!activeEncounter ? <form onSubmit={event => { void form.handleSubmit(start, errors => { if (errors.serviceDate || errors.serviceTime) setDetailsOpen(true) })(event) }}><fieldset disabled={busy || startCommand !== null} className="start-fields">
      <label className="start-template">Visit template<select aria-label="Visit template" {...form.register('releaseId')}><option value="">Select the visit…</option>{releases.map(r => <option key={r.releaseId} value={r.releaseId}>{r.definition.title}{releases.filter(other => other.definition.title === r.definition.title).length > 1 ? ` · v${r.definition.businessVersion}` : ''}</option>)}</select>{form.formState.errors.releaseId ? <span role="alert">{form.formState.errors.releaseId.message}</span> : null}</label>
      {release?.definition.requiresPregnancyEpisode ? <PregnancyEpisodeList episodes={episodes.data} loading={episodes.isPending} failed={episodes.isError} refreshing={episodes.isFetching} selected={selectedEpisode} register={form.register} error={form.formState.errors.episodeId?.message} onRetry={() => { void episodes.refetch() }} /> : null}
      <label>Care mode<select aria-label="Care mode" {...form.register('careMode')}><option value="IN_PERSON">In person</option><option value="TELEPHONE">Telephone</option><option value="TELECONSULTATION">Teleconsultation</option></select></label>
    </fieldset>
    <div className="start-service-summary"><span><strong>Site and service time</strong>{scope.siteName} · {serviceDate} {serviceTime} PHT</span><Button variant="ghost" aria-expanded={detailsOpen} aria-controls="start-service-fields" onClick={() => setDetailsOpen(value => !value)}>{detailsOpen ? 'Hide details' : 'Change details'}</Button></div>
    {detailsOpen ? <fieldset id="start-service-fields" disabled={busy || startCommand !== null} className="start-service-fields">
      <label>Location<select aria-label="Location" value={siteId} disabled={!!queued || episodeCommand !== null} onChange={event => { setSiteId(event.target.value); form.setValue('releaseId', '', { shouldDirty: true }); form.setValue('episodeId', '', { shouldDirty: true }); setError(null) }}>{allowedLocations.map(location => <option key={location.siteId} value={location.siteId}>{location.siteName}</option>)}</select><small>{queued ? 'Set by the queue session.' : 'Locations in this Practice.'}</small></label>
      <label>Date of service<input aria-label="Date of service" type="date" aria-invalid={!!form.formState.errors.serviceDate} aria-describedby={form.formState.errors.serviceDate ? 'service-date-error' : undefined} {...form.register('serviceDate')} />{form.formState.errors.serviceDate ? <span id="service-date-error" role="alert">{form.formState.errors.serviceDate.message}</span> : null}</label>
      <label>Time of service · Asia/Manila<input aria-label="Time of service · Asia/Manila" type="time" aria-invalid={!!form.formState.errors.serviceTime} aria-describedby={form.formState.errors.serviceTime ? 'service-time-error' : undefined} {...form.register('serviceTime')} />{form.formState.errors.serviceTime ? <span id="service-time-error" role="alert">{form.formState.errors.serviceTime.message}</span> : null}</label>
    </fieldset> : null}
    {release ? <details className="start-preview"><summary>What this template includes</summary><p>{release.definition.title} · release {release.definition.businessVersion}</p>{release.definition.sections.map(section => <div key={section.id}><h3>{section.label}</h3><p>{section.fields.map(f => f.label).join(' · ')}</p></div>)}</details> : null}
    <div className="clinical-buttons"><Button variant="outline" onClick={() => dirty ? setCancelOpen(true) : onCancel()} disabled={busy}>Cancel</Button><Button type="submit" disabled={busy || !release || !patientAvailable || patientAccess.isFetching || catalog.isFetching || encounters.isPending || encounters.isError || (release.definition.requiresPregnancyEpisode && (episodes.isPending || episodes.isError || episodes.isFetching))}>{busy ? 'Waiting for server…' : startCommand ? 'Retry original start' : 'Start encounter'}</Button></div></form> : null}
    {!activeEncounter && release?.definition.requiresPregnancyEpisode ? <div className="episode-create"><Button variant="ghost" disabled={busy || !!startCommand || !patientAvailable} onClick={() => setEpisodeOpen(!episodeOpen)}><Plus aria-hidden="true" />Record a pregnancy episode</Button><p>Use an existing episode for the same pregnancy, including the first visit at this Practice.</p>
      {episodeOpen ? <form onSubmit={episodeForm.handleSubmit(createEpisode)}><label>Recorded pregnancy status<select {...episodeForm.register('status')} disabled={episodeCommand !== null}><option value="UNCERTAIN">Uncertain</option><option value="ONGOING">Ongoing, as recorded</option><option value="COMPLETED">Completed, as recorded</option></select></label><label>Source and known context<textarea aria-label="Source and known context" rows={3} {...episodeForm.register('source')} disabled={episodeCommand !== null} /></label>{episodeForm.formState.errors.source ? <p role="alert">{episodeForm.formState.errors.source.message}</p> : null}<Button type="submit" disabled={busy}>{episodeCommand ? 'Retry original episode' : 'Save pregnancy episode'}</Button></form> : null}</div> : null}
    <ConfirmationDialog open={cancelOpen || blocker.state === 'blocked'} title="Leave encounter setup?" description="Unsent form edits will be discarded. An unconfirmed encounter start must first be reconciled; any committed encounter will be reopened." onCancel={() => { setCancelOpen(false); if (blocker.state === 'blocked') blocker.reset() }} onConfirm={() => void cancelStart()} />
  </div></section>
}
