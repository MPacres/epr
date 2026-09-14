import { Navigate, useLocation } from 'react-router-dom'
import { RotateCw, ShieldAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '../../components/ui/button'
import { homePathFor, type AuthRole } from './auth-client'
import { useAuth } from './auth-context'

export function RequireAuth({ children, roles }: { children: ReactNode; roles: AuthRole[] }) {
  const auth = useAuth()
  const location = useLocation()

  if (auth.status === 'loading') {
    return <main className="auth-status-page"><span className="auth-status-mark" aria-hidden="true" /><h1>Verifying your session</h1><p role="status">Connecting securely to EPR…</p></main>
  }
  if (auth.status === 'unavailable') {
    return <main className="auth-status-page"><ShieldAlert aria-hidden="true" /><h1>Session check unavailable</h1><p role="alert">{auth.message}</p><Button onClick={() => void auth.retry()}><RotateCw aria-hidden="true" />Try again</Button></main>
  }
  if (auth.status === 'unauthenticated') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (auth.user.passwordResetRequired) {
    return <Navigate to="/password-reset" replace />
  }
  if (!roles.some(role => auth.user.roles.includes(role))) {
    return <Navigate to={homePathFor(auth.user) ?? '/login'} replace />
  }
  return children
}
