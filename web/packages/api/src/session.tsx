import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError, createApi, type Api } from './client'
import { clearCache } from './hooks'
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
  /** Shown while a saved session is being checked, instead of a blank page. */
  fallback?: ReactNode
  children: ReactNode
}

export function SessionProvider({ storageKey, baseUrl, tenant, allowedRoles, fallback = null, children }: Props) {
  const tokenRef = useRef<string | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [config, setConfig] = useState<PublicConfig | null>(null)
  const [ready, setReady] = useState(false)

  const logout = useCallback(() => {
    tokenRef.current = null
    setUser(null)
    clearCache()
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
    // The tenant config rarely changes: use the last copy at once and refresh it in the background.
    const cfgKey = `${storageKey}.config`
    try { const c = localStorage.getItem(cfgKey); if (c) setConfig(JSON.parse(c) as PublicConfig) } catch { /* ignore */ }
    api.config().then((c) => { setConfig(c); try { localStorage.setItem(cfgKey, JSON.stringify(c)) } catch { /* full */ } }).catch(() => undefined)
    try {
      const raw = localStorage.getItem(storageKey)
      if (raw) {
        // Open the console straight away with the saved user; check the token in the background.
        // Only a rejected token signs out, so a network blip never throws someone out of a working console.
        const saved = JSON.parse(raw) as { token: string; user: User }
        tokenRef.current = saved.token
        setUser(saved.user)
        api.auth.me()
          .then((u) => { if (!allowedRoles.includes(u.role)) { logout(); return } setUser((prev) => (prev && JSON.stringify(prev) === JSON.stringify(u) ? prev : u)) })
          .catch((e: unknown) => { if (e instanceof ApiError && (e.status === 401 || e.status === 403)) logout() })
      }
    } catch { /* ignore */ }
    setReady(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, storageKey, logout])

  useEffect(() => {
    const h = (e: StorageEvent) => { if (e.key === storageKey && e.newValue === null) { tokenRef.current = null; setUser(null) } }
    window.addEventListener('storage', h)
    return () => window.removeEventListener('storage', h)
  }, [storageKey])

  const value = useMemo(() => ({ api, user, config, login, logout }), [api, user, config, login, logout])
  if (!ready) return <>{fallback}</>
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
