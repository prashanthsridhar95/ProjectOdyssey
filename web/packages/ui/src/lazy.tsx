import { lazy, Suspense, useEffect, type ComponentType, type ReactNode } from 'react'
import { SkeletonRows } from './components'

type Loader<M> = () => Promise<M>

/**
 * A page whose code downloads only when it is first opened. `pick` chooses the named export, so pages keep their names.
 * Every loader is also remembered, so `PrefetchPages` can fetch the rest in the background once the console is idle.
 */
const loaders = new Set<Loader<unknown>>()

export function lazyPage<M, K extends keyof M>(load: Loader<M>, pick: K): M[K] {
  loaders.add(load)
  return lazy(() => load().then((m) => ({ default: m[pick] as unknown as ComponentType<unknown> }))) as unknown as M[K]
}

/** Where a page's code is still arriving: a quiet skeleton in the content area, never a blank screen. */
export function PageSuspense({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="page-loading" aria-busy="true" aria-label="Loading"><SkeletonRows rows={8} /></div>}>{children}</Suspense>
}

/** Once the first screen is up and the browser is idle, fetch every other page's code so later clicks open instantly. */
export function PrefetchPages() {
  useEffect(() => {
    const run = () => { loaders.forEach((l) => { l().catch(() => undefined) }) }
    const w = window as Window & { requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => number; cancelIdleCallback?: (id: number) => void }
    if (w.requestIdleCallback) { const id = w.requestIdleCallback(run, { timeout: 4000 }); return () => w.cancelIdleCallback?.(id) }
    const t = window.setTimeout(run, 1500)
    return () => window.clearTimeout(t)
  }, [])
  return null
}
