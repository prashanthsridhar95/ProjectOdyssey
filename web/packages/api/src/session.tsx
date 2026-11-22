import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createApi, type Api } from './client'
import type { PublicConfig, Role, User } from './types'

interface Session {
  api: Api
  user: User | null
  config: PublicConfig | null
  login: (user: User, token: string) => void
  logout: () => void
}

const Ctx = createContext<Session | null>(null)

interface Props {
  /** Storage key, so the admin and vendor apps keep separate sessions. */
  storageKey: string
  baseUrl: string
  tenant?: string
  /** Roles allowed to use this app. Others are signed out. */
  allowedRoles: Role[]
  children: ReactNode
}

export function SessionProvider({ storageKey, baseUrl, tenant, allowedRoles, children }: Props) {
  const tokenRef = useRef<string | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [config, setConfig] = useState<PublicConfig | null>(null)
  const [ready, setReady] = useState(false)

  const logout = useCallback(() => {
    tokenRef.current = null
    setUser(null)
    try { localStorage.removeItem(storageKey) } catch { /* private mode */ }
  }, [storageKey])

  const api = useMemo(
    () => createApi({ baseUrl, tenant, getToken: () => tokenRef.current, onUnauthorized: logout }),
    [baseUrl, tenant, logout],
  )

  const login = useCallback((u: User, token: string) => {
    if (!allowedRoles.includes(u.role)) throw new Error('This account cannot use this app.')
    tokenRef.current = token
    setUser(u)
    try { localStorage.setItem(storageKey, JSON.stringify({ token, user: u })) } catch { /* private mode */ }
  }, [allowedRoles, storageKey])

  useEffect(() => {
    api.config().then(setConfig).catch(() => undefined)
    try {
      const raw = localStorage.getItem(storageKey)
      if (raw) {
        const saved = JSON.parse(raw) as { token: string; user: User }
        tokenRef.current = saved.token
        api.auth.me().then((u) => setUser(u)).catch(() => logout()).finally(() => setReady(true))
        return
      }
    } catch { /* ignore */ }
    setReady(true)
  }, [api, storageKey, logout])

  const value = useMemo(() => ({ api, user, config, login, logout }), [api, user, config, login, logout])
  if (!ready) return null
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useSession(): Session {
  const s = useContext(Ctx)
  if (!s) throw new Error('useSession must be used inside SessionProvider')
  return s
}

/** The vertical's words (Vendor / Outlet / Booking), so no screen hard-codes them. */
export function useLabels() {
  const { config } = useSession()
  return config?.config.labels ?? { provider: 'Provider', location: 'Location', order: 'Order', item: 'Item', customer: 'Customer' }
}
