import { useEffect, useRef, useState } from 'react'
import { RotateCw, ShieldAlert } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import { useAuth } from './auth-context'
import './login.css'

export function Logout() {
  const { status, signOut } = useAuth()
  const navigate = useNavigate()
  const requested = useRef(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    document.title = 'Signing out · EPR'
    if (status === 'unauthenticated') {
      navigate('/login', { replace: true })
    } else if (status !== 'loading' && !requested.current) {
      requested.current = true
      void signOut().catch(reason => {
        setError(reason instanceof Error ? reason.message : 'Sign-out could not be completed. Try again.')
      })
    }
  }, [status, signOut, navigate])

  function retrySignOut() {
    setError(null)
    void signOut().catch(reason => {
      setError(reason instanceof Error ? reason.message : 'Sign-out could not be completed. Try again.')
    })
  }

  return <main className="auth-status-page">
    {error ? <><ShieldAlert aria-hidden="true" /><h1>Sign-out could not be completed</h1><p role="alert">{error}</p><Button onClick={retrySignOut}><RotateCw aria-hidden="true" />Try again</Button></>
      : <><span className="auth-status-mark" aria-hidden="true" /><h1>Signing out</h1><p role="status">Ending your EPR session…</p></>}
  </main>
}
