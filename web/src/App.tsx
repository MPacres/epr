import { lazy, Suspense, useEffect } from 'react'
import { createHashRouter, matchPath, Navigate, RouterProvider, useLocation, useNavigate } from 'react-router-dom'
import { AuthProvider } from './features/auth/AuthProvider'
import { Logout } from './features/auth/Logout'
import { RequireAuth } from './features/auth/RequireAuth'
import { PatientPreviewProvider } from './features/patients/PatientPreviewProvider'
import { portalSurfaceForHostname } from './features/auth/portal-surface'
const Dashboard = lazy(() => import('./features/dashboard/Dashboard').then(module => ({ default: module.Dashboard })))
const Login = lazy(() => import('./features/auth/Login').then(module => ({ default: module.Login })))
const PasswordReset = lazy(() => import('./features/auth/PasswordReset').then(module => ({ default: module.PasswordReset })))
const ProviderPortal = lazy(() => import('./features/provider/ProviderPortal').then(module => ({ default: module.ProviderPortal })))
const PatientForm = lazy(() => import('./features/patients/PatientForm').then(module => ({ default: module.PatientForm })))
const PatientChart = lazy(() => import('./features/chart/PatientChart').then(module => ({ default: module.PatientChart })))
const PatientNotes = lazy(() => import('./features/chart/PatientNotes').then(module => ({ default: module.PatientNotes })))
const Encounter = lazy(() => import('./features/chart/Encounter').then(module => ({ default: module.Encounter })))
const ConnectedClinical = lazy(() => import('./features/encounters/ConnectedClinical').then(module => ({ default: module.ConnectedClinical })))
const PracticeTeam = lazy(() => import('./features/practices/PracticeTeam').then(module => ({ default: module.PracticeTeam })))
const Schedule = lazy(() => import('./features/schedule/Schedule').then(module => ({ default: module.Schedule })))
import { Button } from './components/ui/button'

