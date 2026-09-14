import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { changeRequiredPassword, createSession, deleteSession, fetchSession, type LoginCommand, type PasswordChangeCommand } from './auth-client'
import { AuthContext, type AuthState } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null, message: null })

  const retry = useCallback(async () => {
    setState({ status: 'loading', user: null, message: null })
    try {
      const user = await fetchSession()
      setState(user ? { status: 'authenticated', user, message: null } : { status: 'unauthenticated', user: null, message: null })
    } catch (error) {
      setState({ status: 'unavailable', user: null, message: error instanceof Error ? error.message : 'Your session could not be verified.' })
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    fetchSession(controller.signal)
      .then(user => setState(user ? { status: 'authenticated', user, message: null } : { status: 'unauthenticated', user: null, message: null }))
      .catch(error => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) {
          setState({ status: 'unavailable', user: null, message: error instanceof Error ? error.message : 'Your session could not be verified.' })
        }
      })
    return () => controller.abort()
  }, [])

  const signIn = useCallback(async (command: LoginCommand) => {
    const user = await createSession(command)
    setState({ status: 'authenticated', user, message: null })
    return user
  }, [])

  const signOut = useCallback(async () => {
    await deleteSession()
    setState({ status: 'unauthenticated', user: null, message: null })
  }, [])

  const changePassword = useCallback(async (command: PasswordChangeCommand) => {
    const user = await changeRequiredPassword(command)
    setState({ status: 'authenticated', user, message: null })
    return user
  }, [])

  return <AuthContext.Provider value={{ ...state, signIn, signOut, changePassword, retry }}>{children}</AuthContext.Provider>
}
