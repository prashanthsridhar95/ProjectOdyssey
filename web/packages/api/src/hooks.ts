import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from './client'

export interface Async<T> { data: T | undefined; error: string | null; loading: boolean; reload: () => void; setData: (d: T) => void }

/**
 * Last good response for each screen's data, so going back to a page shows it at once while it refreshes
 * (stale-while-revalidate). Kept in memory for the tab; entries with `persist` also survive a reload. Cleared on sign-out.
 */
const memory = new Map<string, unknown>()
const PERSIST_PREFIX = 'odyssey.cache.'

export function clearCache() {
  memory.clear()
  try { Object.keys(localStorage).filter((k) => k.startsWith(PERSIST_PREFIX)).forEach((k) => localStorage.removeItem(k)) } catch { /* private mode */ }
}

function readCache<T>(key: string, persist: boolean): T | undefined {
  if (memory.has(key)) return memory.get(key) as T
  if (!persist) return undefined
  try { const raw = localStorage.getItem(PERSIST_PREFIX + key); if (raw) { const v = JSON.parse(raw) as T; memory.set(key, v); return v } } catch { /* ignore */ }
  return undefined
}

function writeCache(key: string, value: unknown, persist: boolean) {
  memory.set(key, value)
  if (persist) try { localStorage.setItem(PERSIST_PREFIX + key, JSON.stringify(value)) } catch { /* full or private mode */ }
}

export interface AsyncOptions {
  /**
   * Cache key. By default the loader's source plus its deps, which is the same for the same call with the same arguments.
   * Pass `false` for data that must never be shown stale (one-off previews).
   */
  key?: string | false
  /** Also keep the last copy across reloads (needs an explicit key). Use for small data the first screen needs. */
  persist?: boolean
}

/** Loads data on mount and whenever [deps] change. Shows the last copy straight away if there is one. Ignores stale responses. */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[], opts: AsyncOptions = {}): Async<T> {
  const persist = !!opts.persist && typeof opts.key === 'string'
  const depKey = JSON.stringify(deps)
  const key = opts.key === false ? null : (opts.key ?? load.toString()) + '|' + depKey
  const cached = key ? readCache<T>(key, persist) : undefined
  const [state, setState] = useState<{ key: string | null; data: T | undefined }>({ key, data: cached })
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(cached === undefined)
  const [tick, setTick] = useState(0)
  const seq = useRef(0)
  const loader = useRef(load)
  loader.current = load

  // When the deps change, show that key's cached copy (or nothing) in the same render, never the previous key's data.
  let data = state.data
  if (state.key !== key) { data = cached; setState({ key, data: cached }) }

  useEffect(() => {
    const mine = ++seq.current
    setLoading(true)
    loader.current()
      .then((d) => { if (mine === seq.current) { setState({ key, data: d }); setError(null); if (key) writeCache(key, d, persist) } })
      .catch((e: unknown) => { if (mine === seq.current) setError(messageOf(e)) })
      .finally(() => { if (mine === seq.current) setLoading(false) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [depKey, key, tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  const setData = useCallback((d: T) => { setState({ key, data: d }); if (key) writeCache(key, d, persist) }, [key, persist])
  return { data, error, loading, reload, setData }
}

export function messageOf(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return 'Something went wrong'
}
