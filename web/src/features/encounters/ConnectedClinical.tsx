import { useAuth } from '../auth/auth-context'
import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useBlocker, useNavigate, useSearchParams } from 'react-router-dom'
import { ArrowLeft, ArrowRight, Plus } from 'lucide-react'
import { WorkspaceShell, type Destination } from '../../components/layout/workspace-shell'
import { useFormEdits } from './hooks/use-form-edits'
import { ConfirmationDialog } from '../../components/ui/confirmation-dialog'
import { Button } from '../../components/ui/button'
import { Sheet } from '../../components/ui/sheet'
import { clinicalApi, clinicalKey, type Patient, type Scope } from './services/encounter-api'
import { EncounterWorkspace } from './EncounterWorkspace'
import { patientPath } from './services/encounter-api'
import { PregnancySources } from './PregnancySources'
import { PatientContextHeader } from './PatientContextHeader'
import { ClinicalChartNavigation, type ClinicalChartSection } from '../clinical-chart/ClinicalChartNavigation'
import { ClinicalSummary } from '../clinical-chart/ClinicalSummary'
import { ClinicalListsPanel } from '../clinical-chart/ClinicalListsPanel'
import { clinicalListApi, clinicalListKey, type ClinicalListKind } from '../clinical-chart/clinical-list-api'
import { ClinicalWorklist } from './ClinicalWorklist'
import { loadTasks } from './services/worklist-api'
import { QueueWorkspace } from './QueueWorkspace'
import { queueApi } from './services/queue-api'
import { EncounterStart } from './EncounterStart'
import { SavedPatientSearch } from '../patients/SavedPatientSearch'
import { SavedPatients } from '../patients/SavedPatients'
import { SavedPatientForm } from '../patients/SavedPatientForm'
import { patientApi, patientKeys } from '../patients/patient-api'
import { createUuid } from '../../lib/create-uuid'
import { EncounterHistory } from './EncounterHistory'
import '../dashboard/dashboard.css'
import './encounters.css'
import '../clinical-chart/clinical-chart.css'
export function ConnectedClinical({ practiceId, siteId, patientId, encounterId, queueView = false, worklistView = false, registrationView = false, dashboardView = false }: { dashboardView?: boolean; registrationView?: boolean; worklistView?: boolean; queueView?: boolean; practiceId?: string; siteId?: string; patientId?: string; encounterId?: string }) {
  const navigate = useNavigate()
  const auth = useAuth()
  const [searchParams] = useSearchParams()
  const starting = searchParams.get('start') === '1'
  const startQuery = starting ? '?start=1' : ''
  const scopes = useQuery({ queryKey: ['clinical', 'scopes'], queryFn: ({ signal }) => clinicalApi.scopes(signal), retry: false })
  const directoryScope = searchParams.get('scope')
  const setDirectoryScope = (value: string) => navigate(`/patients?scope=${encodeURIComponent(value)}${starting ? '&start=1' : ''}`)
  const scope = practiceId || siteId
    ? scopes.data?.find(s => s.practiceId === practiceId && s.siteId === siteId)
    : directoryScope ? scopes.data?.find(s => `${s.practiceId}/${s.siteId}` === directoryScope) : scopes.data?.[0]
  const sessions = useQuery({ queryKey: [...(scope ? clinicalKey(scope) : ['clinical']), 'sessions'], queryFn: ({ signal }) => queueApi.sessions(scope!, signal), enabled: !!scope, retry: false, refetchInterval: 15000 })
  const activeSession = sessions.data?.find(s => s.selected && s.state === 'IN_PROGRESS')
  const activeEntries = useQuery({ queryKey: [...(scope ? clinicalKey(scope) : ['clinical']), 'queue', activeSession?.sessionId ?? ''], queryFn: ({ signal }) => queueApi.entries(scope!, activeSession!.sessionId, signal), enabled: !!scope && !!activeSession, retry: false, refetchInterval: 15000 })
  const tasks = useQuery({ queryKey: [...(scope ? clinicalKey(scope) : ['clinical']), 'worklist'], queryFn: ({ signal }) => loadTasks(scope!, signal), enabled: !!scope, retry: false, refetchInterval: 15000 })
  const opener = useRef<HTMLElement | null>(null)
  useEffect(() => {
    const header = document.querySelector('.topbar')
    if (!header) return
    const observer = new ResizeObserver(() => document.documentElement.style.setProperty('--clinical-header-height', `${header.getBoundingClientRect().height}px`))
    observer.observe(header)
    return () => { observer.disconnect(); document.documentElement.style.removeProperty('--clinical-header-height') }
  }, [])
  const [panel, setPanel] = useState<Destination | 'search' | null>(null)
  useEffect(()=>{const key=(event:KeyboardEvent)=>{if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='k'){event.preventDefault();opener.current=document.activeElement as HTMLElement;setPanel(current=>current==='search'?null:'search')}};window.addEventListener('keydown',key);return()=>window.removeEventListener('keydown',key)},[])
  const context = { practiceName: scope?.practiceName ?? 'Connected clinical workspace', siteName: scope?.siteName ?? 'Select an authorized site', clinicianName: scope?.clinicianName ?? auth.user?.displayName ?? 'Signed-in user' }
  const openPanel = (value: Destination | 'search') => { opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setPanel(value) }
  const go = (destination: Destination) => {
    if (destination === 'patients') navigate(scope ? `/patients?scope=${encodeURIComponent(`${scope.practiceId}/${scope.siteId}`)}` : '/patients')
    else if (destination === 'inbox' && scope) navigate(`/clinical/${scope.practiceId}/${scope.siteId}/worklist`)
    else if (destination === 'queue' && scope) navigate(`/clinical/${scope.practiceId}/${scope.siteId}/queue`)
    else if (destination === 'practices' || destination === 'more') navigate('/practices')
    else if (destination === 'dashboard') navigate('/dashboard')
    else openPanel(destination)
  }
  return <WorkspaceShell activeDestination={dashboardView ? 'dashboard' : worklistView ? 'inbox' : queueView ? 'queue' : 'patients'} readyCount={activeSession ? activeEntries.data?.filter(e => e.state === 'READY_FOR_DOCTOR').length ?? 0 : 0} inboxCount={tasks.data?.filter(t => t.state === 'OPEN').length ?? 0} context={context} onNavigate={go} onSearchOpen={() => openPanel('search')} onSignOut={() => navigate('/logout')}>
    {scopes.isPending ? <main id="main-content" className="dashboard" tabIndex={-1}><p role="status">Checking current clinical access…</p></main> : scopes.error ? <main id="main-content" className="dashboard" tabIndex={-1}><h1>Connected workspace unavailable</h1><p role="alert">{scopes.error.message}</p><Button onClick={() => void scopes.refetch()}>Retry</Button></main> : scope ? dashboardView ? <main id="main-content" className="dashboard connected-clinical" tabIndex={-1}><div className="page-heading"><div><h1>Clinical workspace</h1><p>{scope.practiceName} · {scope.siteName}</p></div><span className="badge blue">Demo only</span></div><section className="surface connected-overview"><h2>Continue care</h2><p>Open an authorized patient chart, or register a fictional patient to begin the demo.</p><Button onClick={() => navigate(`/patients?scope=${encodeURIComponent(`${scope.practiceId}/${scope.siteId}`)}`)}>Open My Patients</Button></section><section className="surface connected-overview"><h2>Current work</h2><p>{activeSession ? activeEntries.isPending ? 'Loading the active queue…' : `${activeEntries.data?.filter(entry => entry.state === 'READY_FOR_DOCTOR').length ?? 0} patients ready in the active session.` : 'No active clinical session selected.'}</p><p>{tasks.isPending ? 'Loading clinical inbox…' : tasks.error ? 'Clinical inbox unavailable.' : `${tasks.data?.filter(task => task.state === 'OPEN').length ?? 0} open inbox items.`}</p><div className="clinical-buttons"><Button variant="outline" onClick={() => navigate(`/clinical/${scope.practiceId}/${scope.siteId}/queue`)}>Open queue</Button><Button variant="outline" onClick={() => navigate(`/clinical/${scope.practiceId}/${scope.siteId}/worklist`)}>Open clinical inbox</Button></div></section></main> : worklistView ? <ClinicalWorklist scope={scope} /> : queueView ? <QueueWorkspace scope={scope} /> : <ScopedClinical registrationView={registrationView} key={`${scope.practiceId}/${scope.siteId}/${patientId ?? ''}/${encounterId ?? ''}`} scope={scope} locations={scopes.data ?? []} onScopeChange={setDirectoryScope} patientId={patientId} encounterId={encounterId} starting={starting} /> : <main id="main-content" className="dashboard connected-clinical" tabIndex={-1}><div className="page-heading"><div><h1>{practiceId ? 'Patient workspace unavailable' : 'My Patients'}</h1><p>{practiceId ? 'The requested practice or location is unavailable in your current access scope.' : 'No authorized patient directory is available.'}</p></div></div><p className="clinical-notice">Demo records · Clinical access is required to open a patient chart.</p>{scopes.data?.length ? <div className="clinical-directory">{scopes.data.map(s => <button className="clinical-directory-row" key={`${s.practiceId}/${s.siteId}`} onClick={() => navigate(`/clinical/${s.practiceId}/${s.siteId}${startQuery}`)}><span><strong>{s.practiceName}</strong><small>{s.siteName} · {s.clinicianName}</small></span><ArrowRight aria-hidden="true" /></button>)}</div> : <section className="surface clinical-empty"><h2>No enabled clinical sites</h2><p>This account needs a current clinician membership, explicit site and clinical grants, and an enabled demo Practice. Administrative access alone does not provide chart access.</p><Button variant="outline" onClick={() => navigate('/practices')}>Manage Practice access</Button></section>}</main>}
    {panel==='search'?<SavedPatientSearch onSelect={()=>setPanel(null)}/>:null}
    <Sheet onRestoreFocus={() => opener.current?.isConnected && opener.current.focus()} open={panel !== null && panel !== 'search'} onClose={() => setPanel(null)} title={panel === 'search' ? 'Search patients' : panel === 'practices' ? 'Authorized sites' : 'Connected workspace'} description="Current authorized clinical context">
      {panel === 'practices' ? scopes.data?.map(s => <Button key={`${s.practiceId}/${s.siteId}`} variant="outline" onClick={() => { setPanel(null); navigate(`/clinical/${s.practiceId}/${s.siteId}${startQuery}`) }}>{s.practiceName} · {s.siteName}</Button>) : <p className="clinical-empty">This connected workspace supports encounter and queue workflows. The broader scheduling and clinical inbox workspaces have not been connected.</p>}
    </Sheet>
  </WorkspaceShell>
}
function ScopedClinical({ scope, locations, onScopeChange, patientId, encounterId, starting, registrationView }: { registrationView:boolean; scope: Scope; locations: readonly Scope[]; onScopeChange: (scope: string) => void; patientId?: string; encounterId?: string; starting: boolean }) {
  const detail = useQuery({ queryKey: [...patientKeys(scope),patientId], queryFn: ({ signal }) => patientApi.detail(scope,patientId!,signal), enabled:!!patientId&&!encounterId&&!registrationView,retry:false })
  const encounter = useQuery({ queryKey: clinicalKey(scope, patientId, encounterId), queryFn: ({ signal }) => clinicalApi.encounter(scope, patientId!, encounterId!, signal), enabled: !!patientId && !!encounterId, retry: false })
  if (encounterId && patientId) {
    if (encounter.isPending) return <main id="main-content" className="dashboard"><p role="status">Loading the saved encounter…</p></main>
    if (encounter.error) return <main id="main-content" className="dashboard"><h1>Encounter unavailable</h1><p role="alert">{encounter.error.message}</p><Button onClick={() => void encounter.refetch()}>Retry</Button></main>
    return <EncounterWorkspace key={encounter.data.encounterId} scope={scope} initial={encounter.data} />
  }
  if (registrationView) return <SavedPatientForm scope={scope} patientId={patientId} starting={starting}/>
  if (!patientId) return <SavedPatients scope={scope} locations={locations} onScopeChange={onScopeChange} starting={starting}/>
  if (detail.isPending) return <main id="main-content" className="dashboard"><p role="status">Loading patient context…</p></main>
  const patient = detail.data?.patient
  if (!patient || detail.error) return <main id="main-content" className="dashboard"><h1>Patient unavailable</h1><p role="alert">{detail.error?.message ?? 'This patient is not available in the current authorized scope.'}</p><Button onClick={()=>void detail.refetch()}>Retry</Button></main>
  return <ConnectedPatient scope={scope} locations={locations} patient={patient} sex={detail.data?.data.sex ?? 'Unavailable'} starting={starting} />
}
function ConnectedPatient({ scope, locations, patient, sex, starting }: { scope: Scope; locations: readonly Scope[]; patient: Patient; sex: string; starting: boolean }) {
  const navigate = useNavigate()
  if (starting) return <main id="main-content" className="dashboard connected-clinical" tabIndex={-1}><EncounterStart scope={scope} locations={locations} patient={patient} onCancel={() => navigate(patientPath(scope, patient.patientId), { replace: true })} /></main>
  return <ConnectedPatientChart scope={scope} patient={patient} sex={sex} onStart={() => navigate(`${patientPath(scope, patient.patientId)}?start=1`)} />
}
function ConnectedPatientChart({ scope, patient, sex, onStart }: { scope: Scope; patient: Patient; sex: string; onStart: () => void }) {
  const openKey=useRef(createUuid())
  const [openError,setOpenError]=useState('')
  useEffect(()=>{void patientApi.open(scope,patient.patientId,openKey.current).catch(()=>setOpenError('Recent chart activity could not be recorded.'))},[scope,patient.patientId])
  const auxiliary = useFormEdits()
  const blocker = useBlocker(() => auxiliary.dirty)
  const [confirmStart, setConfirmStart] = useState(false)
  const [section, setSection] = useState<ClinicalChartSection>('overview')
  const [selectedList, setSelectedList] = useState<ClinicalListKind>('ALLERGY')
  const navigate = useNavigate()
  const encounters = useQuery({ queryKey: [...clinicalKey(scope, patient.patientId), 'history'], queryFn: ({ signal }) => clinicalApi.encounters(scope, patient.patientId, signal), retry: false })
  const activeEncounter = encounters.data?.find(encounter => encounter.careState !== 'FINISHED')
  const clinicalLists = useQuery({ queryKey: [...clinicalListKey(scope, patient.patientId), 'summary'], queryFn: ({ signal }) => clinicalListApi.summary(scope, patient.patientId, signal), retry: false })
  const allergy = clinicalLists.data?.allergies
  const allergyStatus = !allergy ? 'Allergies/alerts unavailable' : allergy.state === 'EMPTY_REVIEWED' ? 'Allergies: none known · reviewed' : allergy.preview.length ? `Allergy: ${allergy.preview.map(item => item.display).join(', ')}${allergy.state === 'STALE' ? ' · review due' : ''}` : allergy.state === 'NOT_REVIEWED' ? 'Allergies: not reviewed' : allergy.state === 'UNAVAILABLE' ? 'Allergies/alerts unavailable' : 'Allergies: review due'
  function changeSection(next: ClinicalChartSection, reveal = false) {
    setSection(next)
    if (reveal && window.innerWidth <= 700) requestAnimationFrame(() => document.getElementById(`chart-panel-${next}`)?.scrollIntoView({ block: 'start', behavior: 'instant' }))
  }
  function openList(kind: ClinicalListKind) { setSelectedList(kind); setSection('lists'); requestAnimationFrame(() => { const target = document.getElementById('clinical-lists-title'); target?.focus({ preventScroll: true }); target?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' }) }) }
  return <main onChangeCapture={auxiliary.onChangeCapture} id="main-content" className="dashboard connected-clinical connected-chart" tabIndex={-1}>
    <Button className="clinical-back" variant="ghost" onClick={() => navigate(`/patients?scope=${encodeURIComponent(`${scope.practiceId}/${scope.siteId}`)}`)}><ArrowLeft aria-hidden="true" />My Patients</Button>
    <PatientContextHeader patient={patient} sex={sex} allergyStatus={allergyStatus} actions={<Button onClick={() => auxiliary.dirty ? setConfirmStart(true) : activeEncounter ? navigate(`${patientPath(scope, patient.patientId)}/encounters/${activeEncounter.encounterId}`) : onStart()}><Plus aria-hidden="true" />{activeEncounter ? 'Resume encounter' : 'New encounter'}</Button>} />
    <div className="clinical-alert" role="note"><p><strong>{allergyStatus}.</strong> {allergy?.state === 'EMPTY_REVIEWED' ? 'The reviewed absence is bound to the recorded source and review time.' : 'Check the longitudinal source before care; do not infer reviewed absence.'}</p></div>
    {openError ? <p role="status">{openError}</p> : null}
    <div className="connected-chart-content">
      <ClinicalChartNavigation
        section={section}
        onChange={changeSection}
        patientDetailsEnabled={Boolean(scope.patientCapabilities?.includes('DEMOGRAPHIC_EDIT'))}
        onOpenPatientDetails={() => navigate(`${patientPath(scope,patient.patientId)}/edit`)}
      />
      <div id="chart-panel-overview" role="tabpanel" aria-labelledby="chart-tab-overview" hidden={section !== 'overview'} className="connected-chart-panel">
        <ClinicalSummary practiceName={scope.practiceName} siteName={scope.siteName} data={clinicalLists.data} pending={clinicalLists.isPending} error={clinicalLists.error} onRetry={() => void clinicalLists.refetch()} onManage={openList} />
        <PregnancySources scope={scope} patientId={patient.patientId} />
      </div>
      <div id="chart-panel-encounters" role="tabpanel" aria-labelledby="chart-tab-encounters" hidden={section !== 'encounters'} className="connected-chart-panel">
        <EncounterHistory scope={scope} encounters={encounters.data} pending={encounters.isPending} error={encounters.error} onRetry={() => void encounters.refetch()} onOpen={id => navigate(`${patientPath(scope, patient.patientId)}/encounters/${id}`)} />
      </div>
      <div id="chart-panel-lists" role="tabpanel" aria-labelledby="chart-tab-lists" hidden={section !== 'lists'} className="connected-chart-panel">
        {clinicalLists.isPending ? <p role="status">Loading clinical lists…</p> : clinicalLists.error ? <div className="clinical-error" role="alert"><span>{clinicalLists.error.message}</span><Button variant="outline" onClick={() => void clinicalLists.refetch()}>Retry clinical lists</Button></div> : clinicalLists.data ? <ClinicalListsPanel scope={scope} patient={patient} summary={clinicalLists.data} selectedKind={selectedList} onSelectKind={setSelectedList} /> : null}
      </div>
    </div>
    <ConfirmationDialog open={confirmStart || blocker.state === 'blocked'} title="Leave unsaved source changes?" description="Unsent pregnancy or history changes remain only in this form. Leaving discards those edits." onCancel={() => { setConfirmStart(false); if (blocker.state === 'blocked') blocker.reset() }} onConfirm={() => { auxiliary.discard(); if (blocker.state === 'blocked') blocker.proceed(); else if (activeEncounter) navigate(`${patientPath(scope, patient.patientId)}/encounters/${activeEncounter.encounterId}`); else onStart() }} />
  </main>
}
