import { lazy, Suspense, useEffect } from 'react'
import { createHashRouter, matchPath, RouterProvider, useLocation, useNavigate } from 'react-router-dom'
import { Dashboard } from './features/dashboard/Dashboard'
const PatientForm = lazy(() => import('./features/patients/PatientForm').then(module => ({ default: module.PatientForm })))
import { PatientPreviewProvider } from './features/patients/PatientPreviewProvider'
import { Button } from './components/ui/button'

function WorkspaceRoute() {
  const location = useLocation()
  const navigate = useNavigate()
  const edit = matchPath('/patients/:practiceId/:patientId/edit', location.pathname)
  const isEdit = !!edit
  const isNew = location.pathname === '/patients/new'
  const isDirectory = location.pathname === '/patients'
  const isDashboard = location.pathname === '/' || location.pathname === '/dashboard'
  const view = isDirectory || isNew || edit ? 'patients' : 'dashboard'
  useEffect(() => {
    document.title = `${isNew ? 'New patient' : isEdit ? 'Edit patient' : isDirectory ? 'My Patients' : isDashboard ? 'Dashboard' : 'Page unavailable'} · EPR`
    document.getElementById('main-content')?.focus({ preventScroll:true })
    window.scrollTo({top:0,behavior:'instant'})
  }, [location.pathname, isNew, isDirectory, isDashboard, isEdit])
  const form = isNew ? <PatientForm practiceId={new URLSearchParams(location.search).get('practice') ?? 'practice-a'} /> : edit ? <PatientForm practiceId={edit.params.practiceId!} patientId={edit.params.patientId!} /> : undefined
  const unavailable = !isNew && !edit && !isDirectory && !isDashboard ? <main id="main-content" className="dashboard" tabIndex={-1}><h1>Page unavailable</h1><p>This workspace page could not be found.</p><Button onClick={() => navigate('/patients')}>Go to My Patients</Button></main> : undefined
  return <Dashboard key={location.pathname + (isNew ? location.search : '')} view={view} onViewChange={next => navigate(`/${next}`)} content={form ? <Suspense fallback={<main className="dashboard" id="main-content" tabIndex={-1}><p role="status">Loading patient form…</p></main>}>{form}</Suspense> : unavailable} />
}
const router = createHashRouter([{path:'*',element:<WorkspaceRoute />}])
export default function App() {
  return <PatientPreviewProvider><RouterProvider router={router} /></PatientPreviewProvider>
}
