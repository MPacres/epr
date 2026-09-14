import { lazy, Suspense, useEffect } from 'react'
import { createHashRouter, matchPath, Navigate, RouterProvider, useLocation, useNavigate } from 'react-router-dom'
import { AuthProvider } from './features/auth/AuthProvider'
import { RequireAuth } from './features/auth/RequireAuth'
import { PatientPreviewProvider } from './features/patients/PatientPreviewProvider'
import { portalSurfaceForHostname } from './features/auth/portal-surface'
const Dashboard = lazy(() => import('./features/dashboard/Dashboard').then(module => ({ default: module.Dashboard })))
const Login = lazy(() => import('./features/auth/Login').then(module => ({ default: module.Login })))
const ProviderPortal = lazy(() => import('./features/provider/ProviderPortal').then(module => ({ default: module.ProviderPortal })))
const PatientForm = lazy(() => import('./features/patients/PatientForm').then(module => ({ default: module.PatientForm })))
const PatientChart = lazy(() => import('./features/chart/PatientChart').then(module => ({ default: module.PatientChart })))
const Encounter = lazy(() => import('./features/chart/Encounter').then(module => ({ default: module.Encounter })))
const Schedule = lazy(() => import('./features/schedule/Schedule').then(module => ({ default: module.Schedule })))
import { Button } from './components/ui/button'

function WorkspaceRoute() {
  const location = useLocation()
  const navigate = useNavigate()
  const edit = matchPath('/patients/:practiceId/:patientId/edit', location.pathname)
  const chart = matchPath('/patients/:practiceId/:patientId/chart', location.pathname)
  const encounter = matchPath('/patients/:practiceId/:patientId/encounters/:encounterId', location.pathname)
  const isChart = !!chart
  const isEncounter = !!encounter
  const isEdit = !!edit
  const isNew = location.pathname === '/patients/new'
  const isDirectory = location.pathname === '/patients'
  const isSchedule = location.pathname === '/schedule'
  const isDashboard = location.pathname === '/' || location.pathname === '/dashboard'
  const view = isSchedule ? 'schedule' : isDirectory || isNew || edit || chart || encounter ? 'patients' : 'dashboard'
  useEffect(() => {
    document.title = `${isChart ? 'Patient chart' : isEncounter ? 'Encounter' : isNew ? 'New patient' : isEdit ? 'Edit patient' : isDirectory ? 'My Patients' : isSchedule ? 'Schedule' : isDashboard ? 'Dashboard' : 'Page unavailable'} · EPR`
    document.getElementById('main-content')?.focus({ preventScroll:true })
    window.scrollTo({top:0,behavior:'instant'})
  }, [location.pathname, isNew, isDirectory, isSchedule, isDashboard, isEdit, isChart, isEncounter])
  const workspace = isSchedule ? <Schedule /> : chart ? <PatientChart practiceId={chart.params.practiceId!} patientId={chart.params.patientId!} /> : encounter ? <Encounter practiceId={encounter.params.practiceId!} patientId={encounter.params.patientId!} encounterId={encounter.params.encounterId!} /> : isNew ? <PatientForm practiceId={new URLSearchParams(location.search).get('practice') ?? 'practice-a'} /> : edit ? <PatientForm practiceId={edit.params.practiceId!} patientId={edit.params.patientId!} /> : undefined
  const unavailable = !isSchedule && !chart && !encounter && !isNew && !edit && !isDirectory && !isDashboard ? <main id="main-content" className="dashboard" tabIndex={-1}><h1>Page unavailable</h1><p>This workspace page could not be found.</p><Button onClick={() => navigate('/patients')}>Go to My Patients</Button></main> : undefined
  return <Dashboard key={location.pathname + (isNew ? location.search : '')} view={view} onViewChange={next => navigate(`/${next}`)} content={workspace ? <Suspense fallback={<main className="dashboard" id="main-content" tabIndex={-1}><p role="status">Loading workspace…</p></main>}>{workspace}</Suspense> : unavailable} />
}
const portalSurface = portalSurfaceForHostname(
  window.location.hostname,
  import.meta.env.VITE_CLINICAL_HOSTNAME,
  import.meta.env.VITE_SUPPORT_HOSTNAME,
)
const loginRoute = { path: '/login', element: <Login surface={portalSurface} /> }
const providerRoute = { path: '/provider/*', element: <RequireAuth roles={['SUPERADMIN', 'PROVIDER_SUPPORT']}><ProviderPortal /></RequireAuth> }
const clinicalRoute = { path: '*', element: <RequireAuth roles={['CLINICIAN']}><PatientPreviewProvider><WorkspaceRoute /></PatientPreviewProvider></RequireAuth> }
const router = createHashRouter(portalSurface === 'support'
  ? [loginRoute, providerRoute, { path: '*', element: <Navigate to="/provider" replace /> }]
  : portalSurface === 'clinical'
    ? [loginRoute, { path: '/provider/*', element: <Navigate to="/dashboard" replace /> }, clinicalRoute]
    : [loginRoute, providerRoute, clinicalRoute])
export default function App() {
  return <AuthProvider><Suspense fallback={<main className="auth-status-page"><span className="auth-status-mark" aria-hidden="true" /><h1>Loading EPR</h1><p role="status">Preparing your workspace…</p></main>}><RouterProvider router={router} /></Suspense></AuthProvider>
}