function WorkspaceRoute() {
  const location = useLocation()
  const navigate = useNavigate()
  const connected: { params: { practiceId?: string; siteId?: string; patientId?: string; encounterId?: string } } | null = matchPath('/clinical/:practiceId/:siteId/worklist', location.pathname) ?? matchPath('/clinical/:practiceId/:siteId/queue', location.pathname) ?? matchPath('/clinical/:practiceId/:siteId/patients/:patientId/encounters/:encounterId', location.pathname) ?? matchPath('/clinical/:practiceId/:siteId/patients/:patientId/edit', location.pathname) ?? matchPath('/clinical/:practiceId/:siteId/patients/:patientId', location.pathname) ?? matchPath('/clinical/:practiceId/:siteId', location.pathname)
  const isTeam = location.pathname === '/practices' || location.pathname.startsWith('/practices/')
  const isConnectedDashboard = portalSurface === 'clinical' && (location.pathname === '/' || location.pathname === '/dashboard')
  const isConnected = isConnectedDashboard || location.pathname === '/clinical' || location.pathname === '/patients' || location.pathname === '/patients/new' || !!connected
  const isConnectedPatient = Boolean(connected?.params.patientId)
  const isConnectedEncounter = Boolean(connected?.params.encounterId)
  const isConnectedPatientEdit = isConnectedPatient && location.pathname.endsWith('/edit')
  const edit = matchPath('/patients/preview/:practiceId/:patientId/edit', location.pathname)
  const chart = matchPath('/patients/preview/:practiceId/:patientId/chart', location.pathname)
  const notes = matchPath('/patients/preview/:practiceId/:patientId/notes', location.pathname)
  const encounter = matchPath('/patients/preview/:practiceId/:patientId/encounters/:encounterId', location.pathname)
  const isChart = !!chart
  const isNotes = !!notes
  const isEncounter = !!encounter
  const isEdit = !!edit
  const isNew = location.pathname === '/patients/preview/new'
  const isDirectory = location.pathname === '/patients' || location.pathname === '/patients/preview'
  const isSchedule = location.pathname === '/schedule'
  const isDashboard = location.pathname === '/' || location.pathname === '/dashboard'
  const view = isSchedule ? 'schedule' : isDirectory || isNew || edit || chart || notes || encounter ? 'patients' : 'dashboard'
  useEffect(() => {
    if (isTeam) return
    document.title = `${isConnected ? (isDirectory ? 'My Patients' : isConnectedEncounter ? 'Encounter' : isConnectedPatientEdit ? 'Edit patient' : isConnectedPatient ? 'Patient chart' : 'Connected clinical workspace') : isChart ? 'Patient chart' : isNotes ? 'Clinical notes' : isEncounter ? 'Encounter' : isNew ? 'New patient' : isEdit ? 'Edit patient' : isDirectory ? 'My Patients' : isSchedule ? 'Schedule' : isDashboard ? 'Dashboard' : 'Page unavailable'} · EPR`
    document.getElementById('main-content')?.focus({ preventScroll:true })
    window.scrollTo({top:0,behavior:'instant'})
  }, [location.pathname, isNew, isDirectory, isSchedule, isDashboard, isEdit, isChart, isNotes, isEncounter, isConnected, isConnectedPatient, isConnectedEncounter, isConnectedPatientEdit, isTeam])
  if (isTeam) return <PracticeTeam />
  if (isConnected) return <ConnectedClinical dashboardView={isConnectedDashboard} registrationView={location.pathname === '/patients/new' || location.pathname.endsWith('/edit')} worklistView={location.pathname.endsWith('/worklist')} queueView={location.pathname.endsWith('/queue')} key={location.pathname} practiceId={connected?.params.practiceId} siteId={connected?.params.siteId} patientId={connected?.params.patientId} encounterId={connected?.params.encounterId} />
  const workspace = isSchedule ? <Schedule /> : chart ? <PatientChart practiceId={chart.params.practiceId!} patientId={chart.params.patientId!} /> : notes ? <PatientNotes practiceId={notes.params.practiceId!} patientId={notes.params.patientId!} /> : encounter ? <Encounter practiceId={encounter.params.practiceId!} patientId={encounter.params.patientId!} encounterId={encounter.params.encounterId!} /> : isNew ? <PatientForm practiceId={new URLSearchParams(location.search).get('practice') ?? 'practice-a'} /> : edit ? <PatientForm practiceId={edit.params.practiceId!} patientId={edit.params.patientId!} /> : undefined
  const unavailable = !isSchedule && !chart && !notes && !encounter && !isNew && !edit && !isDirectory && !isDashboard ? <main id="main-content" className="dashboard" tabIndex={-1}><h1>Page unavailable</h1><p>This workspace page could not be found.</p><Button onClick={() => navigate('/patients')}>Go to My Patients</Button></main> : undefined
  return <Dashboard key={location.pathname + (isNew ? location.search : '')} view={view} onViewChange={next => navigate(`/${next}`)} content={workspace ? <Suspense fallback={<main className="dashboard" id="main-content" tabIndex={-1}><p role="status">Loading workspace…</p></main>}>{workspace}</Suspense> : unavailable} />
}
const portalSurface = portalSurfaceForHostname(
  window.location.hostname,
  import.meta.env.VITE_CLINICAL_HOSTNAME,
  import.meta.env.VITE_SUPPORT_HOSTNAME,
)
const loginRoute = { path: '/login', element: <Login surface={portalSurface} /> }
const logoutRoute = { path: '/logout', element: <Logout /> }
const passwordResetRoute = { path: '/password-reset', element: <PasswordReset surface={portalSurface} /> }
const providerRoute = { path: '/provider/*', element: <RequireAuth roles={['SUPERADMIN', 'PROVIDER_SUPPORT']}><ProviderPortal /></RequireAuth> }
const clinicalRoute = { path: '*', element: <RequireAuth roles={['PRACTICE_STAFF', 'CLINICIAN']}><PatientPreviewProvider><WorkspaceRoute /></PatientPreviewProvider></RequireAuth> }
const router = createHashRouter(portalSurface === 'support'
  ? [loginRoute, logoutRoute, passwordResetRoute, providerRoute, { path: '*', element: <Navigate to="/provider" replace /> }]
  : portalSurface === 'clinical'
    ? [loginRoute, logoutRoute, passwordResetRoute, { path: '/provider/*', element: <Navigate to="/dashboard" replace /> }, clinicalRoute]
    : [loginRoute, logoutRoute, passwordResetRoute, providerRoute, clinicalRoute])
export default function App() {
  return <AuthProvider><Suspense fallback={<main className="auth-status-page"><span className="auth-status-mark" aria-hidden="true" /><h1>Loading EPR</h1><p role="status">Preparing your workspace…</p></main>}><RouterProvider router={router} /></Suspense></AuthProvider>
}
