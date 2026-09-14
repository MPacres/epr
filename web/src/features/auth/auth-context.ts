import { createContext, useContext } from 'react'
import type { AuthUser, LoginCommand } from './auth-client'

export type AuthState =
  | { status: 'loading'; user: null; message: null }
  | { status: 'authenticated'; user: AuthUser; message: null }
  | { status: 'unauthenticated'; user: null; message: null }
  | { status: 'unavailable'; user: null; message: string }

export type AuthContextValue = AuthState & {
  signIn: (command: LoginCommand) => Promise<AuthUser>
  signOut: () => Promise<void>
  retry: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('Authentication requires AuthProvider')
  return context
}

