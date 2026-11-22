import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from './client'

export interface Async<T> { data: T | undefined; error: string | null; loading: boolean; reload: () => void; setData: (d: T) => void }

/** Loads data on mount and whenever [deps] change. Ignores stale responses. */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]): Async<T> {
  const [data, setData] = useState<T | undefined>(undefined)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)
  const seq = useRef(0)
  const loader = useRef(load)
  loader.current = load

  useEffect(() => {
    const mine = ++seq.current
    setLoading(true)
    loader.current()
      .then((d) => { if (mine === seq.current) { setData(d); setError(null) } })
      .catch((e: unknown) => { if (mine === seq.current) setError(messageOf(e)) })
      .finally(() => { if (mine === seq.current) setLoading(false) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { data, error, loading, reload, setData }
}

export function messageOf(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return 'Something went wrong'
}
